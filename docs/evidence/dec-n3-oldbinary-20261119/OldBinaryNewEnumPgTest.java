package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * <b>N3 구판 실제 조회</b> (총괄 v1.194 §2-3) — 구판(운영 0e1d2a02 / 복구판 b5cd1d0)이 모르는 ExitReason 값 1행이 있을 때
 * 구판 바이너리의 실제 엔티티 조회 경로가 어떻게 되는지 관측만 한다. 제품 코드는 바꾸지 않는다(이 파일만 구판 사본에 임시로 둔다).
 *
 * <p>새 값은 후보가 저장하는 형태(CLOSED·size 0·exit_reason 'ENTRY_REJECTED')로 행을 직접 쓴다(구판 열거형에는 그 값이 없다).
 * 단계 1 은 새 값 행이 있는 세션 쌍에서 경로를 부르고, 그 행은 DB 에 그대로 남긴다(뒤에 관문 3·4판을 같은 DB 에 대조).
 * 단계 2 는 다른 세션 쌍에 같은 행을 두고 사유만 NULL 로 바꾼 뒤 같은 경로를 불러 실패 원인이 그 값뿐인지 본다.</p>
 * 로컬 전용: 주소가 localhost 가 아니면 실행하지 않는다.
 */
@TestPropertySource(properties = {
        "spring.datasource.url=${OB_PG_URL}",
        "spring.datasource.username=${OB_PG_USER:trader}",
        "spring.datasource.password=${OB_PG_PASSWORD:rtpass}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never"
})
@EnabledIfEnvironmentVariable(named = "OB_PG_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.+")
@DisplayName("N3 구판 — 새 ExitReason 값 1행에서 실제 엔티티 조회 경로 관측 (로컬 PostgreSQL)")
class OldBinaryNewEnumPgTest extends IntegrationTestBase {

    @Autowired private PositionRepository positionRepository;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private RiskManagementService riskManagementService;
    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private PositionService positionService;
    @Autowired private JdbcTemplate jdbc;

    private Long live() {
        return liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT").coinPair("KRW-RND").timeframe("H1")
                .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("10000"))
                .totalAssetKrw(new BigDecimal("10000")).status("STOPPED").startedAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build()).getId();
    }

    private Long dynamic() {
        return dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(new BigDecimal("10000.00")).availableKrw(new BigDecimal("10000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00"))
                .status("STOPPED").scanState("SCANNING").tradingMode("REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60).startedAt(Instant.now().minus(1, ChronoUnit.HOURS)).build()).getId();
    }

    /** 후보의 예약 거절 슬롯과 같은 모양. 사유는 NULL 로 저장한 뒤 열만 직접 바꾼다(구판 열거형에는 그 값이 없다). */
    private Long slot(String kind, Long sid, String reason) {
        Instant t = Instant.now().minus(5, ChronoUnit.MINUTES);
        PositionEntity p = positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-RND").side("BUY").entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(BigDecimal.ZERO).realizedPnl(BigDecimal.ZERO).unrealizedPnl(BigDecimal.ZERO)
                .status("CLOSED").openedAt(t).closedAt(t.plusSeconds(1))
                .sessionKind(kind).sessionId(sid).investedKrw(new BigDecimal("3000")).build());
        if (reason != null) jdbc.update("UPDATE position SET exit_reason = ? WHERE id = ?", reason, p.getId());
        return p.getId();
    }

    private static Map<String, Object> probe(Callable<Object> c) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            Object v = c.call();
            r.put("ok", true);
            r.put("value", String.valueOf(v));
        } catch (Throwable e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            r.put("ok", false);
            r.put("error", e.getClass().getName());
            r.put("message", String.valueOf(e.getMessage()).substring(0, Math.min(300, String.valueOf(e.getMessage()).length())));
            r.put("root", root.getClass().getName() + ": " + String.valueOf(root.getMessage()).substring(0,
                    Math.min(200, String.valueOf(root.getMessage()).length())));
        }
        return r;
    }

    private Map<String, Object> paths(Long ls, Long ds) throws Exception {
        Method cooldown = DynamicTradingService.class.getDeclaredMethod("isInLossCooldown", String.class, Long.class, String.class, int.class);
        cooldown.setAccessible(true);   // 프록시가 아니라 실제 빈에 부른다(1회차는 프록시 필드 null 로 NPE — 관측 도구 결함)
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("P1_repo_findBySessionKindAndSessionId_DYNAMIC", probe(() -> positionRepository.findBySessionKindAndSessionId("DYNAMIC", ds).size()));
        m.put("P2_dynamic_checkCircuitBreaker", probe(() -> {
            var r = riskManagementService.checkCircuitBreaker(dynamicRepo.findById(ds).orElseThrow());
            return r.isTriggered() + "/" + r.getReason();
        }));
        m.put("P3_dynamic_isInLossCooldown", probe(() -> cooldown.invoke(org.springframework.test.util.AopTestUtils.getUltimateTargetObject(dynamicTradingService), "DYNAMIC", ds, "KRW-RND", 60)));
        m.put("P4_live_checkCircuitBreaker", probe(() -> {
            var r = riskManagementService.checkCircuitBreaker(liveRepo.findById(ls).orElseThrow());
            return r.isTriggered() + "/" + r.getReason();
        }));
        m.put("P5_live_getSessionPositions", probe(() -> liveTradingService.getSessionPositions(ls).size()));
        m.put("P6_positions_page", probe(() -> positionService.getAllPositions(PageRequest.of(0, 50)).getNumberOfElements()));
        m.put("P7_native_count_by_reason", probe(() -> jdbc.queryForList(
                "SELECT COALESCE(exit_reason,'(null)') r, COUNT(*) n FROM position WHERE session_id IN (?, ?) GROUP BY 1 ORDER BY 1", ls, ds)));
        return m;
    }

    @Test
    @DisplayName("새 값 1행 → 구판 엔티티 조회 경로 관측, NULL 대조")
    void observe() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("binary_exit_reasons", java.util.Arrays.toString(com.cryptoautotrader.api.entity.ExitReason.values()));
        jdbc.update("UPDATE risk_config SET circuit_breaker_enabled = TRUE, consecutive_loss_limit = 3");

        // 단계 1: 새 값 행 + NULL 사유 대조 행이 있는 세션 쌍 (이 행들은 DB 에 그대로 남긴다 — 관문 대조용)
        Long ls1 = live(), ds1 = dynamic();
        slot("LIVE", ls1, "ENTRY_REJECTED");
        slot("DYNAMIC", ds1, "ENTRY_REJECTED");
        out.put("stage1_new_value_present", paths(ls1, ds1));

        // 단계 2: 다른 세션 쌍에 같은 행을 두고 사유를 NULL 로 바꾼 뒤 같은 경로 (실패 원인이 값뿐인지)
        Long ls2 = live(), ds2 = dynamic();
        Long a = slot("LIVE", ls2, "ENTRY_REJECTED"), b = slot("DYNAMIC", ds2, "ENTRY_REJECTED");
        jdbc.update("UPDATE position SET exit_reason = NULL WHERE id IN (?, ?)", a, b);
        Map<String, Object> s2 = paths(ls2, ds2);
        s2.remove("P6_positions_page");   // 전체 목록은 단계1 행이 남아 있어 대조 의미가 없다
        out.put("stage2_same_rows_reason_null", s2);
        out.put("left_in_db", jdbc.queryForList(
                "SELECT id, session_kind, session_id, exit_reason, status, size FROM position WHERE exit_reason = 'ENTRY_REJECTED' ORDER BY id"));
        out.put("sessions", Map.of("stage1", ls1 + "/" + ds1, "stage2", ls2 + "/" + ds2));

        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        String dir = System.getenv("COSTB_OUT");
        if (dir != null) {
            Files.createDirectories(Path.of(dir));
            Files.writeString(Path.of(dir, "old_binary_new_enum.json"), json + "\n", StandardCharsets.UTF_8);
        }
        System.out.println("[N3 구판] " + json);
    }
}
