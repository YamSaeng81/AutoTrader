package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.RiskConfigEntity;
import com.cryptoautotrader.api.entity.RulesetSnapshotEntity;
import com.cryptoautotrader.api.repository.RulesetSnapshotRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 규칙 지문의 <b>실제 구성</b>을 고정하는 회귀 테스트 — 2026-08-19 배포 전 검토에서 추가.
 *
 * <h3>왜 통합 테스트인가</h3>
 * <p>먼저 만든 {@code RulesetFingerprintTest.requiredKeysArePresent} 는 <b>테스트가 직접 만든
 * 빌더</b>를 검사했다. 프로덕션 경로인 {@code RulesetRegistry.base()} 를 부르지 않으므로
 * 거기서 {@code gate.*} 블록 10줄을 통째로 지워도 깨지지 않았다 — 눈 대조를 없애려고 만든
 * 가드가 정작 눈 대조로 확인해야 하는 대상을 지키지 못하고 있었다.</p>
 *
 * <p>여기서는 진짜 {@code RulesetRegistry} 빈이 남긴 {@code ruleset_snapshot.params_text} 를
 * 읽어 검증한다. 지문에서 무엇이 빠지면 이 테스트가 깨진다.</p>
 */
class RulesetRegistryCompositionTest extends IntegrationTestBase {

    @Autowired private RulesetRegistry registry;
    @Autowired private RulesetSnapshotRepository snapshotRepo;
    @Autowired private RiskManagementService riskService;

    /**
     * 지문에 반드시 들어가야 하는 키. 빠지면 그 값을 바꿔도 지문이 그대로라
     * <b>서로 다른 규칙의 거래가 한 표본에 섞인다.</b>
     */
    private static final String[] REQUIRED_KEYS = {
            "engine=",
            // 지표 계산 구간·워치리스트 틱 허용치
            "engine.candleLookback=",
            "engine.watchlistAllowedSpreadTicks=",
            // 세 엔진이 SL/TP 를 실제로 계산하는 상수 (ExitRuleCalculator)
            "exitcalc.slAtrPeriod=",
            "exitcalc.slAtrMultiplier=",
            "exitcalc.slPctMax=",
            "exitcalc.tpRrMultiplier=",
            "exitcalc.tpPctMax=",
            // 청산 설정 (ExitRuleConfig) — 대표 키
            "exit.stopLossPct=",
            "exit.trailingEnabled=",
            "exit.investRatio=",
            // 진입 게이트 (risk_config) — 10개 전부
            "gate.scanMinTradeValueKrw=",
            "gate.scanMaxAtrPct=",
            "gate.scanRequireUptrend=",
            "gate.scanExcludeCrashing=",
            "gate.scanEma200BuyMarginPct=",
            "gate.scanWeakThreshold=",
            "gate.scanStrongThreshold=",
            "gate.consecutiveLossLimit=",
            "gate.maxPortfolioDrawdownPct=",
            "gate.cooldownMinutes=",
            // 세션 파라미터
            "session.timeframe=",
            "session.maxHoldHours=",
            "session.stopLossPct=",
            "session.investRatio=",
    };

    @Test
    @DisplayName("동적 세션 지문에 필수 키가 전부 들어간다 (워치리스트 필터 포함)")
    void dynamicFingerprintContainsAllRequiredKeys() {
        String params = paramsOf(registry.hashFor(dynamicSession()));

        for (String key : REQUIRED_KEYS) {
            assertThat(params).as("지문 키 누락: %s", key).contains(key);
        }
        for (String key : new String[]{
                "scan.minAtrPct=", "scan.maxSpreadPct=",
                "scan.maxCandidateSize=", "scan.targetWatchSize="}) {
            assertThat(params).as("워치리스트 필터 키 누락: %s", key).contains(key);
        }
        // risk_config 를 못 읽었다면 gate.* 가 통째로 빠지고 이 표식이 붙는다
        assertThat(params).doesNotContain("config.unavailable=");
    }

    @Test
    @DisplayName("페이퍼·라이브 지문도 공통 키를 모두 갖는다")
    void paperAndLiveFingerprintsContainCommonKeys() {
        String live = paramsOf(registry.hashFor(liveSession(null)));
        assertThat(live).contains("strategy.params=");
        for (String key : REQUIRED_KEYS) {
            assertThat(live).as("LIVE 지문 키 누락: %s", key).contains(key);
        }
    }

    @Test
    @DisplayName("진입 게이트 값이 바뀌면 지문이 갈린다 — gate.* 가 해시에 실제로 반영된다")
    void gateChangeSplitsFingerprint() {
        String before = registry.hashFor(dynamicSession());

        // updateRiskConfig 는 이력 보존을 위해 새 행을 INSERT 한다 = 운영에서 설정이 바뀌는 경로
        RiskConfigEntity cfg = riskService.getRiskConfig();
        int newCooldown = (cfg.getCooldownMinutes() == null ? 0 : cfg.getCooldownMinutes()) + 77;
        cfg.setCooldownMinutes(newCooldown);
        riskService.updateRiskConfig(cfg);

        String after = registry.hashFor(dynamicSession());
        assertThat(after)
                .as("cooldownMinutes 를 바꿨는데 지문이 같다 — gate.* 가 해시에 안 들어간 것")
                .isNotEqualTo(before);
        assertThat(paramsOf(after)).contains("gate.cooldownMinutes=" + newCooldown);
    }

    @Test
    @DisplayName("전략 파라미터가 다르면 지문이 갈린다 — 같은 전략의 다른 튜닝을 구분한다")
    void strategyParamChangeSplitsFingerprint() {
        String a = registry.hashFor(liveSession(Map.of("rsiPeriod", 14)));
        String b = registry.hashFor(liveSession(Map.of("rsiPeriod", 21)));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("전략 파라미터의 수치 표기 차이는 지문을 가르지 않는다 (14 == 14.0)")
    void numericFormattingDoesNotSplitFingerprint() {
        Map<String, Object> asInt = new LinkedHashMap<>();
        asInt.put("rsiPeriod", 14);
        asInt.put("threshold", new BigDecimal("0.30"));

        Map<String, Object> asDouble = new LinkedHashMap<>();
        asDouble.put("threshold", 0.3d);              // 삽입 순서도 다르게
        asDouble.put("rsiPeriod", 14.0d);

        assertThat(registry.hashFor(liveSession(asInt)))
                .as("같은 값의 표기 차이로 지문이 갈렸다 — 규칙이 안 바뀌었는데 표본이 쪼개진다")
                .isEqualTo(registry.hashFor(liveSession(asDouble)));
    }

    @Test
    @DisplayName("엔진이 다르면 지문이 갈린다 — 같은 파라미터라도 같은 규칙이 아니다")
    void engineSplitsFingerprint() {
        DynamicSessionEntity real = dynamicSession();
        real.setTradingMode("REAL");
        DynamicSessionEntity paper = dynamicSession();
        paper.setTradingMode("PAPER");

        assertThat(registry.hashFor(real)).isNotEqualTo(registry.hashFor(paper));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** 지문이 남긴 원문. 없으면 등록이 실패한 것이므로 그 자체가 결함이다. */
    private String paramsOf(String hash) {
        return snapshotRepo.findById(hash)
                .map(RulesetSnapshotEntity::getParamsText)
                .orElseThrow(() -> new AssertionError(
                        "ruleset_snapshot 에 원문이 없다 — 지문만 찍히고 무엇이 달랐는지 알 수 없는 상태: " + hash));
    }

    private DynamicSessionEntity dynamicSession() {
        DynamicSessionEntity s = new DynamicSessionEntity();
        s.setTradingMode("PAPER");
        s.setTimeframe("H1");
        s.setMaxHoldHours(24);
        s.setStopLossPct(new BigDecimal("5.0"));
        s.setInvestRatio(new BigDecimal("0.25"));
        s.setMinAtrPct(new BigDecimal("0.5"));
        s.setMaxSpreadPct(new BigDecimal("0.1"));
        s.setMaxCandidateSize(30);
        s.setTargetWatchSize(10);
        return s;
    }

    private LiveTradingSessionEntity liveSession(Map<String, Object> params) {
        LiveTradingSessionEntity s = new LiveTradingSessionEntity();
        s.setTimeframe("H1");
        s.setMaxHoldHours(24);
        s.setStopLossPct(new BigDecimal("5.0"));
        s.setInvestRatio(new BigDecimal("0.25"));
        s.setStrategyParams(params);
        return s;
    }
}
