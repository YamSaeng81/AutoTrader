package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.CoinCollectResult;
import com.cryptoautotrader.api.entity.CandleDataEntity;
import com.cryptoautotrader.api.repository.CandleDataRepository;
import com.cryptoautotrader.exchange.upbit.UpbitCandleCollector;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DataCollectionService {

    private final CandleDataRepository candleDataRepository;
    private final TelegramNotificationService telegramNotificationService;

    /** 단일 코인 수집 (비동기) — 기존 API 유지 */
    @Async("taskExecutor")
    public void collectCandles(String coinPair, String timeframe, LocalDate startDate, LocalDate endDate) {
        log.info("캔들 수집 시작: {} {} {} ~ {}", coinPair, timeframe, startDate, endDate);
        try {
            int count = collectSingle(coinPair, timeframe, startDate, endDate);
            log.info("캔들 수집 완료: {} {}건 저장", coinPair, count);
        } catch (Exception e) {
            log.error("캔들 수집 실패: {} — {}", coinPair, e.getMessage(), e);
        }
    }

    /**
     * 다중 코인 배치 수집 (비동기).
     *
     * <p>코인별로 순차 처리하며 실패 시 1회 재시도한다.
     * 한 코인이 실패해도 나머지 코인은 계속 수집한다.
     * 전체 완료 시 텔레그램으로 결과를 전송한다.
     *
     * @param coinPairs  수집할 코인 목록
     * @param timeframe  타임프레임 (H1, M5 등)
     * @param startDate  수집 시작일
     * @param endDate    수집 종료일
     */
    @Async("taskExecutor")
    public void collectBatch(List<String> coinPairs, String timeframe, LocalDate startDate, LocalDate endDate) {
        log.info("[Batch] 배치 수집 시작: 코인={}, {}봉 {} ~ {}", coinPairs, timeframe, startDate, endDate);
        long batchStart = System.currentTimeMillis();
        List<CoinCollectResult> results = new ArrayList<>();

        for (int i = 0; i < coinPairs.size(); i++) {
            String coinPair = coinPairs.get(i);
            long coinStart = System.currentTimeMillis();
            boolean success = false;
            int candleCount = 0;
            String errorMessage = null;

            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    if (attempt > 1) {
                        log.info("[Batch] {} 재시도 ({}/2)", coinPair, attempt);
                        Thread.sleep(3000);
                    }
                    candleCount = collectSingle(coinPair, timeframe, startDate, endDate);
                    success = true;
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errorMessage = "중단됨";
                    log.warn("[Batch] {} 수집 중단", coinPair);
                    break;
                } catch (Exception e) {
                    errorMessage = e.getMessage();
                    log.warn("[Batch] {} 실패 (시도 {}/2): {}", coinPair, attempt, e.getMessage());
                }
            }

            long durationMs = System.currentTimeMillis() - coinStart;
            results.add(new CoinCollectResult(coinPair, success, candleCount, durationMs, errorMessage));
            log.info("[Batch] {} 완료: success={}, count={}, {}ms", coinPair, success, candleCount, durationMs);

            // 코인 간 2초 딜레이 (Upbit 레이트 리밋 버퍼, 마지막 코인 제외)
            if (i < coinPairs.size() - 1) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[Batch] 배치 수집 중단됨 ({}번째 코인 이후)", i + 1);
                    break;
                }
            }
        }

        long totalDurationMs = System.currentTimeMillis() - batchStart;
        log.info("[Batch] 배치 수집 완료: {}개 코인, {}ms", results.size(), totalDurationMs);
        telegramNotificationService.notifyDataCollectionCompleted(
                timeframe, startDate, endDate, results, totalDurationMs);
    }

    /**
     * 단일 코인 수집 동기 처리 — 저장된 캔들 수 반환.
     *
     * <p>TimescaleDB는 새 시간 범위의 데이터 삽입 시 청크(chunk)를 생성하면서
     * AccessExclusiveLock을 획득한다. 수만 건을 단일 트랜잭션으로 저장하면
     * lock 점유 시간이 길어져 데드락이 발생한다.
     * {@value SAVE_CHUNK_SIZE}건씩 나눠 각각 별도 트랜잭션으로 커밋하여 충돌을 방지한다.
     */
    private static final int SAVE_CHUNK_SIZE = 500;

    private int collectSingle(String coinPair, String timeframe, LocalDate startDate, LocalDate endDate) {
        UpbitRestClient restClient = new UpbitRestClient();
        UpbitCandleCollector collector = new UpbitCandleCollector(restClient);

        Instant from = startDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        Instant to   = endDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();

        List<Candle> candles = collector.fetchCandles(coinPair, timeframe, from, to);

        List<CandleDataEntity> entities = candles.stream()
                .map(c -> CandleDataEntity.builder()
                        .time(c.getTime())
                        .coinPair(coinPair)
                        .timeframe(timeframe)
                        .open(c.getOpen())
                        .high(c.getHigh())
                        .low(c.getLow())
                        .close(c.getClose())
                        .volume(c.getVolume())
                        .build())
                .toList();

        // 청크 단위 저장 — 각 saveAll() 호출은 별도 트랜잭션 (JpaRepository 기본 @Transactional)
        for (int i = 0; i < entities.size(); i += SAVE_CHUNK_SIZE) {
            List<CandleDataEntity> chunk = entities.subList(i, Math.min(i + SAVE_CHUNK_SIZE, entities.size()));
            candleDataRepository.saveAll(chunk);
        }
        return entities.size();
    }
}
