package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.api.support.sqltrace.SqlTrace;
import com.cryptoautotrader.api.support.sqltrace.SqlTraceConfig;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>test B — 생산 경로가 위험한 순서를 실제로 만드는가</b> (2026-09-26).
 *
 * <h3>가설</h3>
 * `processScanningTick`(`@Transactional`) 안에서
 * <ol>
 *   <li>워치리스트 갱신이 <b>바깥 트랜잭션으로 {@code dynamic_session} 행을 UPDATE</b>
 *       (`:1802` / `:1844` 의 {@code dynamicSessionRepo.save(toUpdate)})</li>
 *   <li>{@code executeBuy} 가 {@code position} INSERT (`:1494` 부근)</li>
 *   <li>{@code balanceUpdater.apply}(REQUIRES_NEW, <b>별도 연결</b>)가 <b>같은 행</b>을 UPDATE (`:1513`)</li>
 * </ol>
 * → 1 의 UPDATE 가 <b>실행 완료돼 잠금을 쥔 상태</b>라면 3 이 그 잠금을 기다린다.
 *
 * <h3>이 테스트의 경계 — 무엇이 생산 코드이고 무엇이 골격인가</h3>
 * <table>
 *   <tr><th>구간</th><th>무엇을 쓰는가</th></tr>
 *   <tr><td>바깥 세션 행 UPDATE</td>
 *       <td>테스트가 {@code dynamicSessionRepo.save(...)} 를 부른다 —
 *           {@code refreshWatchlist} 가 내는 <b>같은 문장</b>이며
 *           🔴 <b>운영에 없는 {@code flush()} 는 넣지 않는다.</b></td></tr>
 *   <tr><td>포지션·주문·차감·보상</td>
 *       <td><b>생산 {@code executeBuy} 그대로</b> — 트랜잭션 경계와 REQUIRES_NEW 도 실제 구성</td></tr>
 *   <tr><td>대역</td><td>{@code OrderExecutionEngine}(거래소 주문 실행)만</td></tr>
 * </table>
 *
 * <p>🔴 따라서 B 가 순서를 관측해도 그것은 <b>"현재 생산 경로에서 그 순서가 만들어질 수 있다"</b>
 * 까지다. 바깥 UPDATE 를 실제로 발행하는 것이 {@code refreshWatchlist} 라는 점은 코드로만
 * 확인했고, 2026-09-26 사고 당시의 연결·스레드 기록은 없으므로 <b>과거 사고의 원인이 같았다고
 * 확정하지 않는다.</b>
 *
 * <p>⚠️ 관측 장치의 비개입성은 {@code SqlTraceSelfTest} 가 시험한 경로에 한한 근거다 —
 * 모든 상황을 보증하지 않는다.
 */
@Import(SqlTraceConfig.class)
class DynamicBuyOuterWriteOrderingTest extends IntegrationTestBase {

    @Autowired
    private DynamicTradingService dynamicTradingService;

    @Autowired
    private DynamicSessionRepository sessionRepo;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    /** 🔴 외부 주문 실행만 대역으로 바꾼다. 내부 서비스·트랜잭션 프록시는 실제 구성을 유지한다. */
    @MockBean
    private OrderExecutionEngine orderExecutionEngine;

    @AfterEach
    void off() {
        SqlTrace.stop();
    }

    private Long newRealRunningSession() {
        return sessionRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("10000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
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

    @Test
    @DisplayName("🔴 바깥 세션 UPDATE 가 안쪽 REQUIRES_NEW 진입 전에 실행되는가 — 생산 경로 관측")
    void outerSessionUpdateOrderingAroundRequiresNew() {
        Long id = newRealRunningSession();
        StrategySignal buy = StrategySignal.buy(new BigDecimal("80"), "test B");

        SqlTrace.start();
        String[] failure = new String[]{null};
        new TransactionTemplate(txManager).execute(status -> {
            DynamicSessionEntity s = sessionRepo.findById(id).orElseThrow();
            // ── 바깥 트랜잭션의 세션 행 UPDATE — refreshWatchlist 와 같은 문장 ──
            //    🔴 flush 를 넣지 않는다. 실제로 언제 실행되는지가 이 시험의 대상이다.
            s.setWatchlistJson("[\"KRW-BTC\"]");
            s.setWatchlistRefreshedAt(Instant.now());
            sessionRepo.save(s);

            try {
                dynamicTradingService.executeBuy(s, "KRW-BTC", candles(), buy, BigDecimal.ONE);
            } catch (Throwable t) {
                StringBuilder chain = new StringBuilder();
                for (Throwable c = t; c != null && chain.length() < 900; c = c.getCause()) {
                    chain.append(c.getClass().getSimpleName()).append(": ")
                         .append(String.valueOf(c.getMessage()).replace('\n', ' ')).append(" <- ");
                    if (c.getCause() == c) break;
                }
                failure[0] = chain.toString();
            }
            return null;
        });
        SqlTrace.stop();

        List<SqlTrace.Row> rows = SqlTrace.rows();

        // ① 같은 세션 행의 SQL 실행 순서 + 바깥·안쪽 연결 구분
        OptionalInt outerUpdateEnd = firstIndex(rows,
                r -> r.isUpdateOf("dynamic_session") && r.phase() == SqlTrace.Phase.END);
        OptionalInt positionInsertEnd = firstIndex(rows,
                r -> r.isInsertInto("position") && r.phase() == SqlTrace.Phase.END);
        int outerConn = rows.isEmpty() ? -1 : rows.get(0).connTag();
        OptionalInt firstOtherConnStart = firstIndex(rows,
                r -> r.phase() == SqlTrace.Phase.START && r.connTag() != outerConn);

        System.out.println("=== test B 관측 ===");
        System.out.println("바깥 연결 tag=" + outerConn
                + " / 바깥 dynamic_session UPDATE END idx=" + outerUpdateEnd
                + " / position INSERT END idx=" + positionInsertEnd
                + " / 다른 연결 첫 START idx=" + firstOtherConnStart);
        System.out.println("예외 사슬=" + failure[0]);
        System.out.println(SqlTrace.dump());

        // ③ 종료 후 실제 상태 — 예외 유무보다 이쪽이 중요하다
        DynamicSessionEntity after = sessionRepo.findById(id).orElseThrow();
        long positions = positionRepository.count();
        long orders = orderRepository.count();
        System.out.println("최종 availableKrw=" + after.getAvailableKrw()
                + " / scanState=" + after.getScanState()
                + " / currentPositionId=" + after.getCurrentPositionId()
                + " / watchlistJson=" + after.getWatchlistJson()
                + " / position=" + positions + " / order=" + orders);

        // 🔴 이 단계의 목적은 가설 지지 여부 **관측**이다. 순서가 관측되지 않아도 그 결과를
        //    그대로 남긴다 — 재현을 맞추려고 flush 를 넣지 않는다.
        assertThat(rows)
                .as("기록이 비면 관측 자체가 실패한 것이다")
                .isNotEmpty();
    }

    private static OptionalInt firstIndex(List<SqlTrace.Row> rows,
                                          java.util.function.Predicate<SqlTrace.Row> p) {
        return IntStream.range(0, rows.size()).filter(i -> p.test(rows.get(i))).findFirst();
    }
}
