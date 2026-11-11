package com.cryptoautotrader.api.service;

import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.mock.mockito.MockBean;

/** S5 스냅샷 — 격리 로컬 <b>실제 PostgreSQL</b>(환경 변수 {@code V96_PG_URL} 이 있을 때만). */
@EnabledIfEnvironmentVariable(named = "V96_PG_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.+")
@org.springframework.test.context.TestPropertySource(properties = {
        "test.fixture.entry-rounds-context=PerformanceSnapshotS5PgTest",
        "spring.datasource.url=${V96_PG_URL}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.username=${V96_PG_USER:trader}",
        "spring.datasource.password=",
        "spring.datasource.hikari.connection-init-sql=SET lock_timeout = '20s'",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class PerformanceSnapshotS5PgTest extends AbstractPerformanceSnapshotS5 {
    @MockBean private UpbitWebSocketClient ws;

    @Override
    protected String reportName() {
        return "s5-pg";
    }
}
