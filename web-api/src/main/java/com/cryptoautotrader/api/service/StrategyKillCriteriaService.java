package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.discord.DiscordWebhookClient;
import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.KillCriteriaJudgmentEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.StrategyTypeEnabledEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.KillCriteriaJudgmentRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.StrategyTypeEnabledRepository;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import com.cryptoautotrader.core.risk.KillCriteriaConfig;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 전략 폐기 기준(kill criteria) 판정 — 2026-08-18 신설.
 *
 * <p><b>정책 문서가 본체다: {@code docs/KILL_CRITERIA.md}.</b> 이 클래스는 그 문서를 집행할 뿐이므로,
 * 임계값이나 판정 순서를 바꾸려면 문서(특히 §7 변경 절차)를 먼저 갱신할 것.
 *
 * <h3>왜 필요한가</h3>
 * <p>2026-08-06 벤치마크 측정에서 실전 검증 통과 전략 0/22, 알파 음수, 11일 승률 0/7 이 드러났다.
 * 문제는 성적이 아니라 <b>폐기 조건이 없어 나쁜 전략을 무한히 고쳐 쓰는 루프</b>였다. 기준이 없으면
 * 손실은 항상 "표본이 부족해서"로 설명되고, 표본은 영원히 부족하다.
 *
 * <h3>{@link StrategyDegradationWatchdog}와의 차이</h3>
 * <p>워치독은 <b>신호 품질</b>(사후 4h 수익률)을 6시간마다 보고 경보만 낸다 — 조기 경보.
 * 이쪽은 <b>실현 손익과 자본</b>을 하루 한 번 보고 정지까지 간다 — 최종 판정.
 *
 * <h3>자동 정지는 기본 꺼져 있다</h3>
 * <p>{@code kill-criteria.auto-stop=true} 로 명시적으로 켜기 전엔 판정과 경보만 하고 세션을
 * 건드리지 않는다({@link WalkForwardValidationGate}와 같은 방식). 실자본에 손대는 자동화는
 * 판정이 며칠간 옳게 나오는지 관찰한 뒤 켜는 것을 전제로 한다.
 */
@Service
@Slf4j
public class StrategyKillCriteriaService {

    private static final String CHANNEL_TYPE = "TRADING_REPORT";
    private static final String MESSAGE_TYPE = "KILL_CRITERIA";
    private static final int SCALE = 2;

    private final LiveTradingSessionRepository liveSessionRepo;
    private final DynamicSessionRepository dynamicSessionRepo;
    private final PositionRepository positionRepository;
    private final VirtualBalanceRepository virtualBalanceRepo;
    private final PaperPositionRepository paperPositionRepo;
    private final StrategyTypeEnabledRepository strategyTypeEnabledRepo;
    private final KillCriteriaJudgmentRepository judgmentRepo;
    private final BenchmarkAlphaService benchmarkAlphaService;
    private final LiveTradingService liveTradingService;
    private final DynamicTradingService dynamicTradingService;
    private final PaperTradingService paperTradingService;
    private final DiscordWebhookClient discordClient;
    private final KillCriteriaConfig config = KillCriteriaConfig.defaults();
    private final boolean autoStopEnabled;

    public StrategyKillCriteriaService(
            LiveTradingSessionRepository liveSessionRepo,
            DynamicSessionRepository dynamicSessionRepo,
            PositionRepository positionRepository,
            VirtualBalanceRepository virtualBalanceRepo,
            PaperPositionRepository paperPositionRepo,
            StrategyTypeEnabledRepository strategyTypeEnabledRepo,
            KillCriteriaJudgmentRepository judgmentRepo,
            BenchmarkAlphaService benchmarkAlphaService,
            LiveTradingService liveTradingService,
            DynamicTradingService dynamicTradingService,
            PaperTradingService paperTradingService,
            DiscordWebhookClient discordClient,
            @Value("${kill-criteria.auto-stop:false}") boolean autoStopEnabled) {
        this.liveSessionRepo = liveSessionRepo;
        this.dynamicSessionRepo = dynamicSessionRepo;
        this.positionRepository = positionRepository;
        this.virtualBalanceRepo = virtualBalanceRepo;
        this.paperPositionRepo = paperPositionRepo;
        this.strategyTypeEnabledRepo = strategyTypeEnabledRepo;
        this.judgmentRepo = judgmentRepo;
        this.benchmarkAlphaService = benchmarkAlphaService;
        this.liveTradingService = liveTradingService;
        this.dynamicTradingService = dynamicTradingService;
        this.paperTradingService = paperTradingService;
        this.discordClient = discordClient;
        this.autoStopEnabled = autoStopEnabled;
        if (autoStopEnabled) {
            log.warn("[KillCriteria] 자동 정지 활성화 — 기준 위반 세션이 자동으로 정지되고 전략이 비활성화됩니다.");
        }
    }

    /** 자동 정지가 실제로 세션을 건드리는 상태인지. false면 판정·경보만 한다. */
    public boolean isAutoStopEnabled() {
        return autoStopEnabled;
    }

    // ── 스케줄 ────────────────────────────────────────────────────────────────

    /** 매일 09:00 KST — 08:30 헬스 스냅샷 직후라 그날 상태가 이미 기록된 시점이다. */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void check() {
        log.info("[KillCriteria] 폐기 기준 판정 시작 (자동정지={})", autoStopEnabled ? "ON" : "OFF");
        try {
            List<Judgment> judgments = evaluateAll();
            List<Judgment> actionable = judgments.stream()
                    .filter(j -> j.verdict() != Verdict.KEEP)
                    .toList();
            if (actionable.isEmpty()) {
                log.info("[KillCriteria] 이상 없음 — 평가 {}건 전부 KEEP", judgments.size());
                return;
            }
            List<Judgment> kills = actionable.stream()
                    .filter(j -> j.verdict() == Verdict.KILL)
                    .toList();
            for (Judgment j : kills) {
                stopKilledSession(j);
            }
            disableFullyKilledStrategies(judgments, kills);
            persist(actionable);
            sendAlert(actionable);
        } catch (Exception e) {
            log.error("[KillCriteria] 판정 중 오류", e);
        }
    }

    // ── 판정 ──────────────────────────────────────────────────────────────────

    /**
     * 운영 중(RUNNING) 세션 전체를 판정한다. LIVE·DYNAMIC·PAPER 모두 대상.
     *
     * <p><b>판정 단위가 둘로 나뉜다</b> — 문서 §2의 A/B 구분이 집계 수준에도 그대로 적용된다.</p>
     * <ul>
     *   <li>A 자본 보호 → <b>세션</b> 단위. 자본이 세션마다 따로 잡히므로 다른 수준이 있을 수 없다.</li>
     *   <li>B 엣지 → <b>전략 × 타임프레임</b> 단위로 코인을 가로질러 합산.</li>
     * </ul>
     *
     * <p>B를 세션 단위로 두면 <b>기준이 영원히 발동하지 않는다</b>. 2026-08-18 실측으로
     * 세션당 0.07거래/일 — {@code minTradesForEdgeTest}(20)까지 280일이 걸린다. 같은 전략을
     * 코인 8종 × 타임프레임 2종으로 돌리면 그룹당 16세션이라 같은 표본이 약 18일에 모인다.
     * 코인이 달라도 전략이 같으면 검증 대상인 "그 전략의 우위"는 하나이므로 합산이 통계적으로도 옳다.</p>
     */
    @Transactional(readOnly = true)
    public List<Judgment> evaluateAll() {
        Map<String, long[]> counts = new HashMap<>();          // kind:id → [n, wins]
        Map<String, BigDecimal> pnlSums = new HashMap<>();     // kind:id → sumRealizedPnl

        for (Object[] row : positionRepository.aggregateClosedTradesPerSession()) {
            String key = row[0] + ":" + row[1];
            counts.put(key, new long[]{ toLong(row[2]), toLong(row[5]) });
            pnlSums.put(key, toBigDecimal(row[3]));
        }

        Instant now = Instant.now();
        List<SessionStats> all = new ArrayList<>();

        for (LiveTradingSessionEntity s : liveSessionRepo.findByStatus("RUNNING")) {
            all.add(statsOf("LIVE", s.getId(), s.getStrategyType(), s.getTimeframe(),
                    "LIVE#" + s.getId() + " " + s.getStrategyType() + " " + s.getCoinPair()
                            + "@" + s.getTimeframe(),
                    s.getInitialCapital(), s.getTotalAssetKrw(), s.getMddPeakCapital(),
                    s.getCircuitBreakerTripCount(), s.getStartedAt(), now, counts, pnlSums));
        }

        for (DynamicSessionEntity s : dynamicSessionRepo.findByStatus("RUNNING")) {
            // 포지션의 session_kind 는 REAL="DYNAMIC", PAPER="DYN_PAPER" 로 갈린다 (V67)
            String kind = s.isPaper() ? "DYN_PAPER" : "DYNAMIC";
            all.add(statsOf(kind, s.getId(), s.getStrategyType(), s.getTimeframe(),
                    kind + "#" + s.getId() + " " + s.getStrategyType() + "@" + s.getTimeframe(),
                    s.getInitialCapital(), s.getTotalAssetKrw(), s.getMddPeakCapital(),
                    s.getCircuitBreakerTripCount(), s.getStartedAt(), now, counts, pnlSums));
        }

        // 모의투자(PaperTradingService)는 paper_trading 스키마를 따로 쓴다 — 세션도 포지션도
        // 위 두 루프에 걸리지 않는다. 2026-08-19 에 이게 빠져 있어 페이퍼 112세션 전체가
        // 판정 대상 밖이었다(데이터를 만드는 곳과 판정하는 곳이 분리돼 있었다).
        Map<Long, long[]> paperCounts = new HashMap<>();       // sessionId → [n, wins]
        Map<Long, BigDecimal> paperPnl = new HashMap<>();
        for (Object[] row : paperPositionRepo.aggregateClosedTradesPerSession()) {
            Long sid = ((Number) row[0]).longValue();
            paperCounts.put(sid, new long[]{ toLong(row[1]), toLong(row[3]) });
            paperPnl.put(sid, toBigDecimal(row[2]));
        }
        for (VirtualBalanceEntity s : virtualBalanceRepo.findByStatusOrderByStartedAtAsc("RUNNING")) {
            long[] c = paperCounts.getOrDefault(s.getId(), new long[]{0L, 0L});
            long days = s.getStartedAt() == null ? 0
                    : Duration.between(s.getStartedAt(), now).toDays();
            // mddPeakCapital 은 virtual_balance 에 없다 → null 을 넘겨 MAX_DRAWDOWN 판정만 생략된다.
            // 서킷브레이커도 페이퍼에는 없으므로 0.
            all.add(new SessionStats("PAPER", s.getId(), s.getStrategyName(), s.getTimeframe(),
                    "PAPER#" + s.getId() + " " + s.getStrategyName() + " " + s.getCoinPair()
                            + "@" + s.getTimeframe(),
                    s.getInitialCapital(), s.getTotalKrw(), null, 0,
                    (int) c[0], (int) c[1], paperPnl.getOrDefault(s.getId(), BigDecimal.ZERO),
                    s.getStartedAt(), days));
        }

        // ── A: 세션별 자본 보호 판정 ──────────────────────────────
        List<Judgment> judgments = new ArrayList<>();
        for (SessionStats st : all) {
            judgments.add(decide(st, config));
        }

        // ── B: 전략×타임프레임 그룹별 엣지 판정 ────────────────────
        Map<String, EdgeVerdict> edgeByGroup = new LinkedHashMap<>();
        for (Map.Entry<String, List<SessionStats>> e : groupByStrategyTimeframe(all).entrySet()) {
            EdgeVerdict v = decideEdge(aggregate(e.getValue(), now), config);
            if (v != null) edgeByGroup.put(e.getKey(), v);
        }
        if (edgeByGroup.isEmpty()) return judgments;

        // 그룹이 죽으면 그 그룹의 세션을 전부 폐기한다. 자본 기준으로 이미 KILL 인 세션은
        // 그쪽 사유가 더 구체적이므로 덮어쓰지 않는다.
        List<Judgment> merged = new ArrayList<>(judgments.size());
        for (Judgment j : judgments) {
            EdgeVerdict v = edgeByGroup.get(groupKey(j.stats()));
            merged.add(v == null || j.verdict() == Verdict.KILL
                    ? j
                    : new Judgment(j.stats(), Verdict.KILL, v.code(), v.reason()));
        }
        return merged;
    }

    /**
     * 엣지 판정 그룹 키 — {@code 엔진/전략@타임프레임}.
     *
     * <p><b>엔진을 키에 넣는 이유</b>: 같은 전략·타임프레임이라도 PAPER(코인 고정)와
     * DYN_PAPER(워치리스트 스캔)는 진입 종목을 고르는 방식이 다르고, 세션 자본도 1,000배
     * 차이난다(1,000만 vs 1만). 08-19 첫 판정에서 두 엔진이 한 그룹으로 묶여 DYN_PAPER 세션이
     * 단일코인 결과에 끌려 함께 폐기 대상이 됐다 — 스캔 로직의 우위는 별도로 판정해야 한다.</p>
     *
     * <p>단 LIVE 와 DYNAMIC 은 같은 엔진 계열로 보지 않는다. 각각 고유 키를 갖는다.</p>
     */
    private static String groupKey(SessionStats st) {
        return st.sessionKind() + "/" + st.strategyType()
                + "@" + (st.timeframe() == null ? "?" : st.timeframe());
    }

    private static Map<String, List<SessionStats>> groupByStrategyTimeframe(List<SessionStats> all) {
        Map<String, List<SessionStats>> out = new LinkedHashMap<>();
        for (SessionStats st : all) {
            if (st.strategyType() == null || st.strategyType().isBlank()) continue;
            out.computeIfAbsent(groupKey(st), k -> new ArrayList<>()).add(st);
        }
        return out;
    }

    /** 그룹 합산 — 수익률은 자본 가중(초기자본 합 대비 총자산 합)이라 세션 크기가 달라도 왜곡되지 않는다. */
    private EdgeStats aggregate(List<SessionStats> group, Instant now) {
        int trades = 0, wins = 0;
        BigDecimal pnl = BigDecimal.ZERO, init = BigDecimal.ZERO, asset = BigDecimal.ZERO;
        Instant earliest = null;
        for (SessionStats st : group) {
            trades += st.tradeCount();
            wins += st.winCount();
            pnl = pnl.add(st.sumRealizedPnl());
            init = init.add(nz(st.initialCapital()));
            asset = asset.add(nz(st.totalAsset()));
            if (st.startedAt() != null && (earliest == null || st.startedAt().isBefore(earliest))) {
                earliest = st.startedAt();
            }
        }
        SessionStats first = group.get(0);
        // 벤치마크는 표본이 찼을 때만 조회한다 — 그룹당 1회라 캔들 조회가 세션 수에 비례하지 않는다
        BigDecimal benchmark = trades >= config.getMinTradesForEdgeTest()
                ? benchmarkAlphaService.altAvgHoldReturnPct(earliest, now)
                : null;
        return new EdgeStats(first.sessionKind(), first.strategyType(), first.timeframe(),
                group.size(), trades, wins, pnl, init, asset, benchmark);
    }

    /**
     * 그룹 단위 엣지 판정 — 폐기 사유가 있으면 반환, 없으면 {@code null}.
     *
     * <p>승률이 아니라 <b>기대값</b>으로 본다. 승률 30%라도 손익비 3:1이면 정상이므로
     * 승률 단독 폐기 기준은 두지 않는다(문서 §4.B).</p>
     */
    public static EdgeVerdict decideEdge(EdgeStats st, KillCriteriaConfig cfg) {
        if (st.tradeCount() < cfg.getMinTradesForEdgeTest()) {
            return null;   // 표본 미달 — 부호를 신뢰하지 않는다
        }
        String scope = String.format("%s %s@%s (%d세션 %d거래)",
                st.sessionKind(), st.strategyType(), st.timeframe(), st.sessions(), st.tradeCount());

        if (st.sumRealizedPnl().signum() <= 0) {
            BigDecimal avg = st.sumRealizedPnl()
                    .divide(BigDecimal.valueOf(st.tradeCount()), SCALE, RoundingMode.HALF_UP);
            return new EdgeVerdict("NEGATIVE_EV", String.format(
                    "실현 기대값 음수 — %s 누적 %s원, 평균 %s원 (승 %d)",
                    scope, plain(st.sumRealizedPnl()), plain(avg), st.winCount()));
        }

        BigDecimal returnPct = pct(st.sumInitialCapital(), st.sumTotalAsset());
        if (st.benchmarkReturnPct() != null && returnPct != null) {
            BigDecimal alpha = returnPct.subtract(st.benchmarkReturnPct());
            if (alpha.signum() < 0) {
                return new EdgeVerdict("NEGATIVE_ALPHA", String.format(
                        "알파 음수 %s%%p — %s 수익률 %s%% vs 알트 보유 %s%%",
                        alpha.toPlainString(), scope, returnPct.toPlainString(),
                        st.benchmarkReturnPct().toPlainString()));
            }
        }
        return null;
    }

    private SessionStats statsOf(String kind, Long id, String strategyType, String timeframe, String label,
                                 BigDecimal initialCapital, BigDecimal totalAsset, BigDecimal mddPeak,
                                 int cbTripCount, Instant startedAt, Instant now,
                                 Map<String, long[]> counts, Map<String, BigDecimal> pnlSums) {
        String key = kind + ":" + id;
        long[] c = counts.getOrDefault(key, new long[]{0L, 0L});
        long runningDays = startedAt == null ? 0 : Duration.between(startedAt, now).toDays();

        return new SessionStats(kind, id, strategyType, timeframe, label,
                initialCapital, totalAsset, mddPeak, cbTripCount,
                (int) c[0], (int) c[1], pnlSums.getOrDefault(key, BigDecimal.ZERO),
                startedAt, runningDays);
    }

    /**
     * 순수 판정 함수 — DB 접근 없이 테스트 가능.
     *
     * <p><b>A(자본 보호)와 C(NO_SIGNAL)만 본다.</b> B(엣지)는 세션이 아니라 전략×타임프레임
     * 그룹 단위라 {@link #decideEdge}가 맡는다.</p>
     *
     * <p>A를 먼저 보는 이유 — A는 표본이 필요 없다. 순서를 뒤집으면 "표본이 부족하다"가
     * 자본 한도 초과를 가리게 된다(문서 §2).</p>
     */
    public static Judgment decide(SessionStats st, KillCriteriaConfig cfg) {
        // ── A. 자본 보호 (표본 무관) ──────────────────────────────
        BigDecimal returnPct = pct(st.initialCapital(), st.totalAsset());
        if (returnPct != null && returnPct.compareTo(cfg.getCapitalLossPct()) <= 0) {
            return kill(st, "CAPITAL_LOSS", String.format(
                    "누적 손실 %s%% (한도 %s%%) — 초기자본 %s → 총자산 %s",
                    returnPct.toPlainString(), cfg.getCapitalLossPct().toPlainString(),
                    plain(st.initialCapital()), plain(st.totalAsset())));
        }

        BigDecimal ddPct = pct(st.mddPeakCapital(), st.totalAsset());
        if (ddPct != null && ddPct.compareTo(cfg.getMaxDrawdownPct()) <= 0) {
            return kill(st, "MAX_DRAWDOWN", String.format(
                    "고점 대비 낙폭 %s%% (한도 %s%%) — 최고 %s → 현재 %s",
                    ddPct.toPlainString(), cfg.getMaxDrawdownPct().toPlainString(),
                    plain(st.mddPeakCapital()), plain(st.totalAsset())));
        }

        if (st.circuitBreakerTripCount() >= cfg.getCircuitBreakerRepeatLimit()) {
            return kill(st, "CB_REPEAT", String.format(
                    "서킷브레이커 누적 %d회 발동 (한도 %d회) — 되살릴 때마다 다시 죽는 구조적 결함",
                    st.circuitBreakerTripCount(), cfg.getCircuitBreakerRepeatLimit()));
        }

        // ── B. 엣지는 여기서 보지 않는다 ──────────────────────────
        // 전략×타임프레임 그룹 단위로 evaluateAll() 이 별도 판정한다 — 세션당 표본으로는
        // minTradesForEdgeTest 에 280일이 걸려 기준이 발동하지 않는다(decideEdge 주석 참조).

        // ── C. 판정 불가 (경보만) ─────────────────────────────────
        if (st.runningDays() >= cfg.getNoSignalDays() && st.tradeCount() < cfg.getNoSignalMinTrades()) {
            return new Judgment(st, Verdict.WARN, "NO_SIGNAL", String.format(
                    "%d일 운영에 종료 거래 %d건 — 검증 자체가 불가능하고 자본이 놀고 있다 (회수 후보)",
                    st.runningDays(), st.tradeCount()));
        }

        return new Judgment(st, Verdict.KEEP, "OK", String.format(
                "수익률 %s%%, %d거래", returnPct == null ? "N/A" : returnPct.toPlainString(), st.tradeCount()));
    }

    // ── 집행 ──────────────────────────────────────────────────────────────────

    /** 폐기 판정 세션 정지 — 보유 포지션은 {@code stopSession}의 정상 매도 경로로 청산된다. */
    void stopKilledSession(Judgment j) {
        if (!autoStopEnabled) {
            log.warn("[KillCriteria] {} → KILL({}) — 자동정지 OFF 라 경보만 합니다: {}",
                    j.label(), j.code(), j.reason());
            return;
        }
        try {
            switch (j.sessionKind()) {
                case "LIVE"  -> liveTradingService.stopSession(j.sessionId());
                case "PAPER" -> paperTradingService.stop(j.sessionId());
                default      -> dynamicTradingService.stopSession(j.sessionId());
            }
            log.warn("[KillCriteria] 세션 정지: {} ({})", j.label(), j.code());
        } catch (Exception e) {
            log.error("[KillCriteria] 세션 정지 실패: {} — {}", j.label(), e.getMessage());
        }
    }

    /**
     * 전략 타입 비활성화 — <b>해당 전략의 운영 세션이 전부 폐기 판정일 때만</b>.
     *
     * <p>비활성화 자체는 필수다. 세션만 정지하면 같은 전략으로 새 세션을 만들어 그대로 재개할 수
     * 있고, 그러면 아무것도 바뀌지 않는다(문서 §5). 부활은 Walk Forward 재검증뿐이다(§6).</p>
     *
     * <p><b>그런데 "전부일 때만"인 이유</b>: 판정 단위는 세션(= 전략 × 타임프레임)인데
     * {@code strategy_type_enabled}는 전략명만 키로 쓴다 — 비활성화가 판정보다 한 단계 거칠다.
     * 그래서 MEANREV_BB@M15 하나가 죽었다고 바로 끄면 <b>멀쩡한 MEANREV_BB@H1까지 막힌다</b>.
     * 한 변형의 실패는 그 전략 전체의 실패가 아니므로, 살아 있는 변형이 하나라도 있으면
     * 세션 정지에서 멈추고 전략은 남겨 둔다.</p>
     */
    void disableFullyKilledStrategies(List<Judgment> all, List<Judgment> kills) {
        if (!autoStopEnabled) return;

        Map<String, Boolean> allKilledByStrategy = new HashMap<>();
        for (Judgment j : all) {
            String s = j.strategyType();
            if (s == null || s.isBlank()) continue;
            boolean killed = j.verdict() == Verdict.KILL;
            allKilledByStrategy.merge(s, killed, Boolean::logicalAnd);
        }

        kills.stream()
                .map(Judgment::strategyType)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .forEach(strategy -> {
                    if (!Boolean.TRUE.equals(allKilledByStrategy.get(strategy))) {
                        log.warn("[KillCriteria] 전략 유지: {} — 살아 있는 변형(다른 타임프레임)이 있어 "
                                + "세션 정지까지만 적용", strategy);
                        return;
                    }
                    try {
                        StrategyTypeEnabledEntity row = strategyTypeEnabledRepo.findById(strategy)
                                .orElseGet(() -> StrategyTypeEnabledEntity.builder()
                                        .strategyName(strategy).build());
                        row.setIsActive(false);
                        strategyTypeEnabledRepo.save(row);
                        log.warn("[KillCriteria] 전략 비활성화: {} — 운영 세션 전부 폐기. "
                                + "부활은 Walk Forward 재검증 필요", strategy);
                    } catch (Exception e) {
                        log.error("[KillCriteria] 전략 비활성화 실패: {} — {}", strategy, e.getMessage());
                    }
                });
    }

    // ── 이력 ──────────────────────────────────────────────────────────────────

    /**
     * 조치가 필요한 판정(KILL/WARN)을 DB에 남긴다.
     *
     * <p>Discord 메시지만으로는 근거가 보존되지 않는다 — {@code discord_send_log.message_preview}
     * 가 102자에서 잘린다. 폐기는 부활 경로가 Walk Forward 재검증뿐인 되돌리기 어려운 결정이라
     * "언제 어떤 수치로 걸렸는가" 가 조회 가능해야 한다(문서 §5).</p>
     *
     * <p>KEEP 은 저장하지 않는다 — 매일 100행 이상이 쌓여 신호 대 잡음비만 떨어진다.
     * 기록 실패가 판정·경보를 막지 않도록 예외를 삼킨다.</p>
     */
    void persist(List<Judgment> actionable) {
        Instant now = Instant.now();
        for (Judgment j : actionable) {
            try {
                SessionStats st = j.stats();
                judgmentRepo.save(KillCriteriaJudgmentEntity.builder()
                        .evaluatedAt(now)
                        .sessionKind(st.sessionKind())
                        .sessionId(st.sessionId())
                        .strategyType(st.strategyType())
                        .timeframe(st.timeframe())
                        .verdict(j.verdict().name())
                        .code(j.code())
                        .reason(j.reason())
                        .tradeCount(st.tradeCount())
                        .returnPct(pct(st.initialCapital(), st.totalAsset()))
                        .autoStopApplied(autoStopEnabled && j.verdict() == Verdict.KILL)
                        .build());
            } catch (Exception e) {
                log.error("[KillCriteria] 판정 이력 기록 실패: {} — {}", j.label(), e.getMessage());
            }
        }
    }

    // ── 알림 ──────────────────────────────────────────────────────────────────

    private void sendAlert(List<Judgment> judgments) {
        long kills = judgments.stream().filter(j -> j.verdict() == Verdict.KILL).count();

        StringBuilder desc = new StringBuilder();
        if (kills > 0 && !autoStopEnabled) {
            desc.append("⚠️ 자동 정지가 꺼져 있어 **실제로 정지되지 않았습니다** ")
                .append("(`kill-criteria.auto-stop=false`)\n\n");
        }
        for (Judgment j : judgments) {
            desc.append(j.verdict() == Verdict.KILL ? "🔴 " : "🟠 ")
                .append("**").append(j.label()).append("** — `").append(j.code()).append("`\n")
                .append(j.reason()).append("\n\n");
        }
        if (kills > 0) {
            desc.append("폐기된 전략은 `/backtest/walk-forward` 재검증 → PAPER 재투입 → n≥20 누적을 ")
                .append("거쳐야 실자본에 돌아옵니다 (docs/KILL_CRITERIA.md §6).");
        }

        ObjectNode embed = discordClient.embed(
                String.format("🛑 전략 폐기 기준 판정 — 폐기 %d건 / 경보 %d건", kills, judgments.size() - kills),
                desc.toString().trim(),
                kills > 0 ? DiscordWebhookClient.COLOR_RED : DiscordWebhookClient.COLOR_YELLOW);

        boolean sent = discordClient.sendEmbed(CHANNEL_TYPE, embed, MESSAGE_TYPE);
        log.info("[KillCriteria] 판정 {}건 전송 {}", judgments.size(), sent ? "성공" : "실패(채널 미설정 또는 오류)");
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /** {@code (current / base - 1) × 100}. base가 0 이하거나 null이면 판정 불가로 null. */
    private static BigDecimal pct(BigDecimal base, BigDecimal current) {
        if (base == null || current == null || base.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return current.subtract(base)
                .divide(base, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "N/A" : v.toPlainString();
    }

    private static Judgment kill(SessionStats st, String code, String reason) {
        return new Judgment(st, Verdict.KILL, code, reason);
    }

    private static long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }

    // ── 타입 ──────────────────────────────────────────────────────────────────

    public enum Verdict {
        /** 기준 위반 없음. */
        KEEP,
        /** 경보만 — 성과가 나쁜 게 아니라 판정이 불가능한 상태(NO_SIGNAL). */
        WARN,
        /** 폐기 — 세션 정지 + 전략 비활성화. */
        KILL
    }

    /** 판정 입력 — 세션 하나의 상태 스냅샷. */
    public record SessionStats(String sessionKind, Long sessionId, String strategyType, String timeframe,
                               String label,
                               BigDecimal initialCapital, BigDecimal totalAsset, BigDecimal mddPeakCapital,
                               int circuitBreakerTripCount, int tradeCount, int winCount,
                               BigDecimal sumRealizedPnl, Instant startedAt, long runningDays) {}

    /**
     * 엣지 판정 입력 — 같은 전략×타임프레임 세션들을 코인을 가로질러 합산한 것.
     *
     * @param benchmarkReturnPct 그룹에서 가장 이른 시작 시각 기준 알트 바스켓 보유 수익률(%).
     *                           표본 미달이거나 캔들이 부족하면 null 이고, 그 경우 알파 판정은 생략된다.
     */
    public record EdgeStats(String sessionKind, String strategyType, String timeframe, int sessions,
                            int tradeCount, int winCount, BigDecimal sumRealizedPnl,
                            BigDecimal sumInitialCapital, BigDecimal sumTotalAsset,
                            BigDecimal benchmarkReturnPct) {}

    /** 그룹 엣지 판정 결과. 폐기 사유가 없으면 {@code decideEdge} 가 null 을 돌려준다. */
    public record EdgeVerdict(String code, String reason) {}

    /** 판정 결과. {@code code}는 문서 §4의 기준 코드(CAPITAL_LOSS·NEGATIVE_EV·…)와 1:1 대응한다. */
    public record Judgment(SessionStats stats, Verdict verdict, String code, String reason) {
        public String label() { return stats.label(); }
        public String sessionKind() { return stats.sessionKind(); }
        public Long sessionId() { return stats.sessionId(); }
        public String strategyType() { return stats.strategyType(); }
    }
}
