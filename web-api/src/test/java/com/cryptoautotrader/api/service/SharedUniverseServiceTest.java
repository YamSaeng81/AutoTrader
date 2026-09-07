package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 공용 유니버스 — 전략 비교를 성립시키는 통제 장치. 근거는 {@link SharedUniverseService} 참조.
 */
class SharedUniverseServiceTest {

    private WatchlistFilterService filter;
    private SharedUniverseService service;

    private static final BigDecimal ATR = new BigDecimal("0.5");
    private static final BigDecimal SPREAD = new BigDecimal("0.3");
    private static final WatchlistFilterService.QualityCriteria CRITERIA =
            WatchlistFilterService.QualityCriteria.disabled();

    @BeforeEach
    void setUp() {
        filter = mock(WatchlistFilterService.class);
        service = new SharedUniverseService(filter);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "forceTimeframe", "");
    }

    private List<String> resolve(String timeframe) {
        return service.resolve(30, 10, ATR, SPREAD, timeframe, 60, CRITERIA);
    }

    @Test
    void 같은_버킷_같은_기준이면_한_번만_구성하고_모두_같은_목록을_받는다() {
        when(filter.buildWatchlist(anyInt(), anyInt(), any(), any(), any(), any()))
                .thenReturn(List.of("KRW-BTC", "KRW-ETH"));

        List<String> a = resolve("H1");
        List<String> b = resolve("H1");
        List<String> c = resolve("H1");

        assertThat(a).isEqualTo(b).isEqualTo(c).containsExactly("KRW-BTC", "KRW-ETH");
        // 세션이 몇 개든 버킷당 1회만 구성된다 — 이게 "같은 코인을 본다"의 실체다.
        verify(filter, times(1)).buildWatchlist(anyInt(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void 타임프레임이_다르면_유니버스가_분리된다() {
        when(filter.buildWatchlist(anyInt(), anyInt(), any(), any(), eq("H1"), any()))
                .thenReturn(List.of("KRW-BTC"));
        when(filter.buildWatchlist(anyInt(), anyInt(), any(), any(), eq("M15"), any()))
                .thenReturn(List.of("KRW-ETH"));

        assertThat(resolve("H1")).containsExactly("KRW-BTC");
        assertThat(resolve("M15")).containsExactly("KRW-ETH");
    }

    @Test
    void force_timeframe_를_주면_전_세션이_단일_유니버스를_공유한다() {
        ReflectionTestUtils.setField(service, "forceTimeframe", "H1");
        when(filter.buildWatchlist(anyInt(), anyInt(), any(), any(), eq("H1"), any()))
                .thenReturn(List.of("KRW-BTC"));

        // M15 세션이 물어도 H1 유니버스를 받는다 → 타임프레임 간 비교까지 통제된다.
        assertThat(resolve("M15")).containsExactly("KRW-BTC");
        assertThat(resolve("H1")).containsExactly("KRW-BTC");
        verify(filter, times(1)).buildWatchlist(anyInt(), anyInt(), any(), any(), any(), any());
        assertThat(service.effectiveTimeframe("M15")).isEqualTo("H1");
    }

    /**
     * Upbit 일시 장애로 빈 목록이 나왔을 때 그걸 캐시하면 그 버킷 내내(최대 refreshMin 분)
     * 함대 전체가 스캔을 못 한다. 다음 호출이 다시 시도해야 한다.
     */
    @Test
    void 빈_결과는_캐시하지_않는다() {
        when(filter.buildWatchlist(anyInt(), anyInt(), any(), any(), any(), any()))
                .thenReturn(List.of())
                .thenReturn(List.of("KRW-BTC"));

        assertThat(resolve("H1")).isEmpty();
        assertThat(resolve("H1")).containsExactly("KRW-BTC"); // 재시도됨
        verify(filter, times(2)).buildWatchlist(anyInt(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void 필터_기준이_다르면_유니버스를_공유하지_않는다() {
        when(filter.buildWatchlist(anyInt(), anyInt(), any(), any(), any(), any()))
                .thenReturn(List.of("KRW-BTC"));

        service.resolve(30, 10, ATR, SPREAD, "H1", 60, CRITERIA);
        service.resolve(30, 20, ATR, SPREAD, "H1", 60, CRITERIA); // targetSize 다름

        verify(filter, times(2)).buildWatchlist(anyInt(), anyInt(), any(), any(), any(), any());
    }
}
