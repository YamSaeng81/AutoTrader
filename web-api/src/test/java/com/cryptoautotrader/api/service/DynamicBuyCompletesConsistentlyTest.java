package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.api.support.sqltrace.SqlTrace;
import com.cryptoautotrader.api.support.sqltrace.SqlTraceConfig;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * <b>주 회귀 기준 — 정상 매수가 완료되고 최종 상태가 일관된가</b> (2026-09-28 신설).
 *
 * <h3>왜 이 테스트가 기준인가</h3>
 * `DynamicBuyOuterWriteOrderingTest`(test B)는 <b>SQL 실행 순서를 보는 진단</b>이다.
 * 그 관측에서 드러난 결함은 잠금 대기가 아니라 이것이었다.
 * <pre>
 *   안쪽 REQUIRES_NEW 차감 성공(커밋)
 *     → 바깥 커밋에서 @Version 충돌(where version=0 이 0행)
 *     → 매수 기록(포지션·주문·워치리스트) 전부 롤백
 *     → 보상이 차감을 되돌림
 * </pre>
 * 보상이 <b>잔액 불일치는 막았다.</b> 그러나 <b>정상 매수가 완료된 것은 아니다</b> —
 * 진입 신호가 있었는데 포지션이 남지 않는다. 그래서 감시만 남기지 않고 고친다.
 *
 * <h3>고정하는 것은 구현이 아니라 동작이다</h3>
 * <ol>
 *   <li>정상 매수에서 <b>워치리스트·잔액·포지션·세션 상태가 일관되게</b> 저장된다.</li>
 *   <li>잔액은 <b>한 번만</b> 차감되고, 정상 경로에서는 <b>보상이 필요하지 않다</b>.</li>
 *   <li>실패 경로에서는 기존 <b>보상과 중복 방지</b>가 유지된다.</li>
 * </ol>
 *
 * <h3>🔴 이 테스트가 말하지 않는 것</h3>
 * <ul>
 *   <li>운영에서 관측한 <b>장기 잠금 사고의 원인</b>은 이것으로 해결되지 않는다.
 *       그 추적은 별도로 남는다 — 잠금 대기 가설은 test B 에서 <b>지지되지 않았다</b>.</li>
 *   <li>PostgreSQL 의 시간 제한·자동회복 검증도 별도로 남는다(H2 로는 확인되지 않는다).</li>
 *   <li>REAL 주문은 <b>외부 대역</b>이다. 따라서 DB {@code order=0} 이
 *       "거래소 주문·체결도 없었다"는 뜻이 <b>아니다</b> — 대역 호출 여부를 함께 확인한다.
 *       실제 {@code OrderExecutionEngine.submitOrderAfterCommit} 은 {@code afterCommit}
 *       동기화를 등록하므로(`:145-152`) 롤백 시 제출되지 않지만, <b>트랜잭션이 없으면 즉시
 *       제출</b>한다 — 그 분기는 이 테스트의 범위 밖이다.</li>
 * </ul>
 */
@Import(SqlTraceConfig.class)
class DynamicBuyCompletesConsistentlyTest extends IntegrationTestBase {

    private static final BigDecimal CAPITAL = new BigDecimal("10000.00");

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

    /** 🔴 외부 주문 실행만 대역. 내부 서비스·트랜잭션 프록시는 실제 구성을 유지한다. */
    @MockBean
    private OrderExecutionEngine orderExecutionEngine;

    /**
     * 🔴 보상 <b>진입</b>을 계측하기 위한 스파이 — 대역이 아니라 실제 동작을 그대로 수행한다.
     * 보상은 {@code afterCompletion(ROLLED_BACK)} 에서 {@code balanceUpdater.apply} 를 부르므로,
     * 그 호출을 세면 <b>UPDATE 를 내기 전에 종료·실패한 경우도</b> 잡힌다.
     */
    @SpyBean
    private DynamicSessionBalanceUpdater balanceUpdaterSpy;

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

    @Test
    @DisplayName("🔴 정상 매수는 완료되고 워치리스트·잔액·포지션·세션 상태가 일관되게 저장된다")
    void normalBuyCompletesAndStaysConsistent() {
        Long id = newRealRunningSession();
        StrategySignal buy = StrategySignal.buy(new BigDecimal("80"), "정상 매수 회귀");

        // 워치리스트 갱신과 매수가 **같은 틱**에서 일어나는 경우 — 생산에서 60분마다 성립한다.
        SqlTrace.start();
        Throwable commitFailure = null;
        try {
            new TransactionTemplate(txManager).execute(status -> {
                DynamicSessionEntity s = sessionRepo.findById(id).orElseThrow();
                // 🔴 생산 경로를 그대로 부른다 — persistWatchlist 가 워치리스트 기록을
                //    담당한다(2026-09-28 수정). 테스트가 대신 쓰지 않는다.
                dynamicTradingService.persistWatchlist(id, "[\"KRW-BTC\"]");
                dynamicTradingService.executeBuy(s, "KRW-BTC", candles(), buy, BigDecimal.ONE);
                return null;
            });
        } catch (Throwable t) {
            commitFailure = t;
        }

        SqlTrace.stop();

        DynamicSessionEntity after = sessionRepo.findById(id).orElseThrow();
        // 🔴 전역 count 를 쓰면 같은 H2 컨텍스트를 공유하는 다른 테스트의 행이 세어진다.
        //    실제로 함께 돌리면 서로 간섭해 통과/실패가 바뀜다 — 세션 단위로 좁힌다.
        List<PositionEntity> positions =
                positionRepository.findBySessionKindAndSessionIdAndStatus("DYNAMIC", id, "OPEN");

        // ① 정상 매수가 완료된다 — 커밋이 깨지면 그 자체로 실패다.
        assertThat(commitFailure)
                .as("🔴 진입 신호가 있었는데 커밋이 깨지면 매수가 완료되지 않는다 "
                        + "(관측된 결함: 안쪽 차감 커밋 → 바깥 @Version 충돌 → 매수 기록 롤백)")
                .isNull();

        // ② 잔액은 **정확히 한 번** 차감된다 — 범위 단정으로는 "한 번"을 증명하지 못한다.
        //    🔴 앞서 `0 < 잔액 < 초기값` 으로만 확인했는데 그것은 두 번 차감과 보상 후 재차감도
        //    통과시킨다. 예상 차감액을 계산해 **정확히** 비교한다.
        BigDecimal expectedInvest = CAPITAL.multiply(new BigDecimal("0.8000"));
        assertThat(after.getAvailableKrw())
                .as("초기자본 %s − 투자금 %s 이어야 한다 (초기값이면 보상이 되돌린 것, "
                        + "그보다 작으면 중복 차감)", CAPITAL, expectedInvest)
                .isEqualByComparingTo(CAPITAL.subtract(expectedInvest));

        // 🔴 보상 **실행 경로를 직접 계측**한다. UPDATE 횟수는 직접 증거가 아니다 —
        //    보상이 호출돼도 UPDATE 를 내기 전에 종료·실패할 수 있다.
        //    보상은 afterCompletion(ROLLED_BACK) 에서 balanceUpdater.apply 를 부른다
        //    (`registerBuyDeductionCompensation`). 그 **진입**을 세면 UPDATE 전에 실패해도 잡힌다.
        //    정상 경로의 apply 는 두 번이어야 한다 — persistWatchlist 1 + 매수 차감 1.
        //    ⚠️ 세션 ID 로 좁힌다. 이 컨텍스트에서는 스케줄러도 돌아 다른 세션의 apply 가 섞인다.
        verify(balanceUpdaterSpy, times(2)).apply(eq(id), any());

        // (보조 진단) 세션 UPDATE SQL 횟수 — 직접 증거는 위 계측이다.
        List<SqlTrace.Row> sessionUpdates = SqlTrace.rows().stream()
                .filter(r -> r.isUpdateOf("dynamic_session") && r.phase() == SqlTrace.Phase.END)
                .toList();
        assertThat(sessionUpdates)
                .as("(보조) 세션 UPDATE 는 워치리스트 1 + 차감 1 = 2회로 보인다.%n%s", SqlTrace.dump())
                .hasSize(2);

        // ③ 포지션·세션 상태가 잔액과 함께 일관된다.
        assertThat(positions).as("진입 포지션이 남아야 한다").hasSize(1);
        assertThat(positions.get(0).getStatus()).isEqualTo("OPEN");
        assertThat(after.getCurrentPositionId())
                .as("세션이 그 포지션을 가리켜야 한다")
                .isEqualTo(positions.get(0).getId());
        assertThat(after.getScanState()).isEqualTo("POSITION_MONITORING");

        // ④ 워치리스트도 같은 트랜잭션의 결과로 보존된다.
        assertThat(after.getWatchlistJson())
                .as("같은 틱의 워치리스트 갱신이 매수 때문에 사라지면 일관되지 않다")
                .isEqualTo("[\"KRW-BTC\"]");

        // ⑤ 🔴 외부 주문은 대역이므로 DB 만으로 판단하지 않는다 — 호출 자체를 확인한다.
        ArgumentCaptor<OrderRequest> req = ArgumentCaptor.forClass(OrderRequest.class);
        verify(orderExecutionEngine, times(1)).submitOrderAfterCommit(req.capture());
        assertThat(req.getValue().getSide()).isEqualTo("BUY");
        assertThat(req.getValue().getCoinPair()).isEqualTo("KRW-BTC");
        assertThat(req.getValue().getPositionId())
                .as("제출 요청이 실제로 저장된 포지션을 가리켜야 한다 — "
                        + "여기가 어긋나면 거래소 주문과 DB 가 갈린다")
                .isEqualTo(positions.get(0).getId());
        assertThat(orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id))
                .as("주문 행은 대역이 대신하므로 비어 있다 — 🔴 이 값으로 거래소 상태를 판단하지 않는다")
                .isEmpty();

        // ⑥ 생산 불변식 — 바깥 트랜잭션은 세션 행을 쓰지 않는다.
        //    🔴 이것이 깨지면 버전 충돌 경로가 되살아난다 (그 결과는 test B 가 기록해 둔다).
        List<SqlTrace.Row> rows = SqlTrace.rows();
        int outerConn = rows.isEmpty() ? -1 : rows.get(0).connTag();
        assertThat(rows.stream()
                .filter(r -> r.isUpdateOf("dynamic_session") && r.connTag() == outerConn)
                .toList())
                .as("바깥 연결이 dynamic_session 을 쓰면 안 된다. 덤프:%n%s", SqlTrace.dump())
                .isEmpty();
    }
}
