package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.OrderSettlementDiscrepancyEntity;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.OrderSettlementDiscrepancyRepository;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * 주문 정산 — <b>거래소 보고값과 이미 반영한 값의 차분을, 한 트랜잭션에서 반영한다</b> (2026-10-12).
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>실측(`SettlementIdempotencyTest` 시험 19): 수량은 같고 체결금액·수수료만 늘어난 응답이 도착해도
 * 아무것도 반영되지 않았다. 원인은 ① {@code syncOrderState} 가 "같은 상태면 아무것도 하지 않는다" 로
 * 중복을 막고 있었고(유효한 갱신까지 막힌다) ② 거래소 보고 금액·수수료를 <b>보존하지 않았다</b> 는 것.
 * V81·V82 가 ②를 해결했고, 이 서비스가 ①을 대체한다 — <b>조기 반환이 아니라 최신성 규칙과 정산 버전
 * 가드로</b> 중복을 막는다.</p>
 *
 * <h2>세 가지 규칙</h2>
 *
 * <h3>1. 최신성 — 버전 가드가 대신하지 못한다</h3>
 *
 * <p>🔴 {@code settlement_version} 은 <b>동시 쓰기만</b> 제어한다. 오래된 응답이 <b>최신 버전을 읽고</b>
 * 통과할 수 있으므로 최신성을 따로 본다:</p>
 * <ul>
 *   <li><b>누적 단조성</b> — 누적 체결량·체결금액이 이미 반영한 값보다 작으면 반영하지 않는다.</li>
 *   <li><b>종결 역행 금지</b> — 종결된 주문에 활성 응답이 오면 반영하지 않는다.</li>
 *   <li><b>수수료 증가는 수용</b> — 수량이 같고 수수료·금액만 늘어나는 것은 정산 지연으로 실재한다.</li>
 *   <li>🔴 <b>수수료 감소는 자동 반영하지 않고, 오래된 응답으로 단정해 폐기하지도 않는다</b> —
 *       {@link OrderSettlementDiscrepancyEntity} 로 <b>이전 값과 수신값을 함께 보존</b>해 사람이 본다.
 *       "done 이후 체결 상세가 채워진다" 는 사례는 <b>증가 방향의 설명</b>일 뿐이고, 그것만으로
 *       수수료가 항상 증가만 한다는 보장은 생기지 않는다(정산 정정·환급·우리 쪽 해석 오류가 모두
 *       후보다).</li>
 * </ul>
 *
 * <h3>2. 부분 반영 금지</h3>
 *
 * <p>🔴 주문의 {@code applied_*}·정산 버전, 세션 잔액, 포지션 중 <b>어느 지점에서 실패해도 전부
 * 롤백</b>돼야 한다. 그래서 전부 <b>하나의 트랜잭션</b>에서 수행하고, 잔액 반영도 그 트랜잭션 안에서
 * 세션을 다시 읽어 갱신한다(REQUIRES_NEW 헬퍼를 쓰면 그것만 커밋돼 재처리 시 두 번 반영된다 —
 * 2026-10-07 에 KRW 복원에서 실제로 그 구조였다).</p>
 *
 * <p>실패 후 재처리에서도 차액은 <b>정확히 한 번</b>만 반영된다: 조건부 UPDATE 가 1을 돌려준
 * 호출만 금액을 움직이고, 롤백되면 버전도 되돌아가므로 다음 시도가 같은 차분을 다시 계산한다.</p>
 *
 * <h3>3. 확정은 이 서비스가 하지 않는다</h3>
 *
 * <p>🔴 <b>자동 확정의 근거가 없다.</b> 업비트 응답에는 "정산이 끝났다" 는 신호가 없고,
 * <b>같은 값이 한 번 더 왔다는 사실은 이후 변경이 없다는 증거가 아니다.</b> 그래서 이 서비스는
 * {@code settlement_final} 을 세우지 않는다 — <b>백오프로 간격만 늘리고 재조회는 계속한다.</b></p>
 *
 * <p>확정은 사람이 <b>확정 근거 · 잔여 위험 · 회계 조정 기록</b>과 함께 판정할 때만 성립한다.
 * 🔴 그 수동 경로는 <b>미구현</b>이며, 사람이 세운 확정도 "거래소 정산이 확인됐다" 는 뜻이
 * 아니다 — 그 구분을 기록에 남겨야 한다.</p>
 *
 * <h2>미사용 금액의 정의</h2>
 *
 * <p>🔴 <b>원금 차이와 잔액 조정액은 다르다.</b> 시장가 매수에서 요청 3,000원 중 체결금액이 2,940원이면
 * 원금 차이는 60원이지만, <b>수수료가 요청액 안에서 빠지므로</b>(코드 주석: "세션 KRW 자체는
 * investAmount 를 매수 시점에 이미 전액 차감했고 Upbit 가 그 안에서 수수료를 제하고 코인을 지급")
 * 실제 미사용액은 {@code 3000 − 2940 − 1.47 = 58.53} 이다. 잔액 조정은 <b>요청액 − 체결금액 −
 * 수수료</b>로 계산한다.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderSettlementService {

    /** 조회에 변화가 없을 때 다음 조회까지의 기본 간격 — 5초 폴러를 그대로 때리지 않는다. */
    private static final Duration BACKOFF_BASE = Duration.ofSeconds(30);
    private static final Duration BACKOFF_MAX = Duration.ofMinutes(30);

    private final OrderRepository orderRepository;
    private final OrderSettlementDiscrepancyRepository discrepancyRepository;
    private final SessionBalanceUpdater liveBalanceUpdater;
    private final PlatformTransactionManager txManager;

    private TransactionTemplate settlementTx;

    @PostConstruct
    void initTx() {
        this.settlementTx = new TransactionTemplate(txManager);
        // 🔴 REQUIRES_NEW 다 — 이 정산은 **자기 완결 단위**이고, 호출부(폴러·상태 동기화)의 성패와
        //    독립적으로 커밋/롤백돼야 한다. 단 호출부가 주문·세션·포지션 행을 **UPDATE 하지 않은
        //    상태**에서 불려야 한다(중첩 잠금 대기를 피한다) — 현재 호출부는 조회만 한다.
        this.settlementTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 정산 결과 — 호출부가 로그·판단에 쓴다. */
    public enum Outcome {
        /** 차분을 반영했다. */
        APPLIED,
        /** 반영할 차분이 없었다(이미 최신). */
        NO_CHANGE,
        /** 최신성 규칙에 걸려 반영하지 않았다. 불일치로 보존했다. */
        DISCREPANCY,
        /** 동시 반영에서 다른 쪽이 먼저 통과했다. 아무것도 하지 않았다. */
        LOST_RACE,
        /** 전환 이전 데이터이거나 추적 대상이 아니다. */
        SKIPPED
    }

    /**
     * 거래소 응답 하나를 정산에 반영한다.
     *
     * @return 무엇을 했는지. 🔴 {@link Outcome#LOST_RACE} 와 {@link Outcome#DISCREPANCY} 에서는
     *         <b>금액이 전혀 움직이지 않는다.</b>
     */
    public Outcome settle(Long orderId, OrderResponse response) {
        return settlementTx.execute(st -> settleInTransaction(orderId, response));
    }

    private Outcome settleInTransaction(Long orderId, OrderResponse response) {
        OrderEntity order = orderRepository.findById(orderId).orElse(null);
        if (order == null) return Outcome.SKIPPED;

        // 🔴 전환 이전 데이터는 손대지 않는다 — 과거의 계산 수수료를 실제 수수료였던 것처럼
        //    재해석하지 않는다(V82).
        if (order.getFeeSource() == null) return Outcome.SKIPPED;
        if (Boolean.TRUE.equals(order.getSettlementFinal())) return Outcome.SKIPPED;

        BigDecimal reportedFunds = response.resolveExecutedFunds();
        BigDecimal reportedFee = response.getPaidFee();
        BigDecimal reportedVolume = response.getExecutedVolume();

        // ── 1. 최신성 ──────────────────────────────────────────────────────
        // 🔴 누적 단조성: 이미 반영한 값보다 작으면 반영하지 않는다.
        BigDecimal appliedFunds = nz(order.getAppliedExecutedFunds());
        BigDecimal appliedFee = nz(order.getAppliedPaidFee());

        if (reportedVolume != null && order.getFilledQuantity() != null
                && reportedVolume.compareTo(order.getFilledQuantity()) < 0) {
            recordDiscrepancy(order, OrderSettlementDiscrepancyEntity.KIND_VOLUME_DECREASE,
                    order.getFilledQuantity(), reportedVolume, null, null);
            return Outcome.DISCREPANCY;
        }
        if (reportedFunds != null && reportedFunds.compareTo(appliedFunds) < 0) {
            recordDiscrepancy(order, OrderSettlementDiscrepancyEntity.KIND_FUNDS_DECREASE,
                    appliedFunds, reportedFunds, null, null);
            return Outcome.DISCREPANCY;
        }
        // 🔴 수수료 감소 — **실측값끼리** 감소할 때만 불일치다.
        //
        //    추정값 → 실측값 전환에서는 감소가 정상이다. 매수 체결 시 우리가 체결금액 × 0.0005 로
        //    추정한 값을 임시 반영해 두는데, 거래소 실측값이 그보다 **작을 수 있다**. 그 경우를
        //    불일치로 처리하면 전환 자체가 막힌다 — "증가만 반영" 을 추정→실측 전환에 적용하면 안 된다.
        //
        //    그래서 출처를 본다: 이미 REPORTED 인데 더 작은 실측값이 오면 불일치, ESTIMATED 에서
        //    REPORTED 로 가는 것이면 **차액(음수 포함)을 반영**한다.
        boolean estimateToReported = OrderExecutionEngine.FEE_SOURCE_ESTIMATED.equals(order.getFeeSource())
                && reportedFee != null;
        if (!estimateToReported
                && OrderExecutionEngine.FEE_SOURCE_REPORTED.equals(order.getFeeSource())
                && reportedFee != null && reportedFee.compareTo(appliedFee) < 0) {
            recordDiscrepancy(order, OrderSettlementDiscrepancyEntity.KIND_FEE_DECREASE,
                    appliedFee, reportedFee, null, null);
            return Outcome.DISCREPANCY;
        }

        // ── 2. 차분 ────────────────────────────────────────────────────────
        BigDecimal targetFunds = reportedFunds != null ? reportedFunds : appliedFunds;
        BigDecimal targetFee = reportedFee != null ? reportedFee : appliedFee;
        BigDecimal deltaFunds = targetFunds.subtract(appliedFunds);
        BigDecimal deltaFee = targetFee.subtract(appliedFee);

        boolean reportedComplete = reportedFunds != null && reportedFee != null;

        // 🔴 **이 서비스는 settlement_final 을 세우지 않는다** (2026-10-12 정정).
        //
        //    두 번 틀렸다. ① "종결 상태 + 값이 다 있음" 을 확정 근거로 삼아 1회차에 확정해 2회차의
        //    수수료 증가를 놓쳤다. ② "종결 상태 + 차분 없음이 한 번 확인됨" 으로 옮겼는데 **그것도
        //    근거가 아니다** — 같은 값이 한 번 더 왔다는 사실은 이후 변경이 없다는 증거가 아니고,
        //    `첫 응답 → 동일 응답 → 늦은 수수료 증가` 에서 마지막 갱신을 놓친다.
        //
        //    업비트 응답에는 "정산이 끝났다" 는 신호가 없으므로 자동 확정의 근거가 없다.
        //    🔴 **백오프와 확정은 별개다** — 비용은 백오프가 줄이고, 재조회를 영구히 멈추는 것은
        //    확정이 한다. 차분이 없다는 이유로 멈추지 않는다.

        if (deltaFunds.signum() == 0 && deltaFee.signum() == 0) {
            // 반영할 것이 없다 — **간격만 늘린다.** 확정하지 않는다.
            touchBackoff(order);
            return Outcome.NO_CHANGE;
        }

        // ── 3. 원자적 반영 ─────────────────────────────────────────────────
        // 🔴 조건부 UPDATE 가 1을 돌려준 호출만 금액을 움직인다. 0 이면 그사이 다른 경로가 반영한
        //    것이므로 **아무것도 하지 않는다** — 그러지 않으면 같은 차액이 두 번 들어간다.
        String nextSource = reportedComplete
                ? OrderExecutionEngine.FEE_SOURCE_REPORTED
                : OrderExecutionEngine.FEE_SOURCE_ESTIMATED;
        int applied = orderRepository.applySettlementIfVersionMatches(
                order.getId(), order.getSettlementVersion(),
                reportedFunds, reportedFee,
                targetFunds, targetFee,
                nextSource,
                // 🔴 확정하지 않는다 — 다음 조회를 예약해 정정이 오면 받는다.
                nextCheckAt(order),
                Boolean.FALSE);
        if (applied == 0) {
            log.debug("정산 반영 권한을 얻지 못했다 — 다른 경로가 먼저 반영했다 (orderId={}, ver={})",
                    order.getId(), order.getSettlementVersion());
            return Outcome.LOST_RACE;
        }

        boolean firstApplication = order.getAppliedExecutedFunds() == null
                && order.getAppliedPaidFee() == null;
        applyFundsDeltaToSession(order, targetFunds, targetFee, appliedFunds, appliedFee, firstApplication);
        log.info("정산 반영 (orderId={}): 체결금액 Δ{} 수수료 Δ{} → 출처={} (확정은 다음 회차에 "
                        + "차분 없음이 확인될 때)",
                order.getId(), deltaFunds, deltaFee, nextSource);
        return Outcome.APPLIED;
    }

    /**
     * 잔액 반영 — 🔴 <b>같은 트랜잭션 안에서</b> 세션을 다시 읽어 갱신한다.
     *
     * <p>{@code SessionBalanceUpdater.apply}(REQUIRES_NEW)를 쓰면 <b>잔액만 독립 커밋</b>되어 이
     * 트랜잭션이 롤백돼도 되돌아가지 않는다 → 재처리에서 같은 차액이 두 번 들어간다. 2026-10-07 에
     * KRW 복원이 정확히 그 구조였다. 그래서 {@code applyWithin} 을 쓴다.</p>
     *
     * <p>🔴 <b>원금 차이와 잔액 조정액은 다르다.</b> 매수는 제출 시 요청액({@code quantity})을
     * <b>이미 전액 차감</b>했고 거래소가 그 안에서 수수료를 뺀다. 그러므로 정산이 할 일은 소비액을
     * 다시 빼는 것이 아니라 <b>미사용액을 복원</b>하는 것이다:</p>
     *
     * <pre>미사용액 = 요청액 − 체결금액 − 수수료</pre>
     *
     * <p>요청 3,000 / 체결 2,940 / 수수료 1.47 이면 <b>원금 차이는 60 이지만 미사용액은 58.53</b> 이다.
     * 🔴 처음 구현에서 이 방향을 반대로 잡아(소비액을 또 차감) 요청액만큼 이중 차감했고, 반복
     * 동일성만 보던 시험은 그것을 잡지 못했다.</p>
     *
     * <p>🔴 <b>처음 반영의 기준선</b>: 아직 아무것도 반영하지 않았다면 "이미 복원한 금액" 은 0 이다.
     * 차분 공식에 {@code applied=0} 을 그대로 넣으면 "요청액 전체를 이미 복원했다" 는 뜻이 되어
     * 요청액만큼 어긋난다.</p>
     *
     * <p>🔴 <b>시장가 매수에서만</b> {@code quantity} 가 요청 KRW 금액이다(Upbit price 타입). 지정가
     * 매수의 {@code quantity} 는 코인 수량이고, 매도는 대금이 들어오는 방향이다 — 둘 다 이 증분의
     * 범위 밖이며 미착수다.</p>
     */
    private void applyFundsDeltaToSession(OrderEntity order, BigDecimal targetFunds,
                                           BigDecimal targetFee, BigDecimal appliedFunds,
                                           BigDecimal appliedFee, boolean firstApplication) {
        if (order.getSessionId() == null) return;
        if (!"BUY".equalsIgnoreCase(order.getSide())) return;
        if (!"LIVE".equals(order.getSessionKind())) return;
        if (!"MARKET".equalsIgnoreCase(order.getOrderType())) return;
        if (order.getQuantity() == null) return;

        BigDecimal requested = order.getQuantity();
        BigDecimal targetRestore = requested.subtract(targetFunds).subtract(targetFee);
        BigDecimal appliedRestore = firstApplication
                ? BigDecimal.ZERO
                : requested.subtract(appliedFunds).subtract(appliedFee);
        BigDecimal restoreDelta = targetRestore.subtract(appliedRestore);
        if (restoreDelta.signum() == 0) return;

        liveBalanceUpdater.applyWithin(order.getSessionId(),
                s -> s.setAvailableKrw(s.getAvailableKrw().add(restoreDelta)));
        log.info("정산 잔액 조정 (orderId={}): 미사용 복원 Δ{} (요청={} 체결={} 수수료={})",
                order.getId(), restoreDelta, requested, targetFunds, targetFee);
    }

    /**
     * 반영할 차분이 없을 때 <b>일정만</b> 미룬다.
     *
     * <p>🔴 정산 버전을 올리지 않는다 — 버전은 "반영이 일어났다" 는 가드다. 차분 없는 회차에 올리면
     * 동시에 들어온 진짜 반영이 이유 없이 경합에서 밀린다.</p>
     */
    private void touchBackoff(OrderEntity order) {
        orderRepository.touchSettlementSchedule(
                order.getId(), order.getSettlementVersion(),
                nextCheckAt(order),
                Boolean.FALSE);
    }

    /** 지수 백오프 — 조회가 반복될수록 간격을 늘린다. */
    private Instant nextCheckAt(OrderEntity order) {
        int attempts = order.getSettlementAttempts() == null ? 0 : order.getSettlementAttempts();
        long seconds = Math.min(
                BACKOFF_BASE.getSeconds() * (1L << Math.min(attempts, 6)),
                BACKOFF_MAX.getSeconds());
        return Instant.now().plusSeconds(seconds);
    }

    private void recordDiscrepancy(OrderEntity order, String kind,
                                    BigDecimal previous, BigDecimal received,
                                    String previousState, String receivedState) {
        discrepancyRepository.save(OrderSettlementDiscrepancyEntity.builder()
                .orderId(order.getId())
                .kind(kind)
                .previousValue(previous)
                .receivedValue(received)
                .previousState(previousState)
                .receivedState(receivedState)
                .build());
        log.error("🔴 정산 불일치 — 자동 반영하지 않고 보존한다 (orderId={}, kind={}, 이전={}, 수신={}). "
                        + "사람이 확인해야 한다.",
                order.getId(), kind, previous, received);
    }

    private static boolean isTerminal(String exchangeState) {
        return "done".equalsIgnoreCase(exchangeState) || "cancel".equalsIgnoreCase(exchangeState);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
