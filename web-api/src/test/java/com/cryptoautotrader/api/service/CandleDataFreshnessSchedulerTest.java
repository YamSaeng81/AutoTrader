package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.repository.CandleDataRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 캔들 자동 갱신이 <b>조용히 손을 떼지 않는지</b> 고정한다 — 2026-09-18 신설.
 *
 * <h3>무엇이 문제였나</h3>
 * <p>09-18 실측에서 {@code candle_data} H1 이 이렇게 밀려 있었다:</p>
 * <pre>
 *   2026-09-17   2종   ← ETH·XRP (고정 격자)
 *   2026-09-07   8종
 *   2026-08-30  30종   ← 19일 밀림
 * </pre>
 *
 * <p>원인이 둘이었고 <b>둘 다 "에러 없이 조용히"</b> 동작했다.</p>
 * <ol>
 *   <li><b>갭이 7일을 넘으면 영구히 포기했다.</b> 갭은 매일 커지기만 하므로 한 번 한계를
 *       넘으면 돌아오지 못한다 — 설정된 8종 중 6종이 이미 그 상태였다.</li>
 *   <li><b>대상이 8종 하드코딩이었다.</b> 동적 워치리스트 코인이 통째로 빠져, NEAR 는
 *       60일간 3,885회 평가됐는데 캔들이 0건이었다. 감시는 하면서 검증할 수단이 없었다.</li>
 * </ol>
 *
 * <p>백테스트·WF 는 {@code candle_data} 를 읽는다. 밀린 채로 두면 판정이 최근 구간을
 * 못 보고 나오며, 그 사실이 결과 어디에도 드러나지 않는다.</p>
 */
class CandleDataFreshnessSchedulerTest {

    private CandleDataRepository candleRepo;
    private StrategyLogRepository logRepo;
    private DataCollectionService collector;
    private TelegramNotificationService telegram;
    private CandleDataFreshnessScheduler scheduler;

    /** 요청 사이 대기를 0 으로 만들어 테스트가 실제로 잠들지 않게 한다. */
    private static final int NO_SLEEP = 0;

    @BeforeEach
    void setUp() {
        candleRepo = mock(CandleDataRepository.class);
        logRepo = mock(StrategyLogRepository.class);
        collector = mock(DataCollectionService.class);
        telegram = mock(TelegramNotificationService.class);

        scheduler = new CandleDataFreshnessScheduler(candleRepo, logRepo, collector, telegram);
        ReflectionTestUtils.setField(scheduler, "coinsCsv", "KRW-BTC");
        ReflectionTestUtils.setField(scheduler, "timeframesCsv", "H1");
        ReflectionTestUtils.setField(scheduler, "enabled", true);

        when(logRepo.findRecentlyWatchedCoins(any(), anyLong())).thenReturn(List.of());
    }

    /** {@code findDataSummary} 가 돌려주는 형태: [coinPair, timeframe, min, max, count] */
    private void lastCandle(String coin, String tf, Instant max) {
        List<Object[]> rows = new ArrayList<>(candleRepo.findDataSummary());
        rows.add(new Object[]{coin, tf, max.minus(Duration.ofDays(365)), max, 1000L});
        when(candleRepo.findDataSummary()).thenReturn(rows);
    }

    private List<LocalDate[]> capturedRanges() {
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(collector, atLeast(0)).collectCandles(any(), any(), from.capture(), to.capture());
        List<LocalDate[]> out = new ArrayList<>();
        for (int i = 0; i < from.getAllValues().size(); i++) {
            out.add(new LocalDate[]{from.getAllValues().get(i), to.getAllValues().get(i)});
        }
        return out;
    }

    @Test
    @DisplayName("19일 밀린 갭도 포기하지 않고 잘라서 전부 요청한다 — 종전에는 건너뛰었다")
    void 긴_갭도_쪼개서_수집한다() {
        // 09-18 운영 실측과 같은 상황: 마지막 캔들이 19일 전.
        when(candleRepo.findDataSummary()).thenReturn(new ArrayList<>());
        lastCandle("KRW-BTC", "H1", Instant.now().minus(Duration.ofDays(19)));
        ReflectionTestUtils.setField(scheduler, "spacingSeconds", NO_SLEEP);

        scheduler.refreshNow();

        List<LocalDate[]> ranges = capturedRanges();
        assertThat(ranges)
                .as("갭이 상한을 넘으면 건너뛰던 동작이 남아 있으면 요청이 0건이다 — "
                        + "갭은 매일 커지므로 그 코인은 영원히 복구되지 않는다")
                .isNotEmpty();

        // 조각마다 상한을 지켜야 한다. all-or-nothing 수집이 긴 사슬에서 통째로 실패하는 것을
        // 막는 것이 원래 상한의 목적이고, 그 목적은 "요청을 안 한다"가 아니라 "짧게 끊는다"이다.
        for (LocalDate[] r : ranges) {
            assertThat(Duration.between(r[0].atStartOfDay(), r[1].atStartOfDay()).toDays())
                    .as("조각 하나가 상한보다 길면 요청 사슬이 길어져 수집이 통째로 실패한다")
                    .isLessThanOrEqualTo(7);
        }

        // 조각들이 마지막 캔들부터 오늘까지를 실제로 덮어야 한다.
        assertThat(ranges.get(0)[0])
                .isBeforeOrEqualTo(LocalDate.now().minusDays(18));
        assertThat(ranges.get(ranges.size() - 1)[1])
                .as("마지막 조각이 오늘까지 닿지 않으면 갭이 남아 다음 날 또 밀린다")
                .isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("설정에 없어도 최근 감시한 코인은 갱신 대상에 들어간다")
    void 감시_기록의_코인도_대상이다() {
        when(candleRepo.findDataSummary()).thenReturn(new ArrayList<>());
        lastCandle("KRW-BTC", "H1", Instant.now().minus(Duration.ofDays(3)));
        lastCandle("KRW-ONDO", "H1", Instant.now().minus(Duration.ofDays(3)));
        // 설정(coinsCsv)에는 BTC 만 있다. ONDO 는 감시 기록에서만 나온다.
        when(logRepo.findRecentlyWatchedCoins(any(), anyLong())).thenReturn(List.of("KRW-ONDO"));
        ReflectionTestUtils.setField(scheduler, "spacingSeconds", NO_SLEEP);

        scheduler.refreshNow();

        ArgumentCaptor<String> coin = ArgumentCaptor.forClass(String.class);
        verify(collector, atLeastOnce()).collectCandles(coin.capture(), any(), any(), any());
        assertThat(coin.getAllValues())
                .as("대상이 설정 하드코딩뿐이면 동적 워치리스트가 통째로 빠진다 — "
                        + "NEAR 가 60일간 3,885회 평가되고도 캔들 0건이었던 이유다")
                .contains("KRW-ONDO");
    }

    @Test
    @DisplayName("캔들 이력이 아예 없으면 텔레그램으로 알린다 — 로그만 남기면 아무도 안 본다")
    void 이력_없는_조합은_알림을_보낸다() {
        // BTC 는 있고, 감시 기록의 KAITO 는 캔들이 0건이다.
        when(candleRepo.findDataSummary()).thenReturn(new ArrayList<>());
        lastCandle("KRW-BTC", "H1", Instant.now());
        when(logRepo.findRecentlyWatchedCoins(any(), anyLong())).thenReturn(List.of("KRW-KAITO"));
        ReflectionTestUtils.setField(scheduler, "spacingSeconds", NO_SLEEP);

        scheduler.refreshNow();

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(telegram).sendCustomNotification(msg.capture());
        assertThat(msg.getValue())
                .as("09-14 에 같은 문제를 고치고도 09-18 에 30종이 밀려 있었다 — "
                        + "log.warn 만 남기면 조용히 실패한다")
                .contains("KRW-KAITO");
    }

    @Test
    @DisplayName("요청 상한에 걸려도 '이력 없음' 은 전부 감지해 알린다 — 2026-09-20 실측 결함")
    void 상한에_걸려도_이력없음은_빠짐없이_알린다() {
        // 09-20 운영 실측: H1 에서 상한에 걸려 break 하는 바람에 **M15 는 평가조차 되지 않았다.**
        // 그래서 "이력 없음" 알림이 아예 오지 않고 상한 알림만 왔다. 분류는 맵 조회뿐이라
        // 비용이 없으므로 요청 상한과 무관하게 끝까지 훑어야 한다.
        ReflectionTestUtils.setField(scheduler, "timeframesCsv", "H1,M15");
        ReflectionTestUtils.setField(scheduler, "spacingSeconds", NO_SLEEP);
        when(candleRepo.findDataSummary()).thenReturn(new ArrayList<>());

        // H1 은 밀린 코인을 잔뜩 둬서 상한(40)을 확실히 넘긴다.
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            String coin = "KRW-C" + i;
            many.add(coin);
            lastCandle(coin, "H1", Instant.now().minus(Duration.ofDays(30)));
        }
        // M15 는 어느 코인도 이력이 없다 — 전부 '이력 없음' 으로 잡혀야 한다.
        when(logRepo.findRecentlyWatchedCoins(any(), anyLong())).thenReturn(many);

        scheduler.refreshNow();

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(telegram, atLeastOnce()).sendCustomNotification(msg.capture());
        assertThat(msg.getAllValues())
                .as("상한에 걸렸다고 뒤쪽 조합을 아예 훑지 않으면, 캔들이 없는 조합이 "
                        + "**영원히 보고되지 않는다** — 조용히 실패하는 바로 그 형태다")
                .anyMatch(m -> m.contains("M15"));
    }

    @Test
    @DisplayName("상한이 있을 때 가장 많이 밀린 조합부터 채운다 — 뒤쪽이 굶으면 안 된다")
    void 가장_많이_밀린_것부터_처리한다() {
        ReflectionTestUtils.setField(scheduler, "spacingSeconds", NO_SLEEP);
        when(candleRepo.findDataSummary()).thenReturn(new ArrayList<>());

        // 목록 '앞쪽' 에 살짝 밀린 코인을 잔뜩, '뒤쪽' 에 크게 밀린 코인 하나를 둔다.
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String coin = "KRW-M" + i;
            order.add(coin);
            lastCandle(coin, "H1", Instant.now().minus(Duration.ofDays(3)));
        }
        order.add("KRW-STARVED");
        lastCandle("KRW-STARVED", "H1", Instant.now().minus(Duration.ofDays(60)));
        when(logRepo.findRecentlyWatchedCoins(any(), anyLong())).thenReturn(order);

        scheduler.refreshNow();

        ArgumentCaptor<String> coin = ArgumentCaptor.forClass(String.class);
        verify(collector, atLeastOnce()).collectCandles(coin.capture(), any(), any(), any());
        assertThat(coin.getAllValues())
                .as("고정 순서로 돌면서 상한에 걸리면 목록 뒤쪽은 **매 실행마다** 도달하지 못해 "
                        + "영원히 굶는다. 밀린 순으로 처리해야 스스로 균형이 맞는다")
                .contains("KRW-STARVED");
    }

    @Test
    @DisplayName("최신이면 아무 요청도 하지 않는다 — 매일 1일짜리 꼬리 요청이 반복되면 안 된다")
    void 최신이면_요청하지_않는다() {
        when(candleRepo.findDataSummary()).thenReturn(new ArrayList<>());
        lastCandle("KRW-BTC", "H1", Instant.now());

        scheduler.refreshNow();

        verify(collector, never()).collectCandles(any(), any(), any(), any());
    }

    @Test
    @DisplayName("감시 기록 조회가 실패해도 설정 목록으로는 계속 돈다")
    void 감시기록_조회_실패해도_멈추지_않는다() {
        when(candleRepo.findDataSummary()).thenReturn(new ArrayList<>());
        lastCandle("KRW-BTC", "H1", Instant.now().minus(Duration.ofDays(3)));
        when(logRepo.findRecentlyWatchedCoins(any(), anyLong()))
                .thenThrow(new RuntimeException("DB 장애"));
        ReflectionTestUtils.setField(scheduler, "spacingSeconds", NO_SLEEP);

        scheduler.refreshNow();

        verify(collector, atLeastOnce()).collectCandles(eq("KRW-BTC"), any(), any(), any());
    }
}
