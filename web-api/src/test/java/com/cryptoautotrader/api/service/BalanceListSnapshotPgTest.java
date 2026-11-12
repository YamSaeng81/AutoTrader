package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 총괄 v1.153 I — 잔고({@code getSessionValuation})·목록({@code listSessionValuations})이 세션 금액과 청산 대기 수를 <b>한 스냅샷</b>에서
 * 만드는지, 격리 로컬 <b>실제 PostgreSQL</b> 에서 S5 와 같은 경합 한 번으로 본다 (2026-11-12).
 * 조회가 세션을 읽은 뒤 대기 수를 읽기 직전에 다른 스레드가 실제 제품 정산 경로로 청산 대기를 정산·커밋하고(Future.get 완료 대기), 조회를 잇는다.
 * 기대: 응답은 정산 전 스냅샷 하나(대기 1·정산 전 금액)이고 정산 뒤 값과 섞이지 않는다. 커밋 뒤 새 조회는 정산 뒤 하나(대기 0·DB 금액).
 * H2 의 REPEATABLE READ 는 스냅샷이 아니어서 H2 판은 두지 않는다(S5 와 같은 이유). sleep 없음.
 */
@EnabledIfEnvironmentVariable(named = "V96_PG_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.+")
@org.springframework.test.context.TestPropertySource(properties = {
        "test.fixture.entry-rounds-context=BalanceListSnapshotPgTest",
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
@DisplayName("I 잔고·목록 RR — 실제 PG 경합: 조회 도중 정산이 커밋돼도 응답은 정산 전 스냅샷 하나, 커밋 뒤 조회는 정산 뒤 하나")
class BalanceListSnapshotPgTest extends AbstractSma20SameInputFixture {

    @MockBean private UpbitWebSocketClient ws;

    @Override
    protected Map<String, Object> paperParams() {
        return Map.of(PaperMaxInvestment.KEY, 20000, PaperExecutionMode.KEY, PaperExecutionMode.LIVE_ALIGNED);
    }

    @Override
    protected String reportName() {
        return "balance-list-rr-pg";
    }

    private record Prep(VirtualBalanceEntity session, PaperPositionEntity pos, BigDecimal before) { }

    /** 청산 대기 1건이 있는 정지된 선택 모드 세션. */
    private Prep pending() {
        VirtualBalanceEntity session = paper(BTC);
        PaperPositionEntity pos = paperPos(session, new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"),
                Instant.now().minus(2, ChronoUnit.HOURS));
        markPriceBook.remove(BTC);
        paperTradingService.stop(session.getId());
        assertThat(paperTradingService.pendingCloseCount(session.getId())).isEqualTo(1);
        return new Prep(session, pos, balanceRepo.findById(session.getId()).orElseThrow().getTotalKrw());
    }

    /** 다른 스레드에서 실제 제품 정산 경로(첫 유효 시세)로 정산·커밋하고 끝날 때까지 기다린다. */
    private void settleElsewhere(PaperTradingService target) throws Exception {
        CompletableFuture.runAsync(() -> target.doOnAlignedRealtimePrice(
                new RealtimePriceEvent(BTC, new BigDecimal("101"), Instant.now(), RealtimePriceEvent.WS_TRADE)))
                .get(20, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("잔고 — 세션을 읽은 뒤·대기 수 읽기 직전 정산 커밋")
    void balanceSnapshot() throws Exception {
        Prep p = pending();
        PaperTradingService target = AopTestUtils.getUltimateTargetObject(paperTradingService);
        PaperPositionRepository original = (PaperPositionRepository) ReflectionTestUtils.getField(target, "positionRepo");
        AtomicBoolean injected = new AtomicBoolean(false);
        PaperPositionRepository decorated = mock(PaperPositionRepository.class, org.mockito.AdditionalAnswers.delegatesTo(original));
        doAnswer(inv -> {
            Long sid = inv.getArgument(0);
            if (sid.equals(p.session().getId()) && injected.compareAndSet(false, true)) settleElsewhere(target);
            return original.findBySessionIdAndStatus(sid, inv.getArgument(1));
        }).when(decorated).findBySessionIdAndStatus(anyLong(), eq("CLOSING"));
        ReflectionTestUtils.setField(target, "positionRepo", decorated);
        PaperTradingService.SessionValuation v;
        try {
            v = paperTradingService.getSessionValuation(p.session().getId());
        } finally {
            ReflectionTestUtils.setField(target, "positionRepo", original);
        }
        VirtualBalanceEntity db = balanceRepo.findById(p.session().getId()).orElseThrow();
        String stored = original.findById(p.pos().getId()).orElseThrow().getStatus();
        System.out.println("[RR-balance] 응답 total=" + v.session().getTotalKrw() + " pending=" + v.pendingCloseCount()
                + " | 정산 전 " + p.before() + " | DB 지금 total=" + db.getTotalKrw() + " position=" + stored);
        assertThat(injected.get()).isTrue();
        assertThat(stored).as("조회 도중 정산이 커밋됐다").isEqualTo("CLOSED");
        assertThat(db.getTotalKrw()).isNotEqualByComparingTo(p.before());
        assertThat(v.pendingCloseCount()).as("정산 전 스냅샷의 대기 수").isEqualTo(1);
        assertThat(v.session().getTotalKrw()).as("정산 전 스냅샷의 금액").isEqualByComparingTo(p.before());

        PaperTradingService.SessionValuation after = paperTradingService.getSessionValuation(p.session().getId());
        assertThat(after.pendingCloseCount()).isZero();
        assertThat(after.session().getTotalKrw()).isEqualByComparingTo(db.getTotalKrw());
    }

    @Test
    @DisplayName("목록 — 세션 목록을 읽은 뒤·세션별 대기 수 묶음 질의 직전 정산 커밋")
    void listSnapshot() throws Exception {
        Prep p = pending();
        PaperTradingService target = AopTestUtils.getUltimateTargetObject(paperTradingService);
        PaperPositionRepository original = (PaperPositionRepository) ReflectionTestUtils.getField(target, "positionRepo");
        AtomicBoolean injected = new AtomicBoolean(false);
        PaperPositionRepository decorated = mock(PaperPositionRepository.class, org.mockito.AdditionalAnswers.delegatesTo(original));
        doAnswer(inv -> {
            if (injected.compareAndSet(false, true)) settleElsewhere(target);
            return original.countClosingPerSession();
        }).when(decorated).countClosingPerSession();
        ReflectionTestUtils.setField(target, "positionRepo", decorated);
        List<PaperTradingService.SessionValuation> list;
        try {
            list = paperTradingService.listSessionValuations();
        } finally {
            ReflectionTestUtils.setField(target, "positionRepo", original);
        }
        PaperTradingService.SessionValuation v = list.stream().filter(x -> x.session().getId().equals(p.session().getId())).findFirst().orElseThrow();
        VirtualBalanceEntity db = balanceRepo.findById(p.session().getId()).orElseThrow();
        String stored = original.findById(p.pos().getId()).orElseThrow().getStatus();
        System.out.println("[RR-list] 응답 total=" + v.session().getTotalKrw() + " pending=" + v.pendingCloseCount()
                + " | 정산 전 " + p.before() + " | DB 지금 total=" + db.getTotalKrw() + " position=" + stored);
        assertThat(injected.get()).isTrue();
        assertThat(stored).isEqualTo("CLOSED");
        assertThat(db.getTotalKrw()).isNotEqualByComparingTo(p.before());
        assertThat(v.pendingCloseCount()).isEqualTo(1);
        assertThat(v.session().getTotalKrw()).isEqualByComparingTo(p.before());

        PaperTradingService.SessionValuation after = paperTradingService.listSessionValuations().stream()
                .filter(x -> x.session().getId().equals(p.session().getId())).findFirst().orElseThrow();
        assertThat(after.pendingCloseCount()).isZero();
        assertThat(after.session().getTotalKrw()).isEqualByComparingTo(db.getTotalKrw());
    }
}
