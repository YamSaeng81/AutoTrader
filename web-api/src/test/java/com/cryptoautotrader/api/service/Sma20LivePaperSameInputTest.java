package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.PaperTradingStartRequest;
import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.repository.paper.PaperOrderRepository;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.dailysma.DailySmaTrendStrategy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * 같은 입력 LIVE ↔ <b>종전 PAPER</b>(키 없음 + 상한만) — 차이를 드러내는 쪽. 공통 대역은 {@link AbstractSma20SameInputFixture}.
 * 결과 표 {@code build/sma20-same-input/legacy.tsv}.
 */
@DisplayName("SMA20 같은 입력 LIVE↔PAPER 비교 — 진입·관문·회차·가격·장중 SL/TP·시세 무효·위험/한도·청산")
@org.springframework.test.context.TestPropertySource(properties = "test.fixture.entry-rounds-context=Sma20LivePaperSameInputTest")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class Sma20LivePaperSameInputTest extends AbstractSma20SameInputFixture {

    @Override
    protected Map<String, Object> paperParams() {
        return Map.of(PaperMaxInvestment.KEY, 20000);
    }

    @Override
    protected String reportName() {
        return "legacy";
    }

    // ── 시나리오 ────────────────────────────────────────────────────

    @Test
    @DisplayName("S01 정상 진입 — 결정·매수금 같음, 시점(PAPER 평가 즉시 / LIVE 회차 승인 뒤)과 기준가(PAPER 슬리피지 0.1%) 다름")
    void s01NormalEntry() {
        List<Candle> cs = uptrend(lastOpen);
        insertCache(BTC, cs);
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);

        evaluateLive(l);
        runPaper(p);
        int liveAtEval = livePositions(l).size();
        int paperAtEval = paperPositions(p).size();
        expect(row("S01 정상 진입", "1 평가 직후 포지션", entryState(liveAtEval, "OPEN"), entryState(paperAtEval, "OPEN"), false), "평가 직후");

        approveRound();
        List<PositionEntity> lp = livePositions(l);
        List<PaperPositionEntity> pp = paperPositions(p);
        assertThat(lp).hasSize(1);
        assertThat(pp).hasSize(1);
        expect(row("S01 정상 진입", "2 승인 뒤 진입 결정", "진입", "진입", true), "진입 결정");
        expect(row("S01 정상 진입", "3 매수금", lp.get(0).getInvestedKrw(), pp.get(0).getInvestedKrw(), true), "매수금");
        expect(row("S01 정상 진입", "4 기준 진입가", lp.get(0).getEntryPrice(), pp.get(0).getEntryPrice(), false), "기준가");
        assertThat(pp.get(0).getEntryPrice()).isEqualByComparingTo(
                lastClose(cs).multiply(new BigDecimal("1.001")).setScale(8, RoundingMode.HALF_UP));
        assertThat(lp.get(0).getEntryPrice()).isEqualByComparingTo(lastClose(cs));
        row("S01 정상 진입", "5 손절 거리 %", pctBelow(lp.get(0).getEntryPrice(), lp.get(0).getStopLossPrice()),
                pctBelow(pp.get(0).getEntryPrice(), pp.get(0).getStopLossPrice()), true);
        row("S01 정상 진입", "6 익절 거리 %", pctAbove(lp.get(0).getEntryPrice(), lp.get(0).getTakeProfitPrice()),
                pctAbove(pp.get(0).getEntryPrice(), pp.get(0).getTakeProfitPrice()), true);
        expect(row("S01 정상 진입", "7 거래소 주문", liveOrders(l, "BUY").size() + "건 SUBMITTED", "0건(내부 FILLED)", false), "주문");
        row("S01 정상 진입", "8 신호 로그", lastLog("LIVE", l.getId()), lastLog("PAPER", p.getId()), false);
    }

    @Test
    @DisplayName("S02 낡은 입력(마지막 완료 봉 3시간 전) — LIVE 판정 불가로 진입 없음, PAPER 는 낡은 봉으로 진입")
    void s02StaleInput() {
        Instant stale = lastOpen.minus(3, ChronoUnit.HOURS);
        useCloseHour(stale);
        insertCache(BTC, uptrend(stale));
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);

        evaluateLive(l);
        runPaper(p);
        approveRound();
        expect(row("S02 낡은 입력", "1 진입", entryState(livePositions(l).size(), "OPEN"), entryState(paperPositions(p).size(), "OPEN"), false), "낡은 입력 진입");
        row("S02 낡은 입력", "2 신호 로그", lastLog("LIVE", l.getId()), lastLog("PAPER", p.getId()), false);
        assertThat(livePositions(l)).isEmpty();
        assertThat(paperPositions(p)).hasSize(1);
    }

    @Test
    @DisplayName("S03 BTC 판정 불가(대조 코인 KRW-ETH, BTC 캔들 없음) — LIVE 차단, PAPER 진입. KRW-BTC 세션은 자기 캔들을 써서 S02 로 귀결(비적용)")
    void s03BtcUnknown() {
        insertCache(ETH, uptrend(lastOpen));
        LiveTradingSessionEntity l = live(ETH);
        VirtualBalanceEntity p = paper(ETH);

        evaluateLive(l);
        runPaper(p);
        approveRound();
        expect(row("S03 BTC 판정 불가(ETH)", "1 진입", entryState(livePositions(l).size(), "OPEN"), entryState(paperPositions(p).size(), "OPEN"), false), "BTC 판정 불가 진입");
        String liveLog = lastLog("LIVE", l.getId());
        row("S03 BTC 판정 불가(ETH)", "2 신호 로그", liveLog, lastLog("PAPER", p.getId()), false);
        assertThat(liveLog).contains("BTC_MARKET_GUARD 판정 불가");
        ROWS.add(new String[]{"S03 BTC 판정 불가(ETH)", "3 KRW-BTC 세션", "비적용 — btcMarketGate 가 자기 캔들(최신성 통과분)을 쓴다. 낡으면 S02",
                "비적용 — 자기 캔들로 check", "비적용", "비적용"});
    }

    @Test
    @DisplayName("S04a 승인 실행 만료 E 직전(E−1ms) — LIVE 진입, PAPER 는 이미 평가 시각에 진입")
    void s04aBeforeExpiry() {
        insertCache(BTC, uptrend(lastOpen));
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        evaluateLive(l);
        runPaper(p);
        EntryPriorityRounds.RoundKey k = new EntryPriorityRounds.RoundKey("H1", lastOpen);
        entryClock.setForTest(Clock.fixed(EntryPriorityRounds.expiry(k).minusMillis(1), ZoneOffset.UTC));
        List<EntryPriorityRounds.Outcome> out = approveRound();
        entryClock.setForTest(null);
        String reason = out.isEmpty() ? "후보 없음" : String.valueOf(out.get(0).reason());
        expect(row("S04a E 직전", "1 진입", entryState(livePositions(l).size(), "OPEN"), entryState(paperPositions(p).size(), "OPEN"), true), "E 직전 진입");
        ROWS.add(new String[]{"S04a E 직전", "2 승인 결과", s(reason), "회차 없음(평가 즉시 체결)", "다름", "다름"});
        assertThat(out).hasSize(1);
        assertThat(out.get(0).reason()).isNull();
    }

    @Test
    @DisplayName("S04b 승인 실행 만료 E 경계(E 정각) — LIVE 만료로 진입 없음, PAPER 진입")
    void s04bAtExpiry() {
        insertCache(BTC, uptrend(lastOpen));
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        evaluateLive(l);
        runPaper(p);
        EntryPriorityRounds.RoundKey k = new EntryPriorityRounds.RoundKey("H1", lastOpen);
        entryClock.setForTest(Clock.fixed(EntryPriorityRounds.expiry(k), ZoneOffset.UTC));
        List<EntryPriorityRounds.Outcome> out = approveRound();
        entryClock.setForTest(null);
        expect(row("S04b E 정각", "1 진입", entryState(livePositions(l).size(), "OPEN"), entryState(paperPositions(p).size(), "OPEN"), false), "E 정각 진입");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).reason()).contains("승인 실행 만료");
        ROWS.add(new String[]{"S04b E 정각", "2 승인 결과", s(out.get(0).reason()).replaceAll("\\(.*\\)", "(…)"), "회차 없음(평가 즉시 체결)", "다름", "다름"});
        assertThat(liveOrders(l, "BUY")).isEmpty();
    }

    @Test
    @DisplayName("S05 신호 뒤 가격 변화(평가와 승인 사이 +2%) — 매수금 같음, LIVE 기준가는 승인 시각 가격, PAPER 는 평가 시각 가격")
    void s05PriceMoveAfterSignal() {
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
        expect(row("S05 신호 뒤 +2%", "1 매수금", lp.get(0).getInvestedKrw(), pp.get(0).getInvestedKrw(), true), "매수금");
        expect(row("S05 신호 뒤 +2%", "2 기준 진입가", lp.get(0).getEntryPrice(), pp.get(0).getEntryPrice(), false), "기준가");
        assertThat(lp.get(0).getEntryPrice()).isEqualByComparingTo(c1);
        assertThat(pp.get(0).getEntryPrice()).isEqualByComparingTo(c0.multiply(new BigDecimal("1.001")).setScale(8, RoundingMode.HALF_UP));
        row("S05 신호 뒤 +2%", "3 손절가", lp.get(0).getStopLossPrice(), pp.get(0).getStopLossPrice(), false);
    }

    @Test
    @DisplayName("S06 장중 SL 도달(진행 중 봉 저가 −6%, 종가 −1%) — 평가 회차는 둘 다 유지, 실시간 시세로 LIVE 만 손절")
    void s06IntrabarStopLoss() {
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
        String liveAfterEval = positionRepository.findById(lpos.getId()).orElseThrow().getStatus();
        String paperAfterEval = paperPositionRepo.findById(ppos.getId()).orElseThrow().getStatus();
        expect(row("S06 장중 SL", "1 평가 회차 뒤", liveAfterEval, paperAfterEval, true), "평가 회차");

        realtimeTick(BTC, low);
        PositionEntity lAfter = positionRepository.findById(lpos.getId()).orElseThrow();
        PaperPositionEntity pAfter = paperPositionRepo.findById(ppos.getId()).orElseThrow();
        expect(row("S06 장중 SL", "2 저가 시세 뒤", lAfter.getStatus() + " " + lAfter.getExitReason(), pAfter.getStatus() + " " + pAfter.getExitReason(), false), "실시간 손절");
        assertThat(lAfter.getStatus()).isEqualTo("CLOSING");
        assertThat(lAfter.getExitReason()).isEqualTo(ExitReason.STOP_LOSS);
        assertThat(pAfter.getStatus()).isEqualTo("OPEN");
        ROWS.add(new String[]{"S06 장중 SL", "3 감시 입력", "실시간 체결가(1~5초, 처리기 간격 5초)", "1분 주기·캐시 마지막 봉 종가", "다름", "다름"});
    }

    @Test
    @DisplayName("S07 장중 TP 근처(진행 중 봉 고가 +4%, TP +3%, 종가 +1%) — 둘 다 청산 없음, LIVE 만 실시간 고가로 TP 를 끌어올림")
    void s07IntrabarTakeProfitTrailing() {
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

        realtimeTick(BTC, high);
        evaluateLive(l);
        runPaper(p);
        PositionEntity lAfter = positionRepository.findById(lpos.getId()).orElseThrow();
        PaperPositionEntity pAfter = paperPositionRepo.findById(ppos.getId()).orElseThrow();
        expect(row("S07 장중 TP", "1 청산", lAfter.getStatus(), pAfter.getStatus(), true), "TP 청산 여부");
        expect(row("S07 장중 TP", "2 익절가", lAfter.getTakeProfitPrice(), pAfter.getTakeProfitPrice(), false), "TP 트레일링");
        assertThat(lAfter.getTakeProfitPrice()).isGreaterThan(tp);
        assertThat(pAfter.getTakeProfitPrice()).isEqualByComparingTo(tp);
    }

    @Test
    @DisplayName("S08 시세 무효·회복 — 낡은 캔들(종가 SL 아래)로 PAPER 는 손절, LIVE 는 유효 현재가 없으면 판정 안 함 → 실시간 현재가 회복 뒤 손절")
    void s08StaleQuoteThenRecovery() {
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

        BigDecimal markBefore = markPriceBook.freshPrice(BTC, Instant.now());
        evaluateLive(l);
        runPaper(p);
        PositionEntity l1 = positionRepository.findById(lpos.getId()).orElseThrow();
        PaperPositionEntity p1 = paperPositionRepo.findById(ppos.getId()).orElseThrow();
        ROWS.add(new String[]{"S08 시세 무효·회복", "0 시작 전 LIVE 유효 현재가", s(markBefore), "-", "-", "-"});
        expect(row("S08 시세 무효·회복", "1 낡은 캔들 회차", l1.getStatus() + " " + l1.getExitReason(), p1.getStatus() + " " + p1.getExitReason(), false), "낡은 캔들 회차");
        assertThat(l1.getStatus()).isEqualTo("OPEN");
        assertThat(p1.getStatus()).isEqualTo("CLOSED");
        assertThat(p1.getExitReason()).isEqualTo(ExitReason.STOP_LOSS);

        // 회복 — 실시간 현재가(체결가 관측)가 다시 들어온다. 캔들은 여전히 낡았다.
        markPriceBook.observe(BTC, staleClose, Instant.now(), Instant.now(), RealtimePriceEvent.WS_TRADE);
        evaluateLive(l);
        PositionEntity l2 = positionRepository.findById(lpos.getId()).orElseThrow();
        expect(row("S08 시세 무효·회복", "2 현재가 회복 뒤", l2.getStatus() + " " + l2.getExitReason(), "(이미 CLOSED — 3시간 전 종가 기준)", false), "회복 뒤");
        assertThat(l2.getStatus()).isEqualTo("CLOSING");
        assertThat(l2.getExitReason()).isEqualTo(ExitReason.STOP_LOSS);
    }

    @Test
    @DisplayName("S09a 전역 위험 한도 거절 — LIVE 승인 시각 checkRisk 거절로 진입 없음, PAPER 진입")
    void s09aRiskRejected() {
        insertCache(BTC, uptrend(lastOpen));
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        evaluateLive(l);
        runPaper(p);
        doReturn(com.cryptoautotrader.core.risk.RiskCheckResult.reject("시험 — 일 손실 한도")).when(riskManagementService).checkRisk();
        List<EntryPriorityRounds.Outcome> out = approveRound();
        expect(row("S09a 위험 한도", "1 진입", entryState(livePositions(l).size(), "OPEN"), entryState(paperPositions(p).size(), "OPEN"), false), "위험 한도");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).reason()).contains("리스크 한도");
        ROWS.add(new String[]{"S09a 위험 한도", "2 승인 결과", s(out.get(0).reason()), "위험 관문 없음", "다름", "다름"});
    }

    @Test
    @DisplayName("S09b 공유 풀 총 한도 부족 — LIVE 예약 거절, PAPER 진입(독립 가상 잔액)")
    void s09bPoolCapRejected() {
        insertCache(BTC, uptrend(lastOpen));
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        evaluateLive(l);
        runPaper(p);
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("10000")));
        List<EntryPriorityRounds.Outcome> out = approveRound();
        expect(row("S09b 풀 한도", "1 진입", entryState(livePositions(l).size(), "OPEN"), entryState(paperPositions(p).size(), "OPEN"), false), "풀 한도");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).reason()).isNotNull();
        ROWS.add(new String[]{"S09b 풀 한도", "2 승인 결과", s(out.get(0).reason()), "풀·슬롯·손실 한도 없음", "다름", "다름"});
    }

    @Test
    @DisplayName("S10 정상 청산(일봉 마감 종가 < SMA20, 보유 20h, +1%) — 둘 다 전략 신호 청산, LIVE 는 매도 주문 접수·PAPER 는 즉시 체결")
    void s10StrategyExit() {
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
        PositionEntity lAfter = positionRepository.findById(lpos.getId()).orElseThrow();
        PaperPositionEntity pAfter = paperPositionRepo.findById(ppos.getId()).orElseThrow();
        expect(row("S10 정상 청산", "1 청산 사유", lAfter.getExitReason(), pAfter.getExitReason(), true), "청산 사유");
        expect(row("S10 정상 청산", "2 상태", lAfter.getStatus(), pAfter.getStatus(), false), "상태");
        assertThat(lAfter.getStatus()).isEqualTo("CLOSING");
        assertThat(pAfter.getStatus()).isEqualTo("CLOSED");
        expect(row("S10 정상 청산", "3 매도 주문", liveOrders(l, "SELL").size() + "건 SUBMITTED", "0건(내부 FILLED)", false), "매도 주문");
    }
}
