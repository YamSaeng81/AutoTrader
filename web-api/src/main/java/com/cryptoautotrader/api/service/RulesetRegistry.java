package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.RiskConfigEntity;
import com.cryptoautotrader.api.entity.RulesetSnapshotEntity;
import com.cryptoautotrader.core.selector.CompositeStrategy;
import com.cryptoautotrader.core.selector.SignalQualityDampenGate;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.RulesetSnapshotRepository;
import com.cryptoautotrader.api.util.TradingConstants;
import com.cryptoautotrader.core.risk.RulesetFingerprint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 규칙 지문 등록소 — 2026-08-19 신설.
 *
 * <p>지문을 데이터 행에 찍기 전에 {@code ruleset_snapshot} 에 원문이 있는지 보장한다.
 * 지문만 남고 원문이 없으면 "무엇이 달랐는지" 를 영영 알 수 없다.</p>
 *
 * <p><b>거래마다 DB를 때리지 않는다</b> — 이미 등록한 지문은 메모리 집합에서 걸러
 * 트랜잭션 자체를 열지 않는다. 규칙은 거의 바뀌지 않으므로 집합 크기는 수십 개를 넘지 않는다.
 * 재기동 시 비지만, 첫 거래에서 한 번 UPSERT 하고 끝이다.</p>
 */
@Service
@Slf4j
public class RulesetRegistry {

    private final RulesetSnapshotRepository snapshotRepo;
    private final RiskManagementService riskManagementService;

    /**
     * 별도 트랜잭션 실행기.
     *
     * <p><b>{@code @Transactional(REQUIRES_NEW)} 을 쓰지 않는 이유</b>: 이 클래스의 유일한
     * 등록 호출자가 같은 인스턴스의 {@code hashFor()} 다. 자기호출은 Spring 프록시를 타지 않아
     * 애노테이션이 <b>조용히 무시된다</b> — 2026-08-19 배포 전 검토에서 실제로 그 상태였고,
     * 그 결과 두 가지가 깨져 있었다:</p>
     * <ul>
     *   <li>매수 트랜잭션이 롤백되면 스냅샷도 같이 사라지는데 {@code known} 은 메모리라
     *       롤백되지 않는다 → 재기동 전까지 재시도하지 않아 <b>원문 없는 지문</b>이 남는다.</li>
     *   <li>PostgreSQL 은 트랜잭션 내 INSERT 실패 시 트랜잭션을 abort 로 만든다. 예외를
     *       삼켜도 <b>바깥 매수 트랜잭션이 커밋에서 터진다</b> — 스냅샷 등록 실패가 실거래를 죽인다.</li>
     * </ul>
     * <p>{@link TransactionTemplate} 은 프록시를 거치지 않으므로 자기호출에서도 확실히 분리된다.</p>
     */
    private final TransactionTemplate requiresNew;

    private final Set<String> known = ConcurrentHashMap.newKeySet();
    /** 등록 실패를 지문당 한 번만 WARN 으로 올리기 위한 집합 (영구 실패 시 로그 폭주 방지). */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public RulesetRegistry(RulesetSnapshotRepository snapshotRepo,
                           RiskManagementService riskManagementService,
                           PlatformTransactionManager transactionManager) {
        this.snapshotRepo = snapshotRepo;
        this.riskManagementService = riskManagementService;
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNew = t;
    }

    /**
     * 지문을 등록하고 해시를 돌려준다. 등록 실패가 매매를 막지 않도록 예외를 삼킨다 —
     * 별도 트랜잭션이라 바깥 매수 트랜잭션은 영향을 받지 않는다.
     *
     * <p>실패한 지문은 {@code known} 에 넣지 <b>않는다.</b> 다음 거래에서 다시 시도해야
     * 원문 없는 지문이 영구히 남는 상태를 피할 수 있다.</p>
     */
    public String register(RulesetFingerprint fingerprint) {
        String hash = fingerprint.hash();
        // 트랜잭션을 열기 전에 차단한다 — 전략 로그는 틱마다 코인마다 쓰이므로
        // 여기서 못 막으면 커넥션 획득만으로 풀을 압박한다.
        if (known.contains(hash)) return hash;
        try {
            requiresNew.executeWithoutResult(status -> {
                if (snapshotRepo.existsById(hash)) return;
                snapshotRepo.save(RulesetSnapshotEntity.builder()
                        .rulesetHash(hash)
                        .engine(fingerprint.params().getOrDefault("engine", "?"))
                        .paramsText(fingerprint.toCanonicalString())
                        .build());
                log.info("[Ruleset] 새 규칙 지문 등록: {} ({})", hash,
                        fingerprint.params().get("engine"));
            });
            known.add(hash);
        } catch (Exception e) {
            // 동시 INSERT 충돌이면 다음 호출의 existsById 가 참이 되어 자연히 해소된다.
            if (warned.add(hash)) {
                log.warn("[Ruleset] 지문 원문 저장 실패 — 다음 거래에서 재시도 ({}): {}",
                        hash, e.toString());
            } else {
                log.debug("[Ruleset] 지문 원문 저장 재실패 ({}): {}", hash, e.getMessage());
            }
        }
        return hash;
    }

    // ── 세션별 지문 ───────────────────────────────────────────────────────────
    //
    // 세 엔진이 각자 지문을 만들면 또 같은 드리프트가 난다 — 한 엔진에만 파라미터를
    // 추가하는 순간 서로 다른 규칙이 같은 지문을 갖게 되고, 지문 체계 자체가 거짓말이 된다.
    // 그래서 계산을 여기 한 곳에 모은다. kill criteria 도 같은 메서드를 쓴다.
    //
    // 담는 기준: **매매 거동에 영향을 주는 값만.** 코인은 담지 않는다(규칙이 아니라 대상이다).
    // 새 파라미터를 도입하면 반드시 여기에 추가할 것 — 빠뜨리면 서로 다른 규칙의 데이터가
    // 한 표본에 섞인다. RulesetRegistryCompositionTest 가 구성을 고정한다.

    public String hashFor(LiveTradingSessionEntity session) {
        return register(base("LIVE")
                .put("strategy.params", canonicalParams(session.getStrategyParams()))
                .put("session.timeframe", session.getTimeframe())
                .put("session.maxHoldHours", session.getMaxHoldHours())
                .put("session.stopLossPct", session.getStopLossPct())
                .put("session.investRatio", session.getInvestRatio())
                .build());
    }

    public String hashFor(DynamicSessionEntity session) {
        // V74 부터 동적 세션도 strategy_params 를 갖는다 (A/B 실험용). 이 값이 지문에
        // 실려야 서로 다른 파라미터의 거래가 다른 표본으로 갈린다 — 안 실으면 A/B 가
        // 오히려 데이터를 망친다.
        return register(base(session.isPaper() ? "DYN_PAPER" : "DYNAMIC")
                .put("strategy.params", canonicalParams(session.getStrategyParams()))
                .put("session.timeframe", session.getTimeframe())
                .put("session.maxHoldHours", session.getMaxHoldHours())
                .put("session.stopLossPct", session.getStopLossPct())
                .put("session.investRatio", session.getInvestRatio())
                // 워치리스트 필터 — 08-07 회귀의 진원지. 값이 바뀌면 감시 종목이 통째로
                // 달라지므로 같은 규칙으로 볼 수 없다.
                .put("scan.minAtrPct", session.getMinAtrPct())
                .put("scan.maxSpreadPct", session.getMaxSpreadPct())
                .put("scan.maxCandidateSize", session.getMaxCandidateSize())
                .put("scan.targetWatchSize", session.getTargetWatchSize())
                // 2026-08-30: 세션 간 동일코인 노출 상한. 08-25 에 PAPER 를 면제했을 때 이 값이
                // 지문에 없어 면제 전후 거래가 같은 해시로 섞였고, 사후 분리를 진입 시각으로만
                // 할 수 있었다 — 진입 규칙을 바꾸는 상수는 반드시 여기 실려야 한다.
                .put("scan.maxSessionsPerCoin", DynamicTradingService.MAX_SESSIONS_PER_COIN)
                .build());
    }

    public String hashFor(VirtualBalanceEntity session) {
        return register(base("PAPER")
                .put("strategy.params", canonicalParams(session.getStrategyParams()))
                .put("session.timeframe", session.getTimeframe())
                .put("session.maxHoldHours", session.getMaxHoldHours())
                .put("session.stopLossPct", session.getStopLossPct())
                .put("session.investRatio", session.getInvestRatio())
                // 체결 가정 — 페이퍼만의 값이라 실전 지문과 반드시 갈려야 한다
                .put("paper.slippagePct", TradingConstants.PAPER_SLIPPAGE_PCT)
                .build());
    }

    /**
     * 모든 엔진 공통 파라미터 — 청산 규칙 + 진입 게이트.
     *
     * <p>진입 게이트를 빠뜨리면 "어떤 코인이 매매 대상이 되는가" 가 바뀌어도 지문이 그대로라
     * <b>서로 다른 규칙의 데이터가 한 표본에 섞인다.</b> 08-07 회귀가 정확히 그 유형이었다.</p>
     */
    private RulesetFingerprint.Builder base(String engine) {
        RulesetFingerprint.Builder b = RulesetFingerprint.builder(engine)
                // 지표 계산 구간 — 바뀌면 같은 전략이 다른 값을 보고 판단한다
                .put("engine.candleLookback", TradingConstants.CANDLE_LOOKBACK)
                // 워치리스트 스프레드 필터의 틱 허용치 (2026-08-19 도입) — 감시 종목을 바꾼다
                .put("engine.watchlistAllowedSpreadTicks",
                        TradingConstants.WATCHLIST_ALLOWED_SPREAD_TICKS);

        // 세 엔진이 SL/TP 를 **실제로** 계산하는 상수. exit.* (DB 설정) 과 별개다 —
        // DYNAMIC 은 ExitRuleConfig 를 참조조차 하지 않고 이 값들만 쓴다.
        ExitRuleCalculator.behaviorParams().forEach((k, v) -> b.put("exitcalc." + k, v));

        // 복합 전략의 점수 임계·EMA 필터·ADX 필터 상수 (2026-08-19).
        // gate.scanWeakThreshold 등이 지문에 있지만 운영에서 risk_config 값이 NULL 이라
        // 실제로 쓰이는 건 이 코드 상수다 — 지문에는 null 만 적히고 있었다.
        CompositeStrategy.behaviorParams().forEach((k, v) -> b.put("composite." + k, v));

        // 야간·TRANSITIONAL 신호 감쇠 (2026-08-19). 진입 신호 수를 직접 바꾸는 값이라
        // 지문 밖에 두면 감쇠를 조정한 전후 거래가 한 표본에 섞인다.
        SignalQualityDampenGate.behaviorParams().forEach((k, v) -> b.put("dampen." + k, v));

        try {
            // 설정 엔티티를 **한 번만** 읽는다. getExitRuleConfig() 를 따로 부르면 내부에서
            // getRiskConfig() 를 다시 읽어 전략 로그 1건당 SELECT 가 2회 나간다.
            RiskConfigEntity rc = riskManagementService.getRiskConfig();
            // exit.* — DB 설정 6개 + 코드 기본값 10개. 기본값이라도 담는 이유는
            // ExitRuleConfig 의 @Builder.Default 를 바꾸면 거동이 바뀌기 때문이다.
            b.putExitRules(riskManagementService.toExitRuleConfig(rc));
            b.put("gate.scanMinTradeValueKrw", rc.getScanMinTradeValueKrw())
             .put("gate.scanMaxAtrPct", rc.getScanMaxAtrPct())
             .put("gate.scanRequireUptrend", rc.getScanRequireUptrend() == null
                     ? null : rc.getScanRequireUptrend().toString())
             .put("gate.scanExcludeCrashing", rc.getScanExcludeCrashing() == null
                     ? null : rc.getScanExcludeCrashing().toString())
             .put("gate.scanEma200BuyMarginPct", rc.getScanEma200BuyMarginPct())
             .put("gate.scanWeakThreshold", rc.getScanWeakThreshold())
             .put("gate.scanStrongThreshold", rc.getScanStrongThreshold())
             // 틱마다 읽어 전략에 넘기는 값인데 V71 에서 빠져 있었다 (2026-08-19 발견).
             // 0.0 = 역추세 BUY 전량 차단, 1.0 = 무필터 — 신호 수를 통째로 바꾼다.
             .put("gate.scanEmaDampenFactor", rc.getScanEmaDampenFactor())
             .put("gate.consecutiveLossLimit", rc.getConsecutiveLossLimit())
             .put("gate.maxPortfolioDrawdownPct", rc.getMaxPortfolioDrawdownPct())
             .put("gate.cooldownMinutes", rc.getCooldownMinutes());
        } catch (Exception e) {
            // risk_config 를 못 읽으면 지문에 그 사실을 남긴다 — 조용히 같은 지문이 되면 안 된다
            log.warn("[Ruleset] risk_config 조회 실패 — 청산 설정·진입 게이트를 지문에서 제외: {}",
                    e.getMessage());
            b.put("config.unavailable", true);
        }
        return b;
    }

    /**
     * 전략 파라미터 맵을 정규 문자열로 — 키 정렬 + 수치 정규화.
     *
     * <p>전략 튜닝값이 지문 밖에 있으면 <b>같은 전략을 다르게 튜닝해도 같은 지문</b>이 되어
     * 서로 다른 규칙의 거래가 한 표본에 섞인다 — 파라미터를 바꿔가며 실험하는 것이
     * 이 프로젝트의 핵심이므로 빠뜨리면 지문 체계의 목적이 반쯤 무너진다.</p>
     *
     * <p><b>수치를 정규화하는 이유</b>: 빌더의 {@code put(String, BigDecimal)} 은
     * {@code stripTrailingZeros()} 로 {@code 0.30 == 0.3} 을 보장하는데, 전략 파라미터는
     * JSONB 역직렬화 결과라 같은 값이 {@code 14} 로도 {@code 14.0} 으로도 올 수 있다.
     * 원본 {@code toString()} 을 쓰면 규칙이 바뀌지 않았는데 지문이 갈린다.</p>
     */
    static String canonicalParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "{}";
        return normalize(params);
    }

    private static String normalize(Object v) {
        if (v == null) return "null";
        if (v instanceof Number) {
            try {
                return new BigDecimal(v.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return v.toString();   // NaN·Infinity 등
            }
        }
        if (v instanceof Map<?, ?> m) {
            StringJoiner j = new StringJoiner(",", "{", "}");
            stringKeyed(m).forEach((k, val) -> j.add(k + "=" + normalize(val)));
            return j.toString();
        }
        if (v instanceof Collection<?> c) {
            StringJoiner j = new StringJoiner(",", "[", "]");
            c.forEach(e -> j.add(normalize(e)));   // 순서가 의미를 가지므로 정렬하지 않는다
            return j.toString();
        }
        return v.toString();
    }

    /** 키를 문자열로 통일하고 정렬한다 — 삽입 순서에 지문이 흔들리지 않게. */
    private static TreeMap<String, Object> stringKeyed(Map<?, ?> m) {
        TreeMap<String, Object> out = new TreeMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
