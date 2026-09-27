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
 * <b>test B — 위험 메커니즘 기록 + 실패 경로의 보상 확인</b> (2026-09-26 신설, 2026-09-28 재분류).
 *
 * <p>🔴 <b>2026-09-28 재분류</b>: 처음에 이것을 "생산 경로가 위험한 순서를 만드는가" 로
 * 이름 붙였는다. 이제는 아니다 — 워치리스트 기록을 {@code persistWatchlist}(REQUIRES_NEW)로
 * 이전해 <b>생산에서는 밖이 세션 행을 쓰지 않는다.</b> 이 테스트는 테스트가 직접 그 쓰기를
 * 만들어 <b>그 순서가 어떤 결과를 내는가</b>를 남긴다 — 즉 위험 기록이자,
 * <b>실패 경로에서 보상과 중복 방지가 유지되는지</b>의 확인이다.
 * 생산 동작의 주 회귀 기준은 {@code DynamicBuyCompletesConsistentlyTest} 가 맡는다.
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
        String[] innerFailure = new String[]{null};
        String outerFailure = null;
        try {
            new TransactionTemplate(txManager).execute(status -> {
                DynamicSessionEntity s = sessionRepo.findById(id).orElseThrow();
                // 바깥 트랜잭션의 세션 행 UPDATE — refreshWatchlist 와 같은 문장.
                // flush 를 넣지 않는다. 실제로 언제 실행되는지가 이 시험의 대상이다.
                s.setWatchlistJson("[\"KRW-BTC\"]");
                s.setWatchlistRefreshedAt(Instant.now());
                sessionRepo.save(s);
                try {
                    dynamicTradingService.executeBuy(s, "KRW-BTC", candles(), buy, BigDecimal.ONE);
                } catch (Throwable t) {
                    innerFailure[0] = chain(t);
                }
                return null;
            });
        } catch (Throwable t) {
            // 바깥 커밋 단계의 실패도 관측 대상이다 — 여기서 잡지 않으면 기록을 못 남긴다.
            outerFailure = chain(t);
        }
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
                + " / dynamic_session UPDATE END(첫) idx=" + outerUpdateEnd
                + " / position INSERT END(첫) idx=" + positionInsertEnd
                + " / 다른 연결 첫 START idx=" + firstOtherConnStart);
        System.out.println("안쪽 예외 사슬=" + innerFailure[0]);
        System.out.println("바깥 커밋 예외 사슬=" + outerFailure);
        // 🔴 연결을 구분하지 않으면 같은 SQL 의 출처를 가릴 수 없다 — 관심 테이블만 연결과 함께 본다.
        System.out.println("--- dynamic_session / position 문장만, 연결과 함께 ---");
        for (SqlTrace.Row r : rows) {
            if (r.isUpdateOf("dynamic_session") || r.isInsertInto("position")
                    || r.isUpdateOf("position")) {
                System.out.printf("#%d %-5s conn=%d %s params=%s%n", r.seq(), r.phase(),
                        r.connTag(),
                        r.sql().substring(0, Math.min(60, r.sql().length())), r.params());
            }
        }

        // ③ 종료 후 실제 상태 — 예외 유무보다 이쪽이 중요하다
        DynamicSessionEntity after = sessionRepo.findById(id).orElseThrow();
        // 🔴 세션 단위로 좁힌다 — 전역 count 는 같은 컨텍스트를 쓰는 다른 테스트에 오염된다.
        long positions = positionRepository
                .findBySessionKindAndSessionIdAndStatus("DYNAMIC", id, "OPEN").size();
        long orders = orderRepository
                .findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id).size();
        System.out.println("최종 availableKrw=" + after.getAvailableKrw()
                + " / scanState=" + after.getScanState()
                + " / currentPositionId=" + after.getCurrentPositionId()
                + " / watchlistJson=" + after.getWatchlistJson()
                + " / position=" + positions + " / order=" + orders);

        // ── 관측된 사실을 단정으로 고정한다 (2026-09-26) ─────────────────────
        // 🔴 가설은 지지되지 않았다. 바깥의 dynamic_session UPDATE 는 **커밋 시점까지 지연**돼
        //    안쪽 REQUIRES_NEW 보다 **나중에** 실행됐다. 즉 안쪽이 돌 때 바깥은 그 행의 잠금을
        //    쥐고 있지 않았고, 잠금 대기는 일어나지 않았다.
        // 🔴 "다른 연결의 첫 문장"을 안쪽 진입으로 쓰면 틀린다 — 관측 결과 그것은
        //    balanceUpdater 가 아니라 **더 앞선 별도 연결 작업**(risk_config 쪽)이었다.
        //    관심 문장을 정확히 지목한다: **바깥이 아닌 연결의 dynamic_session UPDATE**.
        OptionalInt innerSessionUpdate = firstIndex(rows,
                r -> r.isUpdateOf("dynamic_session") && r.phase() == SqlTrace.Phase.START
                        && r.connTag() != outerConn);
        assertThat(innerSessionUpdate)
                .as("balanceUpdater(REQUIRES_NEW)의 세션 UPDATE 가 별도 연결에서 관측돼야 한다")
                .isPresent();
        int innerAt = innerSessionUpdate.getAsInt();

        assertThat(positionInsertEnd).as("position INSERT 는 실행돼야 관측이 성립한다").isPresent();
        assertThat(positionInsertEnd.getAsInt())
                .as("🔴 position INSERT 는 안쪽 진입 **전에** 실행된다 — 그러나 이것은 "
                        + "dynamic_session UPDATE 가 실행됐다는 증거가 아니다(따로 확인한다)")
                .isLessThan(innerAt);

        OptionalInt outerSessionUpdate = firstIndex(rows,
                r -> r.isUpdateOf("dynamic_session") && r.phase() == SqlTrace.Phase.END
                        && r.connTag() == outerConn);
        assertThat(outerSessionUpdate).as("바깥 연결의 세션 UPDATE 도 언젠가는 실행된다").isPresent();
        assertThat(outerSessionUpdate.getAsInt())
                .as("🔴 이 단정이 깨지면 **위험한 순서가 생겼다는 신호**다 — 바깥 세션 UPDATE 가 "
                        + "안쪽 REQUIRES_NEW 진입 전에 실행되면 안쪽이 그 잠금을 기다린다. "
                        + "현재는 커밋 시점까지 지연돼 그 순서가 만들어지지 않는다")
                .isGreaterThan(innerAt);

        // ③ 최종 상태 — 예외 없음보다 이쪽이 중요하다. 보상까지 돌아 일관된다.
        assertThat(after.getAvailableKrw())
                .as("안쪽 차감(8,000)이 커밋됐지만 바깥이 롤백됐다 — 보상이 되돌려 초기값이어야 한다")
                .isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(after.getScanState()).isEqualTo("SCANNING");
        assertThat(after.getCurrentPositionId()).isNull();
        assertThat(positions).as("바깥 롤백이므로 포지션이 남지 않는다").isZero();
        assertThat(orders).as("주문은 afterCommit 훅이라 커밋되지 않았으면 남지 않는다").isZero();
        assertThat(after.getWatchlistJson())
                .as("바깥의 워치리스트 쓰기도 함께 롤백된다")
                .isNull();
    }

    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && sb.length() < 900; c = c.getCause()) {
            sb.append(c.getClass().getSimpleName()).append(": ")
              .append(String.valueOf(c.getMessage()).replace('\n', ' ')).append(" <- ");
            if (c.getCause() == c) break;
        }
        return sb.toString();
    }

    private static OptionalInt firstIndex(List<SqlTrace.Row> rows,
                                          java.util.function.Predicate<SqlTrace.Row> p) {
        return IntStream.range(0, rows.size()).filter(i -> p.test(rows.get(i))).findFirst();
    }
}
