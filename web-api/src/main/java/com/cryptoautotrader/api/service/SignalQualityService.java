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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    /**
     * HOLD 기준선 1회 실행 예산 — BUY/SELL 예산과 <b>분리</b>한다.
     * 미평가 HOLD 가 7,658건(coin-hour) 쌓여 있어 같은 예산을 쓰면 신호 평가가 굶는다.
     * 신호 평가가 항상 우선이고 기준선은 남는 호출로 천천히 따라잡는다.
     */
    private static final int MAX_HOLD_PER_RUN = 200;

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
        // 신호 평가 이후에 돌린다 — 기준선이 신호 평가를 밀어내면 안 된다.
        int hold4h  = evaluateHoldBaselineLoop(true,  MAX_HOLD_PER_RUN);
        int hold24h = evaluateHoldBaselineLoop(false, MAX_HOLD_PER_RUN);
        if (hold4h > 0 || hold24h > 0) {
            log.info("[SignalQuality] HOLD 기준선 평가 — 4h: {}건, 24h: {}건", hold4h, hold24h);
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
        // HOLD 기준선은 catchup 에서도 예산을 건다 — 백로그가 7,658 coin-hour 라
        // Integer.MAX_VALUE 로 열면 재시작 때마다 Upbit 로 수천 콜이 한 번에 나간다.
        // 30분 주기로 200건씩 따라잡으면 약 2일이면 소진된다.
        int hold4h  = evaluateHoldBaselineLoop(true,  MAX_HOLD_PER_RUN);
        int hold24h = evaluateHoldBaselineLoop(false, MAX_HOLD_PER_RUN);
        log.info("[SignalQuality] 시작 Catchup 완료 — 4h: {}건, 24h: {}건 (HOLD 기준선 {}/{}건)",
                total4h, total24h, hold4h, hold24h);
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
        int page = 0;

        while (processed < maxTotal) {
            List<StrategyLogEntity> pending = is4h
                    ? strategyLogRepository.findPendingFor4hEval(cutoff,  PageRequest.of(page, BATCH_SIZE))
                    : strategyLogRepository.findPendingFor24hEval(cutoff, PageRequest.of(page, BATCH_SIZE));

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

            // 실패 행 페이지 전진 가드: 캔들 조회 실패 행(상장폐지 코인 등)은 저장되지 않아
            // 다음 조회에서도 정렬 헤드(오래된 순)에 그대로 남는다. page를 고정하면 같은 행을
            // 무한 재조회(무한 루프)하고, 실패 행 100+건이 헤드에 쌓이면 그 뒤의 정상 행이
            // 영영 평가되지 않는다. 실패가 있으면 다음 페이지로 전진해 후속 행을 계속 처리한다
            // (성공분이 pending에서 빠지며 일부 행이 이번 실행에서 건너뛰어질 수 있지만,
            // 다음 스케줄 주기(30분)에 다시 잡힌다).
            if (pending.size() > toSave.size()) {
                page++;
            }

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
    /**
     * 🔴 거래소에 없는 마켓(404) — <b>영구 실패</b>다. 이 프로세스에서 다시 호출하지 않는다
     * (2026-10-01 추가).
     *
     * <p><b>왜 필요한가</b>: 404 를 일시 실패처럼 다시 시도하고 있었다. {@code fetchClosePrice} 가
     * null 이면 그 행은 저장되지 않아 다음 실행의 조회 대상에 그대로 남으므로, 상장폐지 코인 하나가
     * <b>매 실행마다 수백 번</b> 호출된다. 2026-10-01 06시 운영 실측: KRW-BONK 520건 · KRW-STORJ
     * 154건, 정기 실행 1회(scheduler)만으로 337건. 앱 전체가 업비트 호출을 하나의 공유 스로틀로
     * 직렬화하므로 이 낭비가 <b>429 를 유발해 다른 소비자의 호출을 실패시킨다</b> —
     * 06:20:17 에 KRW-G·KRW-INJ 의 캔들 수집이 429 로 실패해 그 회차 동기화가 누락됐다
     * (전향 검증 22코인 중 둘이다).
     *
     * <p>⚠️ 이 집합은 프로세스 메모리다. 재시작하면 마켓당 1회는 다시 호출한다 — 상장폐지 여부가
     * 바뀔 수 있으므로 영구 저장하지 않는 쪽을 택했다. 줄이는 것은 "같은 실행 안의 반복"이다.
     *
     * <p>📌 남는 문제(별도 사안): 그 행들은 <b>영구히 미평가로 남아</b> 조회 대상 자리를 계속
     * 차지한다. 페이지 전진 가드가 무한 루프는 막지만 백로그 자체는 줄지 않는다.
     */
    private final Set<String> delistedMarkets = ConcurrentHashMap.newKeySet();

    private BigDecimal fetchClosePrice(String coinPair, Instant targetTime) {
        if (targetTime.isAfter(Instant.now())) return null;
        if (delistedMarkets.contains(coinPair)) return null;
        try {
            List<UpbitCandleResponse> candles = upbitRestClient.getCandles(
                    coinPair, "minutes", 60, targetTime, 1);
            if (candles.isEmpty()) return null;
            return candles.get(0).getTradePrice();
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            // 🔴 404 만 영구 실패로 본다. 429·5xx·타임아웃은 일시 실패이므로 계속 재시도해야 한다.
            if (msg.contains("호출 실패: 404") && delistedMarkets.add(coinPair)) {
                log.warn("[SignalQuality] {} 는 거래소에 없다(404) — 이 프로세스에서 더 조회하지 "
                        + "않는다. 이 코인의 미평가 행은 남는다(별도 처리 필요).", coinPair);
            }
            log.debug("Upbit 캔들 조회 실패 ({}): {}", coinPair, msg);
            return null;
        }
    }

    /**
     * HOLD 기준선 평가 루프 — (코인, 정시) 당 1건만 평가해 대조군을 만든다.
     *
     * <p>신호 평가 루프({@link #evaluateLoop})와 분리한 이유: 저쪽은 "평가 안 된 행"을 페이지로
     * 훑지만, HOLD 는 같은 시간대에 수십 행이 중복되므로 그 방식이면 같은 값을 수십 번 조회한다.
     * 이쪽은 리포지토리가 DISTINCT ON + NOT EXISTS 로 시간대 단위 중복을 걷어낸 뒤 넘겨준다.</p>
     */
    @Transactional
    public int evaluateHoldBaselineLoop(boolean is4h, int maxTotal) {
        long hours = is4h ? 4 : 24;
        Instant cutoff = Instant.now().minus(hours, ChronoUnit.HOURS);
        int processed = 0;

        while (processed < maxTotal) {
            int remaining = Math.min(BATCH_SIZE, maxTotal - processed);
            List<StrategyLogEntity> pending = is4h
                    ? strategyLogRepository.findPendingHoldFor4hEval(cutoff,  remaining)
                    : strategyLogRepository.findPendingHoldFor24hEval(cutoff, remaining);
            if (pending.isEmpty()) break;

            List<StrategyLogEntity> toSave = new ArrayList<>();
            for (StrategyLogEntity entry : pending) {
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
                    log.warn("[SignalQuality] HOLD {}h 기준선 평가 실패 (id={}): {}",
                            hours, entry.getId(), e.getMessage());
                }
            }
            if (!toSave.isEmpty()) strategyLogRepository.saveAll(toSave);

            // 전량 실패(상장폐지 코인 등)면 다음 조회도 같은 행을 돌려주므로 무한 루프가 된다.
            // 진전이 없으면 이번 실행은 접고 다음 주기에 다시 시도한다.
            if (toSave.isEmpty()) break;

            try { Thread.sleep(100); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return processed;
    }

    /**
     * 신호 방향 기준 수익률 계산 (%)
     * BUY:  (afterPrice - signalPrice) / signalPrice × 100  → 상승이 양수
     * SELL: (signalPrice - afterPrice) / signalPrice × 100  → 하락이 양수
     * HOLD: BUY 와 <b>같은 롱 방향</b> — 기준선이므로 BUY 와 직접 뺄셈이 되어야 한다.
     *       (SELL 방향으로 계산하면 부호가 뒤집혀 대조군 구실을 못 한다.)
     */
    private BigDecimal calcReturn(String signal, BigDecimal signalPrice, BigDecimal afterPrice) {
        if (signalPrice == null || signalPrice.compareTo(BigDecimal.ZERO) == 0) return null;
        BigDecimal delta = ("BUY".equals(signal) || "HOLD".equals(signal))
                ? afterPrice.subtract(signalPrice)
                : signalPrice.subtract(afterPrice);
        return delta.divide(signalPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }
}
