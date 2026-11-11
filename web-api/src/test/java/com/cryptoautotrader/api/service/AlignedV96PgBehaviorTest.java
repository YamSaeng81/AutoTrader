package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.CryptoAutoTraderApplication;
import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.paper.PaperOrderEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.paper.PaperOrderRepository;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import com.cryptoautotrader.api.support.TestRedisConfig;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 총괄 v1.150 §4 — V96 두 열을 <b>실제 PostgreSQL</b>(Flyway V1~V94 + V96)에서 확인 (2026-11-11). 격리 로컬 DB 전용, 환경 변수
 * {@code V96_PG_URL} 이 있을 때만 돈다. 같은 PG DB 로 <b>2번 Spring 컨텍스트를 새로 기동</b>해 복원을 본다(같은 JVM — OS 프로세스 재시작 아님).
 */
@EnabledIfEnvironmentVariable(named = "V96_PG_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.+")
@DisplayName("V96 실제 PG — 조건부 피크 갱신·동시 갱신·정밀도·호출자 롤백·엔티티 저장 불간섭·재기동 복원과 첫 유효 시세 정산 1회")
@org.springframework.test.context.TestPropertySource(properties = {
        "test.fixture.entry-rounds-context=AlignedV96PgBehaviorTest",
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
class AlignedV96PgBehaviorTest extends AbstractSma20SameInputFixture {

    @MockBean private UpbitWebSocketClient ws;

    @Override
    protected Map<String, Object> paperParams() {
        return Map.of(PaperMaxInvestment.KEY, 20000, PaperExecutionMode.KEY, PaperExecutionMode.LIVE_ALIGNED);
    }

    @Override
    protected String reportName() {
        return "v96-pg";
    }

    private PaperTradingService target(PaperTradingService s) {
        return AopTestUtils.getUltimateTargetObject(s);
    }

    private long version(Long id) {
        return jdbc.queryForObject("SELECT version FROM paper_trading.virtual_balance WHERE id = ?", Long.class, id);
    }

    @Test
    @DisplayName("조건부 피크 — NULL→올림, 낮은 값·같은 값은 0행, 높은 값만 1행, version 불변, 엔티티 저장은 열을 바꾸지 않음")
    void conditionalRaiseOnPg() {
        VirtualBalanceEntity s = paper(BTC);
        long v0 = version(s.getId());
        BigDecimal start = balanceRepo.findMddPeakKrw(s.getId());
        int r1 = balanceRepo.raiseMddPeakKrw(s.getId(), new BigDecimal("30000.00"));
        int r2 = balanceRepo.raiseMddPeakKrw(s.getId(), new BigDecimal("25000.00"));
        int r3 = balanceRepo.raiseMddPeakKrw(s.getId(), new BigDecimal("30000.00"));
        int r4 = balanceRepo.raiseMddPeakKrw(s.getId(), new BigDecimal("30000.01"));
        long v1 = version(s.getId());

        VirtualBalanceEntity e = balanceRepo.findById(s.getId()).orElseThrow();
        e.setMddPeakKrw(new BigDecimal("1.00"));     // 묵은/낮은 값 — 엔티티 저장으로는 쓰이지 않아야 한다
        e.setTotalKrw(new BigDecimal("20100.00"));
        balanceRepo.save(e);
        BigDecimal afterSave = balanceRepo.findMddPeakKrw(s.getId());
        String type = jdbc.queryForObject("SELECT pg_typeof(mdd_peak_krw)::text || ' scale=' || scale(mdd_peak_krw) "
                + "FROM paper_trading.virtual_balance WHERE id = ?", String.class, s.getId());
        System.out.println("[V96 PG 피크] 시작=" + start + " 행수=" + List.of(r1, r2, r3, r4) + " 결과=" + afterSave + " (" + type + ")"
                + " version 전/후 갱신=" + v0 + "/" + v1 + " 엔티티 저장 뒤 version=" + version(s.getId()));

        assertThat(start).isNull();
        assertThat(List.of(r1, r2, r3, r4)).containsExactly(1, 0, 0, 1);
        assertThat(v1).isEqualTo(v0);
        assertThat(afterSave).isEqualByComparingTo("30000.01");
        assertThat(type).isEqualTo("numeric scale=2");
    }

    @Test
    @DisplayName("동시 갱신 — 16개 스레드가 서로 다른 값을 동시에 올려도 결과는 최댓값, 늦은 낮은 값이 내리지 못함")
    void concurrentRaisesKeepMaximum() throws Exception {
        VirtualBalanceEntity s = paper(ETH);
        List<BigDecimal> values = new ArrayList<>();
        for (int i = 0; i < 16; i++) values.add(new BigDecimal(40000 + i * 7 % 16 * 100).setScale(2));
        Collections.shuffle(values, new java.util.Random(150));
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch ready = new CountDownLatch(16);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (BigDecimal v : values) {
                fs.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return balanceRepo.raiseMddPeakKrw(s.getId(), v);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            int updated = 0;
            for (Future<Integer> f : fs) updated += f.get(60, TimeUnit.SECONDS);
            int late = balanceRepo.raiseMddPeakKrw(s.getId(), new BigDecimal("40000.00"));
            BigDecimal stored = balanceRepo.findMddPeakKrw(s.getId());
            BigDecimal max = values.stream().max(BigDecimal::compareTo).orElseThrow();
            System.out.println("[V96 PG 동시] 값=" + values + " 갱신 행 합=" + updated + " 늦은 낮은 값 행=" + late + " 저장=" + stored + " 최댓값=" + max);
            assertThat(stored).isEqualByComparingTo(max);
            assertThat(updated).isBetween(1, 16);
            assertThat(late).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("호출자 롤백 — 별도 트랜잭션(REQUIRES_NEW)으로 올린 피크는 바깥 롤백 뒤에도 남음, 바깥 트랜잭션이 행을 읽기만 했을 때 대기 없음")
    void raiseSurvivesCallerRollback() {
        VirtualBalanceEntity s = paper(BTC);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(st -> {
            balanceRepo.findById(s.getId()).orElseThrow();   // 바깥은 읽기만(승인 경로와 같은 순서: 세션 읽기 → 관문)
            balanceRepo.raiseMddPeakKrw(s.getId(), new BigDecimal("50000.00"));
            throw new IllegalStateException("주입 — 호출자 롤백");
        })).hasMessage("주입 — 호출자 롤백");
        BigDecimal stored = balanceRepo.findMddPeakKrw(s.getId());
        System.out.println("[V96 PG 롤백] 바깥 롤백 뒤 저장 피크=" + stored);
        assertThat(stored).isEqualByComparingTo("50000.00");
    }

    @Test
    @DisplayName("판단 시각 정밀도 — CLOSING 전환 시각은 마이크로초로 저장·엔티티 값과 같음, 사유·상태 같은 커밋")
    void exitDecidedAtPrecisionOnPg() {
        VirtualBalanceEntity q = paper(ETH);
        PaperPositionEntity qp = paperPos(q, new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"), Instant.now().minus(2, ChronoUnit.HOURS));
        markPriceBook.remove(ETH);
        paperTradingService.stop(q.getId());
        PaperPositionEntity p = paperPositionRepo.findById(qp.getId()).orElseThrow();
        Map<String, Object> row = jdbc.queryForMap("SELECT status, exit_reason, exit_decided_at, "
                + "extract(microseconds FROM exit_decided_at)::bigint % 1000000 AS micros FROM paper_trading.position WHERE id = ?", qp.getId());
        System.out.println("[V96 PG 시각] 엔티티=" + p.getExitDecidedAt() + " DB=" + row);
        assertThat(row.get("status")).isEqualTo("CLOSING");
        assertThat(row.get("exit_reason")).isEqualTo("FORCED_STOP");
        assertThat(p.getExitDecidedAt()).isEqualTo(p.getExitDecidedAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(((java.sql.Timestamp) row.get("exit_decided_at")).toInstant()).isEqualTo(p.getExitDecidedAt());
    }

    @Test
    @DisplayName("재기동 — 같은 PG 로 2번 컨텍스트 기동: 대기 복원·사유/시각 보존·피크 30,000→25,000 발동 유지·첫 유효 시세 정산 1회·두 번째 0회")
    void runtimeStateSurvivesSecondContextOnPg() {
        VirtualBalanceEntity p = paper(BTC);
        p.setTotalKrw(new BigDecimal("30000"));
        p.setAvailableKrw(new BigDecimal("30000"));
        p = balanceRepo.save(p);
        CircuitBreakerResult warm = ReflectionTestUtils.invokeMethod(target(paperTradingService), "alignedCircuitBreaker", p);
        p.setTotalKrw(new BigDecimal("25000"));
        p.setAvailableKrw(new BigDecimal("25000"));
        p = balanceRepo.save(p);
        CircuitBreakerResult beforeRestart = ReflectionTestUtils.invokeMethod(target(paperTradingService), "alignedCircuitBreaker", p);

        VirtualBalanceEntity q = paper(ETH);
        PaperPositionEntity qp = paperPos(q, new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"),
                Instant.now().minus(2, ChronoUnit.HOURS));
        markPriceBook.remove(ETH);
        paperTradingService.stop(q.getId());
        PaperPositionEntity pending = paperPositionRepo.findById(qp.getId()).orElseThrow();
        Instant decided = pending.getExitDecidedAt();
        assertThat(pending.getStatus()).isEqualTo("CLOSING");
        assertThat(decided).isNotNull();

        String url = System.getenv("V96_PG_URL");
        ConfigurableApplicationContext second = new SpringApplicationBuilder(CryptoAutoTraderApplication.class, TestRedisConfig.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                // 🔴 명령행 인자로 넘긴다 — builder.properties() 는 기본 속성이라 test 프로필 yml(H2)에 밀린다(1회차: 2번이 빈 H2 에 붙어 실패).
                .run("--spring.main.allow-bean-definition-overriding=true",
                        "--test.fixture.entry-rounds-context=AlignedV96PgBehaviorTest-second",
                        "--spring.data.redis.repositories.enabled=false",
                        "--spring.datasource.url=" + url,
                        "--spring.datasource.driver-class-name=org.postgresql.Driver",
                        "--spring.datasource.username=" + System.getenv().getOrDefault("V96_PG_USER", "trader"),
                        "--spring.datasource.password=",
                        "--spring.datasource.hikari.connection-init-sql=SET lock_timeout = '20s'",
                        "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
                        "--spring.sql.init.mode=never",
                        "--spring.flyway.enabled=true");
            String secondUrl = second.getEnvironment().getProperty("spring.datasource.url");
            System.out.println("[V96 PG 재기동] 2번 컨텍스트 datasource=" + secondUrl);
            assertThat(secondUrl).isEqualTo(url);
        try {
            PaperTradingService svc2 = target(second.getBean(PaperTradingService.class));
            assertThat(svc2).isNotSameAs(target(paperTradingService));
            @SuppressWarnings("unchecked")
            Map<String, Set<Long>> restored = (Map<String, Set<Long>>) ReflectionTestUtils.getField(svc2, "alignedClosingByCoin");
            @SuppressWarnings("unchecked")
            Map<Long, BigDecimal> peakMem = (Map<Long, BigDecimal>) ReflectionTestUtils.getField(svc2, "alignedPeakTotalKrw");
            boolean peakMemEmptyAtStart = !peakMem.containsKey(p.getId());
            Set<Long> restoredAtBoot = new java.util.HashSet<>(restored.getOrDefault(ETH, Set.of()));

            VirtualBalanceRepository repo2 = second.getBean(VirtualBalanceRepository.class);
            CircuitBreakerResult afterRestart = ReflectionTestUtils.invokeMethod(svc2, "alignedCircuitBreaker",
                    repo2.findById(p.getId()).orElseThrow());

            MarkPriceBook book2 = second.getBean(MarkPriceBook.class);
            book2.record(ETH, new BigDecimal("101"), Instant.now());
            svc2.doOnAlignedRealtimePrice(new RealtimePriceEvent(ETH, new BigDecimal("101"), Instant.now(), RealtimePriceEvent.WS_TRADE));
            PaperOrderRepository ord2 = second.getBean(PaperOrderRepository.class);
            int sellsAfterFirst = sells(ord2, q.getId()).size();
            svc2.doOnAlignedRealtimePrice(new RealtimePriceEvent(ETH, new BigDecimal("102"), Instant.now(), RealtimePriceEvent.WS_TRADE));

            PaperPositionRepository pos2 = second.getBean(PaperPositionRepository.class);
            PaperPositionEntity settled = pos2.findById(qp.getId()).orElseThrow();
            List<PaperOrderEntity> sells = sells(ord2, q.getId());

            System.out.println("[V96 PG 재기동] 1번 발동(전/후)=" + warm.isTriggered() + "/" + beforeRestart.isTriggered()
                    + " 2번 메모리 피크 비어 시작=" + peakMemEmptyAtStart + " 2번 발동=" + afterRestart.isTriggered()
                    + " 저장 피크=" + repo2.findMddPeakKrw(p.getId()) + " | 기동 직후 복원=" + restoredAtBoot
                    + " 정산=" + settled.getStatus() + "/" + settled.getExitReason() + " 매도(첫 시세 뒤/두 번째 뒤)=" + sellsAfterFirst + "/" + sells.size()
                    + " 판단 시각 저장=" + decided + " 정산 뒤=" + settled.getExitDecidedAt());

            assertThat(warm.isTriggered()).isFalse();
            assertThat(beforeRestart.isTriggered()).isTrue();
            assertThat(peakMemEmptyAtStart).isTrue();
            assertThat(afterRestart.isTriggered()).isTrue();
            assertThat(repo2.findMddPeakKrw(p.getId())).isEqualByComparingTo("30000");
            assertThat(restoredAtBoot).contains(qp.getId());
            assertThat(settled.getStatus()).isEqualTo("CLOSED");
            assertThat(settled.getExitReason()).isEqualTo(ExitReason.FORCED_STOP);
            assertThat(settled.getExitDecidedAt()).isEqualTo(decided);
            assertThat(sellsAfterFirst).isEqualTo(1);
            assertThat(sells).hasSize(1);
            assertThat(sells.get(0).getSignalReason()).contains("청산 판단 " + decided);
        } finally {
            second.close();
        }
    }

    private static List<PaperOrderEntity> sells(PaperOrderRepository repo, Long sid) {
        return repo.findBySessionIdOrderByCreatedAtDesc(sid, org.springframework.data.domain.Pageable.unpaged())
                .getContent().stream().filter(x -> "SELL".equals(x.getSide())).toList();
    }
}
