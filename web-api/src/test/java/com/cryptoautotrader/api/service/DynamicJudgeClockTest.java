package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.core.selector.BtcMarketGuard;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.157 §125-5 / v1.158 결정 4 — DYNAMIC 판단 시계 (H2, 84fb03d6 기점, 2026-11-12).
 *
 * <p>DYNAMIC 의 닫힌 봉 판정·캔들 최신성·캔들 조회 상한·BTC 관문이 LIVE(B01)·회차와 같은 {@link EntryClock} 을 보는지 확인한다. 시험 시계를
 * 과거(2020-01-01)에 두면 시스템 시계와 판정이 갈린다 — 기점은 시스템 시각을 보므로 실패해야 한다. 기본(시스템 UTC)에서는 결과가 같다.
 * 시험 시계는 매 시험 뒤 원래(시스템 UTC)로 되돌린다.</p>
 */
@DisplayName("v1.158 DYNAMIC 판단 시계")
class DynamicJudgeClockTest extends IntegrationTestBase {

    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private EntryClock entryClock;
    @MockBean private MarketDataSyncService marketDataSyncService;
    @MockBean private UpbitRestClient upbitRestClient;

    private static final Instant T = Instant.parse("2020-01-01T00:00:00Z");

    @AfterEach
    void restoreClock() {
        entryClock.setForTest(null);
    }

    private void judgeAt(Instant at) {
        entryClock.setForTest(Clock.fixed(at, ZoneOffset.UTC));
    }

    private Object target() {
        return AopTestUtils.getUltimateTargetObject(dynamicTradingService);
    }

    /** last 를 마지막 봉 시작으로 하는 연속 H1 봉 n 개. */
    private static List<Candle> h1(Instant last, int n) {
        List<Candle> out = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            BigDecimal p = BigDecimal.valueOf(1000 + i);
            out.add(Candle.builder().time(last.minus(i, ChronoUnit.HOURS)).open(p).high(p.add(BigDecimal.TEN))
                    .low(p.subtract(BigDecimal.TEN)).close(p).volume(new BigDecimal("100")).build());
        }
        return out;
    }

    @Test
    @DisplayName("닫힌 봉 판정 — 판단 시계 T+59분은 진행 중 봉 제외, T+60분은 포함")
    void closedCandleUsesJudgeClock() {
        List<Candle> c = h1(T, 3);
        judgeAt(T.plus(59, ChronoUnit.MINUTES));
        List<?> open = ReflectionTestUtils.invokeMethod(target(), "closedCandleSlice", c, "H1");
        judgeAt(T.plus(60, ChronoUnit.MINUTES));
        List<?> closed = ReflectionTestUtils.invokeMethod(target(), "closedCandleSlice", c, "H1");
        System.out.println("[DC158] 닫힌 봉 T+59=" + open.size() + " T+60=" + closed.size());
        assertThat(open).hasSize(2);
        assertThat(closed).hasSize(3);
    }

    @Test
    @DisplayName("캔들 최신성 — 판단 시계 기준으로 최신이면 통과(시스템 시각 기준이면 낡음)")
    void freshnessUsesJudgeClock() {
        List<Candle> c = h1(T, 200);
        judgeAt(T.plus(61, ChronoUnit.MINUTES));   // 마지막 봉 T 가 막 완료된 시각
        List<?> fresh = ReflectionTestUtils.invokeMethod(target(), "freshCandles", "KRW-DCX", "H1", c);
        System.out.println("[DC158] 최신성 판단시계=T+61분 → " + fresh.size() + "개");
        assertThat(fresh).as("판단 시계 기준 최신").isNotEmpty();
    }

    @Test
    @DisplayName("캔들 조회 상한 — 판단 시각 뒤의 봉은 넣지 않는다")
    void queryUpperBound() {
        List<Candle> c = h1(T.plus(2, ChronoUnit.HOURS), 10);
        Mockito.when(marketDataSyncService.fetchWithCache(Mockito.eq("KRW-DCQ"), Mockito.eq("H1"), Mockito.anyInt())).thenReturn(c);
        judgeAt(T.plus(61, ChronoUnit.MINUTES));
        List<Candle> got = ReflectionTestUtils.invokeMethod(target(), "loadCandles", "KRW-DCQ", "H1");
        System.out.println("[DC158] 조회 상한 T+61분 → " + got.size() + "개, 마지막=" + got.get(got.size() - 1).getTime());
        assertThat(got).hasSize(9);   // T+2h 봉 제외(T+1h 는 T+61분 이전 시작)
        assertThat(got.get(got.size() - 1).getTime()).isEqualTo(T.plus(1, ChronoUnit.HOURS));
    }

    @Test
    @DisplayName("BTC 관문 — 판단 시계 기준 최신 BTC 캔들이면 판정 가능")
    void btcGateUsesJudgeClock() {
        List<Candle> btc = h1(T, 200);
        Mockito.when(marketDataSyncService.fetchWithCache(Mockito.eq("KRW-BTC"), Mockito.eq("H1"), Mockito.anyInt())).thenReturn(btc);
        judgeAt(T.plus(61, ChronoUnit.MINUTES));
        BtcMarketGuard.Result r = ReflectionTestUtils.invokeMethod(target(), "btcMarketGate", "H1");
        System.out.println("[DC158] BTC 관문 판단시계=T+61분 → unknown=" + r.unknown() + " " + r.reason());
        assertThat(r.unknown()).as("판단 시계 기준 BTC 캔들 최신 — 판정 가능").isFalse();
    }

    @Test
    @DisplayName("기본 시계(시스템 UTC) — 진행 중 봉 제외가 종전과 같다")
    void defaultSystemClockUnchanged() {
        entryClock.setForTest(null);
        Instant lastOpen = Instant.now().truncatedTo(ChronoUnit.HOURS);
        List<?> slice = ReflectionTestUtils.invokeMethod(target(), "closedCandleSlice", h1(lastOpen, 3), "H1");
        System.out.println("[DC158] 기본 시계 진행 중 봉 → " + slice.size());
        assertThat(slice).hasSize(2);
    }
}
