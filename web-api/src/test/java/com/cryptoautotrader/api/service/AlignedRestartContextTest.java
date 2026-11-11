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
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.148 §3-3 — <b>실제 별도 컨텍스트 기동</b>에서 런타임 상태(청산 사유·판단 시각·MDD 피크)가 보존되는지 (2026-11-11).
 *
 * <p>1번 컨텍스트(시험 fixture)가 상태를 만들고, 같은 H2 메모리 DB 를 쓰는 <b>2번 Spring 컨텍스트를 새로 기동</b>한다. 2번의
 * {@code ApplicationReadyEvent} 는 실제 기동 흐름에서 발생한다(복원 메서드를 시험이 부르지 않는다). 2번의 서비스 인스턴스는 메모리 지도가
 * 비어 있는 새 객체다. 한계: 같은 JVM·같은 H2 메모리 DB 이며 프로세스 재시작·PostgreSQL 이 아니다. 스케줄러는 시험 프로필에서 꺼져 있다.</p>
 */
@DisplayName("LIVE_ALIGNED v1.148 §3-3 — 별도 컨텍스트 기동 뒤 청산 대기·판단 시각·MDD 피크 보존")
@org.springframework.test.context.TestPropertySource(properties = "test.fixture.entry-rounds-context=AlignedRestartContextTest")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AlignedRestartContextTest extends AbstractSma20SameInputFixture {

    @MockBean private UpbitWebSocketClient ws;

    @Override
    protected Map<String, Object> paperParams() {
        return Map.of(PaperMaxInvestment.KEY, 20000, PaperExecutionMode.KEY, PaperExecutionMode.LIVE_ALIGNED);
    }

    @Override
    protected String reportName() {
        return "v148-restart";
    }

    private PaperTradingService target(PaperTradingService s) {
        return AopTestUtils.getUltimateTargetObject(s);
    }

    @Test
    @DisplayName("재기동 전후 — 피크 30,000→현재 25,000 발동 유지, 대기 포지션 복원·첫 유효 시세에 한 번 정산(판단 시각은 저장값)")
    void runtimeStateSurvivesSecondContext() {
        // ── 1번 컨텍스트: 상태 만들기 ──
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

        // ── 2번 컨텍스트 기동(같은 DB) ──
        ConfigurableApplicationContext second = new SpringApplicationBuilder(CryptoAutoTraderApplication.class, TestRedisConfig.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .properties("spring.main.allow-bean-definition-overriding=true",
                        "test.fixture.entry-rounds-context=AlignedRestartContextTest-second",
                        "spring.data.redis.repositories.enabled=false")
                .run();
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

            // 첫 유효 시세 — 2번 컨텍스트의 시세 장부·실시간 처리기
            MarkPriceBook book2 = second.getBean(MarkPriceBook.class);
            book2.record(ETH, new BigDecimal("101"), Instant.now());
            svc2.doOnAlignedRealtimePrice(new RealtimePriceEvent(ETH, new BigDecimal("101"), Instant.now(), RealtimePriceEvent.WS_TRADE));
            svc2.doOnAlignedRealtimePrice(new RealtimePriceEvent(ETH, new BigDecimal("102"), Instant.now(), RealtimePriceEvent.WS_TRADE));

            PaperPositionRepository pos2 = second.getBean(PaperPositionRepository.class);
            PaperOrderRepository ord2 = second.getBean(PaperOrderRepository.class);
            PaperPositionEntity settled = pos2.findById(qp.getId()).orElseThrow();
            List<PaperOrderEntity> sells = ord2.findBySessionIdOrderByCreatedAtDesc(q.getId(), org.springframework.data.domain.Pageable.unpaged())
                    .getContent().stream().filter(x -> "SELL".equals(x.getSide())).toList();

            System.out.println("[재기동] 1번 발동(피크 전/후)=" + warm.isTriggered() + "/" + beforeRestart.isTriggered()
                    + " 2번 메모리 피크 비어 시작=" + peakMemEmptyAtStart + " 2번 발동=" + afterRestart.isTriggered()
                    + " 저장 피크=" + repo2.findMddPeakKrw(p.getId())
                    + " | 기동 직후 복원=" + restoredAtBoot + " 정산 뒤 지도=" + restored + " 정산=" + settled.getStatus() + "/" + settled.getExitReason() + " 매도=" + sells.size()
                    + " 판단 시각 저장=" + decided + " 사유=" + (sells.isEmpty() ? "" : sells.get(0).getSignalReason()));

            assertThat(warm.isTriggered()).isFalse();
            assertThat(beforeRestart.isTriggered()).isTrue();
            assertThat(peakMemEmptyAtStart).isTrue();
            assertThat(afterRestart.isTriggered()).isTrue();
            assertThat(repo2.findMddPeakKrw(p.getId())).isEqualByComparingTo("30000");
            assertThat(restoredAtBoot).contains(qp.getId());   // 실제 기동의 ApplicationReadyEvent 가 복원
            assertThat(restored.getOrDefault(ETH, Set.of())).doesNotContain(qp.getId());   // 정산 뒤 지도에서 빠짐
            assertThat(settled.getStatus()).isEqualTo("CLOSED");
            assertThat(settled.getExitReason()).isEqualTo(ExitReason.FORCED_STOP);
            assertThat(settled.getExitDecidedAt()).isEqualTo(decided);
            assertThat(sells).hasSize(1);
            assertThat(sells.get(0).getSignalReason()).contains("청산 판단 " + decided);
        } finally {
            second.close();
        }
    }
}
