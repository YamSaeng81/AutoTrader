package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.dailysma.DailySmaTrendStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.177 D4·v1.178 §4-3 — SMA20 전향 모의(SMA20-FWD-1)의 <b>생성 → 평가 → 첫 09:00 KST(T0)</b> 경로를 운영 코드·공통 판단 시계로 확인한다
 * (2026-11-17, 시험만 — 운영 코드 변경 없음).
 *
 * <p>운영 등록 전략 인스턴스(기본 생성자, 일봉 마감 23:00 UTC)를 그대로 쓴다. 판단 시계({@link EntryClock})를 사례 시각에 고정하고,
 * 운영 스케줄러가 세션마다 부르는 {@code runSessionStrategy} 와 회차 승인 {@link EntryPriorityRounds#processDue} 를 부른다. 세션은 운영 생성 경로
 * {@link PaperTradingService#start}(LIVE_ALIGNED·forwardTag). 봉은 market_data_cache H1 상승 추세(마감 종가 &gt; SMA20).</p>
 *
 * <p>🔴 시계 범위: 판단(봉 조회·최신성·닫힌 봉·회차 접수/승인/만료)은 고정 시계다. 세션 started_at·포지션 opened_at·주문 filled_at 은
 * 운영 코드가 시스템 시계를 쓰므로 이 시험에서는 실제 현재 시각이다(운영에서는 두 시계가 같다). 그래서 "T0 전 주문 0"·"진입 시각"은
 * <b>어느 고정 시각의 평가/승인 직후에 처음 포지션·주문이 생기는가</b>로 본다(opened_at 값은 표에만 남기고 판정에 쓰지 않는다 —
 * 1회차는 opened_at 을 판단 시계로 잘못 가정해 실패했다). DAILY_SMA20 은 sessionStartedAt 을 읽지 않는다(소스 확인).</p>
 *
 * <p>기대는 실행 전에 소스 읽기로 적었다. 관측이 다르면 실패한다 — 기대를 고쳐 통과시키지 않는다.
 * T0 = 생성 뒤 첫 00:00 UTC. 사례 날짜는 서로 달라 회차 키가 겹치지 않는다.</p>
 */
@DisplayName("SMA20 생성→첫 09:00 KST T0 — 고정 판단 시계, 회차/직접 진입, 09시대 생성 대조, 재기동·늦은 봉")
@org.springframework.test.context.TestPropertySource(properties = "test.fixture.entry-rounds-context=Sma20FirstNineT0PathTest")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class Sma20FirstNineT0PathTest extends AbstractSma20SameInputFixture {

    @MockBean private UpbitWebSocketClient upbitWebSocketClient;

    @Override
    protected Map<String, Object> paperParams() {
        return Map.of(PaperMaxInvestment.KEY, 20000, PaperExecutionMode.KEY, PaperExecutionMode.LIVE_ALIGNED, "forwardTag", "SMA20-FWD-1");
    }

    @Override
    protected String reportName() {
        return "sma20-first-0900-t0";
    }

    // ── 도움 ────────────────────────────────────────────────────────

    /** T0 까지(포함) 닫힌/진행 H1 봉 — t0 − 540시간부터 t0 시작 봉까지, 시간당 +5,000원. */
    private List<Candle> barsUntil(Instant t0) {
        List<Candle> out = new ArrayList<>();
        int n = 541;
        for (int i = 0; i < n; i++) {
            BigDecimal c = BigDecimal.valueOf(10_000_000L + 5_000L * i);
            BigDecimal o = BigDecimal.valueOf(10_000_000L + 5_000L * Math.max(0, i - 1));
            out.add(Candle.builder().time(t0.minus(n - 1 - i, ChronoUnit.HOURS))
                    .open(o).high(c.max(o).add(BigDecimal.valueOf(1000))).low(c.min(o).subtract(BigDecimal.valueOf(1000)))
                    .close(c).volume(new BigDecimal("100")).build());
        }
        return out;
    }

    private void judgeAt(Instant t) {
        entryClock.setForTest(Clock.fixed(t, ZoneOffset.UTC));
    }

    private long orders(VirtualBalanceEntity s) {
        return paperOrderRepo.findAll().stream().filter(o -> s.getId().equals(o.getSessionId())).count();
    }

    /** 고정 시계 t 에서 한 번 평가하고 (포지션 수, 주문 수, 마지막 신호 로그)를 결과 표에 남긴다. */
    private String evalAt(String sc, String step, VirtualBalanceEntity s, Instant t) {
        judgeAt(t);
        runPaper(s);
        String log = lastLog("PAPER", s.getId());
        String obs = "포지션 " + paperPositions(s).size() + " · 주문 " + orders(s) + " · " + log;
        ROWS.add(new String[]{sc, step + " @" + t + " (KST " + t.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalTime() + ")", "-", s(obs), "-", "-"});
        return obs;
    }

    private List<EntryPriorityRounds.Outcome> approveAt(Instant bar23) {
        EntryPriorityRounds.RoundKey k = new EntryPriorityRounds.RoundKey("H1", bar23);
        judgeAt(EntryPriorityRounds.deadline(k));
        return rounds.processDue(EntryPriorityRounds.deadline(k));
    }

    /** 운영 재기동과 같은 메모리 상태 — 마지막 평가 봉·세션 상태 전략 인스턴스를 비운다(DB 상태는 그대로). */
    @SuppressWarnings("unchecked")
    private void simulateRestartMemory() {
        Object target = AopTestUtils.getUltimateTargetObject(paperTradingService);
        ((Map<Long, ?>) ReflectionTestUtils.getField(target, "lastEvaluatedClosedCandle")).clear();
        ((Map<Long, ?>) ReflectionTestUtils.getField(target, "sessionStatefulStrategies")).clear();
    }

    private void realStrategy() {
        StrategyRegistry.register(new DailySmaTrendStrategy());   // 운영 등록과 같은 인스턴스(23:00 UTC 마감)
    }

    /** opened_at 은 시스템 시계 값 — 판정에 쓰지 않고 표에만 남긴다. */
    private void openedRow(String sc, VirtualBalanceEntity p) {
        ROWS.add(new String[]{sc, "opened_at(시스템 시계, 판정 미사용)", "-", s(paperPositions(p).get(0).getOpenedAt()), "-", "-"});
    }

    private void assertNone(String obs, String what) {
        assertThat(obs).as(what).startsWith("포지션 0 · 주문 0");
    }

    // ── 사례 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("T1 회차 경로(운영과 같이 회차 활성) — 14:00 KST 생성: 08:59 까지 HOLD·주문0, 재기동 모사도 HOLD, 09:00:30 접수 → 09:05 승인 진입(T0 이후)")
    void t1RoundsCreateAfternoon() {
        realStrategy();
        Instant t0 = Instant.parse("2026-11-03T00:00:00Z");
        Instant bar23 = t0.minus(1, ChronoUnit.HOURS);
        lastOpen = bar23;
        insertCache(BTC, barsUntil(t0));
        rounds.setForcedForTest(true);
        judgeAt(Instant.parse("2026-11-02T05:00:30Z"));
        VirtualBalanceEntity p = paper(BTC);
        String sc = "T1 회차·14:00 생성";
        for (String at : List.of("2026-11-02T05:00:30Z", "2026-11-02T06:00:30Z", "2026-11-02T15:00:30Z")) {
            assertNone(evalAt(sc, "평가", p, Instant.parse(at)), at);
        }
        simulateRestartMemory();
        assertNone(evalAt(sc, "재기동 모사 뒤 평가", p, Instant.parse("2026-11-02T15:10:30Z")), "재기동 뒤");
        for (String at : List.of("2026-11-02T23:00:30Z", "2026-11-02T23:59:30Z")) {
            assertNone(evalAt(sc, "평가", p, Instant.parse(at)), at);
        }
        String atT0 = evalAt(sc, "T0+30초 평가(접수)", p, t0.plusSeconds(30));
        assertNone(atT0, "접수만 — 승인 전 포지션 0");
        assertThat(atT0).contains("BUY");
        List<EntryPriorityRounds.Outcome> out = approveAt(bar23);
        ROWS.add(new String[]{sc, "회차 승인 @" + EntryPriorityRounds.deadline(new EntryPriorityRounds.RoundKey("H1", bar23)), "-", s(out), "-", "-"});
        assertThat(paperPositions(p)).hasSize(1);
        assertThat(EntryPriorityRounds.deadline(new EntryPriorityRounds.RoundKey("H1", bar23))).as("첫 진입 단계의 판단 시각 ≥ T0").isAfterOrEqualTo(t0);
        assertThat(orders(p)).isEqualTo(1);
        openedRow(sc, p);
    }

    @Test
    @DisplayName("T2 회차 경로 — 09:00 KST 의 23:00 UTC 봉이 늦게 들어옴: 09:00:30 판정 불가·주문0 → 봉 도착 뒤 09:01:30 접수 → 승인 진입(T0 이후)")
    void t2RoundsLateDailyBar() {
        realStrategy();
        Instant t0 = Instant.parse("2026-11-05T00:00:00Z");
        Instant bar23 = t0.minus(1, ChronoUnit.HOURS);
        lastOpen = bar23;
        List<Candle> all = barsUntil(t0);
        insertCache(BTC, all.subList(0, all.size() - 2));   // 23:00·00:00 봉 없음
        rounds.setForcedForTest(true);
        judgeAt(Instant.parse("2026-11-04T05:00:30Z"));
        VirtualBalanceEntity p = paper(BTC);
        String sc = "T2 회차·늦은 일봉";
        assertNone(evalAt(sc, "평가", p, Instant.parse("2026-11-04T05:00:30Z")), "생성 직후");
        String late = evalAt(sc, "T0+30초(23:00 봉 없음)", p, t0.plusSeconds(30));
        assertNone(late, "봉 없음");
        insertCache(BTC, all.subList(all.size() - 2, all.size()));
        String atLate = evalAt(sc, "봉 도착 뒤 T0+90초(접수)", p, t0.plusSeconds(90));
        assertNone(atLate, "승인 전");
        assertThat(atLate).contains("BUY");
        approveAt(bar23);
        assertThat(paperPositions(p)).hasSize(1);
        assertThat(EntryPriorityRounds.deadline(new EntryPriorityRounds.RoundKey("H1", bar23))).isAfterOrEqualTo(t0);
        openedRow(sc, p);
    }

    @Test
    @DisplayName("T3 회차 경로 대조 — 09:02 KST(00:02 UTC) 생성: 첫 평가가 23:00 봉 마감을 보고 접수 → 09:05 진입(다음 날 T0 전) — 09시대 생성 제외의 근거")
    void t3RoundsCreateAt0902() {
        realStrategy();
        Instant day = Instant.parse("2026-11-07T00:00:00Z");
        Instant bar23 = day.minus(1, ChronoUnit.HOURS);
        lastOpen = bar23;
        insertCache(BTC, barsUntil(day));
        rounds.setForcedForTest(true);
        judgeAt(day.plus(2, ChronoUnit.MINUTES));
        VirtualBalanceEntity p = paper(BTC);
        String obs = evalAt("T3 회차·09:02 생성", "첫 평가(접수)", p, day.plus(2, ChronoUnit.MINUTES));
        assertThat(obs).contains("BUY");
        approveAt(bar23);
        ROWS.add(new String[]{"T3 회차·09:02 생성", "승인 뒤", "-", s("포지션 " + paperPositions(p).size()), "-", "-"});
        assertThat(paperPositions(p)).as("09:02 생성은 다음 날 T0(" + day.plus(1, ChronoUnit.DAYS) + ") 전에 진입한다").hasSize(1);
        assertThat(EntryPriorityRounds.deadline(new EntryPriorityRounds.RoundKey("H1", bar23))).as("진입 단계 판단 시각 < 다음 날 T0")
                .isBefore(day.plus(1, ChronoUnit.DAYS));
        openedRow("T3 회차·09:02 생성", p);
    }

    @Test
    @DisplayName("T4 회차 경로 대조 — 09:30 KST 생성: 신호 BUY 지만 회차 마감(09:05) 뒤 도착이라 접수 안 됨·포지션 0")
    void t4RoundsCreateAt0930() {
        realStrategy();
        Instant day = Instant.parse("2026-11-09T00:00:00Z");
        Instant bar23 = day.minus(1, ChronoUnit.HOURS);
        lastOpen = bar23;
        insertCache(BTC, barsUntil(day));
        rounds.setForcedForTest(true);
        judgeAt(day.plus(30, ChronoUnit.MINUTES));
        VirtualBalanceEntity p = paper(BTC);
        String obs = evalAt("T4 회차·09:30 생성", "첫 평가", p, day.plus(30, ChronoUnit.MINUTES));
        assertThat(obs).contains("BUY");
        List<EntryPriorityRounds.Outcome> out = approveAt(bar23);
        ROWS.add(new String[]{"T4 회차·09:30 생성", "승인 시도", "-", s(out), "-", "-"});
        assertThat(paperPositions(p)).isEmpty();
        assertThat(orders(p)).isZero();
    }

    @Test
    @DisplayName("T5 직접 경로(회차 비활성) — 14:00 KST 생성: 08:59 까지 주문0, 09:00:30 평가에서 바로 진입(T0 이후)")
    void t5DirectCreateAfternoon() {
        realStrategy();
        Instant t0 = Instant.parse("2026-11-11T00:00:00Z");
        lastOpen = t0.minus(1, ChronoUnit.HOURS);
        insertCache(BTC, barsUntil(t0));
        rounds.setForcedForTest(false);
        assertThat(rounds.isActive()).as("시험 단계 이름은 등록 단계가 아니다 → 회차 비활성").isFalse();
        judgeAt(Instant.parse("2026-11-10T05:00:30Z"));
        VirtualBalanceEntity p = paper(BTC);
        String sc = "T5 직접·14:00 생성";
        for (String at : List.of("2026-11-10T05:00:30Z", "2026-11-10T15:00:30Z", "2026-11-10T23:59:30Z")) {
            assertNone(evalAt(sc, "평가", p, Instant.parse(at)), at);
        }
        evalAt(sc, "T0+30초 평가", p, t0.plusSeconds(30));
        assertThat(paperPositions(p)).as("첫 진입은 T0+30초 평가에서").hasSize(1);
        assertThat(orders(p)).isEqualTo(1);
        openedRow(sc, p);
    }

    @Test
    @DisplayName("T6 직접 경로 대조 — 09:30 KST 생성: 첫 평가에서 바로 진입(다음 날 T0 전) — 09시대 생성 제외의 근거")
    void t6DirectCreateAt0930() {
        realStrategy();
        Instant day = Instant.parse("2026-11-13T00:00:00Z");
        lastOpen = day.minus(1, ChronoUnit.HOURS);
        insertCache(BTC, barsUntil(day));
        rounds.setForcedForTest(false);
        judgeAt(day.plus(30, ChronoUnit.MINUTES));
        VirtualBalanceEntity p = paper(BTC);
        evalAt("T6 직접·09:30 생성", "첫 평가", p, day.plus(30, ChronoUnit.MINUTES));
        assertThat(paperPositions(p)).as("09:30 첫 평가(다음 날 T0 전)에서 진입").hasSize(1);
        openedRow("T6 직접·09:30 생성", p);
    }
}
