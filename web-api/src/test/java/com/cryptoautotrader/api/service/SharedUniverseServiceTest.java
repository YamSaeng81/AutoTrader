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
        ReflectionTestUtils.setField(service, "refreshMinutes", 30);
    }

    private List<String> resolve(String timeframe) {
        return service.resolve(30, 10, ATR, SPREAD, timeframe, CRITERIA);
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

    /**
     * 회귀 — 2026-09-07 운영에서 실제로 났던 분할.
     *
     * <p>버킷 폭을 세션의 {@code watchlist_refresh_min} 으로 잡았더니, 함대가 30분(8세션)/
     * 60분(6세션) 두 그룹으로 갈려 있어 <b>같은 순간에도 버킷 번호가 달라</b> 유니버스가 둘로
     * 쪼개졌다. 통제가 절반만 된 것이다. 버킷은 함대 공통 설정 하나로만 정해져야 한다.</p>
     */
    @Test
    void 세션의_갱신주기가_달라도_유니버스는_쪼개지지_않는다() {
        // 운영과 같은 설정 — force-timeframe 을 줘야 타임프레임 축이 통제되고
        // 갱신주기 축만 남는다. (비워두면 H1/M15 가 갈리는 건 의도된 동작이다.)
        ReflectionTestUtils.setField(service, "forceTimeframe", "H1");
        when(filter.buildWatchlist(anyInt(), anyInt(), any(), any(), any(), any()))
                .thenReturn(List.of("KRW-BTC"));

        // 예전 운영 함대: 갱신주기 30분 그룹(81~91)과 60분 그룹(76~89)이 섞여 있었다.
        // 이제 resolve() 는 세션 주기를 아예 받지 않으므로 둘 다 같은 버킷에 떨어진다.
        List<String> a = resolve("M15");   // 과거 30분 그룹에 해당
        List<String> b = resolve("H1");    // 과거 60분 그룹에 해당

        assertThat(a).isEqualTo(b);
        verify(filter, times(1)).buildWatchlist(anyInt(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void 필터_기준이_다르면_유니버스를_공유하지_않는다() {
        when(filter.buildWatchlist(anyInt(), anyInt(), any(), any(), any(), any()))
                .thenReturn(List.of("KRW-BTC"));

        service.resolve(30, 10, ATR, SPREAD, "H1", CRITERIA);
        service.resolve(30, 20, ATR, SPREAD, "H1", CRITERIA); // targetSize 다름

        verify(filter, times(2)).buildWatchlist(anyInt(), anyInt(), any(), any(), any(), any());
    }
}
