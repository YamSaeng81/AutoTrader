package com.cryptoautotrader.api.service;

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

/**
 * 폐기 판정 시 <b>전략 타입</b> 비활성화 범위 검증 — 2026-08-18.
 *
 * <p>판정 단위는 세션(= 전략 × 타임프레임)인데 {@code strategy_type_enabled}는 전략명만 키로 쓴다.
 * 비활성화가 판정보다 한 단계 거칠어서, 한 변형이 죽었다고 바로 끄면 <b>다른 타임프레임의 멀쩡한
 * 변형까지 막힌다</b>. 7전략 × 2타임프레임 구성으로 넘어가면서 실제 문제가 되는 지점이라
 * "그 전략의 운영 세션이 전부 폐기일 때만 끈다"로 좁혔고, 여기서 그 경계를 고정한다.</p>
 */
@TestPropertySource(properties = "kill-criteria.auto-stop=true")
class KillCriteriaStrategyDisableTest extends IntegrationTestBase {

    private static final String STRATEGY = "COMPOSITE_MEANREV_BB";

    @Autowired private StrategyKillCriteriaService killCriteriaService;
    @Autowired private StrategyTypeEnabledRepository enabledRepo;

    @BeforeEach
    @AfterEach
    void cleanup() {
        enabledRepo.deleteAll();
    }

    private Judgment judgment(String strategy, String label, Verdict verdict) {
        SessionStats st = new SessionStats("DYN_PAPER", 1L, strategy, "H1", "rs-test", label,
                new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("10000"),
                0, 0, 0, BigDecimal.ZERO, java.time.Instant.now(), 11);
        return new Judgment(st, verdict, verdict == Verdict.KILL ? "CAPITAL_LOSS" : "OK", "테스트");
    }

    private boolean isDisabled(String strategy) {
        return enabledRepo.findById(strategy)
                .map(e -> Boolean.FALSE.equals(e.getIsActive()))
                .orElse(false);
    }

    @Test
    @DisplayName("한 변형만 죽으면 전략은 유지된다 — H1이 멀쩡한데 M15 때문에 막히면 안 된다")
    void partialKill_keepsStrategyEnabled() {
        List<Judgment> all = List.of(
                judgment(STRATEGY, STRATEGY + "@M15", Verdict.KILL),
                judgment(STRATEGY, STRATEGY + "@H1", Verdict.KEEP));
        List<Judgment> kills = List.of(all.get(0));

        killCriteriaService.disableFullyKilledStrategies(all, kills);

        assertThat(isDisabled(STRATEGY))
                .as("한 타임프레임의 실패는 그 전략 전체의 실패가 아니다")
                .isFalse();
    }

    @Test
    @DisplayName("모든 변형이 죽으면 전략을 비활성화한다 — 새 세션으로 재개하는 경로를 막는다")
    void fullKill_disablesStrategy() {
        List<Judgment> all = List.of(
                judgment(STRATEGY, STRATEGY + "@M15", Verdict.KILL),
                judgment(STRATEGY, STRATEGY + "@H1", Verdict.KILL));

        killCriteriaService.disableFullyKilledStrategies(all, all);

        assertThat(isDisabled(STRATEGY))
                .as("세션만 정지하면 같은 전략으로 새 세션을 만들어 그대로 재개할 수 있다")
                .isTrue();
    }

    @Test
    @DisplayName("NO_SIGNAL 경보만 받은 변형이 있으면 전략은 유지된다 — 경보는 폐기가 아니다")
    void warnVariant_blocksStrategyDisable() {
        List<Judgment> all = List.of(
                judgment(STRATEGY, STRATEGY + "@M15", Verdict.KILL),
                judgment(STRATEGY, STRATEGY + "@H1", Verdict.WARN));

        killCriteriaService.disableFullyKilledStrategies(all, List.of(all.get(0)));

        assertThat(isDisabled(STRATEGY)).isFalse();
    }

    @Test
    @DisplayName("다른 전략의 판정에는 영향을 주지 않는다")
    void otherStrategyUnaffected() {
        List<Judgment> all = List.of(
                judgment(STRATEGY, STRATEGY + "@H1", Verdict.KILL),
                judgment("COMPOSITE_PULLBACK_MTF", "PULLBACK@H1", Verdict.KEEP));

        killCriteriaService.disableFullyKilledStrategies(all, List.of(all.get(0)));

        assertThat(isDisabled(STRATEGY)).isTrue();
        assertThat(isDisabled("COMPOSITE_PULLBACK_MTF")).isFalse();
    }
}
