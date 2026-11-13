package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.RiskConfigEntity;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.core.risk.RiskCheckResult;
import com.cryptoautotrader.core.risk.RiskEngine;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.159(398차) §4 — 배포 전 손실 조회문 v2 와 제품 손실 세 보호식의 수치·반올림·판정 대조 (로컬 PostgreSQL 도구, 제품 트리 밖에 보존).
 *
 * <p>같은 시험 DB 행을 두 판독기가 읽는다: ① 조회문 v2(PF_SQL 파일, 기준 시각 SET LOCAL preflight.asof) ② 제품
 * {@link RiskManagementService} 의 실제 메서드(legacyLossKrw·calculateLossPct·resolvePortfolioLimit·runningLiveCapital·
 * runningDynamicRealCapital·configuredOrFallbackLimit·pct 를 같은 기준 시각으로 반사 호출, 비교는 {@link RiskEngine#check}).
 * 결론은 공개 경로 {@link RiskManagementService#checkRealLossGuard()} 와도 맞춘다(행은 창 경계에서 수 시간 떨어뜨린다).</p>
 *
 * <p>🔴 손실 행은 확정된 매도 주문 행·FILL 기록을 직접 넣는다. 체결 경로 시험이 아니다 — 실제 체결 경로의 손실 생성은
 * RealLossThreeFormulaPgTest(8/8)가 맡고, 이 도구는 같은 행에 대한 두 판독 결과의 동일성만 본다.</p>
 */
@TestPropertySource(properties = {
        "spring.datasource.url=${PF_PG_URL}",
        "spring.datasource.username=${PF_PG_USER:trader}",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never"
})
@EnabledIfEnvironmentVariable(named = "PF_PG_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("v1.159 §4 — 배포 전 손실 조회문 v2 ↔ 제품 세 보호식 대조 (로컬 PostgreSQL)")
class RealLossPreflightSqlParityPgTest extends IntegrationTestBase {

    @Autowired private RiskManagementService riskManagementService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitRestClient upbitRestClient;

    private static final String[] WIN = {"1d", "7d", "30d"};
    private static final String[] FORMULA = {"기존", "DYNAMIC REAL", "전체 REAL"};
    private static final List<String> REPORT = new ArrayList<>();

    private Map<String, Object> savedRisk;
    private long seq;

    @BeforeEach
    void isolate() {
        savedRisk = jdbc.queryForMap("SELECT * FROM risk_config ORDER BY id DESC LIMIT 1");
        jdbc.update("UPDATE live_trading_session SET status = 'STOPPED' WHERE status = 'RUNNING'");
        jdbc.update("UPDATE dynamic_session SET status = 'STOPPED' WHERE status = 'RUNNING'");
        jdbc.update("UPDATE \"order\" SET sell_applied_at = sell_applied_at - interval '400 days' WHERE sell_applied_at IS NOT NULL");
        jdbc.update("UPDATE trade_log SET created_at = created_at - interval '400 days'");
        limits("1.50", "4.00", "8.00", null);
    }

    @AfterEach
    void restore() {
        if (jdbc.queryForObject("SELECT count(*) FROM risk_config", Integer.class) == 0) {
            jdbc.update("INSERT INTO risk_config (max_daily_loss_pct) VALUES (1.50)");
        }
        jdbc.update("UPDATE risk_config SET max_daily_loss_pct = ?, max_weekly_loss_pct = ?, max_monthly_loss_pct = ?, portfolio_limit_krw = ?",
                savedRisk.get("max_daily_loss_pct"), savedRisk.get("max_weekly_loss_pct"), savedRisk.get("max_monthly_loss_pct"),
                savedRisk.get("portfolio_limit_krw"));
    }

    // ── 자료 ──
    private void limits(String d, String w, String m, String portfolio) {
        jdbc.update("UPDATE risk_config SET max_daily_loss_pct = ?::numeric, max_weekly_loss_pct = ?::numeric, max_monthly_loss_pct = ?::numeric, "
                + "portfolio_limit_krw = ?::numeric", d, w, m, portfolio);
    }

    private long live(String capital) {
        return jdbc.queryForObject("INSERT INTO live_trading_session (strategy_type, coin_pair, timeframe, initial_capital, available_krw, total_asset_krw, status) "
                + "VALUES ('COMPOSITE_BREAKOUT', 'KRW-PF', 'H1', ?::numeric, ?::numeric, ?::numeric, 'RUNNING') RETURNING id", Long.class, capital, capital, capital);
    }

    private long dynamic(String mode, String capital) {
        return jdbc.queryForObject("INSERT INTO dynamic_session (strategy_type, timeframe, initial_capital, available_krw, total_asset_krw, status, trading_mode) "
                + "VALUES ('COMPOSITE_MOMENTUM_ICHIMOKU_V2', 'H1', ?::numeric, ?::numeric, ?::numeric, 'RUNNING', ?) RETURNING id", Long.class, capital, capital, capital, mode);
    }

    /** 확정된 세션 매도 한 건: pnl = 대금 − 수수료 − 수량 × 평균단가 (평균단가 10,000·수량 1). */
    private void sell(String kind, long sessionId, String pnl, Instant appliedAt) {
        String coin = "KRW-PF" + (++seq);
        Long pos = jdbc.queryForObject("INSERT INTO position (coin_pair, side, entry_price, avg_price, size, session_kind, session_id, status) "
                + "VALUES (?, 'LONG', 10000, 10000, 0, ?, ?, 'CLOSED') RETURNING id", Long.class, coin, kind, sessionId);
        jdbc.update("INSERT INTO \"order\" (coin_pair, side, order_type, quantity, state, session_kind, session_id, position_id, filled_quantity, "
                + "applied_executed_funds, applied_paid_fee, sell_applied_at) VALUES (?, 'SELL', 'MARKET', 1, 'FILLED', ?, ?, ?, 1, "
                + "10000 + ?::numeric + 3, 3, ?)", coin, kind, sessionId, pos, pnl, Timestamp.from(appliedAt));
    }

    private void fill(String pnl, Instant at) {
        jdbc.update("INSERT INTO trade_log (event_type, detail_json, created_at) VALUES ('FILL', "
                + "jsonb_build_object('realizedPnl', ?::numeric), ?)", pnl, Timestamp.from(at));
    }

    private static Instant ago(Instant asof, long hours) { return asof.minus(hours, ChronoUnit.HOURS); }

    // ── 두 판독기 ──
    record Row(String formula, String label, BigDecimal loss, BigDecimal den, String denSrc, BigDecimal pct, BigDecimal lim, String verdict) {}

    private List<Row> sql(Instant asof) throws Exception {
        String text = Files.readString(Path.of(System.getenv("PF_SQL")), StandardCharsets.UTF_8);
        String body = text.substring(text.indexOf("WITH"), text.lastIndexOf("ROLLBACK;")).trim();
        if (body.endsWith(";")) body = body.substring(0, body.length() - 1);
        final String q = body;
        return tx.execute(st -> {
            jdbc.queryForObject("SELECT set_config('preflight.asof', ?, true)", String.class, asof.toString());
            return jdbc.query(q, (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                    rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getString(8)));
        });
    }

    private Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Object target = AopTestUtils.getTargetObject(riskManagementService);
        Method m = RiskManagementService.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(java.lang.reflect.Modifier.isStatic(m.getModifiers()) ? null : target, args);
    }

    /** 제품 메서드로 같은 기준 시각의 [식][창] 손실·분모·손실률과 판정 불가 여부를 만든다. */
    private Object[] product(Instant asof) throws Exception {
        RiskConfigEntity cfg = riskManagementService.getRiskConfig();
        BigDecimal legacyDen = (BigDecimal) call("resolvePortfolioLimit", new Class<?>[]{RiskConfigEntity.class}, cfg);
        BigDecimal liveCap = (BigDecimal) call("runningLiveCapital", new Class<?>[]{});
        BigDecimal dynCap = (BigDecimal) call("runningDynamicRealCapital", new Class<?>[]{});
        BigDecimal totalCap = liveCap.add(dynCap);
        if (totalCap.signum() <= 0) totalCap = (BigDecimal) call("configuredOrFallbackLimit", new Class<?>[]{RiskConfigEntity.class}, cfg);
        BigDecimal[][] loss = new BigDecimal[3][3], den = new BigDecimal[3][3], pct = new BigDecimal[3][3];
        boolean[] impossible = new boolean[3];
        long[] hours = {24, 168, 720};
        for (int i = 0; i < 3; i++) {
            Instant since = asof.minus(hours[i], ChronoUnit.HOURS);
            BigDecimal legacy = (BigDecimal) call("legacyLossKrw", new Class<?>[]{Instant.class}, since);
            BigDecimal d = orderRepository.sumDynamicRealSessionRealizedLossSince(since);
            BigDecimal dyn = d == null || d.signum() >= 0 ? BigDecimal.ZERO : d;
            loss[0][i] = legacy; den[0][i] = legacyDen;
            pct[0][i] = (BigDecimal) call("calculateLossPct", new Class<?>[]{Instant.class, BigDecimal.class}, since, legacyDen);
            loss[1][i] = dyn; den[1][i] = dynCap;
            impossible[i] = dyn.signum() < 0 && dynCap.signum() <= 0;   // 제품 루프와 같이 창마다
            pct[1][i] = dyn.signum() < 0 ? (BigDecimal) call("pct", new Class<?>[]{BigDecimal.class, BigDecimal.class}, dyn, dynCap) : BigDecimal.ZERO;
            loss[2][i] = legacy.add(dyn); den[2][i] = totalCap;
            pct[2][i] = (BigDecimal) call("pct", new Class<?>[]{BigDecimal.class, BigDecimal.class}, legacy.add(dyn), totalCap);
        }
        return new Object[]{loss, den, pct, impossible, cfg};
    }

    /** 조회문 v2 와 제품의 칸별 대조 + 결론 대조. 반환: 조회문 마지막 판정 줄. */
    private String compare(String name, Instant asof, boolean checkPublicPath) throws Exception {
        List<Row> rows = sql(asof);
        Object[] p = product(asof);
        BigDecimal[][] loss = (BigDecimal[][]) p[0], den = (BigDecimal[][]) p[1], pct = (BigDecimal[][]) p[2];
        boolean[] impossible = (boolean[]) p[3];
        RiskEngine engine = new RiskEngine((com.cryptoautotrader.core.risk.RiskConfig) call("toRiskConfig", new Class<?>[]{RiskConfigEntity.class}, riskManagementService.getRiskConfig()));
        assertThat(rows).hasSize(10);
        StringBuilder rep = new StringBuilder("[" + name + "] asof=" + asof);
        for (int f = 0; f < 3; f++) {
            for (int i = 0; i < 3; i++) {
                Row r = rows.get(f * 3 + i);
                assertThat(r.formula()).isEqualTo(FORMULA[f]);
                assertThat(r.label()).isEqualTo(WIN[i]);
                assertThat(r.loss()).as(name + " " + FORMULA[f] + " " + WIN[i] + " 손실").isEqualByComparingTo(loss[f][i]);
                assertThat(r.den()).as(name + " " + FORMULA[f] + " " + WIN[i] + " 분모").isEqualByComparingTo(den[f][i]);
                assertThat(r.pct()).as(name + " " + FORMULA[f] + " " + WIN[i] + " 손실률").isEqualByComparingTo(pct[f][i]);
                BigDecimal z = BigDecimal.ZERO;
                RiskCheckResult one = engine.check(i == 0 ? pct[f][i] : z, i == 1 ? pct[f][i] : z, i == 2 ? pct[f][i] : z, 0, null, null);
                String expect = (f == 1 && impossible[i]) ? "판정 불가(DYNAMIC REAL 손실 있음·자본 0)" : (one.isApproved() ? "허용" : "거절");
                assertThat(r.verdict()).as(name + " " + FORMULA[f] + " " + WIN[i] + " 판정").isEqualTo(expect);
                rep.append(String.format("%n  %s %s 손실=%s 분모=%s(%s) 손실률=%s 한도=%s → %s", r.formula(), r.label(),
                        r.loss().stripTrailingZeros().toPlainString(), r.den().stripTrailingZeros().toPlainString(), r.denSrc(),
                        r.pct().toPlainString(), r.lim(), r.verdict()));
            }
        }
        String verdict = rows.get(9).verdict();
        rep.append("\n  조회문 판정: ").append(verdict);
        if (checkPublicPath) {
            RiskCheckResult pub = riskManagementService.checkRealLossGuard();
            rep.append("\n  제품 checkRealLossGuard: ").append(pub.isApproved() ? "승인" : "거절 — " + pub.getReason());
            assertThat(verdict.startsWith("진행 가능")).as(name + " 결론(진행 가능 ⇔ 제품 승인)").isEqualTo(pub.isApproved());
            if (!pub.isApproved()) {
                String reason = pub.getReason();
                if (verdict.startsWith("🔴 중단: 기존")) {
                    assertThat(reason).doesNotStartWith("[");
                    assertThat(reason).contains(verdict.contains(" 1d ") ? "일일" : verdict.contains(" 7d ") ? "주간" : "월간");
                } else if (verdict.contains("판정 불가(DYNAMIC REAL")) {
                    assertThat(reason).startsWith("[DYNAMIC REAL 보호식] 판정 불가");
                } else if (verdict.startsWith("🔴 중단: DYNAMIC REAL")) {
                    assertThat(reason).startsWith("[DYNAMIC REAL 보호식]");
                } else {
                    assertThat(verdict).startsWith("🔴 중단: 전체 REAL");
                    assertThat(reason).startsWith("[전체 REAL 보호식]");
                }
                if (verdict.contains("거절 ")) {   // 제품 사유의 %.2f 손실률이 조회문 첫 거절 손실률과 같다
                    String sqlPct = verdict.substring(verdict.indexOf("거절 ") + 3, verdict.indexOf("% >"));
                    assertThat(reason).contains(String.format("%.2f%%", new BigDecimal(sqlPct)));
                }
            }
        }
        REPORT.add(rep.toString());
        System.out.println("PFPARITY " + rep);
        return verdict;
    }

    private static Instant asofNow() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    // ── 사례 ──
    @Test @Order(1) @DisplayName("손실 0·자본 0: 허용, 전체 분모는 설정 portfolio_limit_krw → 없으면 최종 폴백 10,000,000")
    void zeroLossZeroCapital() throws Exception {
        limits("1.50", "4.00", "8.00", "5000000");
        assertThat(compare("손실0·자본0·설정한도", asofNow(), true)).startsWith("진행 가능");
        limits("1.50", "4.00", "8.00", null);
        assertThat(compare("손실0·자본0·최종폴백", asofNow(), true)).startsWith("진행 가능");
    }

    @Test @Order(2) @DisplayName("LIVE 단독 2% 거절은 DYNAMIC REAL 자본을 더해도 승인되지 않는다(기존 식 분모는 LIVE 만)")
    void liveTwoPercentNotDilutedByDynamicCapital() throws Exception {
        Instant a = asofNow();
        long l = live("100000");
        sell("LIVE", l, "-2000", ago(a, 3));
        assertThat(compare("LIVE 2%", a, true)).startsWith("🔴 중단: 기존 1d 거절 2.00% > 1.50%");
        dynamic("REAL", "900000");
        assertThat(compare("LIVE 2% + DYNAMIC 자본 900,000", asofNow(), true)).startsWith("🔴 중단: 기존 1d 거절 2.00% > 1.50%");
    }

    @Test @Order(3) @DisplayName("DYNAMIC REAL 손실이 있는데 RUNNING DYNAMIC REAL 자본 0 → 판정 불가(거절)")
    void dynamicLossWithoutCapitalIsImpossible() throws Exception {
        Instant a = asofNow();
        long l = live("1000000");
        long d = dynamic("REAL", "100000");
        sell("DYNAMIC", d, "-500", ago(a, 100));
        jdbc.update("UPDATE dynamic_session SET status = 'STOPPED' WHERE id = ?", d);
        assertThat(compare("DYNAMIC 손실·자본0", a, true)).contains("판정 불가(DYNAMIC REAL 손실 있음·자본 0)");
    }

    @Test @Order(4) @DisplayName("PAPER 제외: DYN_PAPER 손실 행·PAPER 세션 자본은 분자·분모에 없다")
    void paperExcluded() throws Exception {
        Instant a = asofNow();
        long p = dynamic("PAPER", "5000000");
        sell("DYN_PAPER", p, "-90000", ago(a, 2));
        String v = compare("PAPER 만", a, true);
        assertThat(v).startsWith("진행 가능");
    }

    @Test @Order(5) @DisplayName("이익 비상쇄: 같은 창의 이익 행이 손실을 줄이지 않는다(LIVE 세션 매도·FILL 모두)")
    void profitDoesNotOffset() throws Exception {
        Instant a = asofNow();
        long l = live("100000");
        sell("LIVE", l, "5000", ago(a, 2));
        sell("LIVE", l, "-1000", ago(a, 3));
        fill("7000", ago(a, 4));
        fill("-600", ago(a, 5));
        assertThat(compare("이익 비상쇄", a, true)).startsWith("🔴 중단: 기존 1d 거절 1.60% > 1.50%");
    }

    @Test @Order(6) @DisplayName("반올림·동률: 1,500=1.50% 허용, 1,504.99=1.50499%→소수4자리 HALF_UP 0.0150→1.50% 허용, 1,505=1.51% 거절")
    void roundingAndTie() throws Exception {
        long l = live("100000");
        Instant a = asofNow();
        sell("LIVE", l, "-1500", ago(a, 3));
        assertThat(compare("1,500 동률", a, true)).startsWith("진행 가능");
        jdbc.update("UPDATE \"order\" SET sell_applied_at = sell_applied_at - interval '400 days' WHERE sell_applied_at > now() - interval '1 day'");
        a = asofNow();
        sell("LIVE", l, "-1504.99", ago(a, 3));
        assertThat(compare("1,504.99", a, true)).startsWith("진행 가능");
        jdbc.update("UPDATE \"order\" SET sell_applied_at = sell_applied_at - interval '400 days' WHERE sell_applied_at > now() - interval '1 day'");
        a = asofNow();
        sell("LIVE", l, "-1505", ago(a, 3));
        assertThat(compare("1,505", a, true)).startsWith("🔴 중단: 기존 1d 거절 1.51% > 1.50%");
    }

    @Test @Order(7) @DisplayName("전체 REAL: 기존 1%·DYNAMIC 1% 각각 허용, 합 2,000/200,000=1% 허용 → DYNAMIC 자본 감소로 전체 1.5% 초과 시 전체 REAL 거절")
    void totalReal() throws Exception {
        Instant a = asofNow();
        long l = live("100000");
        long d = dynamic("REAL", "100000");
        sell("LIVE", l, "-1000", ago(a, 3));
        sell("DYNAMIC", d, "-1000", ago(a, 3));
        assertThat(compare("전체 1%", a, true)).startsWith("진행 가능");
        limits("1.50", "4.00", "8.00", null);
        jdbc.update("UPDATE dynamic_session SET initial_capital = 70000 WHERE id = ?", d);
        // 기존 1.00% 허용, DYNAMIC 1000/70000=1.4286→1.43% 허용, 전체 2000/170000=1.1765→1.18% 허용
        assertThat(compare("전체 자본 축소", asofNow(), true)).startsWith("진행 가능");
        sell("DYNAMIC", d, "-200", ago(a, 4));
        // DYNAMIC 1200/70000=1.7143→1.71% 거절(DYNAMIC 이 전체보다 먼저)
        assertThat(compare("DYNAMIC 1.71%", asofNow(), true)).startsWith("🔴 중단: DYNAMIC REAL 1d 거절 1.71% > 1.50%");
        jdbc.update("UPDATE dynamic_session SET initial_capital = 1000000 WHERE id = ?", d);
        sell("LIVE", l, "-450", ago(a, 5));
        // 기존 1450/100000=1.45% 허용, DYNAMIC 1200/1,000,000=0.12% 허용, 전체 2650/1,100,000=0.2409→0.24% 허용
        assertThat(compare("전체 희석 허용", asofNow(), true)).startsWith("진행 가능");
        jdbc.update("UPDATE live_trading_session SET status = 'STOPPED' WHERE id = ?", l);
        jdbc.update("UPDATE dynamic_session SET status = 'STOPPED' WHERE id = ?", d);
        limits("1.50", "4.00", "8.00", "100000");
        // RUNNING 0: 기존·전체 분모 = 설정 100,000. DYNAMIC 손실 있음·자본 0 → 그 전에 기존 1450/100000 1.45% 허용 → 판정 불가
        assertThat(compare("RUNNING 없음·설정 분모", asofNow(), true)).contains("판정 불가(DYNAMIC REAL");
    }

    @Test @Order(8) @DisplayName("창 경계(같은 기준 시각): since 정각 포함, 1µs 앞 제외 — 1d·7d·30d")
    void windowBoundary() throws Exception {
        long l = live("1000000");
        Instant a = asofNow().minusSeconds(60);
        long[] h = {24, 168, 720};
        for (long hh : h) {
            Instant since = a.minus(hh, ChronoUnit.HOURS);
            sell("LIVE", l, "-100", since);                         // 포함
            sell("LIVE", l, "-10000", since.minus(1, ChronoUnit.MICROS));   // 제외(이 창)
            fill("-1", since);
            fill("-1000", since.minus(1, ChronoUnit.MICROS));
        }
        compare("경계", a, false);   // 공개 경로는 자체 now 를 써서 경계 사례에는 쓰지 않는다
        List<Row> rows = sql(a);
        // 1d = 1d 정각 행(-100·-1)만. 7d = 1d 의 네 행 + 7d 정각 행. 30d = 7d 까지 + 7d 앞 행 + 30d 정각 행. 30d 앞 행은 어디에도 없다.
        assertThat(rows.get(0).loss()).as("기존 1d").isEqualByComparingTo("-101");
        assertThat(rows.get(1).loss()).as("기존 7d").isEqualByComparingTo("-11202");
        assertThat(rows.get(2).loss()).as("기존 30d").isEqualByComparingTo("-22303");
    }

    @Test @Order(9) @DisplayName("risk_config 행 없음: 제품 거절 ⇔ 조회문 판정 불가")
    void noRiskConfig() throws Exception {
        jdbc.update("DELETE FROM risk_config");
        String v = sqlVerdictOnly();
        assertThat(v).isEqualTo("🔴 중단: 판정 불가(risk_config 행 없음)");
        RiskCheckResult pub = riskManagementService.checkRealLossGuard();
        assertThat(pub.isApproved()).isFalse();
        assertThat(pub.getReason()).contains("risk_config 행이 없다");
        REPORT.add("[risk_config 없음] 조회문: " + v + " / 제품: 거절 — " + pub.getReason());
        System.out.println("PFPARITY " + REPORT.get(REPORT.size() - 1));
    }

    @Test @Order(10) @DisplayName("한도 NULL: 제품은 판정 중 예외(진입 중단) ⇔ 조회문 판정 불가(한도 NULL)")
    void nullLimit() throws Exception {
        jdbc.update("UPDATE risk_config SET max_weekly_loss_pct = NULL");
        String v = sqlVerdictOnly();
        assertThat(v).startsWith("🔴 중단: 기존 7d 판정 불가(한도 NULL)");
        assertThatThrownBy(() -> riskManagementService.checkRealLossGuard()).isInstanceOf(RuntimeException.class);
        REPORT.add("[한도 NULL] 조회문: " + v + " / 제품: 예외(진입 중단)");
        System.out.println("PFPARITY " + REPORT.get(REPORT.size() - 1));
    }

    private String sqlVerdictOnly() throws Exception {
        List<Row> rows = sql(asofNow());
        return rows.get(rows.size() - 1).verdict();
    }
}
