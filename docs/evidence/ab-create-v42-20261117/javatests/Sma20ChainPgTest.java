package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.177 D3·v1.178 §4-2 — SMA20 단건 도구(sma20_create·sma20_complete, 전송 v4.2)를 실제 Spring 서버(임의 포트) HTTP 와 격리 로컬 PG
 * (v152-pg) psql 로 잇는다(2026-11-17, 시험만). 운영 서버·운영 DB·실제 토큰 없음.
 *
 * <p>순서: POST 1 → 운영 스케줄러 평가(runStrategy) 뒤 주문·포지션 0 → psql 확인 → 완성 → 음성(1µs·다른 DB 같은 ID·T0 전 주문·포지션)은 거절(완성본 0)
 * → 같은 기록 폴더 재실행 4·새 폴더 재실행 2(forwardTag 있음) → 세션 1개 그대로(추가 POST 0).</p>
 *
 * <p>환경 변수: SMA20_CHAIN_PG_URL(빈 격리 DB), SMA20_CHAIN_DRIVER(sma20_chain_driver.py), SMA20_CHAIN_CODE_SHA(40자리),
 * SMA20_CHAIN_OTHER_DB(V96 스키마가 있는 다른 격리 DB), SMA20_CHAIN_OUT. 생성 창 규칙상 실제 시각이 01:01~23:24 UTC 일 때만 POST 한다.</p>
 */
@EnabledIfEnvironmentVariable(named = "SMA20_CHAIN_PG_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.+")
@DisplayName("SMA20 단건 실제 연결 — HTTP 생성 1 → 평가 뒤 주문0 → psql 확인 → 완성, 음성 거절·재실행 추가 POST 0")
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.url=${SMA20_CHAIN_PG_URL}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.username=trader",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
})
class Sma20ChainPgTest extends IntegrationTestBase {

    @MockBean private UpbitWebSocketClient ws;
    @MockBean private UpbitRestClient rest;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitOrderClient orders;
    @MockBean private TelegramNotificationService telegram;
    @MockBean private PortfolioSyncService portfolioSync;
    @MockBean private MarketDataSyncService marketDataSync;

    @LocalServerPort private int port;
    @Autowired private PaperTradingService paperTradingService;
    @Autowired private MarketDataCacheRepository cacheRepo;
    @Autowired private JdbcTemplate jdbc;

    private static final String CONTAINER = "v152-pg";
    private final Path out = Path.of(System.getenv().getOrDefault("SMA20_CHAIN_OUT", "build/sma20-chain"));
    private final List<String> transcript = new ArrayList<>();

    private String sh(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(String.join(" ", cmd) + " → " + text).isZero();
        return text;
    }

    private int driver(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("python", "-X", "utf8", System.getenv("SMA20_CHAIN_DRIVER")));
        cmd.addAll(List.of(args));
        String url = System.getenv("SMA20_CHAIN_PG_URL");
        cmd.addAll(List.of("--out", out.toAbsolutePath().toString(), "--db", url.substring(url.lastIndexOf('/') + 1)));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        String line = "$ sma20_chain_driver " + String.join(" ", args) + " → rc=" + p.exitValue() + "\n" + text.trim();
        transcript.add(line);
        System.out.println("[SMA20 연결] " + line);
        return p.exitValue();
    }

    private long sessionId() throws Exception {
        String d = Files.readString(out.resolve("create").resolve("sma20_creation.draft.json"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"session_id\": \"(\\d+)\"").matcher(d);
        assertThat(m.find()).isTrue();
        return Long.parseLong(m.group(1));
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void sma20ChainOnRealServerAndPsql() throws Exception {
        String url = System.getenv("SMA20_CHAIN_PG_URL");
        String db = url.substring(url.lastIndexOf('/') + 1);
        String driverPath = System.getenv("SMA20_CHAIN_DRIVER");
        assertThat(driverPath).isNotBlank();
        assertThat(new File(driverPath).getName()).isEqualTo("sma20_chain_driver.py");
        assertThat(System.getenv("SMA20_CHAIN_CODE_SHA")).isNotNull().matches("[0-9a-f]{40}");
        String other = System.getenv("SMA20_CHAIN_OTHER_DB");
        assertThat(other).as("SMA20_CHAIN_OTHER_DB 필요").isNotBlank().isNotEqualTo(db);
        assertThat(sh("docker", "port", CONTAINER, "5432")).contains(":" + url.replaceAll(".*:(\\d+)/.*", "$1"));
        assertThat(sh("docker", "exec", CONTAINER, "psql", "-U", "trader", "-d", db, "-tAc", "select current_database()").trim()).isEqualTo(db);
        Files.createDirectories(out);

        // 평가 입력 — BTC H1 500봉(마지막 = 직전 완료 정시 봉)
        Instant last = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
        List<MarketDataCacheEntity> rows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            BigDecimal c = BigDecimal.valueOf(100_000_000L + i * 10_000L);
            rows.add(MarketDataCacheEntity.builder().time(last.minus(499 - i, ChronoUnit.HOURS)).coinPair("KRW-BTC").timeframe("H1")
                    .open(c).high(c.add(BigDecimal.TEN)).low(c.subtract(BigDecimal.TEN)).close(c).volume(new BigDecimal("1")).build());
        }
        cacheRepo.saveAll(rows);

        // ① 실제 HTTP 생성 1
        assertThat(driver("create", "--port", String.valueOf(port), "--code-sha", System.getenv("SMA20_CHAIN_CODE_SHA"))).isZero();
        long id = sessionId();
        assertThat(count("SELECT count(*) FROM paper_trading.virtual_balance WHERE strategy_name = 'DAILY_SMA20'")).isEqualTo(1L);

        // ② 운영 스케줄러 평가 — 첫 09:00 전이면 HOLD, 주문·포지션 0
        paperTradingService.runStrategy();
        long ordersAfterEval = count("SELECT count(*) FROM paper_trading.\"order\" WHERE session_id = ?", id);
        long posAfterEval = count("SELECT count(*) FROM paper_trading.position WHERE session_id = ?", id);
        transcript.add("첫 평가 뒤 주문=" + ordersAfterEval + " 포지션=" + posAfterEval);
        assertThat(ordersAfterEval).isZero();
        assertThat(posAfterEval).isZero();

        // ③ 정상 확인 → 완성
        assertThat(driver("check", "--tag", "ok")).isZero();
        assertThat(out.resolve("ok.sma20_creation_log.json")).exists();

        // ④ 음성 — 1마이크로초
        jdbc.update("UPDATE paper_trading.virtual_balance SET started_at = started_at + interval '1 microsecond' WHERE id = ?", id);
        int oneMicro = driver("check", "--tag", "neg_1us");
        jdbc.update("UPDATE paper_trading.virtual_balance SET started_at = started_at - interval '1 microsecond' WHERE id = ?", id);
        // ⑤ 음성 — 다른 DB 의 같은 ID(started_at +1초)
        assertThat(driver("seed-other", "--other-db", other)).isZero();
        assertThat(sh("docker", "exec", CONTAINER, "psql", "-U", "trader", "-d", other, "-tAc",
                "select count(*) from paper_trading.virtual_balance where id = " + id).trim()).as("다른 DB 에 같은 ID").isEqualTo("1");
        int otherDb = driver("check", "--tag", "neg_other_db", "--psql-db", other);
        // ⑥ 음성 — T0 전 주문(형식용 행, 엔진 체결 아님)
        jdbc.update("INSERT INTO paper_trading.\"order\" (coin_pair, side, order_type, price, quantity, state, filled_at, created_at, session_id) "
                + "VALUES ('KRW-BTC', 'BUY', 'MARKET', 100, 1, 'FILLED', ?, ?, ?)", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), id);
        int orderBefore = driver("check", "--tag", "neg_order_before_t0");
        jdbc.update("DELETE FROM paper_trading.\"order\" WHERE session_id = ?", id);
        // ⑦ 음성 — 포지션(형식용)
        jdbc.update("INSERT INTO paper_trading.position (session_id, session_kind, coin_pair, side, entry_price, avg_price, size, position_fee, "
                + "invested_krw, status, opened_at) VALUES (?, 'PAPER', 'KRW-BTC', 'BUY', 100, 100, 1, 0, 100, 'OPEN', ?)", id, Timestamp.from(Instant.now()));
        int position = driver("check", "--tag", "neg_position");
        jdbc.update("DELETE FROM paper_trading.position WHERE session_id = ?", id);

        // ⑧ 재실행 — 같은 기록 폴더(기록부: 생성 반복 금지 4), 새 폴더(forwardTag 행: 2). 세션 1개 그대로.
        int rerunSame = driver("create", "--port", String.valueOf(port), "--code-sha", System.getenv("SMA20_CHAIN_CODE_SHA"));
        int rerunNew = driver("create", "--port", String.valueOf(port), "--code-sha", System.getenv("SMA20_CHAIN_CODE_SHA"), "--sub", "create-rerun-new");
        long sessions = count("SELECT count(*) FROM paper_trading.virtual_balance WHERE strategy_name = 'DAILY_SMA20'");
        transcript.add("음성 rc: 1µs=" + oneMicro + " 다른DB=" + otherDb + " T0전주문=" + orderBefore + " 포지션=" + position
                + " / 재실행 같은폴더=" + rerunSame + " 새폴더=" + rerunNew + " / SMA20 세션 수=" + sessions);
        Files.writeString(out.resolve("transcript.txt"), String.join("\n\n", transcript) + "\n", StandardCharsets.UTF_8);

        assertThat(List.of(oneMicro, otherDb, orderBefore, position)).containsOnly(3);
        for (String t : List.of("neg_1us", "neg_other_db", "neg_order_before_t0", "neg_position")) {
            assertThat(out.resolve(t + ".sma20_creation_log.json")).as(t + " 완성본 없음").doesNotExist();
        }
        assertThat(rerunSame).isEqualTo(4);
        assertThat(rerunNew).isEqualTo(2);
        assertThat(sessions).as("추가 POST 0").isEqualTo(1L);
    }
}
