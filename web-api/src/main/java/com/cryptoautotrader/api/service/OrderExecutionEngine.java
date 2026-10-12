package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.TradeLogEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.TradeLogRepository;
import com.cryptoautotrader.core.risk.RiskCheckResult;
import com.cryptoautotrader.core.risk.RiskConfig;
import com.cryptoautotrader.core.risk.RiskEngine;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.AccountResponse;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 주문 실행 엔진 — 6단계 상태 머신 기반 주문 처리
 *
 * 상태 전이:
 *   PENDING → SUBMITTED → FILLED
 *                       → PARTIAL_FILLED → FILLED
 *                       → CANCELLED
 *            → FAILED
 *
 * 주요 기능:
 * - 리스크 체크 후 거래소 주문 제출
 * - 중복 주문 방지 (동일 코인+방향의 활성 주문 거부)
 * - 5초 간격 활성 주문 상태 폴링
 * - SUBMITTED 상태 5분 경과 시 자동 취소
 * - 모든 상태 변경 시 trade_log 기록
 */
@Service
@Slf4j
public class OrderExecutionEngine {

    private static final Duration ORDER_TIMEOUT = Duration.ofMinutes(5);
    private static final List<String> ACTIVE_STATES = List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED");

    /**
     * 정산 출처 — {@code order.fee_source} (V81·V82).
     *
     * <p>🔴 {@code null} 은 <b>전환 이전 데이터</b>다. 과거의 계산 수수료를 실제 수수료였던 것처럼
     * 재해석하지 않으므로 <b>자동 재정산에서 제외</b>한다. 신규 주문은 생성 시
     * {@link #FEE_SOURCE_PENDING} 을 박아 새 경로에 참여함을 명시한다.</p>
     */
    static final String FEE_SOURCE_PENDING = "PENDING";
    /** 추정값을 임시 반영했다. 실제값이 오면 차액만 정산하고 {@link #FEE_SOURCE_REPORTED} 로 올린다. */
    static final String FEE_SOURCE_ESTIMATED = "ESTIMATED";
    /** 거래소 보고값을 반영했다. */
    static final String FEE_SOURCE_REPORTED = "REPORTED";
    /** Upbit 매수 수수료율 — LiveTradingService/DynamicTradingService의 FEE_RATE와 동일 (0.05%) */
    private static final BigDecimal BUY_FEE_RATE = new BigDecimal("0.0005");

    private final OrderRepository orderRepository;
    private final PositionRepository positionRepository;
    private final TradeLogRepository tradeLogRepository;
    private final RiskManagementService riskManagementService;
    private final ObjectMapper objectMapper;

    // exchange-adapter 모듈에서 빈 등록 전이면 null 허용
    @Autowired(required = false)
    private UpbitRestClient upbitRestClient;

    @Autowired(required = false)
    private UpbitOrderClient upbitOrderClient;

    /** 정산 차분 반영 — 순환 참조를 피하려고 세터 주입한다. */
    @Autowired(required = false)
    private OrderSettlementService orderSettlementService;

    @Autowired(required = false)
    private TelegramNotificationService telegramService;

    @Autowired(required = false)
    private LiveTradingSessionRepository sessionRepository;

    /** 동적 세션 KRW 복원용 (선택적 — DynamicTradingService 미사용 환경에서는 null) */
    @Autowired(required = false)
    private com.cryptoautotrader.api.repository.DynamicSessionRepository dynamicSessionRepository;

    @Autowired(required = false)
    private DynamicSessionBalanceUpdater dynamicBalanceUpdater;

    /** §10 — Upbit API rate limit 대응 */
    @Autowired
    private UpbitApiRateLimiter rateLimiter;

    /** §16 — Micrometer 메트릭 (Prometheus export) */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    /** §14 — 실전/백테스트 drift 트래커 (BUY 체결 시 신호가 vs 체결가 기록) */
    @Autowired(required = false)
    private ExecutionDriftTracker executionDriftTracker;

    /**
     * 자기 자신의 프록시 — {@link #submitOrderAfterCommit}에서 {@link #submitOrder}를 호출할 때
     * {@code this.submitOrder(..)}로 부르면 Spring 프록시를 우회해 {@code @Async}/{@code @Transactional}이
     * 통째로 무시된다(동기 실행 + 트랜잭션 없음). 반드시 프록시를 경유해야 한다.
     */
    @Autowired
    @Lazy
    private OrderExecutionEngine self;

    public OrderExecutionEngine(OrderRepository orderRepository,
                                 PositionRepository positionRepository,
                                 TradeLogRepository tradeLogRepository,
                                 RiskManagementService riskManagementService,
                                 ObjectMapper objectMapper,
                                 org.springframework.transaction.PlatformTransactionManager txManager) {
        this.orderRepository = orderRepository;
        this.positionRepository = positionRepository;
        this.tradeLogRepository = tradeLogRepository;
        this.riskManagementService = riskManagementService;
        this.objectMapper = objectMapper;
        this.syncTx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.syncTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 주문 한 건의 거래소 상태 반영을 <b>자기 트랜잭션</b>에서 실행하기 위한 템플릿 (2026-10-07).
     *
     * <p>🔴 왜 필요한가 — <b>체결이 조용히 사라지는 경로가 있었다.</b> {@link #checkOrderStatus} 는
     * 거래소 조회와 {@code syncOrderState} 를 한 {@code try/catch} 로 묶고 <b>예외를 삼켰다.</b>
     * {@code syncOrderState} 는 주문 엔티티를 먼저 변형하므로(상태·체결수량), 처리 중간에
     * 실패해도 <b>그 부분 변경이 그대로 커밋됐다.</b></p>
     *
     * <p>실측(2026-10-07 `FillProcessingFailureTest`): 거래소가 {@code done} 을 보고했으나 체결가
     * 산출값이 없어 {@code handleBuyFill} 에서 NPE 가 났을 때, 주문은 <b>{@code FILLED} 로 기록되고
     * 포지션 수량은 0 으로 남았다.</b> 주문이 더는 활성이 아니므로 {@link #pollActiveOrders} 가
     * <b>다시 시도하지 않는다</b> — 체결된 돈이 어디에도 반영되지 않는다.</p>
     *
     * <p>그래서 반영을 <b>REQUIRES_NEW 로 분리</b>한다. 실패하면 그 트랜잭션만 롤백되어 주문은
     * 활성으로 남고, 다음 폴링(5초)에서 다시 시도된다. 엔티티는 <b>그 트랜잭션 안에서 다시
     * 읽는다</b> — 바깥 영속성 컨텍스트의 사본을 넘기면 롤백해도 바깥 커밋에서 다시 쓰인다.</p>
     */
    private final org.springframework.transaction.support.TransactionTemplate syncTx;

    /**
     * 주문 제출을 <b>호출자 트랜잭션 커밋 이후</b>로 지연시킨다 — 신규 포지션을 만든 직후의 매수 주문 전용.
     *
     * <p><b>왜 필요한가 (2026-07-29 P0)</b>: {@code executeBuy} 계열은 {@code @Transactional} 안에서
     * {@code positionRepository.save(pos)}로 포지션을 만든 뒤, <b>커밋 전에</b> {@link #submitOrder}에
     * 그 {@code positionId}를 넘겼다. {@code submitOrder}는 {@code @Async}라 <b>별도 스레드·별도 트랜잭션</b>이고,
     * 거기서는 아직 커밋되지 않은 position 행이 보이지 않는다. 그래서 order INSERT의
     * {@code order_position_id_fkey} 검사가 부모 커밋까지 락 대기에 걸리고, 타임아웃/데드락으로
     * async 측이 희생되면서 <b>주문 행이 통째로 롤백</b>됐다.</p>
     *
     * <p>운영 증거(2026-07-29): 동적 세션 포지션 6건이 생성됐는데 {@code "order"}의 DYNAMIC 행은 0건.
     * {@code order_id_seq}는 8591까지 소비됐으나 {@code MAX(id)}는 8587 — INSERT 후 롤백된 흔적이며,
     * {@code trade_log_id_seq}는 미소비라 {@code orderRepository.save()} 자체에서 실패했음이 확정된다.
     * 결과적으로 동적 매매는 실체결이 한 건도 성립하지 못했다.</p>
     *
     * <p>커밋 이후로 미루면 async 스레드가 이미 커밋된 position을 보게 되어 FK 대기가 사라진다.
     * 트랜잭션이 <b>롤백되면 주문은 아예 발행되지 않는다</b> — 포지션 없이 주문만 나가는 사고를 막는
     * 올바른 동작이다. 트랜잭션 밖에서 호출되면 즉시 제출로 폴백한다.</p>
     */
    public void submitOrderAfterCommit(OrderRequest request) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            self.submitOrder(request);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                self.submitOrder(request);
            }
        });
    }

    /**
     * 주문 제출 — PENDING 상태로 생성 후 리스크 체크 및 거래소 주문
     * @Async 는 void 또는 Future<T> 반환만 지원 (Spring Boot 3.x)
     *
     * <p>⚠️ 신규 생성한 포지션을 참조하는 매수 주문이라면 이 메서드를 직접 부르지 말고
     * {@link #submitOrderAfterCommit}을 사용할 것 — 이유는 그 Javadoc 참조.</p>
     */
    @Transactional
    @Async("orderExecutor")
    public void submitOrder(OrderRequest request) {
        log.info("주문 제출 요청: {} {} {} 수량={}", request.getCoinPair(), request.getSide(),
                request.getOrderType(), request.getQuantity());

        // 세션 종류 — live_trading_session/dynamic_session은 별도 BIGSERIAL 이라 sessionId만으로는
        // 구분 불가. 호출자(LiveTradingService/DynamicTradingService)가 명시하지 않으면 LIVE로 간주.
        String sessionKind = request.getSessionKind() != null ? request.getSessionKind() : "LIVE";

        // 1. 중복 주문 방지 — 세션 주문은 세션종류+세션 단위로, 비세션 주문은 전역으로 체크
        //    (kind 미구분 시 live/dynamic 세션이 같은 sessionId를 가지면 서로를 오차단할 수 있었음 — 2026-07-02)
        boolean duplicateExists = request.getSessionId() != null
                ? orderRepository.existsBySessionKindAndSessionIdAndCoinPairAndSideAndStateIn(
                        sessionKind, request.getSessionId(), request.getCoinPair(), request.getSide(), ACTIVE_STATES)
                : orderRepository.existsByCoinPairAndSideAndStateIn(
                        request.getCoinPair(), request.getSide(), ACTIVE_STATES);
        if (duplicateExists) {
            log.warn("중복 주문 거부 (sessionKind={}, sessionId={}, {} {}): 이미 활성 주문이 있습니다",
                    sessionKind, request.getSessionId(), request.getCoinPair(), request.getSide());
            return;
        }

        // 2. 주문 엔티티 생성 (PENDING)
        OrderEntity order = OrderEntity.builder()
                .coinPair(request.getCoinPair())
                .side(request.getSide())
                .orderType(request.getOrderType())
                .price(request.getPrice())
                .quantity(request.getQuantity())
                .state("PENDING")
                .signalReason(request.getReason())
                .signalPrice(request.getSignalPrice())
                .sessionId(request.getSessionId())
                .sessionKind(sessionKind)
                .positionId(request.getPositionId())
                // 🔴 새 회계 경로 참여를 **생성 시 명시한다** (2026-10-12, V82).
                //
                //    V81 의 정산 컬럼은 전부 NULL 로 시작한다. 전환 규칙이 "fee_source IS NULL 이면
                //    손대지 않는다"(= 전환 이전 데이터) 이므로, **신규 주문도 NULL 로 두면 새 경로에서
                //    영원히 빠진다.** 그래서 'PENDING'(참여하지만 아직 아무것도 반영하지 않았다)을
                //    박고 정산 버전을 0 으로 명시한다.
                //
                //    🔴 기존 NULL 주문은 그대로 둔다 — 자동 재정산 대상이 아니다. 아직 미체결인
                //    기존 주문의 전환은 **별도 작업**이며, 여기서 섞으면 과거 데이터를 재해석하게 된다.
                .feeSource(FEE_SOURCE_PENDING)
                .settlementVersion(0L)
                .settlementFinal(Boolean.FALSE)
                .settlementAttempts(0)
                .build();
        order = orderRepository.save(order);
        recordTradeLog(order.getId(), "STATE_CHANGE", null, "PENDING", "주문 생성");

        // 3. 리스크 체크
        // - SELL: 손절 등 청산이 막히면 손실 확대 → 항상 허용
        // - 세션 BUY (positionId != null): LiveTradingService에서 이미 체크 + 포지션/KRW 처리 완료 → 재체크 생략
        // - 비세션 BUY (positionId == null): 리스크 체크 필요
        boolean needsRiskCheck = "BUY".equalsIgnoreCase(request.getSide())
                && request.getPositionId() == null;
        if (needsRiskCheck) {
            RiskCheckResult riskResult = riskManagementService.checkRisk();
            if (!riskResult.isApproved()) {
                log.warn("리스크 체크 거부 (orderId={}): {}", order.getId(), riskResult.getReason());
                transitionState(order, "FAILED", riskResult.getReason());
                return;
            }
        }

        // 🔴 4. 정지와의 순서를 구조로 강제한다 (2026-10-07) — **신규 진입(BUY)만**.
        //
        //    평가 루프가 세션을 RUNNING 으로 읽고 매수를 결정한 뒤, 그 제출이 **정지 커밋 이후에**
        //    거래소에 닿는 일이 실제로 일어났다(`LateOrderSubmissionAfterStopTest` 로 재현). 그러면
        //    사람은 정지시켰다고 보는데 새 포지션이 생기고, 정지된 세션에는 평가 루프가 없으므로
        //    **그 포지션을 청산할 주체가 없다.**
        //
        //    🔴 "제출 직전에 RUNNING 을 한 번 더 읽는" 것으로는 부족하다 — 재확인과 제출 사이에도
        //    정지가 끼어들 수 있다. 그래서 **세션 행을 배타 잠금으로 읽는다.** 이 메서드는
        //    `@Transactional` 이므로 잠금은 **이 트랜잭션이 끝날 때까지**, 즉 거래소 호출을 지나
        //    유지된다. 정지 쪽의 세션 행 쓰기는 그동안 블록되고, 정지가 먼저 커밋됐다면 여기서
        //    STOPPED 를 보게 된다. 둘 중 하나만 성립한다.
        //
        //    🔴 측정으로 확인한 전제 (2026-10-07 `SubmitOrderBoundaryProbeTest`):
        //    이 메서드는 `order-exec-N` 스레드에서 **자기 트랜잭션**
        //    (`OrderExecutionEngine.submitOrder`)으로 돈다 — 호출자(`stopSession`)의 트랜잭션이
        //    아니다. 그래서 여기에 `REQUIRES_NEW` 를 쓰면 **주문 행을 만든 바로 이 트랜잭션이
        //    정지돼** 그 행이 보이지 않고, 분리된 엔티티 저장이 INSERT 로 떨어져 **같은 주문이 두
        //    행으로 저장됐다.** 템플릿을 쓰지 않고 이 트랜잭션에서 그대로 잠그는 것이 맞다.
        //
        //    🔴 **매도는 절대 막지 않는다.** 정지·손절의 청산이 막히면 손실이 확대되고, 정지된
        //    세션의 포지션을 팔 수단이 사라진다. (리스크 체크도 같은 이유로 SELL 을 항상 허용한다.)
        //    신규 진입 차단과 기존 주문·포지션 정리는 별개 상태이며 이 가드는 전자만 담당한다.
        if ("BUY".equalsIgnoreCase(request.getSide()) && "LIVE".equals(sessionKind)
                && request.getSessionId() != null && sessionRepository != null) {
            // 엔티티가 아니라 **상태 문자열만** 받는다 — 잠금은 필요하지만 엔티티 관리는 필요하지
            // 않다. 🔴 단, 이것이 정지 실패의 원인을 고친 것은 아니다. 응답 불명 경로 뒤 정지가
            // 낙관적 잠금으로 실패하는 현상은 네이티브 잠금으로 바꾼 뒤에도 재현되며, 실제로
            // 세션 행을 UPDATE 하는 경로는 아직 찾지 못했다(`SubmitResponseUnknownTest`).
            String lockedStatus = sessionRepository.lockAndReadStatus(request.getSessionId())
                    .orElse(null);
            if (!"RUNNING".equals(lockedStatus)) {
                log.warn("세션이 실행 중이 아니어서 주문을 제출하지 않는다 (orderId={}, sessionId={}, status={})",
                        order.getId(), request.getSessionId(),
                        lockedStatus == null ? "없음" : lockedStatus);
                transitionState(order, "FAILED", "세션이 실행 중이 아니다 — 제출하지 않았다");
                return;
            }
        }

        // 5. 거래소 주문 제출
        if (upbitOrderClient == null) {
            log.warn("UpbitOrderClient 미등록 — 거래소 연동 불가 (orderId={})", order.getId());
            transitionState(order, "FAILED", "거래소 클라이언트 미등록");
            return;
        }

        try {
            UpbitOrderClient.ExchangeResult exchangeResult = submitToExchange(order);
            OrderResponse exchangeResponse = exchangeResult.response();
            order.setExchangeOrderId(exchangeResponse.getUuid());
            order.setSubmittedAt(Instant.now());
            order.setResponseJson(exchangeResult.rawBody());
            log.info("거래소 주문 제출 완료 (orderId={}, exchangeId={}, initialState={})",
                    order.getId(), exchangeResponse.getUuid(), exchangeResponse.getState());

            // 주문 생성 응답에서 이미 최종 상태인 경우 즉시 처리 (폴러 대기 불필요)
            String initialState = mapExchangeState(exchangeResponse.getState());
            if ("FILLED".equals(initialState)) {
                if (exchangeResponse.getExecutedVolume() != null) {
                    order.setFilledQuantity(exchangeResponse.getExecutedVolume());
                }
                // 체결가 확정 시도 — 시장가 매도는 executed_funds/executed_volume 로 평균 단가 산출
                applyFillPrice(order, exchangeResponse);
                // ⚠️ 시장가 매도가 즉시 done 으로 떠도 trades(executed_funds)가 미정산이면 체결가 null.
                //    이대로 FILLED 확정하면 finalizeSellPosition 이 avgPrice 로 대체 → realizedPnl 이
                //    항상 -매도수수료로 기록되는 "가짜 본전" 버그(2026-05-31 분석: 포지션 146건).
                //    체결가 미확보 SELL 은 SUBMITTED 로 두어 5초 폴러가 정산 후 재조회하게 한다.
                if ("SELL".equalsIgnoreCase(order.getSide()) && order.getPrice() == null) {
                    log.warn("매도 즉시 체결이나 체결가 미정산 — SUBMITTED 유지, 폴러 재조회 (orderId={}, uuid={})",
                            order.getId(), exchangeResponse.getUuid());
                    transitionState(order, "SUBMITTED", null);
                } else {
                    order.setFilledAt(Instant.now());
                    transitionState(order, "FILLED", null);
                    processFilledOrder(order);
                }
            } else {
                transitionState(order, "SUBMITTED", null);
                // CANCELLED이지만 부분 체결된 경우도 즉시 처리
                if ("CANCELLED".equals(initialState)
                        && "BUY".equalsIgnoreCase(order.getSide())
                        && exchangeResponse.getExecutedVolume() != null
                        && exchangeResponse.getExecutedVolume().compareTo(BigDecimal.ZERO) > 0) {
                    log.warn("주문 생성 응답에서 부분 체결 후 취소 감지 (orderId={}, executed_volume={})",
                            order.getId(), exchangeResponse.getExecutedVolume());
                    order.setFilledQuantity(exchangeResponse.getExecutedVolume());
                    order.setFilledAt(Instant.now());
                    transitionState(order, "FILLED", null);
                    processFilledOrder(order);
                }
            }
        } catch (Exception e) {
            log.error("거래소 주문 제출 실패 (orderId={}): {}", order.getId(), e.getMessage(), e);
            transitionState(order, "FAILED", "거래소 주문 실패: " + e.getMessage());
        }
    }

    /**
     * 주문 상태 동기화 — 거래소에서 최신 상태를 조회하여 업데이트
     */
    @Transactional
    public void checkOrderStatus(Long orderId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: id=" + orderId));

        // 🔴 종결 주문도 **정산이 미확정이면 다시 조회한다** (2026-10-12).
        //
        //    종전에는 `!ACTIVE_STATES` 면 바로 돌아갔다. 그 조기 반환이 "같은 응답 반복"·"종결 후
        //    활성 응답" 을 막아주긴 했지만(시험 17·18), **유효한 정산 갱신까지 막았다**(시험 19) —
        //    시장가 매도의 체결 상세가 done 이후에 채워지는 경우가 실재한다. 이제 중복은 조기
        //    반환이 아니라 **최신성 규칙 + 정산 버전 가드**(OrderSettlementService)가 막는다.
        //
        //    🔴 재조회 대상은 "보고값 ≠ 반영값" 이 아니라 **"정산 미확정"** 이다 — 둘이 같거나 둘 다
        //    null 이어도 나중에 도착할 수 있다. 백오프는 settlement_next_check_at 이 담당한다.
        boolean needStateSync = ACTIVE_STATES.contains(order.getState());
        boolean needSettlement = order.getFeeSource() != null
                && !Boolean.TRUE.equals(order.getSettlementFinal())
                && (order.getSettlementNextCheckAt() == null
                    || !order.getSettlementNextCheckAt().isAfter(Instant.now()));
        if (!needStateSync && !needSettlement) {
            return;
        }

        if (upbitRestClient == null || order.getExchangeOrderId() == null) {
            return;
        }

        // 🔴 2026-10-07: 조회와 반영을 분리한다. 종전에는 둘을 한 try/catch 로 묶어 예외를 삼켰고,
        //    그 결과 체결 처리가 실패해도 주문이 FILLED 로 기록돼 **폴러가 다시 시도하지 않았다**
        //    (체결이 조용히 사라지는 경로 — `syncTx` javadoc 참조).
        OrderResponse exchangeStatus;
        try {
            exchangeStatus = queryExchangeOrder(order.getExchangeOrderId());
        } catch (Exception e) {
            // 조회 실패 — 아무것도 바꾸지 않았으므로 주문은 활성으로 남고 다음 폴링에서 재시도된다.
            log.error("주문 상태 조회 실패 (orderId={}): {}", orderId, e.getMessage());
            return;
        }

        if (needStateSync) {
            try {
                // 반영은 자기 트랜잭션에서, 엔티티도 그 안에서 다시 읽는다 — 실패 시 부분 변경이 남지 않는다.
                syncTx.executeWithoutResult(st -> {
                    OrderEntity fresh = orderRepository.findById(orderId).orElseThrow(
                            () -> new IllegalArgumentException("주문을 찾을 수 없습니다: id=" + orderId));
                    syncOrderState(fresh, exchangeStatus);
                });
            } catch (Exception e) {
                // 🔴 '조회 실패' 와 구분해서 적는다. 조회는 성공했고 **반영이 실패**한 것이다.
                log.error("체결 반영 실패 — 변경을 롤백하고 주문을 활성으로 유지한다 (orderId={}): {}",
                        orderId, e.getMessage(), e);
            }
        }

        // 🔴 정산은 상태 동기화와 **별개 트랜잭션**이다. 상태 전이가 없어도(이미 종결) 체결금액·
        //    수수료가 늘어났으면 차분을 반영해야 한다. 실패하면 그 트랜잭션만 롤백되고 주문은
        //    미확정으로 남아 다음 조회에서 다시 시도된다.
        if (orderSettlementService != null) {
            try {
                orderSettlementService.settle(orderId, exchangeStatus);
            } catch (Exception e) {
                log.error("정산 반영 실패 — 롤백하고 미확정으로 남긴다 (orderId={}): {}",
                        orderId, e.getMessage(), e);
            }
        }
    }

    /**
     * 주문 취소 요청
     */
    @Transactional
    public OrderEntity cancelOrder(Long orderId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: id=" + orderId));

        String currentState = order.getState();
        if (!"PENDING".equals(currentState) && !"SUBMITTED".equals(currentState)
                && !"PARTIAL_FILLED".equals(currentState)) {
            throw new IllegalStateException(
                    String.format("취소 불가 상태: %s (orderId=%d)", currentState, orderId));
        }

        // 거래소에 취소 요청
        if (upbitOrderClient != null && order.getExchangeOrderId() != null) {
            try {
                cancelOnExchange(order.getExchangeOrderId());
            } catch (Exception e) {
                log.error("거래소 주문 취소 실패 (orderId={}): {}", orderId, e.getMessage());

                // (B) 취소 실패는 '이미 체결되어 취소 불가'일 수 있다 — 거래소 상태를 재확인해
                //     실제 체결됐다면 CANCELLED 로 박지 말고 체결 처리한다 (체결을 취소로 오기록 방지).
                //
                // 🔴 2026-10-07: **조회와 그 뒤의 처리를 분리한다.** 종전에는 둘을 한
                //    try/catch 로 묶어서, `syncOrderState` 안의 실패(체결가 정산 등)까지
                //    "거래소 조회 실패" 로 취급해 **CANCELLED 로 흘려보냈다** — 체결된 주문이
                //    취소로 기록되는 경로다. 조회 성공 이후의 실패는 **결과 불명이 아니다.**
                OrderResponse latest;
                try {
                    latest = queryExchangeOrder(order.getExchangeOrderId());
                } catch (Exception ex) {
                    // 🔴 여기만이 진짜 '결과 불명' 이다. 취소 요청이 실패하고 상태 재조회까지
                    //    실패하면 **주문이 거래소에 살아 있는지 알 수 없다.** 종전에는 그대로
                    //    빠져나가 CANCELLED 로 전이했는데, 그러면 pollActiveOrders()(5초 주기,
                    //    활성 주문만 동기화)의 추적 대상에서 빠진다. 그 주문이 나중에 체결되면
                    //    관리 주체 없는 포지션과 어긋난 KRW 가 남는다.
                    //    거짓 종결보다 미결이 낫다 — 미결은 **추적 대상에 남는다.**
                    log.error("취소 실패 후 상태 재조회도 실패 (orderId={}): {} — 상태를 바꾸지 않고 "
                                    + "활성으로 남긴다. pollActiveOrders() 의 추적 대상에 남는다.",
                            orderId, ex.getMessage());
                    throw new IllegalStateException(
                            "취소 결과 불명 — 주문을 활성으로 남긴다: orderId=" + orderId, ex);
                }

                // 조회 성공 — 이 아래의 실패는 '불명' 으로 둔갑시키지 않고 그대로 올린다.
                boolean executed = latest.getExecutedVolume() != null
                        && latest.getExecutedVolume().compareTo(BigDecimal.ZERO) > 0;
                String exchangeState = mapExchangeState(latest.getState());
                if ("FILLED".equals(exchangeState) || executed) {
                    log.warn("취소 실패 + 거래소 체결 확인 — CANCELLED 대신 체결 처리 (orderId={}, state={}, executed={})",
                            orderId, latest.getState(), latest.getExecutedVolume());
                    syncOrderState(order, latest);
                    return order;
                }
                // 🔴 체결이 아니더라도 거래소에서 **아직 활성**이면 그 주문은 살아 있다.
                //    취소를 요청했다는 사실이 사라졌다는 증거가 아니다.
                if (ACTIVE_STATES.contains(exchangeState)) {
                    log.error("취소 실패 + 거래소에 주문이 아직 살아 있다 — CANCELLED 로 박지 않는다 "
                                    + "(orderId={}, 거래소상태={}). pollActiveOrders() 의 추적 대상에 남는다.",
                            orderId, latest.getState());
                    throw new IllegalStateException(
                            "취소되지 않았고 거래소에 활성으로 남아 있다: orderId=" + orderId);
                }
                // 여기 도달 = 조회 성공 + 체결도 활성도 아님(취소 확인) → 아래에서 CANCELLED 전이
            }
        }

        order.setCancelledAt(Instant.now());
        transitionState(order, "CANCELLED", "사용자 취소 요청");
        log.info("주문 취소 완료: orderId={}", orderId);
        return order;
    }

    /**
     * 모든 활성 주문 취소 (비상 정지용)
     */
    @Transactional
    public int cancelAllActiveOrders() {
        List<OrderEntity> activeOrders = orderRepository.findByStateIn(ACTIVE_STATES);
        int cancelledCount = 0;

        for (OrderEntity order : activeOrders) {
            try {
                cancelOrder(order.getId());
                cancelledCount++;
            } catch (Exception e) {
                log.error("활성 주문 취소 실패 (orderId={}): {}", order.getId(), e.getMessage());
            }
        }

        log.info("활성 주문 일괄 취소: {}건 취소됨", cancelledCount);
        return cancelledCount;
    }

    /**
     * 주문 ID로 상세 조회
     */
    @Transactional(readOnly = true)
    public Optional<OrderEntity> getOrder(Long orderId) {
        return orderRepository.findById(orderId);
    }

    /**
     * 전체 주문 내역 조회 (페이징) — 세션/날짜 필터 선택 적용
     */
    @Transactional(readOnly = true)
    public Page<OrderEntity> getOrders(Pageable pageable, List<Long> sessionIds, Instant dateFrom, Instant dateTo) {
        boolean hasSession = sessionIds != null && !sessionIds.isEmpty();
        boolean hasDate    = dateFrom != null && dateTo != null;

        if (hasSession && hasDate) {
            return orderRepository.findBySessionIdInAndCreatedAtBetweenOrderByCreatedAtDesc(sessionIds, dateFrom, dateTo, pageable);
        } else if (hasSession) {
            return orderRepository.findBySessionIdInOrderByCreatedAtDesc(sessionIds, pageable);
        } else if (hasDate) {
            return orderRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(dateFrom, dateTo, pageable);
        } else {
            return orderRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
    }

    /**
     * 활성 주문 목록 조회
     */
    @Transactional(readOnly = true)
    public List<OrderEntity> getActiveOrders() {
        return orderRepository.findByStateIn(ACTIVE_STATES);
    }

    // ── 스케줄: 활성 주문 상태 폴링 (5초 간격) ────────────────────

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void pollActiveOrders() {
        List<OrderEntity> activeOrders = orderRepository.findByStateIn(ACTIVE_STATES);
        if (activeOrders.isEmpty()) {
            return;
        }

        for (OrderEntity order : activeOrders) {
            // 타임아웃 체크: SUBMITTED 상태에서 5분 경과 시 자동 취소
            if ("SUBMITTED".equals(order.getState()) && order.getSubmittedAt() != null) {
                Duration elapsed = Duration.between(order.getSubmittedAt(), Instant.now());
                if (elapsed.compareTo(ORDER_TIMEOUT) > 0) {
                    // (A) 취소 직전 거래소 최신 상태 재확인 — 이미 체결됐다면 취소가 아니라 체결 처리.
                    //     시장가 매도가 거래소에서 done 됐는데 취소로 오기록 → 포지션 OPEN 롤백 →
                    //     무한 재매도 + 잔고 불일치로 이어지던 버그의 근본 차단선.
                    if (order.getExchangeOrderId() != null) {
                        try {
                            OrderResponse latest = queryExchangeOrder(order.getExchangeOrderId());
                            if ("FILLED".equals(mapExchangeState(latest.getState()))) {
                                log.warn("타임아웃 직전 체결 감지 — 취소 대신 체결 동기화 (orderId={}, state={}, executed={})",
                                        order.getId(), latest.getState(), latest.getExecutedVolume());
                                syncOrderState(order, latest);
                                continue;
                            }
                        } catch (Exception e) {
                            log.warn("타임아웃 전 상태 재조회 실패 (orderId={}): {} — 취소 진행",
                                    order.getId(), e.getMessage());
                        }
                    }
                    log.warn("주문 타임아웃 (orderId={}, {}분 경과)", order.getId(), elapsed.toMinutes());
                    try {
                        cancelOrder(order.getId());
                        recordTradeLog(order.getId(), "TIMEOUT", "SUBMITTED", "CANCELLED",
                                "제출 후 5분 경과 자동 취소");
                    } catch (Exception e) {
                        log.error("타임아웃 취소 실패 (orderId={}): {}", order.getId(), e.getMessage());
                    }
                    continue;
                }
            }

            // 거래소 상태 동기화
            checkOrderStatus(order.getId());
        }
    }

    // ── 체결 처리 ──────────────────────────────────────────────

    /**
     * 체결 완료 시 포지션 업데이트
     */
    @Transactional
    public void processFilledOrder(OrderEntity order) {
        log.info("체결 처리 시작 (orderId={}, {} {} {})", order.getId(),
                order.getCoinPair(), order.getSide(), order.getFilledQuantity());

        if ("BUY".equalsIgnoreCase(order.getSide())) {
            handleBuyFill(order);
        } else if ("SELL".equalsIgnoreCase(order.getSide())) {
            handleSellFill(order);
        }
    }

    /**
     * 체결 콜백이 반영할 포지션 조회.
     *
     * <p>{@code positionId} 가 있으면 그것이 유일한 정답이다. 없는 경우(구주문·수동주문)에만
     * 코인으로 역추적하는데, 이때 <b>같은 코인을 여러 세션이 동시에 보유</b>할 수 있으므로
     * 반드시 {@code (session_kind, session_id)} 로 범위를 좁힌다. 좁히지 않으면 다른 세션의
     * 포지션에 체결 수량이 얹혀 그 세션의 평균단가·손익이 오염된다.
     * 세션 정보조차 없는 전역 주문은 마지막 수단으로 코인 단건 매칭하되, 후보가 여러 개면
     * 어느 것도 고르지 않는다(오염보다 미반영이 안전 — 고아 정리 안전망이 처리한다).</p>
     */
    private Optional<PositionEntity> findPositionForOrder(OrderEntity order) {
        if (order.getPositionId() != null) {
            return positionRepository.findById(order.getPositionId());
        }

        if (order.getSessionId() != null && order.getSessionKind() != null) {
            return positionRepository.findBySessionKindAndSessionIdAndCoinPairAndStatus(
                    order.getSessionKind(), order.getSessionId(), order.getCoinPair(), "OPEN");
        }

        List<PositionEntity> candidates =
                positionRepository.findAllByCoinPairAndStatus(order.getCoinPair(), "OPEN");
        if (candidates.size() > 1) {
            log.warn("포지션 역추적 모호 — {} OPEN 포지션 {}건, positionId 없는 주문이라 매칭 포기 (orderId={})",
                    order.getCoinPair(), candidates.size(), order.getId());
            return Optional.empty();
        }
        return candidates.stream().findFirst();
    }

    private void handleBuyFill(OrderEntity order) {
        BigDecimal filledQty = order.getFilledQuantity();
        if (filledQty == null || filledQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("BUY 체결 처리 건너뜀: filledQty={} (orderId={})", filledQty, order.getId());
            return;
        }

        // 평균 체결 단가 계산
        // - MARKET(price 타입) 매수: executedFunds(실제 체결에 사용된 KRW) / executed_volume 이 정확한 평균단가.
        //   부분 체결 후 취소된 경우 order.getQuantity()(원래 주문 KRW 총액)로 나누면 실제보다 낮은 체결량
        //   대비 총액이 부풀려져 평균단가가 실제의 배수로 부풀려진다 → 즉시 허위 손절 유발(2026-07-02 감사).
        //   executedFunds 미확보(구주문 등) 시에만 quantity로 폴백.
        // - LIMIT 매수: order.getPrice() = 지정 단가
        BigDecimal avgFillPrice;
        if ("MARKET".equalsIgnoreCase(order.getOrderType())) {
            BigDecimal fundsUsed = order.getExecutedFunds() != null
                    ? order.getExecutedFunds() : order.getQuantity();
            avgFillPrice = fundsUsed.divide(filledQty, 8, RoundingMode.HALF_UP);
        } else {
            avgFillPrice = order.getPrice();
        }

        // positionId가 있으면 해당 포지션을 직접 조회 (세션 데이터 혼재 방지)
        Optional<PositionEntity> existingPos = findPositionForOrder(order);

        if (existingPos.isPresent()) {
            // 기존 포지션에 체결가/수량 반영 (평균 단가 갱신)
            PositionEntity pos = existingPos.get();
            BigDecimal oldTotal = pos.getAvgPrice().multiply(pos.getSize());
            BigDecimal newTotal = avgFillPrice.multiply(filledQty);
            BigDecimal totalSize = pos.getSize().add(filledQty);
            BigDecimal newAvgPrice = oldTotal.add(newTotal)
                    .divide(totalSize, 8, RoundingMode.HALF_UP);
            pos.setAvgPrice(newAvgPrice);
            pos.setSize(totalSize);
            // 매수 수수료 명시 계상 — 기존에는 positionFee가 매도 수수료만 반영해 대시보드
            // "총 수수료" 합계가 실제의 절반 수준으로 과소 표시됐다 (2026-07-02 감사 D-4).
            // 세션 KRW 자체는 investAmount(quantity)를 매수 시점에 이미 전액 차감했고 Upbit가
            // 그 안에서 수수료를 제하고 코인을 지급하므로 잔고 드리프트는 없음 — 이건 순수 리포팅 보강.
            BigDecimal buyFee = newTotal.multiply(BUY_FEE_RATE);
            BigDecimal accumulatedFee = pos.getPositionFee() != null ? pos.getPositionFee() : BigDecimal.ZERO;
            pos.setPositionFee(accumulatedFee.add(buyFee));
            positionRepository.save(pos);
            log.info("BUY 체결 반영: posId={}, 평균단가={}, 수량={}", pos.getId(), pos.getAvgPrice(), pos.getSize());

            // 부분 체결 후 취소 시 미사용 KRW 복원
            // executedFunds(실제 사용 KRW) < quantity(원래 차감 KRW) 이면 차액을 session에 반환.
            // sessionKind로 LIVE/DYNAMIC 세션 테이블을 명확히 구분한다 — 미구분 시 두 세션 테이블이
            // 같은 sessionId를 가질 경우 엉뚱한 세션의 잔고를 복원할 수 있었다 (2026-07-02).
            if (order.getSessionId() != null
                    && order.getExecutedFunds() != null
                    && order.getQuantity() != null
                    && order.getExecutedFunds().compareTo(order.getQuantity()) < 0) {
                BigDecimal unusedKrw = order.getQuantity().subtract(order.getExecutedFunds());
                if ("DYNAMIC".equals(order.getSessionKind())) {
                    if (dynamicBalanceUpdater != null) {
                        dynamicBalanceUpdater.apply(order.getSessionId(),
                                s -> s.setAvailableKrw(s.getAvailableKrw().add(unusedKrw)));
                        log.info("부분 체결 후 미사용 KRW 복원 (동적, orderId={}, sessionId={}, 복원={})",
                                order.getId(), order.getSessionId(), unusedKrw);
                    }
                } else if (sessionRepository != null) {
                    sessionRepository.findById(order.getSessionId()).ifPresent(session -> {
                        session.setAvailableKrw(session.getAvailableKrw().add(unusedKrw));
                        sessionRepository.save(session);
                        log.info("부분 체결 후 미사용 KRW 복원 (orderId={}, sessionId={}, 복원={})",
                                order.getId(), order.getSessionId(), unusedKrw);
                    });
                }
            }

            if (order.getSessionId() != null && telegramService != null) {
                telegramService.bufferTradeEvent(
                        "세션#" + order.getSessionId(), order.getCoinPair(), "BUY",
                        avgFillPrice, filledQty, buyFee, null, order.getSignalReason());
            }

            // §14 drift 기록 — 신호 시점 가격(order.signalPrice) vs 실제 평균 체결가.
            // signalPrice 미보존 주문(V54 이전 생성분 등)은 record()가 생략 처리한다.
            if (order.getSessionId() != null && executionDriftTracker != null
                    && !"DYNAMIC".equals(order.getSessionKind()) && sessionRepository != null) {
                sessionRepository.findById(order.getSessionId()).ifPresent(s ->
                        executionDriftTracker.record(
                                order.getSessionId(), order.getSessionKind(), order.getCoinPair(),
                                s.getStrategyType(),
                                "BUY", order.getSignalPrice(), avgFillPrice, Instant.now()));
            }
        } else {
            // positionId 없는 비세션 주문만 새 포지션 생성.
            //
            // ruleset_hash(V71)를 찍지 않는다 — 세션이 없으므로 적용된 매매 규칙이라는 것이
            // 존재하지 않는다. sessionId 가 null 이라 kill criteria 집계
            // (aggregateClosedTradesPerSession 의 sessionId IS NOT NULL 조건)에서도 제외된다.
            // 지문 없는 행을 만든다고 착각하지 말 것 — 표본에 들어가지 않는 수동/전역 포지션이다.
            PositionEntity pos = PositionEntity.builder()
                    .coinPair(order.getCoinPair())
                    .side("LONG")
                    .entryPrice(avgFillPrice)
                    .avgPrice(avgFillPrice)
                    .size(filledQty)
                    .status("OPEN")
                    .build();
            pos = positionRepository.save(pos);
            order.setPositionId(pos.getId());
            orderRepository.save(order);
            log.info("새 포지션 생성: posId={}, {} @ {}", pos.getId(), order.getCoinPair(), avgFillPrice);
        }
    }

    private void handleSellFill(OrderEntity order) {
        // 세션 연결 매도 주문은 LiveTradingService.reconcileClosingPositions()에서 처리
        // (KRW 복원·손익 확정·수수료 기록까지 실제 체결가 기반으로 일괄 처리)
        if (order.getSessionId() != null) {
            log.debug("세션 매도 주문 체결 — reconcileClosingPositions에서 처리됨 (orderId={}, sessionId={})",
                    order.getId(), order.getSessionId());
            return;
        }

        // positionId가 있으면 해당 포지션을 직접 조회 (세션 데이터 혼재 방지)
        Optional<PositionEntity> openPos = findPositionForOrder(order);

        if (openPos.isEmpty()) {
            log.warn("매도 체결이지만 열린 포지션 없음: {} (orderId={})", order.getCoinPair(), order.getId());
            return;
        }

        PositionEntity pos = openPos.get();
        BigDecimal soldQuantity = order.getFilledQuantity();
        if (soldQuantity == null || soldQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("SELL 체결 처리 건너뜀: filledQty={} (orderId={})", soldQuantity, order.getId());
            return;
        }
        BigDecimal remainingSize = pos.getSize().subtract(soldQuantity);

        // 실현 손익 계산 — order.getPrice()는 syncOrderState에서 세팅됨
        // market 매도: executedFunds/executedVolume 으로 역산한 단가 (gross 금액 기준)
        // 단가 미확보 시 pos.getAvgPrice()로 대체 (PnL = 0으로 처리)
        BigDecimal fillPrice = order.getPrice() != null ? order.getPrice() : pos.getAvgPrice();
        BigDecimal grossProceeds = fillPrice.multiply(soldQuantity);
        BigDecimal sellFee = grossProceeds.multiply(new BigDecimal("0.0005"));
        BigDecimal realizedPnl = grossProceeds.subtract(sellFee)
                .subtract(soldQuantity.multiply(pos.getAvgPrice()));
        pos.setRealizedPnl(pos.getRealizedPnl().add(realizedPnl));

        if (remainingSize.compareTo(BigDecimal.ZERO) <= 0) {
            // 전량 매도 → 포지션 종료
            pos.setSize(BigDecimal.ZERO);
            pos.setUnrealizedPnl(BigDecimal.ZERO);
            pos.setStatus("CLOSED");
            pos.setClosedAt(Instant.now());
            log.info("포지션 종료: posId={}, 실현손익={}", pos.getId(), realizedPnl);
        } else {
            // 부분 매도
            pos.setSize(remainingSize);
            log.info("부분 매도: posId={}, 잔여수량={}, 실현손익={}", pos.getId(), remainingSize, realizedPnl);
        }

        positionRepository.save(pos);
        order.setPositionId(pos.getId());
        orderRepository.save(order);

        // 체결 로그에 실현 손익 기록
        recordTradeLog(order.getId(), "FILL", null, null,
                String.format("{\"realizedPnl\": %s, \"fillPrice\": %s, \"fillQuantity\": %s}",
                        realizedPnl.toPlainString(), fillPrice.toPlainString(),
                        soldQuantity.toPlainString()));
    }

    // ── 거래소 연동 (UpbitRestClient 래핑) ─────────────────────

    private UpbitOrderClient.ExchangeResult submitToExchange(OrderEntity order) throws Exception {
        // BUY:  Upbit bid + price 타입 (총 KRW 금액 = quantity 필드에 KRW 금액이 설정된 경우)
        //       또는 bid + market 타입 (수량 기반 시장가)
        // SELL: Upbit ask + market 타입 (코인 수량)
        // LIMIT: bid/ask + limit 타입 (지정가)
        String upbitSide = "BUY".equalsIgnoreCase(order.getSide()) ? "bid" : "ask";
        String upbitOrderType;
        BigDecimal volume;
        BigDecimal price;

        if ("MARKET".equalsIgnoreCase(order.getOrderType())) {
            if ("bid".equals(upbitSide)) {
                // 시장가 매수: price 타입 — quantity 필드를 KRW 총액으로 사용
                upbitOrderType = "price";
                volume = null;
                price = order.getQuantity();
            } else {
                // 시장가 매도: market 타입 — Upbit 실제 잔고 기준으로 수량 결정
                // 이유: price-type 매수 체결량(executed_volume)은 코인별 매도 허용 단위를 초과할 수 있음.
                //       Upbit 잔고(account.balance)는 항상 해당 코인의 유효 단위로 반환되므로
                //       invalid_volume_ask 없이 전량 매도 가능.
                upbitOrderType = "market";
                price = null;
                volume = resolveAskVolume(order);
            }
        } else {
            // 지정가
            upbitOrderType = "limit";
            volume = order.getQuantity();
            price = order.getPrice();
        }

        // §10 rate limit — permit 확보 후 Upbit API 호출
        if (!rateLimiter.acquire()) {
            throw new RuntimeException("Upbit API rate limit 대기 타임아웃 — 주문 제출 재시도 필요");
        }
        return upbitOrderClient.createOrder(order.getCoinPair(), upbitSide, volume, price, upbitOrderType);
    }

    /**
     * 시장가 매도 수량 결정 — Upbit 실제 잔고 기준
     *
     * Upbit price-type 매수의 executed_volume은 코인별 매도 허용 단위를 초과할 수 있다.
     * (예: 8000 ÷ 3,437,000 = 0.00232761181... → 8자리 초과 또는 단위 불일치)
     * 계좌 잔고(account.balance)는 Upbit이 직접 반환하는 값이므로 항상 유효한 단위이다.
     * 포지션 수량과 잔고 중 작은 값을 사용해 과매도를 방지한다.
     */
    private BigDecimal resolveAskVolume(OrderEntity order) {
        BigDecimal positionVolume = order.getQuantity();
        String coinPair = order.getCoinPair();
        int dashIdx = coinPair.indexOf('-');
        String currency = dashIdx >= 0 ? coinPair.substring(dashIdx + 1) : coinPair;  // "KRW-ETH" → "ETH"

        try {
            List<AccountResponse> accounts = upbitOrderClient.getAccounts();
            Optional<AccountResponse> account = accounts.stream()
                    .filter(a -> currency.equals(a.getCurrency()))
                    .findFirst();

            if (account.isPresent()) {
                BigDecimal freeBalance = account.get().getBalance() != null
                        ? account.get().getBalance() : BigDecimal.ZERO;
                BigDecimal lockedBalance = account.get().getLocked() != null
                        ? account.get().getLocked() : BigDecimal.ZERO;
                BigDecimal totalHeld = freeBalance.add(lockedBalance);

                if (freeBalance.compareTo(BigDecimal.ZERO) <= 0 && lockedBalance.compareTo(BigDecimal.ZERO) > 0) {
                    // ETH가 pending 주문에 잠겨있는 경우 — 중복 매도 시도 차단
                    log.warn("매도 불가: {} 잔고 전량 잠김 (locked={}, free={}). 기존 pending 주문 확인 필요.",
                            currency, lockedBalance, freeBalance);
                    throw new IllegalStateException(
                            currency + " 잔고가 전량 주문 잠금 상태입니다 (locked=" + lockedBalance + "). 중복 매도 방지.");
                }

                if (freeBalance.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal volume = freeBalance.min(positionVolume);
                    log.info("매도 수량 결정: 포지션={}, 가용잔고={}, 잠금잔고={}, 사용={} ({})",
                            positionVolume, freeBalance, lockedBalance, volume, currency);
                    return volume;
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("업비트 잔고 조회 실패, 포지션 수량 사용 ({}): {}", currency, e.getMessage());
        }

        // 폴백: setScale(8) 으로 소수점 정리
        return positionVolume.setScale(8, RoundingMode.DOWN);
    }

    private OrderResponse queryExchangeOrder(String exchangeOrderId) throws Exception {
        return upbitOrderClient.getOrder(exchangeOrderId);
    }

    private void cancelOnExchange(String exchangeOrderId) throws Exception {
        // §10 rate limit — permit 확보 후 Upbit API 호출
        if (!rateLimiter.acquire()) {
            log.warn("Upbit API rate limit 대기 타임아웃 — 취소 재시도 필요 (orderId={})", exchangeOrderId);
        }
        upbitOrderClient.cancelOrder(exchangeOrderId);
    }

    // ── 상태 동기화 ────────────────────────────────────────────

    private void syncOrderState(OrderEntity order, OrderResponse exchangeStatus) {
        String newState = mapExchangeState(exchangeStatus.getState());
        String currentState = order.getState();

        if (currentState.equals(newState)) {
            return; // 변경 없음
        }

        // 🔴 거래소 보고 원본을 **모든 응답에서** 보존한다 (2026-10-07, V81).
        //
        //    종전에는 `executed_funds` 를 "부분 체결 후 취소" 분기에서만 저장했고 수수료는 어디에도
        //    저장하지 않았다. 그래서 정상 전량 체결에서 `executedFunds` 가 null 로 남고, 시장가 매수의
        //    평균단가가 `quantity`(요청 KRW) 폴백을 타며, 미사용 KRW 복원 조건도 성립하지 않았다.
        //    수수료는 포지션에 **우리가 계산한 추정값**만 쌓였다.
        //
        //    🔴 여기서는 **원본만 기록한다.** 회계(평균단가·잔액·포지션 수수료)에는 아직 연결하지
        //    않는다 — 연결은 차분 정산 규칙과 함께 와야 하고, 그 전에 연결하면 기존 금액 경로가
        //    조용히 바뀐다. `reported_*` 는 "거래소가 말한 값", `applied_*` 는 "회계에 반영한 값" 이다.
        //    🔴 NULL 을 0 으로 바꾸지 않는다 — 미보고와 0원은 다르다.
        BigDecimal reportedFunds = exchangeStatus.resolveExecutedFunds();
        if (reportedFunds != null) {
            order.setReportedExecutedFunds(reportedFunds);
        }
        if (exchangeStatus.getPaidFee() != null) {
            order.setReportedPaidFee(exchangeStatus.getPaidFee());
        }

        // 체결 수량 업데이트
        if (exchangeStatus.getExecutedVolume() != null) {
            order.setFilledQuantity(exchangeStatus.getExecutedVolume());
        }

        if ("FILLED".equals(newState)) {
            // 체결가 확정 — 시장가 매도는 executed_funds/executed_volume 로 평균 단가 산출
            applyFillPrice(order, exchangeStatus);
            // ⚠️ 체결가 미확보 SELL 은 FILLED 확정 보류 — 상태를 SUBMITTED 로 유지(여기서 return)하여
            //    다음 폴링(5초)에서 trades 정산 후 재시도한다. avgPrice 대체로 인한 "가짜 본전" 방지.
            if ("SELL".equalsIgnoreCase(order.getSide()) && order.getPrice() == null) {
                log.warn("매도 체결(done)이나 체결가 미정산 — FILLED 보류, 폴러 재조회 (orderId={}, uuid={})",
                        order.getId(), exchangeStatus.getUuid());
                return;
            }
            order.setFilledAt(Instant.now());
        }

        // CANCELLED지만 일부 체결된 경우 (부분 체결 후 취소) — BUY/SELL 공통.
        // Upbit: state=cancel + executed_volume>0 → 실제로 체결된 수량이 있으므로 체결 처리해야 한다.
        // SELL 쪽을 누락하면 절반만 팔린 매도가 CANCELLED로만 기록되어 포지션이 전체 수량으로
        // OPEN 롤백되고, 이미 팔린 절반의 실현손익은 어디에도 기록되지 않는 정합성 구멍이 생긴다
        // (2026-07-02 감사 D-3).
        boolean partialFill = "CANCELLED".equals(newState)
                && order.getFilledQuantity() != null
                && order.getFilledQuantity().compareTo(BigDecimal.ZERO) > 0;

        if (partialFill) {
            log.warn("부분 체결 후 취소 감지 (orderId={}, side={}, executed_volume={}, executed_funds={}): CANCELLED → FILLED 처리",
                    order.getId(), order.getSide(), order.getFilledQuantity(), exchangeStatus.getExecutedFunds());
            // 실제 사용된 KRW 저장 — handleBuyFill()에서 미사용 KRW 복원에 사용
            BigDecimal executedFunds = exchangeStatus.resolveExecutedFunds();
            if (executedFunds != null) {
                order.setExecutedFunds(executedFunds);
            }
            // SELL: 체결가가 없으면 finalizeSellPosition/finalizeDynamicSell이 처리 불가 — 여기서 확정 시도.
            if ("SELL".equalsIgnoreCase(order.getSide()) && order.getPrice() == null) {
                applyFillPrice(order, exchangeStatus);
                if (order.getPrice() == null) {
                    log.warn("부분 체결 매도 취소 — 체결가 미확보로 FILLED 보류, 폴러 재조회 (orderId={})", order.getId());
                    return;
                }
            }
            order.setFilledAt(Instant.now());
            transitionState(order, "FILLED", null);
            processFilledOrder(order);
            return;
        }

        transitionState(order, newState, null);

        // 체결 완료 시 포지션 처리
        if ("FILLED".equals(newState)) {
            processFilledOrder(order);
        }
    }

    /**
     * 체결가(평균 단가)를 주문에 반영한다. 즉시 체결 경로와 폴러 동기화 경로가 공통 사용.
     *
     * <ul>
     *   <li>매도(ask): 실제 평균 체결가 = executed_funds / executed_volume.
     *       정산 전이라 executed_funds 가 없으면 price 를 건드리지 않는다(null 유지) →
     *       호출자가 FILLED 확정을 보류하고 재조회하도록 한다.
     *       limit 매도이면서 funds 미정산 시에는 지정가(price)로 대체.</li>
     *   <li>지정가 매수(limit/bid): 거래소 지정 단가(price).</li>
     *   <li>시장가 매수(price/bid): order.price = 원래 KRW 총액 유지 (handleBuyFill 에서 재계산).</li>
     * </ul>
     */
    private void applyFillPrice(OrderEntity order, OrderResponse resp) {
        // executed_funds 가 최상위에 없으면 trades[] 합산으로 산출 (시장가 매도 정산 신뢰 소스)
        BigDecimal executedFunds = resp.resolveExecutedFunds();
        boolean hasFunds = executedFunds != null
                && resp.getExecutedVolume() != null
                && resp.getExecutedVolume().compareTo(BigDecimal.ZERO) > 0;
        if ("ask".equalsIgnoreCase(resp.getSide())) {
            if (hasFunds) {
                order.setPrice(executedFunds
                        .divide(resp.getExecutedVolume(), 8, RoundingMode.HALF_UP));
            } else if ("limit".equalsIgnoreCase(resp.getOrdType()) && resp.getPrice() != null) {
                order.setPrice(resp.getPrice());
            }
            // market 매도 + funds 미정산 → price 는 null 유지 (호출자가 재조회)
        } else if ("limit".equalsIgnoreCase(resp.getOrdType()) && resp.getPrice() != null) {
            order.setPrice(resp.getPrice());
        }
        // 시장가 매수(price/bid)는 order.price 그대로 유지
    }

    /**
     * Upbit 주문 상태를 내부 상태로 매핑
     * Upbit: wait, watch, done, cancel
     * 내부: PENDING, SUBMITTED, PARTIAL_FILLED, FILLED, CANCELLED, FAILED
     */
    private String mapExchangeState(String upbitState) {
        if (upbitState == null) return "PENDING";
        return switch (upbitState.toLowerCase()) {
            case "wait", "watch" -> "SUBMITTED";
            case "done" -> "FILLED";
            case "cancel" -> "CANCELLED";
            default -> "PENDING";
        };
    }

    // ── 상태 전이 + 로그 ──────────────────────────────────────

    private void transitionState(OrderEntity order, String newState, String failedReason) {
        String oldState = order.getState();
        order.setState(newState);

        if ("FAILED".equals(newState) && failedReason != null) {
            order.setFailedReason(failedReason);
        }

        orderRepository.save(order);
        recordTradeLog(order.getId(), "STATE_CHANGE", oldState, newState, failedReason);

        // §16 — 주문 상태 전이 카운터 (Prometheus: order_state_transition_total{from,to})
        if (meterRegistry != null) {
            Counter.builder("order.state.transition")
                    .tag("from", oldState != null ? oldState : "UNKNOWN")
                    .tag("to", newState)
                    .description("주문 상태 전이 횟수")
                    .register(meterRegistry)
                    .increment();
        }

        log.info("주문 상태 전이 (orderId={}): {} → {}{}", order.getId(), oldState, newState,
                failedReason != null ? " (" + failedReason + ")" : "");
    }

    private void recordTradeLog(Long orderId, String eventType, String oldState,
                                 String newState, String detail) {
        TradeLogEntity logEntry = TradeLogEntity.builder()
                .orderId(orderId)
                .eventType(eventType)
                .oldState(oldState)
                .newState(newState)
                .detailJson(toJsonDetail(detail))
                .build();
        tradeLogRepository.save(logEntry);
    }

    /**
     * detail_json 컬럼은 JSONB 타입이므로 plain string은 JSON으로 감싸야 한다.
     * '{' 또는 '['로 시작하면 이미 JSON으로 간주하고 그대로 사용한다.
     */
    private String toJsonDetail(String detail) {
        if (detail == null) return null;
        String trimmed = detail.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        try {
            return objectMapper.writeValueAsString(Map.of("message", trimmed));
        } catch (Exception e) {
            return "{\"message\":\"" + trimmed.replace("\"", "'") + "\"}";
        }
    }
}
