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
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.150 §4 — A/B 생성 호출기 → 실제 서버 HTTP 생성 8개 → 첫 평가(규칙 지문) → 실제 psql 확인 SQL → 완성기 → 실제 psql 추출 SQL → 집계기.
 *
 * <p>격리 로컬 PG(환경 변수 {@code AB_CHAIN_PG_URL}, 빈 DB) + 임의 포트의 실제 Spring 서버. 단계 구동은 {@code AB_CHAIN_DRIVER}(chain_driver.py)를
 * 프로세스로 부른다(도구 원문 그대로 import). 실제 생성 API 승인 아님 — 로컬 시험 서버다. 거래 행(포지션)은 SQL·집계 형식 확인용으로
 * 시험이 직접 넣는다(엔진 체결 아님). 음성 입력은 실제 DB 상태를 바꾼 뒤 같은 SQL 을 다시 실행해 완성기가 거절하는지 본다.</p>
 */
@EnabledIfEnvironmentVariable(named = "AB_CHAIN_PG_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.+")
@DisplayName("A/B 실제 연결 — HTTP 생성 8 → 지문 → psql 확인 → 완성 → psql 추출 → 집계, 음성(지문 없음·복수·T0 전 거래·저장값 불일치)")
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.url=${AB_CHAIN_PG_URL}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.username=trader",
        "spring.datasource.password=",
        "spring.datasource.hikari.connection-init-sql=SET lock_timeout = '20s'",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
})
class AbChainPgTest extends IntegrationTestBase {

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

    private final Path out = Path.of(System.getenv().getOrDefault("AB_CHAIN_OUT", "build/ab-chain"));
    private final List<String> transcript = new ArrayList<>();

    private int driver(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("python", "-X", "utf8", System.getenv("AB_CHAIN_DRIVER")));
        cmd.addAll(List.of(args));
        cmd.addAll(List.of("--out", out.toAbsolutePath().toString()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        String line = "$ chain_driver " + String.join(" ", args) + " → rc=" + p.exitValue() + "\n" + text.trim();
        transcript.add(line);
        System.out.println("[A/B 연결] " + line);
        return p.exitValue();
    }

    private void seed(String coin, String tf, int minutes) {
        Instant lastOpen = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        lastOpen = lastOpen.minus(lastOpen.getEpochSecond() / 60 % minutes, ChronoUnit.MINUTES).minus(minutes, ChronoUnit.MINUTES);
        List<MarketDataCacheEntity> rows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            BigDecimal c = BigDecimal.valueOf(100_000 + (i * 37 % 101) * 10L);
            rows.add(MarketDataCacheEntity.builder().time(lastOpen.minus((long) (499 - i) * minutes, ChronoUnit.MINUTES))
                    .coinPair(coin).timeframe(tf).open(c).high(c.add(BigDecimal.TEN)).low(c.subtract(BigDecimal.TEN)).close(c)
                    .volume(new BigDecimal("100")).build());
        }
        cacheRepo.saveAll(rows);
    }

    private List<Long> ids() throws Exception {
        String d = Files.readString(out.resolve("create").resolve("creation_log.draft.json"));
        List<Long> ids = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"session_id\": \"(\\d+)\"").matcher(d);
        while (m.find()) ids.add(Long.parseLong(m.group(1)));
        return ids;
    }

    private Instant t0() throws Exception {
        String d = Files.readString(out.resolve("create").resolve("creation_log.draft.json"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"t0\": \"([^\"]+)\"").matcher(d);
        assertThat(m.find()).isTrue();
        return OffsetDateTime.parse(m.group(1)).toInstant();
    }

    private void insertPosition(long sid, String coin, String status, Instant opened, Instant closed, String invested, String realized) {
        jdbc.update("INSERT INTO paper_trading.position (session_id, session_kind, coin_pair, side, entry_price, avg_price, size, position_fee, "
                        + "invested_krw, status, opened_at, closed_at, realized_pnl, exit_reason) VALUES (?, 'PAPER', ?, 'BUY', 100, 100, 200, 10, ?, ?, ?, ?, ?, ?)",
                sid, coin, new BigDecimal(invested), status, Timestamp.from(opened), closed == null ? null : Timestamp.from(closed),
                realized == null ? BigDecimal.ZERO : new BigDecimal(realized), "CLOSED".equals(status) ? "TAKE_PROFIT" : ("CLOSING".equals(status) ? "TIME_STOP" : null));
    }

    @Test
    void chainOnRealServerAndPsql() throws Exception {
        Files.createDirectories(out);
        for (String coin : List.of("KRW-BTC", "KRW-ETH")) {
            seed(coin, "M15", 15);
            seed(coin, "H1", 60);
            seed(coin, "H4", 240);
        }

        // ① 실제 HTTP 생성 8 (토큰은 시험 프로필 로컬 값)
        assertThat(driver("create", "--port", String.valueOf(port), "--code-sha", System.getenv("AB_CHAIN_CODE_SHA"))).isZero();
        List<Long> ids = ids();
        Instant t0 = t0();
        assertThat(ids).hasSize(8);

        // ② 첫 평가 — 운영 스케줄러가 부르는 runStrategy 그대로(신호 로그에 규칙 지문)
        paperTradingService.runStrategy();
        List<Map<String, Object>> fp = jdbc.queryForList("SELECT session_id, count(DISTINCT ruleset_hash) AS n, min(ruleset_hash) AS h FROM strategy_log "
                + "WHERE session_type = 'PAPER' AND session_id = ANY (?) GROUP BY session_id ORDER BY session_id",
                (Object) jdbc.getDataSource().getConnection().createArrayOf("bigint", ids.toArray()));
        System.out.println("[A/B 연결] 첫 평가 지문=" + fp);
        transcript.add("첫 평가 지문=" + fp);

        // ③ 정상 — 실제 psql 확인 → 완성
        assertThat(driver("check", "--tag", "ok")).isZero();

        long x = ids.get(2);
        // ④ 음성 — 지문 없음(그 세션 로그를 잠시 다른 ID 로 옮김)
        jdbc.update("UPDATE strategy_log SET session_id = -session_id WHERE session_type = 'PAPER' AND session_id = ?", x);
        int fpMissing = driver("check", "--tag", "neg_fp_missing");
        jdbc.update("UPDATE strategy_log SET session_id = -session_id WHERE session_type = 'PAPER' AND session_id = ?", -x);
        // ⑤ 음성 — 지문 복수(다른 지문의 로그 한 줄 추가)
        jdbc.update("INSERT INTO strategy_log (strategy_name, coin_pair, signal, reason, created_at, session_type, session_id, was_executed, ruleset_hash, timeframe) "
                + "SELECT strategy_name, coin_pair, signal, reason, now(), session_type, session_id, false, 'ffffffffffff', timeframe FROM strategy_log "
                + "WHERE session_type = 'PAPER' AND session_id = ? ORDER BY id LIMIT 1", x);
        int fpMulti = driver("check", "--tag", "neg_fp_multiple");
        jdbc.update("DELETE FROM strategy_log WHERE session_type = 'PAPER' AND session_id = ? AND ruleset_hash = 'ffffffffffff'", x);
        // ⑥ 음성 — T0 이전 거래
        insertPosition(x, "KRW-BTC", "OPEN", t0.minus(1, ChronoUnit.MINUTES), null, "20000", null);
        int before = driver("check", "--tag", "neg_trade_before_t0");
        jdbc.update("DELETE FROM paper_trading.position WHERE session_id = ?", x);
        // ⑦ 음성 — 저장값 불일치(한 세션의 투자 비율)
        jdbc.update("UPDATE paper_trading.virtual_balance SET invest_ratio = 0.7 WHERE id = ?", x);
        int stored = driver("check", "--tag", "neg_stored_ratio");
        jdbc.update("UPDATE paper_trading.virtual_balance SET invest_ratio = 0.8 WHERE id = ?", x);

        // ⑧ T0 뒤 거래(형식 확인용, 엔진 체결 아님): CLOSED 1 · OPEN 1 · CLOSING 1 → 실제 psql 추출 → 집계
        String coinOf0 = jdbc.queryForObject("SELECT coin_pair FROM paper_trading.virtual_balance WHERE id = ?", String.class, ids.get(0));
        String coinOf1 = jdbc.queryForObject("SELECT coin_pair FROM paper_trading.virtual_balance WHERE id = ?", String.class, ids.get(1));
        String coinOf2 = jdbc.queryForObject("SELECT coin_pair FROM paper_trading.virtual_balance WHERE id = ?", String.class, ids.get(2));
        insertPosition(ids.get(0), coinOf0, "CLOSED", t0.plus(1, ChronoUnit.DAYS), t0.plus(2, ChronoUnit.DAYS), "20010", "190");
        insertPosition(ids.get(1), coinOf1, "OPEN", t0.plus(40, ChronoUnit.DAYS), null, "20010", null);
        insertPosition(ids.get(2), coinOf2, "CLOSING", t0.plus(41, ChronoUnit.DAYS), null, "20010", null);
        int agg = driver("extract", "--tag", "ok");

        Files.writeString(out.resolve("transcript.txt"), String.join("\n\n", transcript) + "\n", StandardCharsets.UTF_8);
        System.out.println("[A/B 연결] 음성 rc: 지문 없음=" + fpMissing + " 복수=" + fpMulti + " T0 전 거래=" + before + " 저장 비율=" + stored + " / 집계 rc=" + agg);

        assertThat(fp).hasSize(8);
        assertThat(fp).allSatisfy(r -> assertThat(((Number) r.get("n")).intValue()).isEqualTo(1));
        assertThat(List.of(fpMissing, fpMulti, before, stored)).containsOnly(3);
        assertThat(agg).isZero();
        String positions = Files.readString(out.resolve("ok.positions.csv"));
        assertThat(positions).contains("CLOSING").contains("OPEN").contains("CLOSED");
        assertThat(new File(out.toFile(), "neg_fp_missing.creation_log.json")).doesNotExist();
    }
}
