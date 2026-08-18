package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.PaperTradingStartRequest;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.core.risk.ExitRuleChecker;
import com.cryptoautotrader.core.risk.ExitRuleConfig;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-06 신규 — <b>모의투자(PAPER)가 실전매매(LIVE)와 동일 조건으로 매매하는지</b>를 잠그는 테스트.
 *
 * <p><b>배경</b>: `PaperTradingService`는 `LiveTradingService`와 완전히 다른 로직으로 돌고 있었다 —
 * 진입 게이트 5종 전무, SL/TP 산정식 상이, 전략 SELL 게이트 없음, 닫힌 캔들 게이팅 없음, 슬리피지 0.
 * 그 결과 "페이퍼에서 검증하고 실전에 올린다"는 절차가 성립하지 않았다(거래 모집단 자체가 달랐다).</p>
 *
 * <p><b>이 테스트가 지키는 것</b>: 청산 게이트 상수 3종이 두 서비스에서 <b>같은 값</b>이어야 한다.
 * 한쪽만 튜닝하면 그 순간부터 페이퍼 성적이 실전 예측력을 잃는데, 코드만 봐서는 눈치채기 어렵다.
 * 상수를 바꿀 일이 생기면 <b>양쪽을 함께</b> 바꾸고 이 테스트를 통과시켜야 한다.</p>
 */
class PaperLiveAlignmentTest extends IntegrationTestBase {

    @Autowired
    private PaperTradingService paperTradingService;

    @Autowired
    private VirtualBalanceRepository balanceRepository;

    @BeforeEach
    @AfterEach
    void cleanup() {
        balanceRepository.deleteAll();
    }

    private static Object readStatic(Class<?> clazz, String fieldName) throws Exception {
        Field f = clazz.getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(null);
    }

    // ── 청산 게이트 상수 정합성 ──────────────────────────────────────

    @Test
    @DisplayName("전략 SELL 최소 보유시간이 LIVE와 동일하다")
    void minHoldMinutes_matchesLive() throws Exception {
        Object live = readStatic(LiveTradingService.class, "MIN_HOLD_MINUTES_FOR_SIGNAL_EXIT");
        Object paper = readStatic(PaperTradingService.class, "MIN_HOLD_MINUTES_FOR_SIGNAL_EXIT");

        assertThat(paper)
                .as("한쪽만 바꾸면 페이퍼 성적이 실전 예측력을 잃는다 — 양쪽을 함께 수정할 것")
                .isEqualTo(live);
    }

    @Test
    @DisplayName("본전 청산 차단 임계가 LIVE와 동일하다")
    void minPnlForSignalExit_matchesLive() throws Exception {
        BigDecimal live = (BigDecimal) readStatic(LiveTradingService.class, "MIN_PNL_PCT_FOR_SIGNAL_EXIT");
        BigDecimal paper = (BigDecimal) readStatic(PaperTradingService.class, "MIN_PNL_PCT_FOR_SIGNAL_EXIT");

        assertThat(paper).isEqualByComparingTo(live);
    }

    @Test
    @DisplayName("손실 탈출 임계가 LIVE·DYNAMIC·백테스트와 모두 동일하다")
    void lossEscapeThreshold_matchesLive() throws Exception {
        BigDecimal live = (BigDecimal) readStatic(LiveTradingService.class, "LOSS_ESCAPE_THRESHOLD");
        BigDecimal paper = (BigDecimal) readStatic(PaperTradingService.class, "LOSS_ESCAPE_THRESHOLD");
        BigDecimal dynamic = (BigDecimal) readStatic(DynamicTradingService.class, "LOSS_ESCAPE_THRESHOLD");

        assertThat(paper).isEqualByComparingTo(live);
        assertThat(dynamic)
                .as("DYNAMIC이 빠져 있어 08-18 이전까지 이 상수는 네 군데에 복제돼 있었다")
                .isEqualByComparingTo(live);
        assertThat(live)
                .as("백테스트(ExitRuleConfig)와 갈리면 백테스트 수치가 실전 거동을 반영하지 못한다")
                .isEqualByComparingTo(ExitRuleConfig.defaults().getLossEscapeThresholdPct());
    }

    @Test
    @DisplayName("본전 데드밴드가 대칭이다 — 손실 쪽만 넓으면 게이트가 작은 손실을 큰 손실로 확정시킨다")
    void breakEvenDeadBand_isSymmetric() {
        // 2026-08-18: 기존 −1.00% ~ +0.30% 비대칭이 실측으로 손해였다(4/4, 평균 0.797%p).
        // 전략이 SELL을 내도 −1%를 넘기 전에는 못 나가서, 작은 손실이 전부 1% 이상 손실로 끝났다.
        ExitRuleConfig cfg = ExitRuleConfig.defaults();

        assertThat(cfg.getLossEscapeThresholdPct().abs())
                .isEqualByComparingTo(cfg.getMinPnlPctForSignalExit().abs());
    }

    @Test
    @DisplayName("손실 구간 전략 SELL이 −0.3% 밑에서 허용된다 — 게이트에 갇히지 않는다")
    void allowsSignalExit_escapesSmallLoss() {
        ExitRuleChecker checker = new ExitRuleChecker(ExitRuleConfig.defaults());
        long held = 200; // 최소 보유시간(180분) 통과

        // 운영에서 게이트가 막았던 실제 값들 — 이제는 빠져나올 수 있어야 한다
        assertThat(checker.allowsSignalExit(held, new BigDecimal("-0.371"))).isTrue();
        assertThat(checker.allowsSignalExit(held, new BigDecimal("-0.428"))).isTrue();
        assertThat(checker.allowsSignalExit(held, new BigDecimal("-1.174"))).isTrue();

        // 본전 근처 churn 방지는 유지 — 좁은 노이즈 구간은 여전히 차단
        assertThat(checker.allowsSignalExit(held, new BigDecimal("-0.10"))).isFalse();
        assertThat(checker.allowsSignalExit(held, new BigDecimal("0.10"))).isFalse();

        // 최소 보유시간 미달이면 손실이 커도 전략 SELL은 여전히 무시(SL/TP는 별도 경로)
        assertThat(checker.allowsSignalExit(10, new BigDecimal("-5.0"))).isFalse();
    }

    @Test
    @DisplayName("캔들 조회 개수가 LIVE와 동일하다 — 같은 지표 입력을 보장")
    void candleLookback_matchesLive() throws Exception {
        Object live = readStatic(LiveTradingService.class, "CANDLE_LOOKBACK");
        Object paper = readStatic(PaperTradingService.class, "CANDLE_LOOKBACK");

        assertThat(paper).isEqualTo(live);
    }

    // ── 데이터 공급 불변식 ──────────────────────────────────────────

    @Test
    @DisplayName("동기화 캔들 수가 소비 측 lookback 이상이다 — 미달이면 EMA200 전략이 조용히 죽는다")
    void syncCandleCount_coversLookback() throws Exception {
        int sync = (int) readStatic(MarketDataSyncService.class, "SYNC_CANDLE_COUNT");
        int paperLookback = (int) readStatic(PaperTradingService.class, "CANDLE_LOOKBACK");
        int liveLookback = (int) readStatic(LiveTradingService.class, "CANDLE_LOOKBACK");

        assertThat(sync)
                .as("SYNC_CANDLE_COUNT(%d)가 lookback 미만이면 신규 코인은 캔들이 그만큼만 쌓여, "
                        + "EMA200 계열 전략이 '데이터 부족'으로 영원히 HOLD만 낸다 "
                        + "(2026-08-06 실측: 값이 120이라 BTC·ETH 외 코인은 사실상 검증 불가였다)", sync)
                .isGreaterThanOrEqualTo(paperLookback)
                .isGreaterThanOrEqualTo(liveLookback);
    }

    @Test
    @DisplayName("EMA200 계열 전략이 요구하는 최소 캔들(201개)보다 충분히 많이 동기화한다")
    void syncCandleCount_coversEma200() throws Exception {
        int sync = (int) readStatic(MarketDataSyncService.class, "SYNC_CANDLE_COUNT");

        assertThat(sync)
                .as("닫힌 캔들 201개 이상이 필요하다 — 미마감 캔들 1개가 잘려나가는 것까지 감안")
                .isGreaterThan(201);
    }

    @Test
    @DisplayName("코인 N × 전략 M 격자 실험이 가능한 세션 수를 허용한다 (10×10=100)")
    void maxConcurrentSessions_supportsGridExperiment() throws Exception {
        int max = (int) readStatic(PaperTradingService.class, "MAX_CONCURRENT_SESSIONS");

        assertThat(max)
                .as("실전 진입 빈도(6일 8거래)로는 유의성에 도달할 수 없어 페이퍼 격자 실험이 필요하다")
                .isGreaterThanOrEqualTo(100);
    }

    // ── 세션 설정 배선 ──────────────────────────────────────────────

    private PaperTradingStartRequest baseRequest() {
        PaperTradingStartRequest req = new PaperTradingStartRequest();
        req.setStrategyType("COMPOSITE_BREAKOUT");
        req.setCoinPair("KRW-BTC");
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("1000000"));
        return req;
    }

    @Test
    @DisplayName("LIVE 세션 설정(손절률·투자비율·time stop)을 그대로 지정해 페이퍼를 돌릴 수 있다")
    void sessionConfig_isPersisted() {
        PaperTradingStartRequest req = baseRequest();
        req.setStopLossPct(new BigDecimal("5.00"));
        req.setInvestRatio(new BigDecimal("0.8000"));
        req.setMaxHoldHours(24);

        VirtualBalanceEntity session = paperTradingService.start(req);

        VirtualBalanceEntity reloaded = balanceRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getStopLossPct()).isEqualByComparingTo("5.00");
        assertThat(reloaded.getInvestRatio()).isEqualByComparingTo("0.8000");
        assertThat(reloaded.getMaxHoldHours()).isEqualTo(24);
    }

    @Test
    @DisplayName("손절률·투자비율을 생략하면 NULL로 저장된다 — risk_config 기본값 폴백(LIVE와 동일 경로)")
    void sessionConfig_nullFallsBackToRiskConfig() {
        VirtualBalanceEntity session = paperTradingService.start(baseRequest());

        VirtualBalanceEntity reloaded = balanceRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getStopLossPct()).isNull();
        assertThat(reloaded.getInvestRatio()).isNull();
    }

    @Test
    @DisplayName("time stop을 생략하면 LIVE 기본값(24)이 그대로 들어간다 — 페이퍼만 time stop 없이 도는 일 방지")
    void sessionConfig_maxHoldHoursDefaultsToLive() {
        // 손절률·투자비율과 달리 maxHoldHours 는 NULL 폴백을 두지 않는다. NULL이면
        // ExitRuleCalculator.shouldTimeStop 이 곧바로 false 라, 페이퍼만 time stop 없이 도는
        // 비대칭이 생긴다 — 그러면 페이퍼 성적을 LIVE 예측에 쓸 수 없다(2026-08-18, V68).
        VirtualBalanceEntity session = paperTradingService.start(baseRequest());

        VirtualBalanceEntity reloaded = balanceRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getMaxHoldHours())
                .isEqualTo(LiveTradingSessionEntity.DEFAULT_MAX_HOLD_HOURS)
                .isEqualTo(24);
    }
}
