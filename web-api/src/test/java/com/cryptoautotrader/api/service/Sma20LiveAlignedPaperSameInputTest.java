package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.dto.PaperTradingStartRequest;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.dailysma.DailySmaTrendStrategy;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

/**
 * 같은 입력 LIVE ↔ <b>PAPER LIVE_ALIGNED</b>({@code paperExecutionMode=LIVE_ALIGNED} + 상한 20,000) — 후보가 판단 흐름을 맞추는지.
 *
 * <p>기대(실행 전 기록): 진입·거절·청산 <b>결정과 사유 계열</b>이 같다. 다름으로 남기는 것은 두 갈래뿐이다 —
 * ① 어댑터 가정(PAPER 체결 슬리피지 0.1%·즉시 체결 / LIVE 거래소 접수 뒤 정산), ② REAL 계좌 단위 관문(전역 위험 한도·공유 풀 한도)으로
 * 모의 계좌에 대응물이 없는 것. 결과 표 {@code build/sma20-same-input/aligned.tsv}.</p>
 */
@DisplayName("SMA20 같은 입력 LIVE↔PAPER(LIVE_ALIGNED) — 판단 흐름 정렬 확인")
@org.springframework.test.context.TestPropertySource(properties = "test.fixture.entry-rounds-context=Sma20LiveAlignedPaperSameInputTest")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class Sma20LiveAlignedPaperSameInputTest extends AbstractSma20SameInputFixture {

    /** 새 구독 출처가 실제 Upbit 연결을 열지 않게 한다(외부 호출 0). */
    @MockBean private UpbitWebSocketClient upbitWebSocketClient;
    @Autowired private WsSubscriptionManager wsSubscriptionManager;
    @Autowired private OrderExecutionEngine orderExecutionEngine;

    private static final BigDecimal SLIP = new BigDecimal("1.001");

    @Override
    protected Map<String, Object> paperParams() {
        return Map.of(PaperMaxInvestment.KEY, 20000, PaperExecutionMode.KEY, PaperExecutionMode.LIVE_ALIGNED);
    }

    @Override
    protected String reportName() {
        return "aligned";
    }

    private Object paperTarget() {
        return AopTestUtils.getUltimateTargetObject(paperTradingService);
    }

    /** 운영 runStrategy 끝의 갱신과 같은 호출 — 실시간 처리기 코인 목록·구독 출처. */
    private void refreshAligned() {
        ReflectionTestUtils.invokeMethod(paperTarget(), "refreshAlignedCoins",
                balanceRepo.findByStatusOrderByStartedAtAsc("RUNNING"));
    }

    /** 같은 시세를 LIVE·PAPER 실시간 처리기 본문에 동기로 넣는다(@Async 프록시 없이 — 1회차 S06 교훈). */
    @SuppressWarnings("unchecked")
    private void tickBoth(String coin, BigDecimal price) {
        realtimeTick(coin, price);
        ((Map<String, ?>) ReflectionTestUtils.getField(paperTarget(), "alignedRtLastCheckMs")).clear();
        refreshAligned();
        ReflectionTestUtils.invokeMethod(paperTarget(), "doOnAlignedRealtimePrice",
                new RealtimePriceEvent(coin, price, Instant.now(), RealtimePriceEvent.WS_TRADE));
    }

    private String outcome(List<EntryPriorityRounds.Outcome> out, String kind) {
        return out.stream().filter(o -> kind.equals(o.candidate().kind())).findFirst()
                .map(o -> o.reason() == null ? "진입" : o.reason().replaceAll("\\(.*?\\)", "(…)"))
                .orElse("후보 없음");
    }

    private static BigDecimal unslip(BigDecimal paperEntry) {
        return paperEntry.divide(SLIP, 8, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    // ── 생성 계약 ───────────────────────────────────────────────────

    @Test
    @DisplayName("C00 실행 모드 생성 계약 — 키 없음/LIVE_ALIGNED 통과, null·다른 문자열·소문자·숫자 거절(세션 없음), DTO 와 같은 판정")
    void c00ModeContract() {
        Object[][] rows = {
                {"없음", true}, {PaperExecutionMode.LIVE_ALIGNED, true},
                {null, false}, {"LEGACY", false}, {"live_aligned", false}, {1, false}, {true, false},
        };
        Validator v = Validation.buildDefaultValidatorFactory().getValidator();
        for (Object[] r : rows) {
            PaperTradingStartRequest req = new PaperTradingStartRequest();
            req.setStrategyType(DailySmaTrendStrategy.NAME);
            req.setCoinPair(BTC);
            req.setTimeframe("H1");
            req.setInitialCapital(new BigDecimal("100000"));
            req.setInvestRatio(new BigDecimal("0.8"));
            Map<String, Object> p = new HashMap<>();
            if (!"없음".equals(r[0])) p.put(PaperExecutionMode.KEY, r[0]);
            req.setStrategyParams(p);
            String label = String.valueOf(r[0]);
            assertThat(v.validate(req).isEmpty()).as("DTO " + label).isEqualTo((boolean) r[1]);
            long before = balanceRepo.count();
            if ((boolean) r[1]) {
                paperIds.add(paperTradingService.start(req).getId());
                assertThat(balanceRepo.count()).as(label).isEqualTo(before + 1);
            } else {
                assertThatThrownBy(() -> paperTradingService.start(req)).as(label).isInstanceOf(IllegalStateException.class);
                assertThat(balanceRepo.count()).as(label).isEqualTo(before);
            }
        }
    }

    // ── 시나리오(종전 실행기와 같은 입력) ─────────────────────────────

    @Test
    @DisplayName("A01 정상 진입 — 평가 직후 둘 다 대기, 승인 뒤 둘 다 진입·같은 매수금·같은 손절/익절 거리, 기준가 차이는 슬리피지뿐")
    void a01NormalEntry() {
        List<Candle> cs = uptrend(lastOpen);
        insertCache(BTC, cs);
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        evaluateLive(l);
        runPaper(p);
        expect(row("A01 정상 진입", "1 평가 직후 포지션", entryState(livePositions(l).size(), "OPEN"), entryState(paperPositions(p).size(), "OPEN"), true), "평가 직후");
        List<EntryPriorityRounds.Outcome> out = approveRound();
        expect(row("A01 정상 진입", "2 승인 결과", outcome(out, "LIVE"), outcome(out, EntryPriorityRounds.KIND_PAPER), true), "승인 결과");
        List<PositionEntity> lp = livePositions(l);
        List<PaperPositionEntity> pp = paperPositions(p);
        assertThat(lp).hasSize(1);
        assertThat(pp).hasSize(1);
        expect(row("A01 정상 진입", "3 매수금", lp.get(0).getInvestedKrw(), pp.get(0).getInvestedKrw(), true), "매수금");
        expect(row("A01 정상 진입", "4 기준가(PAPER 슬리피지 제외)", lp.get(0).getEntryPrice(), unslip(pp.get(0).getEntryPrice()), true), "기준가");
        expect(row("A01 정상 진입", "5 손절 거리 %", pctBelow(lp.get(0).getEntryPrice(), lp.get(0).getStopLossPrice()),
                pctBelow(pp.get(0).getEntryPrice(), pp.get(0).getStopLossPrice()), true), "손절 거리");
        expect(row("A01 정상 진입", "6 익절 거리 %", pctAbove(lp.get(0).getEntryPrice(), lp.get(0).getTakeProfitPrice()),
                pctAbove(pp.get(0).getEntryPrice(), pp.get(0).getTakeProfitPrice()), true), "익절 거리");
        expect(row("A01 정상 진입", "7 체결 방식(어댑터)", liveOrders(l, "BUY").size() + "건 SUBMITTED", "내부 FILLED", false), "체결 방식");
    }

    @Test
    @DisplayName("A02 낡은 입력 — 둘 다 판정 불가로 진입 없음·신호 로그 없음")
    void a02StaleInput() {
        Instant stale = lastOpen.minus(3, ChronoUnit.HOURS);
        useCloseHour(stale);
        insertCache(BTC, uptrend(stale));
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        evaluateLive(l);
        runPaper(p);
        approveRound();
        expect(row("A02 낡은 입력", "1 진입", entryState(livePositions(l).size(), "OPEN"), entryState(paperPositions(p).size(), "OPEN"), true), "진입");
        expect(row("A02 낡은 입력", "2 신호 로그", lastLog("LIVE", l.getId()), lastLog("PAPER", p.getId()), true), "로그");
    }

    @Test
    @DisplayName("A03 BTC 판정 불가(KRW-ETH, BTC 캔들 없음) — 둘 다 같은 HOLD 사유, 진입 없음")
    void a03BtcUnknown() {
        insertCache(ETH, uptrend(lastOpen));
        LiveTradingSessionEntity l = live(ETH);
        VirtualBalanceEntity p = paper(ETH);
        evaluateLive(l);
        runPaper(p);
        approveRound();
        expect(row("A03 BTC 판정 불가(ETH)", "1 진입", entryState(livePositions(l).size(), "OPEN"), entryState(paperPositions(p).size(), "OPEN"), true), "진입");
        expect(row("A03 BTC 판정 불가(ETH)", "2 신호 로그", lastLog("LIVE", l.getId()), lastLog("PAPER", p.getId()), true), "로그");
    }

    @Test
    @DisplayName("A04a E 직전 — 둘 다 진입")
    void a04aBeforeExpiry() {
        insertCache(BTC, uptrend(lastOpen));
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        evaluateLive(l);
        runPaper(p);
        EntryPriorityRounds.RoundKey k = new EntryPriorityRounds.RoundKey("H1", lastOpen);
        entryClock.setForTest(Clock.fixed(EntryPriorityRounds.expiry(k).minusMillis(1), ZoneOffset.UTC));
        List<EntryPriorityRounds.Outcome> out = approveRound();
        entryClock.setForTest(null);
        expect(row("A04a E 직전", "1 승인 결과", outcome(out, "LIVE"), outcome(out, EntryPriorityRounds.KIND_PAPER), true), "승인");
        expect(row("A04a E 직전", "2 진입", entryState(livePositions(l).size(), "OPEN"), entryState(paperPositions(p).size(), "OPEN"), true), "진입");
        expect(row("A04a E 직전", "3 승인 순서", out.get(0).candidate().kind(), out.get(out.size() - 1).candidate().kind(), false), "순서");
        assertThat(out.get(0).candidate().kind()).as("실자금 후보가 먼저").isEqualTo("LIVE");
    }

    @Test
    @DisplayName("A04b E 정각 — 둘 다 만료로 진입 없음")
    void a04bAtExpiry() {
        insertCache(BTC, uptrend(lastOpen));
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        evaluateLive(l);
        runPaper(p);
        EntryPriorityRounds.RoundKey k = new EntryPriorityRounds.RoundKey("H1", lastOpen);
        entryClock.setForTest(Clock.fixed(EntryPriorityRounds.expiry(k), ZoneOffset.UTC));
        List<EntryPriorityRounds.Outcome> out = approveRound();
        entryClock.setForTest(null);
        expect(row("A04b E 정각", "1 승인 결과", outcome(out, "LIVE"), outcome(out, EntryPriorityRounds.KIND_PAPER), true), "승인");
        expect(row("A04b E 정각", "2 진입", entryState(livePositions(l).size(), "OPEN"), entryState(paperPositions(p).size(), "OPEN"), true), "진입");
    }

    @Test
    @DisplayName("A05 신호 뒤 +2% — 둘 다 승인 시각 가격 기준(PAPER 는 거기에 슬리피지)")
    void a05PriceMoveAfterSignal() {
        List<Candle> cs = uptrend(lastOpen);
        insertCache(BTC, cs);
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        evaluateLive(l);
        runPaper(p);
        BigDecimal c0 = lastClose(cs);
        BigDecimal c1 = c0.multiply(new BigDecimal("1.02")).setScale(0, RoundingMode.HALF_UP);
        insertInProgress(BTC, c0, c1, c0, c1);
        approveRound();
        List<PositionEntity> lp = livePositions(l);
        List<PaperPositionEntity> pp = paperPositions(p);
        assertThat(lp).hasSize(1);
        assertThat(pp).hasSize(1);
        expect(row("A05 신호 뒤 +2%", "1 매수금", lp.get(0).getInvestedKrw(), pp.get(0).getInvestedKrw(), true), "매수금");
        expect(row("A05 신호 뒤 +2%", "2 기준가(PAPER 슬리피지 제외)", lp.get(0).getEntryPrice(), unslip(pp.get(0).getEntryPrice()), true), "기준가");
        assertThat(lp.get(0).getEntryPrice()).isEqualByComparingTo(c1);
    }

    @Test
    @DisplayName("A06 장중 SL — 평가 회차 둘 다 유지, 같은 실시간 저가로 둘 다 손절(LIVE CLOSING 접수 / PAPER 즉시 CLOSED)")
    void a06IntrabarStopLoss() {
        List<Candle> cs = uptrend(lastOpen);
        insertCache(BTC, cs);
        BigDecimal entry = lastClose(cs);
        BigDecimal sl = entry.multiply(new BigDecimal("0.95"));
        BigDecimal tp = entry.multiply(new BigDecimal("1.10"));
        Instant opened = Instant.now().minus(20, ChronoUnit.HOURS);
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        PositionEntity lpos = livePos(l, entry, sl, tp, opened);
        PaperPositionEntity ppos = paperPos(p, entry, sl, tp, opened);
        BigDecimal low = entry.multiply(new BigDecimal("0.94")).setScale(0, RoundingMode.HALF_UP);
        BigDecimal close = entry.multiply(new BigDecimal("0.99")).setScale(0, RoundingMode.HALF_UP);
        insertInProgress(BTC, entry, entry, low, close);
        evaluateLive(l);
        runPaper(p);
        expect(row("A06 장중 SL", "1 평가 회차 뒤", positionRepository.findById(lpos.getId()).orElseThrow().getStatus(),
                paperPositionRepo.findById(ppos.getId()).orElseThrow().getStatus(), true), "평가 회차");
        tickBoth(BTC, low);
        PositionEntity la = positionRepository.findById(lpos.getId()).orElseThrow();
        PaperPositionEntity pa = paperPositionRepo.findById(ppos.getId()).orElseThrow();
        expect(row("A06 장중 SL", "2 실시간 저가 뒤 청산 사유", la.getExitReason(), pa.getExitReason(), true), "청산 사유");
        expect(row("A06 장중 SL", "3 상태(어댑터)", la.getStatus(), pa.getStatus(), false), "상태");
        assertThat(pa.getExitReason()).isEqualTo(ExitReason.STOP_LOSS);
        assertThat(wsSubscriptionManager.getSubscribedCoins()).as("PAPER_ALIGNED 구독 출처").contains(BTC);
    }

    @Test
    @DisplayName("A07 장중 TP 근처 — 둘 다 청산 없음, 같은 실시간 고가로 같은 익절가 트레일링")
    void a07IntrabarTakeProfitTrailing() {
        List<Candle> cs = uptrend(lastOpen);
        insertCache(BTC, cs);
        BigDecimal entry = lastClose(cs);
        BigDecimal sl = entry.multiply(new BigDecimal("0.95"));
        BigDecimal tp = entry.multiply(new BigDecimal("1.03"));
        Instant opened = Instant.now().minus(20, ChronoUnit.HOURS);
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        PositionEntity lpos = livePos(l, entry, sl, tp, opened);
        PaperPositionEntity ppos = paperPos(p, entry, sl, tp, opened);
        BigDecimal high = entry.multiply(new BigDecimal("1.04")).setScale(0, RoundingMode.HALF_UP);
        BigDecimal close = entry.multiply(new BigDecimal("1.01")).setScale(0, RoundingMode.HALF_UP);
        insertInProgress(BTC, entry, high, entry, close);
        tickBoth(BTC, high);
        evaluateLive(l);
        runPaper(p);
        PositionEntity la = positionRepository.findById(lpos.getId()).orElseThrow();
        PaperPositionEntity pa = paperPositionRepo.findById(ppos.getId()).orElseThrow();
        expect(row("A07 장중 TP", "1 청산", la.getStatus(), pa.getStatus(), true), "청산");
        expect(row("A07 장중 TP", "2 익절가", la.getTakeProfitPrice(), pa.getTakeProfitPrice(), true), "익절가");
    }

    @Test
    @DisplayName("A08 시세 무효·회복 — 낡은 캔들·유효 현재가 없음: 둘 다 유지 → 실시간 현재가 회복: 둘 다 손절")
    void a08StaleQuoteThenRecovery() {
        Instant stale = lastOpen.minus(3, ChronoUnit.HOURS);
        List<Candle> cs = series(stale, i -> 10_000_000L + 5_000L * i);
        insertCache(BTC, cs);
        BigDecimal staleClose = lastClose(cs);
        BigDecimal entry = staleClose.divide(new BigDecimal("0.94"), 0, RoundingMode.HALF_UP);
        BigDecimal sl = entry.multiply(new BigDecimal("0.95"));
        BigDecimal tp = entry.multiply(new BigDecimal("1.10"));
        Instant opened = Instant.now().minus(20, ChronoUnit.HOURS);
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        PositionEntity lpos = livePos(l, entry, sl, tp, opened);
        PaperPositionEntity ppos = paperPos(p, entry, sl, tp, opened);
        ROWS.add(new String[]{"A08 시세 무효·회복", "0 시작 전 유효 현재가", s(markPriceBook.freshPrice(BTC, Instant.now())), "(같은 MarkPriceBook)", "-", "-"});
        evaluateLive(l);
        runPaper(p);
        PositionEntity l1 = positionRepository.findById(lpos.getId()).orElseThrow();
        PaperPositionEntity p1 = paperPositionRepo.findById(ppos.getId()).orElseThrow();
        expect(row("A08 시세 무효·회복", "1 낡은 캔들 회차", l1.getStatus(), p1.getStatus(), true), "낡은 캔들 회차");
        markPriceBook.observe(BTC, staleClose, Instant.now(), Instant.now(), RealtimePriceEvent.WS_TRADE);
        evaluateLive(l);
        runPaper(p);
        PositionEntity l2 = positionRepository.findById(lpos.getId()).orElseThrow();
        PaperPositionEntity p2 = paperPositionRepo.findById(ppos.getId()).orElseThrow();
        expect(row("A08 시세 무효·회복", "2 현재가 회복 뒤 청산 사유", l2.getExitReason(), p2.getExitReason(), true), "회복 뒤");
        assertThat(p2.getExitReason()).isEqualTo(ExitReason.STOP_LOSS);
    }

    @Test
    @DisplayName("A09a 전역 위험 한도 거절 — LIVE 만 막힘(REAL 계좌 관문, 모의 계좌 대응물 없음 — 남은 차이)")
    void a09aRiskRejected() {
        insertCache(BTC, uptrend(lastOpen));
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        evaluateLive(l);
        runPaper(p);
        doReturn(com.cryptoautotrader.core.risk.RiskCheckResult.reject("시험 — 일 손실 한도")).when(riskManagementService).checkRisk();
        List<EntryPriorityRounds.Outcome> out = approveRound();
        expect(row("A09a 위험 한도", "1 승인 결과", outcome(out, "LIVE"), outcome(out, EntryPriorityRounds.KIND_PAPER), false), "위험 한도");
    }

    @Test
    @DisplayName("A09b 공유 풀 총 한도 부족 — LIVE 만 막힘(REAL 계좌 관문 — 남은 차이)")
    void a09bPoolCapRejected() {
        insertCache(BTC, uptrend(lastOpen));
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        evaluateLive(l);
        runPaper(p);
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("10000")));
        List<EntryPriorityRounds.Outcome> out = approveRound();
        expect(row("A09b 풀 한도", "1 승인 결과", outcome(out, "LIVE"), outcome(out, EntryPriorityRounds.KIND_PAPER), false), "풀 한도");
    }

    @Test
    @DisplayName("A10 정상 청산 — 둘 다 전략 신호 청산(LIVE 접수 / PAPER 즉시)")
    void a10StrategyExit() {
        List<Candle> cs = downtrend(lastOpen);
        insertCache(BTC, cs);
        BigDecimal close = lastClose(cs);
        BigDecimal entry = close.multiply(new BigDecimal("0.99")).setScale(0, RoundingMode.HALF_UP);
        BigDecimal sl = entry.multiply(new BigDecimal("0.95"));
        BigDecimal tp = entry.multiply(new BigDecimal("1.10"));
        Instant opened = Instant.now().minus(20, ChronoUnit.HOURS);
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        PositionEntity lpos = livePos(l, entry, sl, tp, opened);
        PaperPositionEntity ppos = paperPos(p, entry, sl, tp, opened);
        evaluateLive(l);
        runPaper(p);
        expect(row("A10 정상 청산", "1 청산 사유", positionRepository.findById(lpos.getId()).orElseThrow().getExitReason(),
                paperPositionRepo.findById(ppos.getId()).orElseThrow().getExitReason(), true), "청산 사유");
    }

    @Test
    @DisplayName("A11 같은 체결가 대역 — LIVE 매수 주문을 PAPER 체결가로 정산(운영 폴러 checkOrderStatus): 잔액 같음, 수량·수수료는 수수료 기준 차이")
    void a11SameFillPriceSettlement() {
        List<Candle> cs = uptrend(lastOpen);
        insertCache(BTC, cs);
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        evaluateLive(l);
        runPaper(p);
        approveRound();
        PaperPositionEntity pp = paperPositions(p).get(0);
        OrderEntity buy = liveOrders(l, "BUY").get(0);
        // 업비트 시장가 매수(price): 체결 금액 + 수수료(체결 금액의 0.05%) = 주문 금액 20,000. 체결가 = PAPER 체결가.
        BigDecimal funds = new BigDecimal("20000").divide(new BigDecimal("1.0005"), 8, RoundingMode.DOWN);
        BigDecimal fee = funds.multiply(new BigDecimal("0.0005")).setScale(8, RoundingMode.HALF_UP);
        BigDecimal volume = funds.divide(pp.getEntryPrice(), 8, RoundingMode.DOWN);
        OrderResponse r = new OrderResponse();
        r.setUuid(buy.getExchangeOrderId());
        r.setSide("bid");
        r.setOrdType("price");
        r.setState("done");
        r.setMarket(BTC);
        r.setPrice(new BigDecimal("20000"));
        r.setExecutedVolume(volume);
        r.setExecutedFunds(funds);
        r.setPaidFee(fee);
        Mockito.doReturn(r).when(upbitOrderClient).getOrder(buy.getExchangeOrderId());
        orderExecutionEngine.checkOrderStatus(buy.getId());
        awaitDrained();
        PositionEntity lp = livePositions(l).get(0);
        VirtualBalanceEntity pb = balanceRepo.findById(p.getId()).orElseThrow();
        LiveTradingSessionEntity lb = liveRepo.findById(l.getId()).orElseThrow();
        expect(row("A11 같은 체결가", "1 체결 뒤 가용 잔액", lb.getAvailableKrw(), pb.getAvailableKrw(), true), "잔액");
        row("A11 같은 체결가", "2 수량", lp.getSize(), pp.getSize(), false);
        row("A11 같은 체결가", "3 평균가(수수료 포함 여부)", lp.getAvgPrice(), pp.getAvgPrice(), false);
        row("A11 같은 체결가", "4 매수 수수료", orderRepository.findById(buy.getId()).orElseThrow().getAppliedPaidFee(), pp.getPositionFee(), false);
        row("A11 같은 체결가", "5 주문 상태", orderRepository.findById(buy.getId()).orElseThrow().getState(), "FILLED", false);
    }
}
