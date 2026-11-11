package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.151 §3 / v1.152 A — 세션 시작 시각의 <b>저장 정밀도 계약</b>을 실제 로컬 PostgreSQL 에서 확인 (2026-11-11).
 * 제품 저장 경로(JPA 저장 → Hibernate → pgjdbc → timestamptz)로 나노초 시작 시각을 저장하고, psql 과 같은 DB 값(문자열)과
 * 사후 API 목록(GET /api/v1/paper-trading/sessions)의 startedAt 을 함께 읽는다. 기대 계약: 가장 가까운 마이크로초로 반올림(0.5 는 올림),
 * 초 경계를 넘는 올림 포함. A/B 생성 도구 v4 의 round_to_micros 가 이 계약을 쓴다. 격리 로컬 DB 전용({@code V96_PG_URL}).
 */
@EnabledIfEnvironmentVariable(named = "V96_PG_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.+")
@DisplayName("시작 시각 저장 정밀도 — 실제 PG 에서 나노초 → 마이크로초 반올림(0.5 올림·초 경계), API 목록 = DB 값")
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.url=${V96_PG_URL}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.username=${V96_PG_USER:trader}",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
})
class StartedAtPrecisionPgTest extends IntegrationTestBase {

    @MockBean private UpbitWebSocketClient ws;
    @MockBean private UpbitRestClient rest;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitOrderClient orders;
    @MockBean private TelegramNotificationService telegram;
    @MockBean private PortfolioSyncService portfolioSync;
    @MockBean private MarketDataSyncService marketDataSync;

    @Autowired private VirtualBalanceRepository balanceRepo;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void storedPrecisionContract() throws Exception {
        // 입력 나노초 → 기대 저장값(마이크로초 반올림)
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("2026-11-11T07:36:29.080737499Z", "2026-11-11T07:36:29.080737Z");
        cases.put("2026-11-11T07:36:29.080737500Z", "2026-11-11T07:36:29.080738Z");
        cases.put("2026-11-11T07:36:29.080737501Z", "2026-11-11T07:36:29.080738Z");
        cases.put("2026-11-11T07:36:29.000000499Z", "2026-11-11T07:36:29Z");
        cases.put("2026-11-11T07:36:29.000000500Z", "2026-11-11T07:36:29.000001Z");
        cases.put("2026-11-11T07:36:29.999999499Z", "2026-11-11T07:36:29.999999Z");
        cases.put("2026-11-11T07:36:29.999999500Z", "2026-11-11T07:36:30Z");
        cases.put("2026-11-11T23:59:59.999999700Z", "2026-11-12T00:00:00Z");
        cases.put("2026-11-11T07:36:29.080737Z", "2026-11-11T07:36:29.080737Z");

        List<String> report = new ArrayList<>();
        Map<Long, String> expectById = new LinkedHashMap<>();
        for (Map.Entry<String, String> c : cases.entrySet()) {
            VirtualBalanceEntity s = balanceRepo.save(VirtualBalanceEntity.builder()
                    .totalKrw(new BigDecimal("100000")).availableKrw(new BigDecimal("100000")).initialCapital(new BigDecimal("100000"))
                    .strategyName("PRECISION_PROBE").coinPair("KRW-BTC").timeframe("M15").status("STOPPED")
                    .startedAt(Instant.parse(c.getKey())).build());
            expectById.put(s.getId(), c.getValue());
            String db = jdbc.queryForObject("SELECT to_char(started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US') FROM paper_trading.virtual_balance WHERE id = ?",
                    String.class, s.getId());
            Instant repo = balanceRepo.findById(s.getId()).orElseThrow().getStartedAt();
            report.add(c.getKey() + " -> db=" + db + " repo=" + repo + " expect=" + c.getValue());
            assertThat(Instant.parse(db + "Z")).as(c.getKey()).isEqualTo(Instant.parse(c.getValue()));
        }
        // 사후 API 목록(같은 ID 의 startedAt 원문) = DB 값
        String body = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/paper-trading/sessions"))
                .andReturn().getResponse().getContentAsString();
        JsonNode rows = objectMapper.readTree(body).get("data");
        int matched = 0;
        for (JsonNode r : rows) {
            long id = r.get("id").asLong();
            if (expectById.containsKey(id)) {
                report.add("api id=" + id + " startedAt=" + r.get("startedAt").asText() + " expect=" + expectById.get(id));
                assertThat(Instant.parse(r.get("startedAt").asText())).isEqualTo(Instant.parse(expectById.get(id)));
                matched++;
            }
        }
        report.forEach(l -> System.out.println("[정밀도] " + l));
        assertThat(matched).isEqualTo(cases.size());
    }
}
