package com.cryptoautotrader.api.report;

import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.UpbitCandleResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketTrendScannerTest {

    private final UpbitRestClient client = mock(UpbitRestClient.class);
    private final MarketTrendScanner scanner = new MarketTrendScanner(client);

    /**
     * 시간봉 200개(newest-first)를 만든다. index 0=현재가 가장 높고 과거로 갈수록 낮음(상승 추세).
     * price[i] = base - i  →  now > 24h전 > 48h전, 현재가 > EMA.
     */
    private List<UpbitCandleResponse> ascendingCandles(int n, double base) {
        List<UpbitCandleResponse> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double p = base - i; // newest-first: 0이 최신·최고가
            UpbitCandleResponse c = new UpbitCandleResponse();
            c.setTradePrice(BigDecimal.valueOf(p));
            c.setHighPrice(BigDecimal.valueOf(p + 1));
            c.setLowPrice(BigDecimal.valueOf(p - 1));
            c.setOpeningPrice(BigDecimal.valueOf(p));
            list.add(c);
        }
        return list;
    }

    @Test
    void scanTopCoins_computes48hAnd24hChangeTrendAndVolatility() throws Exception {
        when(client.getMarkets()).thenReturn(List.of(
                Map.of("market", "KRW-BTC", "korean_name", "비트코인"),
                Map.of("market", "USDT-XYZ", "korean_name", "제외대상"))); // KRW- 아니면 제외
        when(client.getTicker(anyString())).thenReturn(List.of(
                Map.of("market", "KRW-BTC", "acc_trade_price_24h", 1_000_000_000_000.0, "trade_price", 200.0)));
        when(client.getCandles(eq("KRW-BTC"), anyString(), anyInt(), any(Instant.class), anyInt()))
                .thenReturn(ascendingCandles(200, 200.0));

        List<MarketTrendScanner.CoinTrend> trends = scanner.scanTopCoins(5);

        assertThat(trends).hasSize(1);
        MarketTrendScanner.CoinTrend t = trends.get(0);
        assertThat(t.market()).isEqualTo("KRW-BTC");
        assertThat(t.koreanName()).isEqualTo("비트코인");
        // now=200, 48h전=152 → +31.58%, 24h전=176 → +13.64%
        assertThat(t.change48hPct()).isEqualByComparingTo(new BigDecimal("31.58"));
        assertThat(t.change24hPct()).isEqualByComparingTo(new BigDecimal("13.64"));
        assertThat(t.change48hPct()).isGreaterThan(t.change24hPct()); // 48h 상승폭이 더 큼
        assertThat(t.uptrend()).isTrue();                              // 현재가 > EMA
        assertThat(t.atrPct()).isNotNull().isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void scanTopCoins_returnsEmptyWhenCandlesInsufficient() throws Exception {
        when(client.getMarkets()).thenReturn(List.of(
                Map.of("market", "KRW-BTC", "korean_name", "비트코인")));
        when(client.getTicker(anyString())).thenReturn(List.of(
                Map.of("market", "KRW-BTC", "acc_trade_price_24h", 1_000_000_000_000.0, "trade_price", 200.0)));
        when(client.getCandles(eq("KRW-BTC"), anyString(), anyInt(), any(Instant.class), anyInt()))
                .thenReturn(ascendingCandles(10, 200.0)); // 48h(49개) 미만 → 제외

        assertThat(scanner.scanTopCoins(5)).isEmpty();
    }
}
