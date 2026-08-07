package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 벤치마크(매수 후 보유) 대비 알파 측정 — 2026-08-07 신설.
 *
 * <p><b>왜 필요한가</b>: 수개월간 "이 시스템이 잘하고 있는가"를 판정할 수 없었던 근본 원인은
 * 수익률을 <i>절대값</i>으로만 봤기 때문이다. −3%가 나쁜 성적인지 아닌지는 같은 기간 시장이
 * 어땠는지를 알아야 판정된다(시장이 −10%였다면 훌륭한 성적이다). 2026-08-06 수동 SQL로
 * 처음 측정했을 때 동적 −3.30% vs 알트 평균 −1.63%로 <b>알파가 음수</b>임이 드러났고,
 * 이 비교를 매번 손으로 하지 않도록 시스템에 내장한다.
 *
 * <p><b>측정 방식</b>: 세션 시작 시점을 기준일로 잡아, 같은 기간 주요 코인을 그냥 사서
 * 들고만 있었을 때의 수익률과 비교한다. 시세는 {@code market_data_cache}에서 읽는다
 * ({@code candle_data}는 백테스트용 과거 저장소라 최신 구간이 비어 있을 수 있다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BenchmarkAlphaService {

    /** 벤치마크 바스켓 — 운영에서 실제로 다루는 주요 코인. BTC는 시장 대표라 별도 표기한다. */
    private static final List<String> BENCHMARK_COINS =
            List.of("KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL", "KRW-DOGE");
    private static final String MARKET_PROXY = "KRW-BTC";
    private static final String TIMEFRAME = "H1";
    private static final int SCALE = 2;

    private final LiveTradingSessionRepository liveSessionRepo;
    private final DynamicSessionRepository dynamicSessionRepo;
    private final MarketDataCacheRepository marketDataCacheRepo;

    /**
     * 운영 중(RUNNING) 세션 전체의 수익률을 같은 기간 매수후보유와 비교한다.
     *
     * @return {@code system}(엔진별·전체 수익률), {@code benchmark}(코인별 보유 수익률),
     *         {@code alpha}(전체 시스템 − 알트평균 / − BTC), {@code periodStart}
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getAlphaSummary() {
        List<LiveTradingSessionEntity> live = liveSessionRepo.findByStatus("RUNNING");
        List<DynamicSessionEntity> dynamic = dynamicSessionRepo.findByStatus("RUNNING").stream()
                .filter(s -> !s.isPaper())   // PAPER는 실자본 성과가 아니므로 알파 집계에서 제외
                .toList();

        Instant periodStart = earliestStart(live, dynamic);
        Instant now = Instant.now();

        Map<String, Object> result = new LinkedHashMap<>();
        if (periodStart == null) {
            result.put("available", false);
            result.put("reason", "운영 중인 실자본 세션이 없어 비교할 기간이 없습니다.");
            return result;
        }

        // ── 시스템 수익률 ────────────────────────────────────────────
        BigDecimal liveInit = BigDecimal.ZERO, liveNow = BigDecimal.ZERO;
        for (LiveTradingSessionEntity s : live) {
            liveInit = liveInit.add(nz(s.getInitialCapital()));
            liveNow = liveNow.add(nz(s.getTotalAssetKrw()));
        }
        BigDecimal dynInit = BigDecimal.ZERO, dynNow = BigDecimal.ZERO;
        for (DynamicSessionEntity s : dynamic) {
            dynInit = dynInit.add(nz(s.getInitialCapital()));
            dynNow = dynNow.add(nz(s.getTotalAssetKrw()));
        }

        BigDecimal totalInit = liveInit.add(dynInit);
        BigDecimal totalNow = liveNow.add(dynNow);
        BigDecimal totalReturn = pct(totalInit, totalNow);

        Map<String, Object> system = new LinkedHashMap<>();
        system.put("liveSessions", live.size());
        system.put("liveReturnPct", pct(liveInit, liveNow));
        system.put("dynamicSessions", dynamic.size());
        system.put("dynamicReturnPct", pct(dynInit, dynNow));
        system.put("totalInitialCapital", totalInit);
        system.put("totalAssetKrw", totalNow);
        system.put("totalReturnPct", totalReturn);
        result.put("system", system);

        // ── 벤치마크: 같은 기간 매수 후 보유 ──────────────────────────
        List<Map<String, Object>> benchmark = new ArrayList<>();
        BigDecimal altSum = BigDecimal.ZERO;
        int altCount = 0;
        BigDecimal btcReturn = null;

        for (String coin : BENCHMARK_COINS) {
            BigDecimal r = holdReturnPct(coin, periodStart, now);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("coinPair", coin);
            row.put("returnPct", r);
            row.put("available", r != null);
            benchmark.add(row);
            if (r == null) continue;
            if (MARKET_PROXY.equals(coin)) {
                btcReturn = r;
            } else {
                altSum = altSum.add(r);
                altCount++;
            }
        }
        result.put("benchmark", benchmark);

        BigDecimal altAvg = altCount > 0
                ? altSum.divide(BigDecimal.valueOf(altCount), SCALE, RoundingMode.HALF_UP)
                : null;

        // ── 알파 ────────────────────────────────────────────────────
        Map<String, Object> alpha = new LinkedHashMap<>();
        alpha.put("altAvgReturnPct", altAvg);
        alpha.put("btcReturnPct", btcReturn);
        alpha.put("vsAltAvgPct", altAvg == null ? null : totalReturn.subtract(altAvg));
        alpha.put("vsBtcPct", btcReturn == null ? null : totalReturn.subtract(btcReturn));
        result.put("alpha", alpha);

        result.put("available", true);
        result.put("periodStart", periodStart);
        result.put("measuredAt", now);
        return result;
    }

    /**
     * 해당 코인을 periodStart에 사서 지금까지 들고 있었을 때의 수익률(%).
     * 구간에 캔들이 2개 미만이면 판정 불가로 null.
     */
    private BigDecimal holdReturnPct(String coinPair, Instant start, Instant end) {
        List<MarketDataCacheEntity> candles =
                marketDataCacheRepo.findCandles(coinPair, TIMEFRAME, start, end);
        if (candles.size() < 2) {
            return null;
        }
        BigDecimal first = candles.get(0).getClose();
        BigDecimal last = candles.get(candles.size() - 1).getClose();
        if (first == null || last == null || first.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return last.subtract(first)
                .divide(first, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private Instant earliestStart(List<LiveTradingSessionEntity> live, List<DynamicSessionEntity> dynamic) {
        Instant earliest = null;
        for (LiveTradingSessionEntity s : live) {
            earliest = min(earliest, s.getStartedAt());
        }
        for (DynamicSessionEntity s : dynamic) {
            earliest = min(earliest, s.getStartedAt());
        }
        return earliest;
    }

    private Instant min(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private BigDecimal pct(BigDecimal initial, BigDecimal current) {
        if (initial == null || initial.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return current.subtract(initial)
                .divide(initial, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
