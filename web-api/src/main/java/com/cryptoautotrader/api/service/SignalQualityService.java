package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.UpbitCandleResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 신호 품질 사후 평가 서비스
 * - BUY/SELL 신호 발생 후 4시간·24시간 뒤 가격을 Upbit에서 조회해 저장
 * - 신호 방향 기준 수익률을 계산 → "이 신호가 맞았는가" 사후 추적
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalQualityService {

    private static final int BATCH_SIZE = 20;

    private final StrategyLogRepository strategyLogRepository;
    private final UpbitRestClient upbitRestClient;

    /**
     * 30분마다 실행 — 4h·24h 평가 대상 신호를 한 번씩 처리
     */
    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    @Transactional
    public void evaluateSignalQuality() {
        evaluate4h();
        evaluate24h();
    }

    private void evaluate4h() {
        Instant cutoff = Instant.now().minus(4, ChronoUnit.HOURS);
        List<StrategyLogEntity> pending = strategyLogRepository
                .findPendingFor4hEval(cutoff, PageRequest.of(0, BATCH_SIZE));

        if (pending.isEmpty()) return;

        log.debug("신호 품질 4h 평가 대상: {}건", pending.size());
        for (StrategyLogEntity logEntry : pending) {
            try {
                Instant targetTime = logEntry.getCreatedAt().plus(4, ChronoUnit.HOURS);
                BigDecimal price = fetchClosePrice(logEntry.getCoinPair(), targetTime);
                if (price == null) continue;

                BigDecimal ret = calcReturn(logEntry.getSignal(), logEntry.getSignalPrice(), price);
                logEntry.setPriceAfter4h(price);
                logEntry.setReturn4hPct(ret);
                strategyLogRepository.save(logEntry);
            } catch (Exception e) {
                log.warn("4h 가격 평가 실패 (logId={}): {}", logEntry.getId(), e.getMessage());
            }
        }
    }

    private void evaluate24h() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        List<StrategyLogEntity> pending = strategyLogRepository
                .findPendingFor24hEval(cutoff, PageRequest.of(0, BATCH_SIZE));

        if (pending.isEmpty()) return;

        log.debug("신호 품질 24h 평가 대상: {}건", pending.size());
        for (StrategyLogEntity logEntry : pending) {
            try {
                Instant targetTime = logEntry.getCreatedAt().plus(24, ChronoUnit.HOURS);
                BigDecimal price = fetchClosePrice(logEntry.getCoinPair(), targetTime);
                if (price == null) continue;

                BigDecimal ret = calcReturn(logEntry.getSignal(), logEntry.getSignalPrice(), price);
                logEntry.setPriceAfter24h(price);
                logEntry.setReturn24hPct(ret);
                strategyLogRepository.save(logEntry);
            } catch (Exception e) {
                log.warn("24h 가격 평가 실패 (logId={}): {}", logEntry.getId(), e.getMessage());
            }
        }
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
