package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.RiskConfigEntity;
import com.cryptoautotrader.api.repository.RiskConfigRepository;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>공유 풀 손실 한도</b> — 선행 조건 1번 (2026-10-14, {@code LIVE_ENTRY_PREREG.md} §6 의 2026-10-06 개정 사양).
 *
 * <pre>
 * 순자산 변화 = 현금 흐름 + 보유 평가액
 *   현금 흐름   = Σ 매도 (반영 대금 − 반영 수수료) − Σ 매수 (반영 대금 + 반영 수수료)
 *   보유 평가액 = Σ 열린 포지션 수량 × 시세 × (1 − 수수료율)      ← 팔 때 낼 수수료까지 뺀다
 *   대상        = 실자금 포지션(LIVE·DYNAMIC) 중 단계 시작 이후에 열린 것과 그 주문
 *
 * 누적   : 순자산 변화                      ≤ −(총 자본 × 누적 한도%)   → 신규 진입 차단
 * 24시간 : 순자산 변화 − 24시간 전 순자산 변화 ≤ −(총 자본 × 24시간 한도%)
 *          (단계 시작 후 24시간 미만이면 단계 시작(0)과 비교)
 * </pre>
 *
 * <h3>🔴 분모는 단계의 고정 자본이다</h3>
 * <p>{@link CapitalPoolLimits#getTotalCapKrw()} — 사전 등록값과 정확히 같아야 하는 값이다. 세션 수·세션 상태와 무관하다.
 * 대상도 세션 상태로 고르지 않는다(정지된 세션의 포지션·주문도 들어간다). 그래서 세션을 멈추거나 다시 시작해도 한도 금액과
 * 순자산 변화가 바뀌지 않는다. 종전 {@link RiskManagementService#checkRisk()} 는 RUNNING 세션의 초기자본 합을 분모로 쓰고 손실
 * 체결만 합산했다 — 그 판정은 LIVE 의 추가 보호로 남고, 등록 한도는 여기서 LIVE·DYNAMIC 공통으로 강제한다.</p>
 *
 * <h3>🔴 실현손익 열을 쓰지 않는다</h3>
 * <p>포지션의 {@code realized_pnl} 은 평균단가 기준이라 매수 수수료가 빠지고, 전량 청산 때 부분 매도분을 덮어쓰는 경로가
 * 있다. 주문의 <b>반영값</b>(잔액에 실제로 반영한 대금·수수료)으로 현금 흐름을 다시 계산한다 — 세션 잔액이 움직인 바로
 * 그 금액이다. 반영되지 않은 주문(불명·체결 대기)은 현금도 보유도 바꾸지 않았으므로 양쪽 모두에 없다.</p>
 *
 * <h3>🔴 risk_config 는 사전 등록값과 같아야 한다</h3>
 * <p>{@code max_daily_loss_pct} = 3.0 (최근 24시간), {@code max_portfolio_drawdown_pct} = 15.0 (단계 누적). 다르면 같은 이름의
 * 한도가 두 개가 되므로 실자금 진입을 막는다. 등록 단계({@code STAGE_1}·{@code STAGE_2})에서만 대조하고, 시험 런타임의 시험
 * 단계는 risk_config 값을 그대로 쓴다.</p>
 *
 * <h3>🔴 대체 시세로는 승인하지 않는다 (2026-10-15 리뷰 8회차)</h3>
 * <p>보유 평가에 쓴 시세가 하나라도 유효한 실시간 시세(받은 지 {@code position-limit.mark-max-age-seconds} 이내 — 포지션별 한도와
 * 같은 기준)가 아니면, 순자산은 계산되지만 <b>판정 불가</b>로 보고 풀 전체의 신규 진입을 승인하지 않는다
 * ({@link #POOL_VALUATION_UNKNOWN}). 대체 값(평가 루프 값·평균단가·낡은 시세)이 손실을 과소평가하지 않는다는 근거가 없다 — 값이
 * 계산된다는 사실만으로 위험 한도를 판정할 수 없다. 같은 이유로 그런 평가는 24시간 기준 스냅숏에도 남기지 않는다(빈 칸은 기준
 * 계산이 더 엄격한 쪽으로 대신한다). 주문 추적·청산·정리는 계속한다.</p>
 *
 * <h3>차단의 범위</h3>
 * <p>신규 진입만 막는다. 청산·정지·주문 추적은 막지 않는다. 사전 등록은 "전량 정지" 를 요구하지만 자동 정지는 OFF 이고
 * 정지는 사람이 한다 — 여기서는 경보를 한 번 보낸다.</p>
 */
@Slf4j
@Service
public class PoolLossGuard {

    /** {@code LIVE_ENTRY_PREREG.md} §6 — 1·2단계 공통. */
    static final BigDecimal REGISTERED_ROLLING_24H_PCT = new BigDecimal("3.0");
    static final BigDecimal REGISTERED_CUMULATIVE_PCT = new BigDecimal("15.0");

    static final Duration WINDOW = Duration.ofHours(24);
    /** 24시간 전 기준 스냅숏이 이만큼 넘게 비어 있으면 공백으로 본다 — 기록 주기 1분의 10배. */
    static final Duration SNAPSHOT_TOLERANCE = Duration.ofMinutes(10);
    static final Duration SNAPSHOT_RETENTION = Duration.ofDays(8);
    static final BigDecimal FEE_RATE = new BigDecimal("0.0005");

    public static final String POOL_LOSS_LIMIT = "POOL_LOSS_LIMIT";
    public static final String RISK_CONFIG_MISMATCH = "RISK_CONFIG_MISMATCH";
    public static final String POOL_VALUATION_UNKNOWN = "POOL_VALUATION_UNKNOWN";

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * 순자산 평가 결과.
     *
     * @param unobserved 유효한 실시간 시세 없이 대체 값으로 평가한 보유 — 비어 있지 않으면 한도를 판정하지 않는다
     */
    public record Valuation(BigDecimal cashFlow, BigDecimal holdings, int openPositions, String marks, List<String> unobserved) {
        public BigDecimal net() {
            return cashFlow.add(holdings);
        }

        public boolean observed() {
            return unobserved.isEmpty();
        }
    }

    /** 24시간 전 기준값. {@code substitute} 면 기준 시각 근처 기록이 비어 보수적으로 대신한 값이다. */
    record Reference(BigDecimal net, String basis, boolean substitute) {}

    private final CapitalPoolLimits limits;
    private final RiskConfigRepository riskConfigRepository;
    private final MarkPriceBook markPriceBook;

    @Autowired(required = false)
    private TelegramNotificationService telegram;

    @PersistenceContext
    private EntityManager em;

    /** 끄기는 시험 런타임에서만 받는다 — 운영에서 설정으로 끌 수 있는 손실 한도는 한도가 아니다. */
    @Value("${trading.pool-loss-guard.enabled:true}")
    private boolean enabledSetting = true;

    private final AtomicReference<String> lastAlert = new AtomicReference<>();

    public PoolLossGuard(CapitalPoolLimits limits, RiskConfigRepository riskConfigRepository, MarkPriceBook markPriceBook) {
        this.limits = limits;
        this.riskConfigRepository = riskConfigRepository;
        this.markPriceBook = markPriceBook;
    }

    @PostConstruct
    void reportAtStartup() {
        if (!enabledSetting && !CapitalPoolLimits.testRuntime()) {
            log.error("🔴 trading.pool-loss-guard.enabled=false 는 시험 런타임에서만 받는다 — 운영에서는 무시하고 손실 한도를 건다");
        }
        if (!limits.isRegistered()) return;   // 풀 등록 문제는 CapitalPoolLimits 가 이미 알린다
        // 🔴 Optional.map 에 null(문제 없음)을 돌려주면 빈 값이 되어 "행이 없다" 로 잘못 알린다 — 나눠서 읽는다.
        RiskConfigEntity rc0 = riskConfigRepository.findTopByOrderByIdDesc().orElse(null);
        String problem = rc0 == null ? "risk_config 행이 없다" : riskConfigProblem(rc0, registeredStage());
        if (problem != null) {
            log.error("🔴 위험 한도 설정이 사전 등록과 맞지 않는다 — 실자금 진입을 거절한다: {}", problem);
        } else {
            log.info("위험 한도 확인: 단계 {} 총 자본 {} 기준 누적 {}%·24시간 {}%", limits.getStage(), limits.getTotalCapKrw(),
                    riskConfigRepository.findTopByOrderByIdDesc().map(RiskConfigEntity::getMaxPortfolioDrawdownPct).orElse(null),
                    riskConfigRepository.findTopByOrderByIdDesc().map(RiskConfigEntity::getMaxDailyLossPct).orElse(null));
        }
    }

    public boolean isEnabled() {
        return enabledSetting || !CapitalPoolLimits.testRuntime();
    }

    /** 시험용. 운영에서는 끄더라도 {@link #isEnabled()} 가 참이다. */
    public void setEnabledSetting(boolean v) {
        this.enabledSetting = v;
    }

    // ── 판정 ────────────────────────────────────────────────────────

    /**
     * 신규 진입 판정 — <b>계좌 잠금 안에서</b> 부른다(호출자 트랜잭션). 거절이면 아무것도 바꾸지 않는다(기준 시각 생성 제외).
     */
    public CapitalReservationService.Decision checkForEntry(Instant now) {
        return checkForEntry(now, null);
    }

    /**
     * @param selfPositionId 이 매수가 같은 트랜잭션에서 먼저 만든 슬롯 포지션. 단계의 첫 매수라면 기준 시각을 그 포지션이 열린
     *                       시각으로 잡는다 — 🔴 지금 시각으로 잡으면 방금 연 포지션이 기준보다 몇 ms 앞서 대상에서 빠진다
     *                       (재시작 시험 S14 에서 첫 매수의 손익이 빠진 것으로 발견).
     */
    public CapitalReservationService.Decision checkForEntry(Instant now, Long selfPositionId) {
        if (!isEnabled()) {
            return CapitalReservationService.Decision.approve("손실 한도: 시험에서 끔");
        }
        RiskConfigEntity rc = riskConfigRepository.findTopByOrderByIdDesc().orElse(null);
        String problem = rc == null ? "risk_config 행이 없다" : riskConfigProblem(rc, registeredStage());
        if (problem != null) {
            return CapitalReservationService.Decision.reject(RISK_CONFIG_MISMATCH,
                    "위험 한도 설정이 사전 등록과 맞지 않는다 — 실자금 진입을 승인하지 않는다: " + problem);
        }
        Limits l = limitsFor(rc);
        Instant start = ensureBaseline(earliest(now, selfPositionId));
        Valuation v = valuate(start, now);
        if (v == null) {
            return CapitalReservationService.Decision.reject(POOL_VALUATION_UNKNOWN,
                    "순자산을 계산할 수 없다(반영값 없는 체결 주문) — 손실 한도를 확인할 수 없으므로 승인하지 않는다");
        }
        if (!v.observed()) {
            String msg = "유효한 실시간 시세 없이 대체 가격으로만 평가되는 보유 " + v.unobserved().size() + "건 " + v.unobserved()
                    + " — 대체 가격이 손실을 과소평가하지 않는다는 근거가 없어 손실 한도를 판정할 수 없다. 풀 전체 신규 진입을 승인하지 "
                    + "않는다(주문 추적·청산은 계속)";
            log.warn("[공유 풀 손실 한도] {}", msg);
            return CapitalReservationService.Decision.reject(POOL_VALUATION_UNKNOWN, msg);
        }
        Reference ref = reference24h(start, now);
        if (ref == null) {
            return CapitalReservationService.Decision.reject(POOL_VALUATION_UNKNOWN,
                    "24시간 전 순자산 기록이 없다 — 최근 24시간 한도를 확인할 수 없으므로 승인하지 않는다");
        }
        BigDecimal cumulative = v.net();
        BigDecimal rolling = v.net().subtract(ref.net());
        String detail = String.format("단계 %s(시작 %s) 순자산 변화 %s [현금 %s + 보유 %s, 포지션 %d%s] | 24시간 변화 %s (기준 %s, %s) | "
                        + "한도 누적 −%s · 24시간 −%s (총 자본 %s 고정)",
                limits.getStage(), start, plain(cumulative), plain(v.cashFlow()), plain(v.holdings()), v.openPositions(),
                v.marks().isEmpty() ? "" : " " + v.marks(), plain(rolling), plain(ref.net()), ref.basis(),
                plain(l.cumulative()), plain(l.rolling()), plain(limits.getTotalCapKrw()));
        if (cumulative.compareTo(l.cumulative().negate()) <= 0) {
            return breach("CUMULATIVE", "누적 손실 한도 도달 — " + detail);
        }
        if (rolling.compareTo(l.rolling().negate()) <= 0) {
            return breach("ROLLING_24H", "최근 24시간 순자산 감소 한도 도달 — " + detail);
        }
        lastAlert.set(null);
        return CapitalReservationService.Decision.approve("손실 한도: " + detail);
    }

    /** 같은 종류의 도달은 풀릴 때까지 경보를 한 번만 보낸다. */
    private CapitalReservationService.Decision breach(String kind, String message) {
        log.error("🔴 [공유 풀 손실 한도] 신규 진입 차단: {}", message);
        if (!kind.equals(lastAlert.getAndSet(kind)) && telegram != null) {
            try {
                telegram.notifyRiskLimitBreached("공유 풀 " + message + " — 신규 진입 차단, 정지는 사람이 판단");
            } catch (Exception e) {
                log.warn("손실 한도 경보 전송 실패: {}", e.getMessage());
            }
        }
        return CapitalReservationService.Decision.reject(POOL_LOSS_LIMIT, message);
    }

    /** 한도 금액 — 총 자본 × 비율. 세션과 무관하다. */
    public record Limits(BigDecimal cumulative, BigDecimal rolling) {}

    public Limits limitsFor(RiskConfigEntity rc) {
        BigDecimal cap = limits.getTotalCapKrw();
        return new Limits(
                cap.multiply(rc.getMaxPortfolioDrawdownPct()).divide(HUNDRED, 2, RoundingMode.HALF_UP),
                cap.multiply(rc.getMaxDailyLossPct()).divide(HUNDRED, 2, RoundingMode.HALF_UP));
    }

    /** 등록 단계면 등록값과 정확히 같아야 하고, 시험 단계면 값이 있어야 한다. 문제가 없으면 {@code null}. */
    static String riskConfigProblem(RiskConfigEntity rc, boolean registeredStage) {
        BigDecimal daily = rc.getMaxDailyLossPct();
        BigDecimal cumulative = rc.getMaxPortfolioDrawdownPct();
        if (daily == null || daily.signum() <= 0 || cumulative == null || cumulative.signum() <= 0) {
            return "max_daily_loss_pct=" + daily + " max_portfolio_drawdown_pct=" + cumulative + " — 한도 값이 없다";
        }
        if (!registeredStage) return null;
        if (daily.compareTo(REGISTERED_ROLLING_24H_PCT) != 0 || cumulative.compareTo(REGISTERED_CUMULATIVE_PCT) != 0) {
            return "max_daily_loss_pct=" + daily.stripTrailingZeros().toPlainString()
                    + " max_portfolio_drawdown_pct=" + cumulative.stripTrailingZeros().toPlainString()
                    + " — 사전 등록값은 최근 24시간 " + REGISTERED_ROLLING_24H_PCT + " · 단계 누적 " + REGISTERED_CUMULATIVE_PCT;
        }
        return null;
    }

    private boolean registeredStage() {
        String s = limits.getStage();
        return s != null && CapitalPoolLimits.REGISTERED.containsKey(s.trim());
    }

    // ── 단계 기준 시각 ────────────────────────────────────────────────

    /** 단계 시작 시각 — 없으면 지금으로 만든다. 한 번 만들면 바꾸지 않는다. <b>계좌 잠금 안에서</b> 부른다. */
    public Instant ensureBaseline(Instant now) {
        Instant existing = baseline();
        if (existing != null) return existing;
        em.createNativeQuery("INSERT INTO capital_stage_baseline (stage, started_at, total_cap_krw) VALUES (?1, ?2, ?3)")
                .setParameter(1, stageKey())
                .setParameter(2, Timestamp.from(now))
                .setParameter(3, limits.getTotalCapKrw())
                .executeUpdate();
        // 저장된 값을 돌려준다 — DB 정밀도(마이크로초)로 잘린 값이 이후 모든 호출이 보는 기준이다.
        Instant stored = baseline();
        log.info("공유 풀 단계 시작 기록: 단계 {} 시작 {} 총 자본 {}", stageKey(), stored, limits.getTotalCapKrw());
        return stored;
    }

    /** 기준 시각 후보 — 지금과 이 매수의 슬롯 포지션이 열린 시각 중 이른 쪽. */
    private Instant earliest(Instant now, Long selfPositionId) {
        if (selfPositionId == null) return now;
        List<?> rows = em.createNativeQuery("SELECT opened_at FROM position WHERE id = ?1")
                .setParameter(1, selfPositionId).getResultList();
        if (rows.isEmpty() || rows.get(0) == null) return now;
        Instant opened = toInstant(rows.get(0));
        return opened.isBefore(now) ? opened : now;
    }

    /** 단계 시작 시각 — 없으면 {@code null}. */
    public Instant baseline() {
        List<?> rows = em.createNativeQuery("SELECT started_at FROM capital_stage_baseline WHERE stage = ?1")
                .setParameter(1, stageKey()).getResultList();
        return rows.isEmpty() ? null : toInstant(rows.get(0));
    }

    private String stageKey() {
        return limits.getStage() == null ? "" : limits.getStage().trim();
    }

    // ── 평가 ────────────────────────────────────────────────────────

    /** 순자산 변화 — 계산할 수 없으면 {@code null}. 시세 최신성은 지금 기준. */
    public Valuation valuate(Instant start) {
        return valuate(start, Instant.now());
    }

    /** 순자산 변화 — 계산할 수 없으면 {@code null}. {@code now} 는 시세 최신성 판단 기준. */
    public Valuation valuate(Instant start, Instant now) {
        Timestamp from = Timestamp.from(start);
        // 🔴 현금 흐름·보유·미반영 개수를 **한 문장**으로 읽는다. 따로 읽으면 그 사이에 커밋된 체결(주문 반영값과 포지션 수량을 한
        //    트랜잭션으로 쓴다)이 한쪽에만 보여, 한 주문 금액만큼 거짓 손실·이익이 생긴다. 한 문장은 같은 스냅숏을 본다.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT 'U', CAST(NULL AS VARCHAR(20)), CAST(COUNT(*) AS NUMERIC), CAST(NULL AS NUMERIC), CAST(NULL AS NUMERIC) FROM \"order\" o JOIN position p ON p.id = o.position_id "
                        + "WHERE p.session_kind IN ('LIVE', 'DYNAMIC') AND p.opened_at >= ?1 "
                        + "AND o.filled_quantity > 0 AND o.applied_executed_funds IS NULL "
                        + "UNION ALL "
                        + "SELECT 'C', CAST(NULL AS VARCHAR(20)), COALESCE(SUM(CASE WHEN o.side = 'BUY' "
                        + "THEN -(COALESCE(o.applied_executed_funds, 0) + COALESCE(o.applied_paid_fee, 0)) "
                        + "ELSE COALESCE(o.applied_executed_funds, 0) - COALESCE(o.applied_paid_fee, 0) END), 0), CAST(NULL AS NUMERIC), CAST(NULL AS NUMERIC) "
                        + "FROM \"order\" o JOIN position p ON p.id = o.position_id "
                        + "WHERE p.session_kind IN ('LIVE', 'DYNAMIC') AND p.opened_at >= ?1 "
                        + "UNION ALL "
                        + "SELECT 'P', p.coin_pair, p.size, p.avg_price, p.unrealized_pnl FROM position p "
                        + "WHERE p.session_kind IN ('LIVE', 'DYNAMIC') AND p.opened_at >= ?1 "
                        + "AND p.status IN ('OPEN', 'CLOSING') AND p.size > 0")
                .setParameter(1, from).getResultList();
        BigDecimal cashFlow = BigDecimal.ZERO;
        List<Object[]> open = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            String kind = String.valueOf(r[0]).trim();
            if ("U".equals(kind) && toDecimal(r[2]).signum() > 0) {
                log.error("🔴 체결 수량이 있는데 반영값이 없는 실자금 주문 {}건 — 순자산을 계산하지 않는다", r[2]);
                return null;
            } else if ("C".equals(kind)) {
                cashFlow = toDecimal(r[2]);
            } else if ("P".equals(kind)) {
                open.add(new Object[]{r[1], r[2], r[3], r[4]});
            }
        }
        BigDecimal holdings = BigDecimal.ZERO;
        StringBuilder marks = new StringBuilder();
        List<String> unobserved = new java.util.ArrayList<>();
        for (Object[] r : open) {
            String coin = (String) r[0];
            BigDecimal size = toDecimal(r[1]);
            BigDecimal avg = toDecimal(r[2]);
            BigDecimal unrealized = r[3] == null ? null : toDecimal(r[3]);
            PriceMark pm = markFor(coin, size, avg, unrealized);
            holdings = holdings.add(size.multiply(pm.price()).multiply(BigDecimal.ONE.subtract(FEE_RATE)));
            String label = coin + '=' + pm.price().stripTrailingZeros().toPlainString() + '(' + pm.source() + ')';
            // 포지션별 한도와 같은 기준 — 실시간 시세이고 받은 지 기준 이내여야 관측 가격이다.
            if (!pm.realtime() || markPriceBook.freshPrice(coin, now) == null) {
                label = label + "[대체]";
                unobserved.add(label);
            }
            if (marks.length() > 0) marks.append(", ");
            marks.append(label);
        }
        return new Valuation(cashFlow, holdings.setScale(8, RoundingMode.HALF_UP), open.size(), marks.toString(), List.copyOf(unobserved));
    }

    /** 보유 평가에 쓴 시세와 그 출처. */
    /** @param at 실시간 시세를 받은 시각(대체 시세면 {@code null}) — 최신성 판단은 쓰는 쪽이 한다({@link PositionLimitGuard}). */
    public record PriceMark(BigDecimal price, String source, boolean realtime, Instant at) {}

    /**
     * 보유 평가 시세 — 마지막 실시간 시세, 없으면 평가 루프가 마지막으로 적은 평가손익에서 되돌린 값, 그것도 없으면 평균단가.
     * 공유 풀 손실 한도와 포지션별 한도({@link PositionLimitGuard})가 <b>같은 시세</b>를 쓴다.
     */
    public PriceMark markFor(String coin, BigDecimal size, BigDecimal avg, BigDecimal unrealized) {
        MarkPriceBook.Mark m = markPriceBook.get(coin);
        if (m != null) {
            // 출처와 관측 시각(체결가면 체결 시각, 호가면 호가 시각)을 적는다 — 받은 시각이 아니다.
            return new PriceMark(m.price(), m.source() + "@" + m.at(), true, m.at());
        }
        BigDecimal price = unrealized == null || size.signum() == 0 ? avg
                : avg.add(unrealized.divide(size, 12, RoundingMode.HALF_UP));
        return new PriceMark(price, unrealized == null ? "평균단가" : "평가루프", false, null);
    }

    /**
     * 24시간 전 순자산 변화. 기준 시각 근처(±{@link #SNAPSHOT_TOLERANCE}) 기록이 없으면 <b>더 엄격한 쪽</b>으로 대신한다 —
     * 기준 시각 직전 기록과 그 뒤 24시간 기록 중 가장 큰 값(감소폭이 가장 크게 계산되는 값). 그것도 없으면 {@code null}.
     */
    Reference reference24h(Instant start, Instant now) {
        Instant t = now.minus(WINDOW);
        if (start.isAfter(t)) {
            return new Reference(BigDecimal.ZERO, "단계 시작", false);
        }
        @SuppressWarnings("unchecked")
        List<Object[]> before = em.createNativeQuery(
                "SELECT taken_at, net_change_krw FROM pool_equity_snapshot WHERE stage = ?1 AND taken_at <= ?2 "
                        + "ORDER BY taken_at DESC LIMIT 1")
                .setParameter(1, stageKey()).setParameter(2, Timestamp.from(t)).getResultList();
        if (!before.isEmpty()) {
            Instant at = toInstant(before.get(0)[0]);
            if (Duration.between(at, t).compareTo(SNAPSHOT_TOLERANCE) <= 0) {
                return new Reference(toDecimal(before.get(0)[1]), "기록 " + at, false);
            }
        }
        Object maxAfter = em.createNativeQuery(
                "SELECT MAX(net_change_krw) FROM pool_equity_snapshot WHERE stage = ?1 AND taken_at > ?2")
                .setParameter(1, stageKey()).setParameter(2, Timestamp.from(t)).getSingleResult();
        BigDecimal best = maxAfter == null ? null : toDecimal(maxAfter);
        if (!before.isEmpty()) {
            BigDecimal b = toDecimal(before.get(0)[1]);
            best = best == null ? b : best.max(b);
        }
        if (best == null) return null;
        log.warn("24시간 전 순자산 기록에 공백이 있다 — 기준을 구간 최댓값 {} 로 대신한다(더 엄격한 쪽)", best);
        return new Reference(best, "공백 대체(구간 최댓값)", true);
    }

    // ── 기록 ────────────────────────────────────────────────────────

    /**
     * 순자산 변화를 1분마다 남긴다 — 최근 24시간 한도의 기준이다. 단계가 시작되기 전(기준 시각 없음)이거나 풀이 등록되지
     * 않았으면 남기지 않는다. 기록은 프로세스 재시작을 넘어 남으므로 재시작 뒤에도 같은 기준을 쓴다.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @Transactional
    public void recordSnapshot() {
        if (!limits.isRegistered()) return;
        Instant start = baseline();
        if (start == null) return;
        recordSnapshotAt(start, Instant.now());
    }

    /**
     * 시험에서도 쓰는 본체. 기록했으면 그 값을, 계산할 수 없거나 대체 가격이 섞였으면 {@code null}. 🔴 대체 가격 평가는 24시간
     * 기준으로 쓰지 않는다 — 그 칸은 비워 두고, 기준 계산이 구간 최댓값(더 엄격한 쪽)으로 대신한다.
     */
    @Transactional
    public Valuation recordSnapshotAt(Instant start, Instant now) {
        Valuation v = valuate(start, now);
        if (v == null) return null;
        if (!v.observed()) {
            log.debug("[공유 풀 손실 한도] 대체 가격 평가 {} — 스냅숏을 남기지 않는다", v.unobserved());
            return null;
        }
        em.createNativeQuery("INSERT INTO pool_equity_snapshot (stage, taken_at, cash_flow_krw, holdings_krw, net_change_krw) "
                        + "VALUES (?1, ?2, ?3, ?4, ?5)")
                .setParameter(1, stageKey()).setParameter(2, Timestamp.from(now))
                .setParameter(3, v.cashFlow()).setParameter(4, v.holdings()).setParameter(5, v.net())
                .executeUpdate();
        em.createNativeQuery("DELETE FROM pool_equity_snapshot WHERE taken_at < ?1")
                .setParameter(1, Timestamp.from(now.minus(SNAPSHOT_RETENTION))).executeUpdate();
        return v;
    }

    // ── 보조 ────────────────────────────────────────────────────────

    private static BigDecimal toDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        return new BigDecimal(o.toString());
    }

    private static Instant toInstant(Object o) {
        if (o instanceof Timestamp ts) return ts.toInstant();
        if (o instanceof Instant i) return i;
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (o instanceof java.time.LocalDateTime ldt) return Timestamp.valueOf(ldt).toInstant();
        throw new IllegalStateException("시각 형식을 알 수 없다: " + o.getClass());
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
