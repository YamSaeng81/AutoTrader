package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.UpbitCandleResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 신호 품질 사후 평가 서비스
 * - BUY/SELL 신호 발생 후 4시간·24시간 뒤 가격을 Upbit에서 조회해 저장
 * - 신호 방향 기준 수익률을 계산 → "이 신호가 맞았는가" 사후 추적
 *
 * <p>병목 해소:
 * <ul>
 *   <li>정규 스케줄러: 30분마다 최대 MAX_PER_RUN(500)건까지 루프 처리</li>
 *   <li>시작 Catchup: 서버 재시작 시 미평가 신호 전량을 비동기로 처리</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalQualityService {

    /** 1회 DB 조회 배치 크기 */
    private static final int BATCH_SIZE    = 100;
    /** 스케줄러 1회 실행 당 최대 처리 건수 (Upbit API 부하 방지) */
    private static final int MAX_PER_RUN   = 500;

    private final StrategyLogRepository strategyLogRepository;
    private final UpbitRestClient upbitRestClient;

    // ── 정규 스케줄러 ─────────────────────────────────────────────────────────

    /**
     * 30분마다 실행 — 미평가 신호를 최대 MAX_PER_RUN건까지 루프 처리.
     * 한 번의 실행으로 쌓인 신호를 모두 소진하므로 장기간 다운타임 후에도 빠르게 따라잡는다.
     */
    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void evaluateSignalQuality() {
        int processed4h  = evaluateLoop(true,  MAX_PER_RUN);
        int processed24h = evaluateLoop(false, MAX_PER_RUN);
        if (processed4h > 0 || processed24h > 0) {
            log.info("[SignalQuality] 정기 평가 완료 — 4h: {}건, 24h: {}건", processed4h, processed24h);
        }
    }

    // ── 시작 Catchup ──────────────────────────────────────────────────────────

    /**
     * 서버 재시작 시 쌓인 미평가 신호를 비동기로 전량 처리한다.
     * 건수 제한 없이 루프를 돌며 Upbit API 과부하를 막기 위해 배치 사이별 100ms 대기.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void catchupOnStartup() {
        log.info("[SignalQuality] 시작 Catchup 시작");
        int total4h  = evaluateLoop(true,  Integer.MAX_VALUE);
        int total24h = evaluateLoop(false, Integer.MAX_VALUE);
        log.info("[SignalQuality] 시작 Catchup 완료 — 4h: {}건, 24h: {}건", total4h, total24h);
    }

    // ── 공통 평가 루프 ────────────────────────────────────────────────────────

    /**
     * 미평가 신호를 배치 단위로 반복 조회·평가한다.
     *
     * @param is4h     true → 4h 평가, false → 24h 평가
     * @param maxTotal 이번 실행에서 처리할 최대 건수 (초과 시 중단)
     * @return 실제 처리된 건수
     */
    @Transactional
    public int evaluateLoop(boolean is4h, int maxTotal) {
        long hours  = is4h ? 4 : 24;
        Instant cutoff = Instant.now().minus(hours, ChronoUnit.HOURS);
        int processed = 0;

        while (processed < maxTotal) {
            List<StrategyLogEntity> pending = is4h
                    ? strategyLogRepository.findPendingFor4hEval(cutoff,  PageRequest.of(0, BATCH_SIZE))
                    : strategyLogRepository.findPendingFor24hEval(cutoff, PageRequest.of(0, BATCH_SIZE));

            if (pending.isEmpty()) break;

            List<StrategyLogEntity> toSave = new ArrayList<>();
            for (StrategyLogEntity entry : pending) {
                if (processed >= maxTotal) break;
                try {
                    Instant targetTime = entry.getCreatedAt().plus(hours, ChronoUnit.HOURS);
                    BigDecimal price = fetchClosePrice(entry.getCoinPair(), targetTime);
                    if (price == null) continue;

                    BigDecimal ret = calcReturn(entry.getSignal(), entry.getSignalPrice(), price);
                    if (is4h) {
                        entry.setPriceAfter4h(price);
                        entry.setReturn4hPct(ret);
                    } else {
                        entry.setPriceAfter24h(price);
                        entry.setReturn24hPct(ret);
                    }
                    toSave.add(entry);
                    processed++;
                } catch (Exception e) {
                    log.warn("[SignalQuality] {}h 평가 실패 (id={}): {}", hours, entry.getId(), e.getMessage());
                }
            }
            if (!toSave.isEmpty()) strategyLogRepository.saveAll(toSave);

            // 꽉 찬 배치가 아니면 더 이상 없음
            if (pending.size() < BATCH_SIZE) break;

            // Upbit API 연속 호출 완화
            try { Thread.sleep(100); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return processed;
    }

    /**
     * Upbit H1 캔들 1개를 targetTime 시점으로 조회해 종가 반환.
     * targetTime이 미래이면 null 반환.
     */
    private BigDecimal fetchClosePrice(String coinPair, Instant targetTime) {
        if (targetTime.isAfter(Instant.now())) return null;
        try {
            List<UpbitCandleResponse> candles = upbitRestClient.getCandles(
                    coinPair, "minutes", 60, targetTime, 1);
            if (candles.isEmpty()) return null;
            return candles.get(0).getTradePrice();
        } catch (Exception e) {
            log.debug("Upbit 캔들 조회 실패 ({}): {}", coinPair, e.getMessage());
            return null;
        }
    }

    /**
     * 신호 방향 기준 수익률 계산 (%)
     * BUY:  (afterPrice - signalPrice) / signalPrice × 100  → 상승이 양수
     * SELL: (signalPrice - afterPrice) / signalPrice × 100  → 하락이 양수
     */
    private BigDecimal calcReturn(String signal, BigDecimal signalPrice, BigDecimal afterPrice) {
        if (signalPrice == null || signalPrice.compareTo(BigDecimal.ZERO) == 0) return null;
        BigDecimal delta = "BUY".equals(signal)
                ? afterPrice.subtract(signalPrice)
                : signalPrice.subtract(afterPrice);
        return delta.divide(signalPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }
}
