package com.cryptoautotrader.api.forwardagg;

import com.cryptoautotrader.api.entity.paper.PaperOrderEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.paper.PaperOrderRepository;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전향 집계기 허용오차 규칙의 전제 확인 — 앱의 실제 저장 경로(Hibernate·JDBC → PostgreSQL)가 금액을 어떻게 남기는가 (2026-10-27).
 *
 * <p>설계 {@code docs/FORWARD_AGGREGATOR_DESIGN.md} §3-5 의 상한(저장 한 번 오차 ≤ 반 단위)은 <b>값이 자르지 않고 DB 까지 가서
 * 가장 가까운 값으로(절반은 0 에서 먼 쪽) 반올림된다</b>는 전제에 선다. 중간에서 버림이 일어나거나 double 로 바뀌면 깨진다
 * (예: 2.505 를 double 로 보내면 2.50499… 라 2.50 이 된다). 그래서 엔티티·저장소로 저장 → flush → clear → 재조회해
 * 양수·음수 경계값과 컬럼 자릿수를 본다. 기대값은 {@code setScale(scale, HALF_UP)} — Java HALF_UP 은 0 에서 먼 쪽이다.</p>
 *
 * <p>🔴 <b>H2 로 돌리지 않는다</b> — H2 의 반올림은 이 전제의 증거가 아니다. 로컬 PostgreSQL 전용이며 주소가 localhost 가
 * 아니면 실행하지 않는다(운영 DB 보호). 실행:</p>
 * <pre>
 * docker exec cat-rt-pg psql -U trader -d postgres -c "CREATE DATABASE fa_storage_check"   # 없을 때 한 번
 * FA_PG_URL=jdbc:postgresql://localhost:15432/fa_storage_check ./gradlew :web-api:cleanTest :web-api:test --tests '*PaperLedgerStoragePrecisionPgTest'
 * </pre>
 * Flyway 가 운영과 같은 마이그레이션을 적용한다. 각 시험은 트랜잭션 안에서 돌고 롤백된다.
 * 🔴 {@code cleanTest} 를 붙인다 — Gradle 은 환경변수를 시험 입력으로 보지 않아, 앞서 건너뛴 결과를 그대로 재사용한다(2026-10-27 실측).
 */
@DataJpaTest(properties = {
        "spring.datasource.url=${FA_PG_URL}",
        "spring.datasource.username=${FA_PG_USER:trader}",
        "spring.datasource.password=${FA_PG_PASSWORD:rtpass}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "FA_PG_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.+")
@DisplayName("전향 집계기 전제 — 모의 장부 금액의 실제 저장 정밀도 (로컬 PostgreSQL)")
class PaperLedgerStoragePrecisionPgTest {

    @Autowired private TestEntityManager em;
    @Autowired private VirtualBalanceRepository balanceRepo;
    @Autowired private PaperPositionRepository positionRepo;
    @Autowired private PaperOrderRepository orderRepo;
    @Autowired private JdbcTemplate jdbc;

    /** 경계값 — 정확히 절반(양·음), 절반 바로 아래, 버림이면 달라지는 값, double 로 바뀌면 달라지는 값. */
    private static final String[] BOUNDARY = {
            "2.505", "-2.505", "2.50499999999999999999", "-2.50499999999999999999",
            "0.005", "-0.005", "0.0049999999", "1000002.505", "5007.495",
            "0.000000005", "-0.000000005", "0.0000000049999", "12.345678905", "-12.345678905",
            "1234.567890125", "0.12345678912345678901"
    };

    @Test
    @DisplayName("컬럼 자릿수 — 설계가 전제한 형식 그대로")
    void columnScales() {
        Map<String, String> actual = new LinkedHashMap<>();
        jdbc.query("""
                select table_name, column_name, numeric_precision, numeric_scale
                  from information_schema.columns
                 where table_schema = 'paper_trading' and table_name in ('virtual_balance','position','order')
                   and data_type = 'numeric'
                """, rs -> {
            actual.put(rs.getString(1) + "." + rs.getString(2), rs.getInt(3) + "," + rs.getInt(4));
        });
        Map<String, String> expected = new LinkedHashMap<>();
        for (String c : List.of("total_krw", "available_krw", "initial_capital", "realized_pnl", "total_fee")) {
            expected.put("virtual_balance." + c, "20,2");
        }
        expected.put("virtual_balance.stop_loss_pct", "5,2");
        expected.put("virtual_balance.invest_ratio", "5,4");
        for (String c : List.of("entry_price", "avg_price", "size", "unrealized_pnl", "realized_pnl",
                "stop_loss_price", "take_profit_price", "invested_krw")) {
            expected.put("position." + c, "20,8");
        }
        expected.put("position.position_fee", "20,2");
        for (String c : List.of("price", "quantity", "filled_quantity")) {
            expected.put("order." + c, "20,8");
        }
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    @DisplayName("세션 금액 3컬럼 — 신규 저장·갱신 모두 가장 가까운 값(절반은 0 에서 먼 쪽), 저장 뒤 메모리 값은 그대로")
    void sessionColumns() {
        checkAll(2, v -> newSession(), this::reloadSession, (s, v) -> {
            s.setAvailableKrw(v);
            s.setTotalFee(v);
            s.setRealizedPnl(v);
        }, s -> List.of(s.getAvailableKrw(), s.getTotalFee(), s.getRealizedPnl()));

        // 설계 모형의 전제 — 엔진이 들고 있는 엔티티는 저장(flush) 뒤에도 반올림 전 값이다. 다음 조회에서 비로소 반올림 값을 받는다.
        VirtualBalanceEntity s = newSession();
        s.setAvailableKrw(new BigDecimal("1000002.505"));
        em.persistAndFlush(s);
        assertThat(s.getAvailableKrw()).isEqualByComparingTo("1000002.505");
        em.clear();
        assertThat(balanceRepo.findById(s.getId()).orElseThrow().getAvailableKrw()).isEqualByComparingTo("1000002.51");
    }

    @Test
    @DisplayName("포지션 — position_fee 는 2자리, 가격·수량·손익·투자금은 8자리로 반올림 저장")
    void positionColumns() {
        VirtualBalanceEntity s = em.persistAndFlush(newSession());
        checkAll(2, v -> newPosition(s.getId()), this::reloadPosition,
                (p, v) -> p.setPositionFee(v), p -> List.of(p.getPositionFee()));
        checkAll(8, v -> newPosition(s.getId()), this::reloadPosition, (p, v) -> {
            p.setRealizedPnl(v);
            p.setUnrealizedPnl(v);
            p.setSize(v);
            p.setAvgPrice(v);
            p.setEntryPrice(v);
            p.setInvestedKrw(v);
        }, p -> List.of(p.getRealizedPnl(), p.getUnrealizedPnl(), p.getSize(), p.getAvgPrice(),
                p.getEntryPrice(), p.getInvestedKrw()));
    }

    @Test
    @DisplayName("주문 — 가격·수량 8자리 반올림 저장")
    void orderColumns() {
        VirtualBalanceEntity s = em.persistAndFlush(newSession());
        checkAll(8, v -> newOrder(s.getId()), this::reloadOrder, (o, v) -> {
            o.setPrice(v);
            o.setQuantity(v);
            o.setFilledQuantity(v);
        }, o -> List.of(o.getPrice(), o.getQuantity(), o.getFilledQuantity()));
    }

    // ── 공통 ─────────────────────────────────────────────────────────────

    /**
     * 경계값 + 무작위 값(양·음, 소수 최대 20자리, 시드 고정)을 새 행으로 저장하고, 같은 행을 다른 값으로 갱신해 다시 확인한다.
     * 신규 저장(INSERT)과 갱신(UPDATE)은 엔진이 쓰는 두 경로다.
     */
    private <E> void checkAll(int scale, Function<BigDecimal, E> create, Function<E, E> reload,
                              BiConsumer<E, BigDecimal> set, Function<E, List<BigDecimal>> get) {
        List<BigDecimal> values = new ArrayList<>();
        for (String b : BOUNDARY) values.add(new BigDecimal(b));
        Random rnd = new Random(20261027L);
        for (int i = 0; i < 200; i++) {
            BigDecimal v = new BigDecimal(Math.abs(rnd.nextLong()) % 10_000_000_000L)
                    .movePointLeft(rnd.nextInt(21));
            values.add(rnd.nextBoolean() ? v : v.negate());
        }
        List<String> failures = new ArrayList<>();
        BigDecimal prev = null;
        for (BigDecimal v : values) {
            E e = create.apply(v);
            set.accept(e, v);
            e = em.persistAndFlush(e);
            em.clear();
            compare("INSERT", v, scale, get.apply(reload.apply(e)), failures);
            if (prev != null) {                                   // 같은 행을 갱신 — 직전 값으로 바꿔 UPDATE 경로를 본다
                E loaded = reload.apply(e);
                set.accept(loaded, prev);
                em.flush();
                em.clear();
                compare("UPDATE", prev, scale, get.apply(reload.apply(e)), failures);
            }
            prev = v;
        }
        assertThat(failures).as("저장값 ≠ setScale(%d, HALF_UP)", scale).isEmpty();
    }

    private static void compare(String path, BigDecimal input, int scale, List<BigDecimal> stored, List<String> failures) {
        BigDecimal expected = input.setScale(scale, RoundingMode.HALF_UP);
        for (BigDecimal s : stored) {
            if (s.compareTo(expected) != 0 || s.scale() != scale) {
                failures.add(path + " " + input.toPlainString() + " → " + s.toPlainString()
                        + " (기대 " + expected.toPlainString() + ", scale " + s.scale() + ")");
            }
        }
    }

    private VirtualBalanceEntity newSession() {
        return VirtualBalanceEntity.builder()
                .totalKrw(new BigDecimal("1000000"))
                .availableKrw(new BigDecimal("1000000"))
                .initialCapital(new BigDecimal("1000000"))
                .strategyName("FA_STORAGE_CHECK")
                .coinPair("KRW-TEST")
                .timeframe("H1")
                .status("STOPPED")
                .telegramEnabled(false)
                .startedAt(Instant.parse("2026-10-01T06:16:12Z"))
                .build();
    }

    private PaperPositionEntity newPosition(Long sessionId) {
        return PaperPositionEntity.builder()
                .sessionId(sessionId).sessionKind("PAPER").coinPair("KRW-TEST").side("BUY")
                .entryPrice(BigDecimal.ONE).avgPrice(BigDecimal.ONE).size(BigDecimal.ONE)
                .positionFee(BigDecimal.ZERO).status("OPEN")
                .build();
    }

    private PaperOrderEntity newOrder(Long sessionId) {
        return PaperOrderEntity.builder()
                .sessionId(sessionId).coinPair("KRW-TEST").side("BUY").orderType("MARKET")
                .price(BigDecimal.ONE).quantity(BigDecimal.ONE).state("FILLED")
                .build();
    }

    private VirtualBalanceEntity reloadSession(VirtualBalanceEntity s) {
        return balanceRepo.findById(s.getId()).orElseThrow();
    }

    private PaperPositionEntity reloadPosition(PaperPositionEntity p) {
        return positionRepo.findById(p.getId()).orElseThrow();
    }

    private PaperOrderEntity reloadOrder(PaperOrderEntity o) {
        return orderRepo.findById(o.getId()).orElseThrow();
    }
}
