package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.StrategyTimeframeEnabledEntity;
import com.cryptoautotrader.api.repository.StrategyTimeframeEnabledRepository;
import com.cryptoautotrader.api.repository.StrategyTypeEnabledRepository;
import com.cryptoautotrader.api.service.StrategyKillCriteriaService.Judgment;
import com.cryptoautotrader.api.service.StrategyKillCriteriaService.SessionStats;
import com.cryptoautotrader.api.service.StrategyKillCriteriaService.Verdict;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 폐기 판정 시 <b>(전략 × 타임프레임)</b> 차단 — 2026-09-08 신설 (V80).
 *
 * <h3>무엇이 문제였나</h3>
 * <p>{@code KILL_CRITERIA.md} §5 가 전략 비활성화를 두는 이유는 <b>"세션만 정지하면 같은 전략으로
 * 새 세션을 만들어 그대로 재개할 수 있다"</b> 를 막기 위해서다. 그런데 {@code strategy_type_enabled}
 * 가 전략명만 키로 써서, {@code disableFullyKilledStrategies} 는 "모든 변형이 죽었을 때만" 끈다 —
 * 멀쩡한 {@code @H1} 을 {@code @M15} 때문에 막을 수 없으므로 그 판단 자체는 옳다
 * ({@link KillCriteriaStrategyDisableTest} 가 그 경계를 고정한다).</p>
 *
 * <p><b>문제는 그 우회의 결과다.</b> 타임프레임 단위 폐기는 아무것도 막지 못했다:</p>
 * <pre>
 *   MEANREV_BB@M15 KILL  →  세션 정지                          O
 *                        →  MEANREV_BB@M15 새 세션 생성 차단?   X  (아무도 안 막았다)
 * </pre>
 *
 * <p>{@code kill-criteria.auto-stop} 이 OFF 라 아직 실제 동작은 아니었다 —
 * <b>켜는 순간 구멍이 된다.</b> 그래서 켜기 전에 막아 둔다.</p>
 */
@TestPropertySource(properties = "kill-criteria.auto-stop=true")
class KillCriteriaTimeframeDisableTest extends IntegrationTestBase {

    private static final String STRATEGY = "COMPOSITE_MEANREV_BB";

    @Autowired private StrategyKillCriteriaService killCriteriaService;
    @Autowired private StrategyEnablementGate enablementGate;
    @Autowired private StrategyTimeframeEnabledRepository tfRepo;
    @Autowired private StrategyTypeEnabledRepository enabledRepo;

    @BeforeEach
    @AfterEach
    void cleanup() {
        tfRepo.deleteAll();
        enabledRepo.deleteAll();
    }

    private Judgment judgment(String strategy, String timeframe, Verdict verdict) {
        SessionStats st = new SessionStats("DYN_PAPER", 1L, strategy, timeframe, "rs-test",
                strategy + "@" + timeframe,
                new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("10000"),
                0, 0, 0, BigDecimal.ZERO, java.time.Instant.now(), 11);
        return new Judgment(st, verdict, verdict == Verdict.KILL ? "CAPITAL_LOSS" : "OK", "테스트");
    }

    private boolean tfDisabled(String strategy, String timeframe) {
        return tfRepo.findById(new StrategyTimeframeEnabledEntity.Key(strategy, timeframe))
                .map(e -> Boolean.FALSE.equals(e.getIsActive()))
                .orElse(false);
    }

    @Test
    @DisplayName("폐기된 타임프레임이 차단된다 — 세션 정지만으로는 재생성을 못 막는다 (핵심 회귀)")
    void 폐기된_타임프레임이_차단된다() {
        killCriteriaService.disableKilledTimeframes(
                List.of(judgment(STRATEGY, "M15", Verdict.KILL)));

        assertThat(tfDisabled(STRATEGY, "M15"))
                .as("KILL 판정을 받고도 같은 조합으로 새 세션을 만들 수 있으면 "
                        + "폐기 기준이 사실상 동작하지 않는다")
                .isTrue();
    }

    @Test
    @DisplayName("다른 타임프레임은 영향받지 않는다 — 보수적 우회의 취지는 그대로 지킨다")
    void 다른_타임프레임은_살아있다() {
        killCriteriaService.disableKilledTimeframes(
                List.of(judgment(STRATEGY, "M15", Verdict.KILL)));

        assertThat(tfDisabled(STRATEGY, "H1"))
                .as("한 변형의 실패는 그 전략 전체의 실패가 아니다")
                .isFalse();
        assertThat(enablementGate.isEnabled(STRATEGY, "H1")).isTrue();
    }

    @Test
    @DisplayName("게이트가 차단된 조합의 세션 생성을 거부한다")
    void 게이트가_차단된_조합을_거부한다() {
        killCriteriaService.disableKilledTimeframes(
                List.of(judgment(STRATEGY, "M15", Verdict.KILL)));

        assertThat(enablementGate.isEnabled(STRATEGY, "M15")).isFalse();
        assertThatThrownBy(() -> enablementGate.assertEnabled(STRATEGY, "M15"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("M15");

        // 다른 타임프레임은 통과해야 한다 — 여기가 무너지면 종전보다 나빠진다.
        enablementGate.assertEnabled(STRATEGY, "H1");
    }

    @Test
    @DisplayName("KILL 이 아닌 판정은 차단하지 않는다 — WARN 은 경보이지 폐기가 아니다")
    void 경보는_차단하지_않는다() {
        killCriteriaService.disableKilledTimeframes(
                List.of(judgment(STRATEGY, "M15", Verdict.WARN)));

        assertThat(tfDisabled(STRATEGY, "M15")).isFalse();
    }

    @Test
    @DisplayName("타임프레임을 모르는 판정은 건너뛴다 — 전 조합을 실수로 막지 않는다")
    void 타임프레임_없는_판정은_건너뛴다() {
        killCriteriaService.disableKilledTimeframes(
                List.of(judgment(STRATEGY, null, Verdict.KILL)));

        assertThat(tfRepo.count())
                .as("타임프레임이 null 인데 행을 쓰면 어떤 조합이 막힌 건지 알 수 없다")
                .isZero();
    }

    @Test
    @DisplayName("타임프레임 차단은 전략 전체를 막지 않는다 — 두 층은 독립이다")
    void 타임프레임_차단이_전략을_막지_않는다() {
        killCriteriaService.disableKilledTimeframes(
                List.of(judgment(STRATEGY, "M15", Verdict.KILL)));

        assertThat(enablementGate.isEnabled(STRATEGY))
                .as("전략 레벨 차단은 모든 변형이 죽었을 때만 — disableFullyKilledStrategies 담당")
                .isTrue();
    }
}
