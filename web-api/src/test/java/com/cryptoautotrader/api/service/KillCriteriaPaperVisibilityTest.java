package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import com.cryptoautotrader.api.service.StrategyKillCriteriaService.Judgment;
import com.cryptoautotrader.api.service.StrategyKillCriteriaService.Verdict;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-19 회귀 테스트 — <b>모의투자 세션이 폐기 판정 대상에 포함되는가</b>.
 *
 * <p><b>사고</b>: 실자본을 전면 중단하고 데이터 생성을 페이퍼 112세션으로 옮긴 다음 날,
 * kill criteria 가 그 112세션을 <b>하나도 보지 못하는</b> 상태였다. 원인은 스키마 분리다 —
 * {@code evaluateAll()} 이 {@code live_trading_session}·{@code dynamic_session} 만 순회하고,
 * 거래 집계도 {@code public.position} 만 읽는데 모의투자는 {@code paper_trading} 스키마를 쓴다.
 * 데이터를 만드는 곳과 판정하는 곳이 분리돼 있어, 표본이 아무리 쌓여도 기준이 발동할 수 없었다.</p>
 *
 * <p>발견 당시 실측: {@code COMPOSITE_PULLBACK_MTF@H1} 이 8세션 23거래 누적 −1,054,645원으로
 * 이미 {@code NEGATIVE_EV} 조건(n≥20, 합계≤0)을 충족했는데 판정에 잡히지 않았다.</p>
 */
class KillCriteriaPaperVisibilityTest extends IntegrationTestBase {

    private static final String STRATEGY = "COMPOSITE_PULLBACK_MTF";

    @Autowired private StrategyKillCriteriaService killCriteriaService;
    @Autowired private VirtualBalanceRepository balanceRepo;
    @Autowired private PaperPositionRepository paperPositionRepo;
    @Autowired private RulesetRegistry rulesetRegistry;

    @BeforeEach
    @AfterEach
    void cleanup() {
        paperPositionRepo.deleteAll();
        balanceRepo.deleteAll();
    }

    private VirtualBalanceEntity paperSession(String coinPair, String timeframe, String totalKrw) {
        return balanceRepo.saveAndFlush(VirtualBalanceEntity.builder()
                .strategyName(STRATEGY)
                .coinPair(coinPair)
                .timeframe(timeframe)
                .status("RUNNING")
                .initialCapital(new BigDecimal("10000000"))
                .totalKrw(new BigDecimal(totalKrw))
                .availableKrw(new BigDecimal(totalKrw))
                .startedAt(Instant.now().minus(11, ChronoUnit.DAYS))
                .build());
    }

    /**
     * size &gt; 0 이라야 표본에 잡힌다 — 체결되지 않은 고아 포지션 제외 규칙.
     *
     * <p>규칙 지문(V71)도 함께 찍는다. 지문이 없으면 "규칙 미상" 으로 표본에서 제외되는 것이
     * 정상 동작이라, 스탬프 없이 만들면 거래가 세어지지 않는다.</p>
     */
    private void closedTrade(VirtualBalanceEntity session, String coinPair, String realizedPnl) {
        paperPositionRepo.saveAndFlush(PaperPositionEntity.builder()
                .sessionId(session.getId())
                .rulesetHash(rulesetRegistry.hashFor(session))
                .coinPair(coinPair)
                .side("BUY")
                .entryPrice(new BigDecimal("1000"))
                .avgPrice(new BigDecimal("1000"))
                .size(new BigDecimal("1.5"))
                .realizedPnl(new BigDecimal(realizedPnl))
                .status("CLOSED")
                .openedAt(Instant.now().minus(2, ChronoUnit.DAYS))
                .closedAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .build());
    }

    private Judgment findByLabelPart(List<Judgment> js, String part) {
        return js.stream().filter(j -> j.label().contains(part)).findFirst().orElse(null);
    }

    @Test
    @DisplayName("모의투자 세션이 판정 목록에 나타난다 — 스키마가 달라도 빠지지 않는다")
    void paperSessionsAreEvaluated() {
        VirtualBalanceEntity s = paperSession("KRW-SOL", "H1", "10000000");

        List<Judgment> judgments = killCriteriaService.evaluateAll();

        assertThat(judgments)
                .as("페이퍼가 빠지면 표본이 아무리 쌓여도 기준이 발동하지 않는다")
                .anyMatch(j -> "PAPER".equals(j.sessionKind()) && s.getId().equals(j.sessionId()));
    }

    @Test
    @DisplayName("엣지 판정은 코인을 가로질러 합산한다 — 세션당 4거래 × 5세션 = 20으로 발동")
    void edgeAggregatesAcrossCoinsWithinStrategyAndTimeframe() {
        // 어느 세션도 단독으로는 n=20 에 못 미친다. 그룹 합산이라야 판정된다.
        String[] coins = {"KRW-SOL", "KRW-BTC", "KRW-DOGE", "KRW-LINK", "KRW-ADA"};
        for (String coin : coins) {
            VirtualBalanceEntity s = paperSession(coin, "H1", "9900000");
            for (int i = 0; i < 4; i++) {
                closedTrade(s, coin, "-50000");
            }
        }

        List<Judgment> judgments = killCriteriaService.evaluateAll();

        assertThat(judgments).hasSize(5);
        assertThat(judgments).allSatisfy(j -> {
            assertThat(j.verdict()).isEqualTo(Verdict.KILL);
            assertThat(j.code()).isEqualTo("NEGATIVE_EV");
            assertThat(j.reason()).contains("5세션 20거래");
        });
    }

    @Test
    @DisplayName("같은 전략이라도 타임프레임이 다르면 별개로 판정된다")
    void h1AndM15AreJudgedSeparately() {
        for (String coin : new String[]{"KRW-SOL", "KRW-BTC", "KRW-DOGE", "KRW-LINK", "KRW-ADA"}) {
            VirtualBalanceEntity h1 = paperSession(coin, "H1", "9900000");
            for (int i = 0; i < 4; i++) closedTrade(h1, coin, "-50000");
        }
        // M15 는 표본 미달(세션당 1거래 × 5 = 5) — 판정 대상이 아니다
        for (String coin : new String[]{"KRW-SOL", "KRW-BTC", "KRW-DOGE", "KRW-LINK", "KRW-ADA"}) {
            VirtualBalanceEntity m15 = paperSession(coin, "M15", "10050000");
            closedTrade(m15, coin, "10000");
        }

        List<Judgment> judgments = killCriteriaService.evaluateAll();

        assertThat(findByLabelPart(judgments, "@H1").verdict())
                .as("H1 그룹은 n=20 이고 합계가 음수라 폐기")
                .isEqualTo(Verdict.KILL);
        assertThat(findByLabelPart(judgments, "@M15").verdict())
                .as("H1 이 죽었다고 M15 까지 죽이면 멀쩡한 변형을 잃는다")
                .isEqualTo(Verdict.KEEP);
    }

    @Test
    @DisplayName("자본 보호는 페이퍼에도 세션 단위로 걸린다 — 거래 0건이어도 −15%면 폐기")
    void capitalProtectionAppliesToPaperSessions() {
        // 1,000만 → 850만 = −15.00%
        VirtualBalanceEntity s = paperSession("KRW-SOL", "H1", "8500000");

        Judgment j = findByLabelPart(killCriteriaService.evaluateAll(), "PAPER#" + s.getId());

        assertThat(j).isNotNull();
        assertThat(j.verdict()).isEqualTo(Verdict.KILL);
        assertThat(j.code()).isEqualTo("CAPITAL_LOSS");
    }

    @Test
    @DisplayName("MAX_DRAWDOWN 은 페이퍼에서 생략된다 — virtual_balance 에 고점 컬럼이 없다")
    void drawdownIsSkippedForPaperSessions() {
        // 고점 추적이 없으므로 mddPeakCapital=null → 낙폭 판정 자체가 성립하지 않는다.
        // CAPITAL_LOSS 한도(−15%)에는 못 미치는 −10% 로 두면 KEEP 이어야 한다.
        VirtualBalanceEntity s = paperSession("KRW-SOL", "H1", "9000000");

        Judgment j = findByLabelPart(killCriteriaService.evaluateAll(), "PAPER#" + s.getId());

        assertThat(j).isNotNull();
        assertThat(j.verdict())
                .as("고점 정보가 없는데 낙폭을 판정하면 근거 없는 폐기가 된다")
                .isEqualTo(Verdict.KEEP);
    }

    @Test
    @DisplayName("규칙 지문이 없는 거래는 표본에서 제외된다 — V71 이전 데이터")
    void unstampedTradesAreExcluded() {
        // 규칙이 바뀌면 옛 거래는 "다른 조건의 관측" 이다. 지문이 없으면 어느 조건인지
        // 알 수 없으므로 합산하지 않는다 — 이 배제가 없으면 규칙 변경 때마다 데이터를
        // 통째로 버려야 했던 과거 문제로 되돌아간다.
        String[] coins = {"KRW-SOL", "KRW-BTC", "KRW-DOGE", "KRW-LINK", "KRW-ADA"};
        for (String coin : coins) {
            VirtualBalanceEntity s = paperSession(coin, "H1", "9900000");
            for (int i = 0; i < 4; i++) {
                paperPositionRepo.saveAndFlush(PaperPositionEntity.builder()
                        .sessionId(s.getId())
                        .rulesetHash(null)           // 지문 없음
                        .coinPair(coin).side("BUY")
                        .entryPrice(new BigDecimal("1000")).avgPrice(new BigDecimal("1000"))
                        .size(new BigDecimal("1.5")).realizedPnl(new BigDecimal("-50000"))
                        .status("CLOSED")
                        .openedAt(Instant.now().minus(2, ChronoUnit.DAYS))
                        .closedAt(Instant.now().minus(1, ChronoUnit.DAYS))
                        .build());
            }
        }

        List<Judgment> judgments = killCriteriaService.evaluateAll();

        assertThat(judgments).hasSize(5);
        assertThat(judgments).allSatisfy(j -> assertThat(j.verdict())
                .as("지문 없는 20거래가 세어졌다면 규칙이 섞인 표본으로 폐기 판정한 것이다")
                .isEqualTo(Verdict.KEEP));
    }

    @Test
    @DisplayName("규칙이 바뀌면 표본이 분할된다 — 폐기가 아니라 재출발")
    void rulesetChangePartitionsSampleInsteadOfDiscarding() {
        // 옛 규칙에서 20거래를 쌓아 폐기 조건을 채운 뒤, 규칙이 바뀌면(지문이 갈리면)
        // 그 거래들은 현재 표본에서 빠진다. 데이터는 남아 있고 판정만 리셋된다.
        String[] coins = {"KRW-SOL", "KRW-BTC", "KRW-DOGE", "KRW-LINK", "KRW-ADA"};
        for (String coin : coins) {
            VirtualBalanceEntity s = paperSession(coin, "H1", "9900000");
            for (int i = 0; i < 4; i++) {
                paperPositionRepo.saveAndFlush(PaperPositionEntity.builder()
                        .sessionId(s.getId())
                        .rulesetHash("oldrule0001")   // 지금과 다른 규칙
                        .coinPair(coin).side("BUY")
                        .entryPrice(new BigDecimal("1000")).avgPrice(new BigDecimal("1000"))
                        .size(new BigDecimal("1.5")).realizedPnl(new BigDecimal("-50000"))
                        .status("CLOSED")
                        .openedAt(Instant.now().minus(2, ChronoUnit.DAYS))
                        .closedAt(Instant.now().minus(1, ChronoUnit.DAYS))
                        .build());
            }
        }

        assertThat(killCriteriaService.evaluateAll()).allSatisfy(j -> assertThat(j.verdict())
                .as("옛 규칙의 손실 20건으로 현재 규칙을 폐기하면 안 된다")
                .isEqualTo(Verdict.KEEP));

        // 데이터 자체는 남아 있다 — 버려진 게 아니라 분리된 것이다
        assertThat(paperPositionRepo.count()).isEqualTo(20);
    }
}
