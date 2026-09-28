package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * <b>외부 주문 경계 — 커밋 후 1회, 롤백 후 0회</b> (2026-09-28 신설).
 *
 * <h3>왜 별도 테스트인가</h3>
 * 주 회귀 테스트는 {@code OrderExecutionEngine} 자체를 대역으로 두므로
 * {@code submitOrderAfterCommit} <b>호출을 캡처</b>할 뿐, 그 등록 메서드가 실제로 실행되지 않는다.
 * 🔴 따라서 "커밋 후 외부 제출 1회 / 롤백 후 0회"는 <b>확인된 적이 없었다.</b>
 * 여기서는 <b>엔진을 실제로 쓰고</b> 최종 외부 경계인 {@link UpbitOrderClient} 만 대역으로 둔다.
 *
 * <h3>비동기라 기다려야 한다</h3>
 * {@code submitOrder} 는 {@code @Async} 다. 🔴 <b>즉시 0회를 검사하면 늦게 실행되는 호출을
 * 놓친다.</b> 그래서 롤백 뒤에 <b>정상 매수를 한 번 더 실행해 그 외부 호출을 기다린다</b> —
 * 그 호출이 도착하면 비동기 실행이 진행됐다는 뜻이고, 그 시점의 <b>총 호출이 1회</b>라면
 * 롤백분은 0회다. 고정 대기(sleep)보다 이쪽이 근거가 된다.
 */
class DynamicBuyExternalSubmissionBoundaryTest extends IntegrationTestBase {

    private static final BigDecimal CAPITAL = new BigDecimal("10000.00");

    @Autowired
    private DynamicTradingService dynamicTradingService;

    @Autowired
    private DynamicSessionRepository sessionRepo;

    @Autowired
    private PlatformTransactionManager txManager;

    /** 🔴 최종 외부 경계만 대역. {@code OrderExecutionEngine} 은 실제 빈을 쓴다. */
    @MockBean
    private UpbitOrderClient upbitOrderClient;

    /**
     * {@code submitOrder} 는 {@code @Async("orderExecutor")} 이고 이 실행기는 <b>코어 2 스레드</b>다.
     * 🔴 병렬이므로 "정상분 호출이 도착했다"가 "롤백분 작업도 끝났다"를 뜻하지 않는다 —
     * 이 실행기가 <b>비었음을 확인</b>한 뒤에 검사해야 한다.
     */
    @Autowired
    @Qualifier("orderExecutor")
    private ThreadPoolTaskExecutor orderExecutor;

    private Long newRealRunningSession() {
        return sessionRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(CAPITAL).availableKrw(CAPITAL).totalAssetKrw(CAPITAL)
                .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("SCANNING").tradingMode("REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build()).getId();
    }

    private List<Candle> candles() {
        List<Candle> out = new ArrayList<>();
        Instant t0 = Instant.now().minus(300, ChronoUnit.HOURS);
        for (int i = 0; i < 300; i++) {
            BigDecimal p = new BigDecimal(1000 + i);
            out.add(Candle.builder()
                    .time(t0.plus(i, ChronoUnit.HOURS))
                    .open(p).high(p.add(BigDecimal.TEN)).low(p.subtract(BigDecimal.TEN)).close(p)
                    .volume(new BigDecimal("1000"))
                    .build());
        }
        return out;
    }

    /** @param rollback true 면 매수 뒤 트랜잭션을 롤백시킨다(틱 후반 실패를 대신한다). */
    private Throwable runBuy(Long id, String coinPair, boolean rollback) {
        StrategySignal buy = StrategySignal.buy(new BigDecimal("80"), "외부 경계 시험");
        try {
            new TransactionTemplate(txManager).execute(status -> {
                DynamicSessionEntity s = sessionRepo.findById(id).orElseThrow();
                dynamicTradingService.persistWatchlist(id, "[\"" + coinPair + "\"]");
                dynamicTradingService.executeBuy(s, coinPair, candles(), buy, BigDecimal.ONE);
                if (rollback) {
                    throw new IllegalStateException("틱 후반 실패를 대신한다");
                }
                return null;
            });
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @Test
    @DisplayName("🔴 롤백분 0회 · 정상분 1회 — 비동기 실행기가 빈 것을 확인한 뒤 식별자로 구분해 검사한다")
    void rollbackSubmitsNothingAndCommitSubmitsOnce() {
        // 🔴 두 시나리오를 **다른 코인**으로 돌려 요청 식별자를 만든다.
        //    같은 코인이면 호출을 구분할 수 없어 "총 1회"라는 약한 단정밖에 못 한다.
        Long rolledBack = newRealRunningSession();
        assertThat(runBuy(rolledBack, "KRW-ETH", true))
                .as("이 시나리오는 롤백돼야 한다 — 롤백되지 않으면 0회 검사의 전제가 무너진다")
                .isNotNull();

        Long committed = newRealRunningSession();
        assertThat(runBuy(committed, "KRW-BTC", false))
                .as("정상 시나리오는 커밋돼야 한다")
                .isNull();

        // 🔴 제출된 비동기 작업이 **모두 끝났음**을 확인한다. 고정 대기를 늘리는 대신
        //    실행기가 비었는지를 본다 — 활성 0 + 큐 비어 있음이 연속으로 관측될 때까지.
        awaitOrderExecutorDrained();

        // 이제 식별자로 구분해 검사한다.
        verify(upbitOrderClient, never())
                .createOrder(eq("KRW-ETH"), anyString(), any(), any(), anyString());
        verify(upbitOrderClient, times(1))
                .createOrder(eq("KRW-BTC"), anyString(), any(), any(), anyString());

        DynamicSessionEntity rb = sessionRepo.findById(rolledBack).orElseThrow();
        assertThat(rb.getAvailableKrw())
                .as("롤백 시나리오는 보상으로 초기 잔액이 복원된다")
                .isEqualByComparingTo(CAPITAL);
    }

    /**
     * {@code orderExecutor} 가 빌 때까지 기다린다 — 활성 0 · 큐 비어 있음이 <b>연속 3회</b>
     * 관측되면 제출된 작업이 모두 끝난 것으로 본다(제출 자체가 이 테스트 안에서만 일어난다).
     * ⚠️ 이 컨텍스트에는 스케줄러도 있으므로 다른 작업이 섞일 수 있다 — 그래서 위 검사는
     * <b>코인으로 구분</b>한다.
     */
    private void awaitOrderExecutorDrained() {
        long deadline = System.currentTimeMillis() + 20_000;
        int quiet = 0;
        while (System.currentTimeMillis() < deadline) {
            boolean idle = orderExecutor.getActiveCount() == 0
                    && orderExecutor.getThreadPoolExecutor().getQueue().isEmpty();
            quiet = idle ? quiet + 1 : 0;
            if (quiet >= 3) return;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("orderExecutor 가 20초 안에 비지 않았다 — 0회 검사를 신뢰할 수 없다");
    }
}
