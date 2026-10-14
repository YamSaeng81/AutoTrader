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
    /**
     * 활성 주문 상태 — <b>자금·슬롯을 점유한다</b>.
     *
     * <p>🔴 V84 에서 {@code INTENT_READY}·{@code SUBMITTING}·{@code UNKNOWN} 을 넣었다.
     * 특히 {@code UNKNOWN} 은 <b>접수됐을 수 있으므로</b> 점유로 본다 — 종전에는 그 주문이
     * {@code FAILED} 가 돼 자금·슬롯이 풀렸고, 실제로 체결됐다면 쓰인 돈을 되돌리는 셈이었다.</p>
     *
     * <p>{@code PENDING} 은 V84 이전 주문을 위해 남긴다 — 신규 주문은 {@code INTENT_READY} 로
     * 시작한다.</p>
     */
    private static final List<String> ACTIVE_STATES = List.of(
            "PENDING", "INTENT_READY", "SUBMITTING", "UNKNOWN", "SUBMITTED", "PARTIAL_FILLED");

    /**
     * 이 프로세스의 식별자 — 기동마다 새로 만든다 (V86).
     *
     * <p>🔴 <b>철회의 정당성은 시간이 아니라 소유자의 생존으로 정한다.</b> V84 는 60초가 지난
     * {@code INTENT_READY} 를 철회했는데, 그것은 "오래 걸렸을 뿐 살아 있는 작업" 도 철회한다 —
     * 시간 기반 철회다. 조건부 전환은 경합을 해결할 뿐 철회가 옳은지는 정하지 않는다.</p>
     *
     * <p>의도를 claim 할 수 있는 것은 그 의도를 만든 프로세스의 작업뿐이다. 그 작업이 존재하지
     * 않음이 <b>확정될 때만</b> 철회한다:</p>
     * <ul>
     *   <li>{@link #WITHDRAWN_OWNER_LOST} — 소유자가 지금 프로세스가 아니다. 작업 큐는 프로세스와 함께
     *       사라졌다. 🔴 <b>단일 인스턴스 전제</b> — 여러 인스턴스면 생존 신호가 필요하다.</li>
     *   <li>{@link #WITHDRAWN_DISPATCH_LOST} — 소유자는 지금 프로세스지만 그 의도를 맡은 작업이
     *       {@link #DISPATCHING} 에 없다(디스패치 거절·claim 전 예외로 끝났다).</li>
     *   <li>{@link #WITHDRAWN_STOPPED} — 작업 자신이 claim 직전 세션 잠금 안에서 정지를 봤다.</li>
     * </ul>
     *
     * <p>🔴 <b>경과 시간은 경보에만 쓴다</b>({@link #STALE_INTENT_ALERT}). 살아 있는 작업이 맡은 의도는
     * 얼마나 오래됐든 철회하지 않는다.</p>
     */
    static final String INSTANCE_ID = "inst-" + java.util.UUID.randomUUID();

    /**
     * claim 을 시도할 작업이 <b>살아 있는</b> 의도 — 이 프로세스 안의 소유권 등록부 (V86).
     *
     * <p>의도가 커밋되기 <b>전에</b> 넣고(복구가 보기 전에 등록돼 있어야 한다), claim 시도가 끝난 뒤
     * 뺀다. 트랜잭션이 롤백되면 뺀다.</p>
     */
    private static final java.util.Set<Long> DISPATCHING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 살아 있는 작업이 맡은 의도가 이만큼 claim 되지 않으면 사람에게 알린다 — 철회하지 않는다. */
    private static final Duration STALE_INTENT_ALERT = Duration.ofSeconds(60);

    /** {@code FAILED} 의 근거 (V86) — 거래소 거절과 우리 철회를 섞지 않는다. */
    public static final String WITHDRAWN_STOPPED = "WITHDRAWN_STOPPED";
    public static final String WITHDRAWN_OWNER_LOST = "WITHDRAWN_OWNER_LOST";
    public static final String WITHDRAWN_DISPATCH_LOST = "WITHDRAWN_DISPATCH_LOST";
    public static final String RISK_REJECTED = "RISK_REJECTED";
    public static final String CLIENT_UNAVAILABLE = "CLIENT_UNAVAILABLE";
    /**
     * 거래소의 명시적 거절 (2026-10-14부터 기록한다). 오류 의미가 주문 생성 전 검증·인증 거절이고 <b>식별자 조회로 주문이
     * 없음을 확인한 경우에만</b> 쓴다({@code SubmitRejection}). 그 밖의 제출 실패는 {@code UNKNOWN} 이다.
     */
    public static final String EXCHANGE_REJECTED = "EXCHANGE_REJECTED";
    /** 실자금 공유 풀 세션의 지정가 — 정산 미구현이라 받지 않는다 (2026-10-14). */
    public static final String ORDER_TYPE_UNSUPPORTED = "ORDER_TYPE_UNSUPPORTED";

    private static String unsupportedOrderTypeMessage(OrderRequest r) {
        return "🔴 실자금 세션의 지정가 주문은 받지 않는다 — 예약·체결 정산이 시장가 전제다 (sessionKind="
                + r.getSessionKind() + ", sessionId=" + r.getSessionId() + ", side=" + r.getSide()
                + ", type=" + r.getOrderType() + ")";
    }

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

    /** 단일 인스턴스 잠금 — claim 직전 확인 (2026-10-14). */
    @Autowired(required = false)
    private com.cryptoautotrader.api.config.SingleInstanceGuard singleInstanceGuard;

    /** 매수 예약의 직렬화 지점 (V85). 🔴 호출자의 트랜잭션 안에서 불러야 한다. */
    @Autowired(required = false)
    private CapitalReservationService capitalReservationService;

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

    /**
     * 제출 단계의 트랜잭션 경계 (V84).
     *
     * <p>🔴 {@code submitOrder} 에서 {@code @Transactional} 을 뗐으므로 A·B·D 는 각자 경계를 가져야
     * 한다. {@code @Transactional} 메서드로 쪼개면 <b>자기 호출이 프록시를 우회해 조용히 무시된다</b>
     * — 그 결함을 2026-03-17 에 이미 한 번 겪었다({@code PositionStateTransition} 참조). 그래서
     * 템플릿을 쓴다.</p>
     *
     * <p>{@code REQUIRED} 다. 이 메서드는 {@code @Async} 스레드에서 돌아 바깥 트랜잭션이 없으므로
     * 새로 열리고, 혹시 호출자가 트랜잭션을 들고 있다면 <b>참여</b>한다 — {@code REQUIRES_NEW} 로
     * 하면 주문 행을 만든 트랜잭션이 정지돼 같은 주문이 두 행으로 저장된다(2026-10-07 측정).</p>
     */
    private final org.springframework.transaction.support.TransactionTemplate submitTx;

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
        this.submitTx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.submitTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED);
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
     * 🔴 <b>{@code @Transactional} 을 떼고 A·B·C·D 로 나눴다</b> (2026-10-12, V84).
     *
     * <p>종전에는 이 메서드 전체가 한 트랜잭션이었고 거래소 호출이 그 <b>안에</b> 있었다. 그래서
     * <b>외부에 나간 사실이 커밋된 적이 없다</b> — 호출이 시간 초과되면 트랜잭션은 롤백되거나
     * {@code FAILED} 로 끝나고, UUID 는 응답에만 오므로 <b>그 주문을 물어볼 단서가 사라진다.</b>
     * 접수됐는지 아닌지 확인할 방법이 없는데 자금과 슬롯은 풀렸다.</p>
     *
     * <table>
     *   <tr><th>단계</th><th>경계</th><th>내용</th></tr>
     *   <tr><td>A</td><td>트랜잭션 + 세션 행 잠금</td>
     *       <td>중복·한도 확인 → 주문 생성({@code INTENT_READY}) + <b>식별자 커밋</b></td></tr>
     *   <tr><td>B</td><td>트랜잭션 + 세션 행 잠금</td>
     *       <td>{@code INTENT_READY} → {@code SUBMITTING} <b>조건부 전환(claim)</b>. 세션 상태 재확인</td></tr>
     *   <tr><td>C</td><td><b>트랜잭션 밖</b></td>
     *       <td>거래소 호출. B 가 <b>커밋된 뒤에만</b> 일어난다</td></tr>
     *   <tr><td>D</td><td>트랜잭션</td>
     *       <td>{@code SUBMITTED} / {@code UNKNOWN} / {@code FAILED} 기록</td></tr>
     * </table>
     *
     * <h3>🔴 정지 보장의 범위가 좁아졌다</h3>
     *
     * <p>종전 가드는 거래소 호출을 트랜잭션 안에서 하며 세션 행 잠금을 쥐고 있었으므로
     * <b>"정지 커밋 후 제출 0회"</b> 가 성립했다. 외부 호출이 밖으로 나가면 claim 커밋과 외부 호출
     * 사이에 정지가 끼어들 수 있고, <b>이미 claim 된 주문이 나가는 것은 막을 방법이 없다</b> —
     * 접수 가능성이 있기 때문이다. 보장은 이렇게 바뀐다:</p>
     *
     * <pre>
     * 종전: 정지 커밋 후 **제출** 0회
     * 이후: 정지 커밋 후 **신규 claim** 0회 + 이미 claim 된 주문의 추적·정리 지속
     * </pre>
     *
     * <p>바꾼 이유는 <b>추적 불가가 더 큰 위험</b>이기 때문이다. 제출을 막지 못한 주문은 식별자로
     * 추적되지만, 추적 단서가 없는 주문은 영구히 확인할 수 없다. 그 대가로 정지는 "신규 승인 차단"
     * 까지만 보장하고, 미해결 주문이 남은 세션을 <b>정리 완료로 표시하지 않는다.</b></p>
     *
     * <p>⚠️ 신규 생성한 포지션을 참조하는 매수 주문이라면 이 메서드를 직접 부르지 말고
     * {@link #submitOrderAfterCommit}을 사용할 것 — 이유는 그 Javadoc 참조.</p>
     */
    @Async("orderExecutor")
    public void submitOrder(OrderRequest request) {
        log.info("주문 제출 요청: {} {} {} 수량={}", request.getCoinPair(), request.getSide(),
                request.getOrderType(), request.getQuantity());

        // A — 의도·식별자를 **커밋한다**. 이 커밋 이후의 모든 실패는 "나갔을 수 있다" 로 다룬다.
        Long orderId = submitTx.execute(st -> prepareIntent(request));
        if (orderId == null) {
            return;
        }

        // B — claim. 🔴 1행을 돌려받고 **이 트랜잭션이 커밋에 성공한** 경우에만 C 로 간다.
        if (!claimOwned(orderId, request)) {
            return;
        }

        // C·D — 외부 호출은 **트랜잭션 밖**이고, 결과 기록만 트랜잭션이다.
        callExchangeAndRecord(orderId);
    }

    /**
     * B 를 소유자로서 시도하고, 시도가 끝나면 소유권을 내려놓는다 (V86).
     *
     * <p>🔴 claim 자체가 예외로 끝나면 이 의도를 claim 할 작업이 사라진다 — 같은 프로세스 안에서는
     * 복구가 소유자 생존으로 판단하므로, 여기서 <b>스스로 철회를 시도한다</b>(조건부라 claim 이
     * 실제로 커밋됐다면 0행이다). 철회마저 실패하면 등록부에서 빠졌으므로 복구가 이어받는다.</p>
     */
    private boolean claimOwned(Long orderId, OrderRequest request) {
        try {
            Boolean claimed = submitTx.execute(st -> claimForSubmission(orderId, request));
            return Boolean.TRUE.equals(claimed);
        } catch (RuntimeException e) {
            log.error("claim 시도가 예외로 끝났다 — 철회를 시도한다 (orderId={}): {}", orderId, e.getMessage(), e);
            try {
                submitTx.execute(st -> withdrawIntent(orderId, WITHDRAWN_DISPATCH_LOST,
                        "claim 시도 중 예외 — 제출하지 않았다"));
            } catch (RuntimeException again) {
                log.error("철회도 실패했다 — 복구가 이어받는다 (orderId={}): {}", orderId, again.getMessage());
            }
            return false;
        } finally {
            DISPATCHING.remove(orderId);
        }
    }

    /**
     * 디스패치되지 못한 의도를 철회한다 (V86) — 호출자가 {@link #submitPreparedOrder} 를 넘기지 못했을 때.
     *
     * <p>🔴 예: 주문 실행기 큐가 가득 차 작업이 거절됐다. 그 의도를 claim 할 작업은 생기지 않는다.
     * 조건부 전환이므로 이미 claim 됐다면 0행이고 아무것도 바꾸지 않는다.</p>
     *
     * @return 이 호출이 철회에 성공했으면 {@code true}
     */
    public boolean withdrawUndispatchedIntent(Long orderId, String why) {
        try {
            // 🔴 REQUIRES_NEW 다 — 이 메서드는 커밋 후 훅(afterCommit)에서도 불린다. 그 시점에는 끝난
            //    트랜잭션의 자원이 아직 묶여 있어 REQUIRED 로 열면 그 트랜잭션에 "참여" 하고 커밋되지 않는다.
            return Boolean.TRUE.equals(syncTx.execute(st ->
                    withdrawIntent(orderId, WITHDRAWN_DISPATCH_LOST, why)));
        } finally {
            DISPATCHING.remove(orderId);
        }
    }

    /**
     * 철회 — 조건부 전환. 🔴 정당성은 호출자가 확정한다 — 여기서는 사유 코드를 남기고 경합만 가린다.
     *
     * @return 이 호출이 철회에 성공했으면 {@code true} (claim 은 실패한다)
     */
    private boolean withdrawIntent(Long orderId, String code, String reason) {
        int withdrawn = orderRepository.withdrawIntentIfStillReady(orderId, reason, code);
        if (withdrawn != 1) {
            log.info("철회가 성립하지 않았다 — 이미 claim 됐거나 종결됐다 (orderId={}, 사유={})", orderId, code);
            return false;
        }
        recordTradeLog(orderId, "STATE_CHANGE", "INTENT_READY", "FAILED", code + ": " + reason);
        log.warn("의도를 철회했다 — 외부에 나간 적이 없다 (orderId={}, 사유={}: {}). "
                + "🔴 예약 해제는 고아 매수 정리가 담당한다(FAILED 매수 + size=0 포지션).", orderId, code, reason);
        return true;
    }

    /**
     * 🔴 <b>예약과 의도를 한 트랜잭션으로 묶는다</b> (2026-10-12, V85) — 시험 11 의 수정.
     *
     * <p><b>호출자의 트랜잭션 안에서</b> 돌아, 계좌 잠금을 쥔 채 ① 한도 확인 ② 자금 예약
     * ③ 슬롯(포지션) ④ 주문 의도 저장을 함께 커밋한다. 네 가지가 함께 커밋되거나 함께 롤백된다.</p>
     *
     * <p>🔴 종전에는 §8 한도 확인이 <b>읽고 → 판단하고 → 쓰는</b> 사이에 직렬화가 없었고 차감은
     * 주문 제출 <b>뒤에</b> 일어났다. 실측으로 <b>승인 2건 / 예약 합계 6,000 &gt; 상한 5,000</b> 이
     * 재현됐다. 🔴 <b>거래소의 잔고 부족 거절은 안전장치가 아니다</b> — 계좌에 여유 자금이 있으면
     * 초과 주문도 접수되고, 그 응답이 유실되면 초과분이 불명으로 남는다.</p>
     *
     * <p>🔴 슬롯(포지션)은 <b>호출자가 같은 트랜잭션에서</b> 만들고 그 id 를 넘긴다. 여기서 만들지
     * 않는 이유는 포지션의 진입가·손절·익절이 전략 평가의 결과이고 이 클래스가 알 수 없기
     * 때문이다. 같은 트랜잭션이므로 원자성은 성립한다.</p>
     *
     * @return 생성된 주문 id, 한도·중복·정지로 거절되면 {@code null}
     */
    public Long reserveAndPrepareIntent(OrderRequest request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "예약·의도 저장은 호출자의 트랜잭션 안에서만 할 수 있다 — 잠금 수명이 짧으면 "
                            + "한도 확인과 의도 저장 사이가 벌어진다");
        }
        String sessionKind = request.getSessionKind() != null ? request.getSessionKind() : "LIVE";

        // 🔴 **잠금을 먼저 잡고 한도를 본다.** 중복·정지 확인은 그다음이다 — 순서를 바꾸면
        //    확인과 예약 사이가 다시 벌어진다.
        if (!"BUY".equalsIgnoreCase(request.getSide())
                || !("LIVE".equals(sessionKind) || "DYNAMIC".equals(sessionKind))
                || request.getSessionId() == null) {
            // 🔴 이 진입점은 **실자금 공유 풀 세션의 매수** 전용이다(LIVE·DYNAMIC). 다른 것을 넣으면
            //    예약 없이 통과하므로 막는다.
            throw new IllegalArgumentException("reserveAndPrepareIntent 는 실자금 세션 매수 전용이다: "
                    + request.getSide() + "/" + sessionKind);
        }
        if (!"MARKET".equalsIgnoreCase(request.getOrderType())) {
            // 🔴 지정가는 예약·정산이 시장가 전제라 받지 않는다 (2026-10-14) — 예약을 잡기 전에 거절한다.
            throw new ReservationRejected(ORDER_TYPE_UNSUPPORTED, unsupportedOrderTypeMessage(request));
        }
        if (capitalReservationService == null) {
            throw new IllegalStateException("예약 서비스가 없다 — 한도 확인 없이 승인하지 않는다");
        }
        CapitalReservationService.Decision decision = capitalReservationService.reserveForBuy(
                sessionKind, request.getSessionId(), request.getQuantity(), request.getPositionId());
        if (!decision.approved()) {
            // 🔴 **거절도 롤백한다.** 종전에는 null 을 돌려줘 호출자 트랜잭션이 그대로 커밋됐고,
            //    같은 트랜잭션에서 만든 슬롯(size=0 포지션)이 **차감 없이** 남았다. 그 포지션을
            //    고아 정리가 배정액으로 복원하면 **없던 돈이 생긴다.**
            throw new ReservationRejected(decision.code(), decision.message());
        }

        Long orderId = prepareIntent(request);
        if (orderId == null) {
            // 🔴 의도가 거절되면 **예약도 되돌려야 한다.** LIVE 는 호출자가 트랜잭션을 롤백하고,
            //    DYNAMIC 은 틱 트랜잭션이라 롤백하면 신호 기록까지 사라지므로 차감을 같은 트랜잭션
            //    안에서 정확히 되돌린다. 둘 다 해도 해롭지 않다(롤백되면 되돌림도 사라진다).
            capitalReservationService.releaseWithinTransaction(
                    sessionKind, request.getSessionId(), request.getQuantity());
            throw new ReservationRejected("INTENT_REJECTED", "주문 의도가 거절됐다 — 예약을 되돌린다");
        }
        orderRepository.findById(orderId).ifPresent(o -> {
            o.setReservedKrw(request.getQuantity());
            orderRepository.save(o);
        });
        return orderId;
    }

    /**
     * 예약·의도가 들어 있는 <b>호출자 트랜잭션이 커밋된 뒤에</b> 제출을 넘긴다 (2026-10-12, DYNAMIC).
     *
     * <p>🔴 틱 트랜잭션이 롤백되면 예약·슬롯·의도가 함께 사라지고 <b>제출은 0회</b>다 — 종전
     * {@link #submitOrderAfterCommit} 의 계약(롤백분 0회·정상분 1회)을 그대로 지킨다.</p>
     *
     * <p>🔴 실행기가 작업을 받지 못하면 <b>그 자리에서 철회</b>한다 — 그 의도를 claim 할 작업은 생기지
     * 않는다.</p>
     */
    public void submitPreparedOrderAfterCommit(Long orderId, OrderRequest request) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("커밋 후 제출은 트랜잭션 안에서만 등록할 수 있다 (orderId=" + orderId + ")");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                com.cryptoautotrader.api.support.CrashPoint.hit("AFTER_INTENT_COMMIT");
                try {
                    self.submitPreparedOrder(orderId, request);
                } catch (RuntimeException dispatchFailed) {
                    log.error("의도를 실행기에 넘기지 못했다 — 철회한다 (orderId={}): {}",
                            orderId, dispatchFailed.getMessage());
                    withdrawUndispatchedIntent(orderId,
                            "실행기 디스패치 실패 — 제출하지 않았다: " + dispatchFailed.getClass().getSimpleName());
                }
            }
        });
    }

    /**
     * 예약 트랜잭션을 되돌리기 위한 신호.
     *
     * <p>🔴 거절은 오류가 아니다. 그래서 호출자가 이것만 잡아 <b>조용히</b> 끝내고, 다른 예외는
     * 그대로 올라가게 한다.</p>
     */
    public static class ReservationRejected extends RuntimeException {
        private final String code;

        public ReservationRejected(String code, String message) {
            super(message);
            this.code = code;
        }

        /** 거절 사유 코드 — {@link CapitalReservationService} 의 상수 또는 {@code INTENT_REJECTED}. */
        public String getCode() {
            return code;
        }
    }

    /**
     * B·C·D — 이미 커밋된 의도를 claim 하고 제출한다 (V85).
     *
     * <p>🔴 A 는 호출자의 트랜잭션에서 이미 끝났다. 이 메서드는 <b>트랜잭션 없이</b> 시작해 B 와 D
     * 만 각자 경계를 갖는다.</p>
     */
    @Async("orderExecutor")
    public void submitPreparedOrder(Long orderId, OrderRequest request) {
        if (!claimOwned(orderId, request)) {
            return;
        }
        com.cryptoautotrader.api.support.CrashPoint.hit("AFTER_CLAIM_COMMIT");
        callExchangeAndRecord(orderId);
    }

    /**
     * A — 중복·리스크·정지 확인 후 {@code INTENT_READY} 로 <b>커밋</b>한다.
     *
     * @return 생성된 주문 id, 거절되면 {@code null}
     */
    private Long prepareIntent(OrderRequest request) {
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
            return null;
        }

        // 🔴 실자금 공유 풀 세션은 **시장가만** 받는다 (2026-10-14). 지정가는 발행 경로가 없고, 예약(`quantity` 를 원화로
        //    본다)·원자 체결 회계·매도 확정이 모두 시장가 전제다 — 들어오면 코인 수량을 원화로 예약하고 미사용 정산이
        //    틀린다. 정산을 구현하기 전까지는 식별자를 소비하기 전에 거절한다. 매수·매도 모두다.
        if (OrderSettlementService.isSharedPoolKind(sessionKind) && !"MARKET".equalsIgnoreCase(request.getOrderType())) {
            log.error(unsupportedOrderTypeMessage(request));
            return null;
        }

        // 🔴 2. 정지와의 순서를 구조로 강제한다 (2026-10-07) — **신규 진입(BUY)만**.
        //
        //    평가 루프가 세션을 RUNNING 으로 읽고 매수를 결정한 뒤, 그 제출이 **정지 커밋 이후에**
        //    거래소에 닿는 일이 실제로 일어났다(`LateOrderSubmissionAfterStopTest` 로 재현). 그러면
        //    사람은 정지시켰다고 보는데 새 포지션이 생기고, 정지된 세션에는 평가 루프가 없으므로
        //    **그 포지션을 청산할 주체가 없다.**
        //
        //    🔴 **매도는 절대 막지 않는다.** 정지·손절의 청산이 막히면 손실이 확대되고, 정지된
        //    세션의 포지션을 팔 수단이 사라진다. 신규 진입 차단과 기존 주문·포지션 정리는 별개
        //    상태이며 이 가드는 전자만 담당한다.
        //
        //    🔴 **의도를 만들기 전에 본다.** 거절될 주문의 식별자를 소비하지 않기 위해서다 —
        //    업비트 식별자는 재사용할 수 없다.
        if (!approveNewEntry(request, sessionKind)) {
            return null;
        }

        // 3. 주문 엔티티 생성 — 🔴 PENDING 이 아니라 INTENT_READY 다.
        //
        //    PENDING 은 "제출 전" 과 "제출했는지 모름" 을 구분하지 못했다. 재시작 복구가
        //    `PENDING` + `exchange_order_id IS NULL` 을 **미제출로 단정**해 FAILED 로 바꾸고 자금을
        //    풀던 근거가 그 모호함이다. INTENT_READY 는 "아직 claim 되지 않았으므로 나간 적이 없다"
        //    를 **상태로** 말한다.
        OrderEntity order = OrderEntity.builder()
                .coinPair(request.getCoinPair())
                .side(request.getSide())
                .orderType(request.getOrderType())
                .price(request.getPrice())
                .quantity(request.getQuantity())
                .state("INTENT_READY")
                .signalReason(request.getReason())
                .signalPrice(request.getSignalPrice())
                .sessionId(request.getSessionId())
                .sessionKind(sessionKind)
                .positionId(request.getPositionId())
                .clientOrderId(newClientOrderId())
                .intentCommittedAt(Instant.now())
                .intentOwner(INSTANCE_ID)
                // 🔴 새 회계 경로 참여를 **생성 시 명시한다** (2026-10-12, V82).
                //
                //    V81 의 정산 컬럼은 전부 NULL 로 시작한다. 전환 규칙이 "fee_source IS NULL 이면
                //    손대지 않는다"(= 전환 이전 데이터) 이므로, **신규 주문도 NULL 로 두면 새 경로에서
                //    영원히 빠진다.** 그래서 'PENDING'(참여하지만 아직 아무것도 반영하지 않았다)을
                //    박고 정산 버전을 0 으로 명시한다.
                //
                //    🔴 기존 NULL 주문은 그대로 둔다 — 자동 재정산 대상이 아니다.
                .feeSource(FEE_SOURCE_PENDING)
                .settlementVersion(0L)
                .settlementFinal(Boolean.FALSE)
                .settlementAttempts(0)
                .build();
        order = orderRepository.save(order);
        registerDispatching(order.getId());
        recordTradeLog(order.getId(), "STATE_CHANGE", null, "INTENT_READY",
                "주문 의도 생성 (식별자=" + order.getClientOrderId() + ")");

        // 4. 리스크 체크
        // - SELL: 손절 등 청산이 막히면 손실 확대 → 항상 허용
        // - 세션 BUY (positionId != null): LiveTradingService에서 이미 체크 + 포지션/KRW 처리 완료 → 재체크 생략
        // - 비세션 BUY (positionId == null): 리스크 체크 필요
        boolean needsRiskCheck = "BUY".equalsIgnoreCase(request.getSide())
                && request.getPositionId() == null;
        if (needsRiskCheck) {
            RiskCheckResult riskResult = riskManagementService.checkRisk();
            if (!riskResult.isApproved()) {
                log.warn("리스크 체크 거부 (orderId={}): {}", order.getId(), riskResult.getReason());
                order.setFailureCode(RISK_REJECTED);
                transitionState(order, "FAILED", riskResult.getReason());
                DISPATCHING.remove(order.getId());
                return null;
            }
        }

        if (upbitOrderClient == null) {
            log.warn("UpbitOrderClient 미등록 — 거래소 연동 불가 (orderId={})", order.getId());
            order.setFailureCode(CLIENT_UNAVAILABLE);
            transitionState(order, "FAILED", "거래소 클라이언트 미등록");
            DISPATCHING.remove(order.getId());
            return null;
        }

        return order.getId();
    }

    /**
     * 소유권 등록 — 의도가 <b>커밋되기 전에</b> 넣는다 (V86).
     *
     * <p>🔴 복구가 이 의도를 볼 수 있게 되는 시점(커밋)보다 먼저 등록돼 있어야 "작업 없음" 으로
     * 오판하지 않는다. 트랜잭션이 롤백되면 의도도 없으므로 뺀다.</p>
     */
    private void registerDispatching(Long orderId) {
        DISPATCHING.add(orderId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        DISPATCHING.remove(orderId);
                    }
                }
            });
        }
    }

    /** 이 프로세스에서 claim 을 시도할 작업이 살아 있는가 — 시험에서 상태를 확인하는 데 쓴다. */
    static boolean isDispatching(Long orderId) {
        return DISPATCHING.contains(orderId);
    }

    /**
     * 신규 진입 승인 — 세션 행을 <b>배타 잠금</b>으로 읽고 {@code RUNNING} 을 확인한다.
     *
     * <p>🔴 "제출 직전에 RUNNING 을 한 번 더 읽는" 것으로는 부족하다 — 재확인과 쓰기 사이에도 정지가
     * 끼어들 수 있다. 잠금은 이 트랜잭션이 끝날 때까지 유지되므로 정지 쪽의 세션 행 쓰기는 그동안
     * 블록되고, 정지가 먼저 커밋됐다면 여기서 {@code STOPPED} 를 보게 된다.</p>
     *
     * <p>🔴 측정으로 확인한 전제 (2026-10-07 {@code SubmitOrderBoundaryProbeTest}): 이 호출은
     * {@code order-exec-N} 스레드의 <b>자기 트랜잭션</b>에서 돈다 — 호출자({@code stopSession})의
     * 트랜잭션이 아니다. 여기에 {@code REQUIRES_NEW} 를 쓰면 주문 행을 만든 트랜잭션이 정지돼 그 행이
     * 보이지 않고, 분리 엔티티 저장이 INSERT 로 떨어져 <b>같은 주문이 두 행으로 저장됐다.</b></p>
     */
    private boolean approveNewEntry(OrderRequest request, String sessionKind) {
        // 🔴 LIVE·DYNAMIC 이 **같은 가드**를 지난다 (2026-10-12). 종전에는 LIVE 만 봤고 DYNAMIC 매수는
        //    정지·회계 미반영·미해결 제출 어느 것에도 막히지 않았다 — 예약은 공통화됐는데 그 앞의
        //    승인 조건이 갈려 있었다.
        if (!"BUY".equalsIgnoreCase(request.getSide()) || !guardedKind(sessionKind)
                || request.getSessionId() == null) {
            return true;
        }

        // 🔴 **회계 미반영 체결이 있으면 신규 매수를 막는다** (2026-10-12, V83).
        //
        //    ⓑ(로컬 회계를 한 트랜잭션으로) 를 택하면, 체결을 관측했지만 회계를 아직 반영하지
        //    못한 구간이 생긴다. 그동안 포지션 수량은 0 이므로 **슬롯 점유로 보이지 않고**
        //    평가 루프가 "포지션 없음" 으로 보고 재진입할 수 있다. 그것은 원자성의 단점이
        //    아니라 **가드의 공백**이므로 여기서 막는다.
        //
        //    🔴 **추적과 위험 축소는 막지 않는다** — 이 가드는 BUY 전용이고, 매도·청산·폴링은
        //    그대로 돈다.
        long pendingFills = orderRepository.countFillAccountingPending(sessionKind, request.getSessionId());
        if (pendingFills > 0) {
            log.warn("회계 미반영 체결이 있어 신규 매수를 승인하지 않는다 (sessionId={}, 미반영={}건)",
                    request.getSessionId(), pendingFills);
            return false;
        }

        // 🔴 **응답 불명 주문이 있으면 신규 매수를 막는다** (2026-10-12, V84).
        //
        //    불명 주문은 접수됐을 수 있으므로 자금과 슬롯을 점유한 것으로 본다. 그 위에 새로
        //    진입하면 한도를 초과한 노출이 되고, 나중에 체결이 확인되면 되돌릴 수 없다.
        //    🔴 "아마 실패했을 것" 으로 가정해 진입을 허용하는 것이 바로 종전의 결함이다.
        long unresolved = orderRepository.countUnresolvedSubmissions(sessionKind, request.getSessionId());
        if (unresolved > 0) {
            log.warn("미해결 제출이 있어 신규 매수를 승인하지 않는다 (sessionId={}, 미해결={}건)",
                    request.getSessionId(), unresolved);
            return false;
        }

        // 🔴 위험 설정 행이 없으면 신규 매수를 막는다 (2026-10-14) — 한도 판단의 근거가 없다. 청산은 막지 않는다.
        if (!riskManagementService.hasRiskConfig()) {
            log.error("risk_config 행이 없어 신규 매수를 승인하지 않는다 (sessionId={})", request.getSessionId());
            return false;
        }

        // 엔티티가 아니라 **상태 문자열만** 받는다 — 잠금은 필요하지만 엔티티 관리는 필요하지 않다.
        String lockedStatus = lockAndReadSessionStatus(sessionKind, request.getSessionId());
        if (!"RUNNING".equals(lockedStatus)) {
            log.warn("세션이 실행 중이 아니어서 신규 매수를 승인하지 않는다 (sessionId={}, status={})",
                    request.getSessionId(), lockedStatus == null ? "없음" : lockedStatus);
            return false;
        }
        return true;
    }

    /** 체결 회계를 원자 경로({@code OrderSettlementService.observeAndApplyBuyFill})로만 반영하는 주문인가. */
    private boolean isAtomicMarketBuy(OrderEntity order) {
        return orderSettlementService != null
                && order.getFeeSource() != null
                && "BUY".equalsIgnoreCase(order.getSide())
                && "MARKET".equalsIgnoreCase(order.getOrderType())
                && OrderSettlementService.isSharedPoolKind(order.getSessionKind());
    }

    /** 정지·회계 미반영·미해결 제출 가드의 대상 — 공유 풀 실자금 세션이고 그 세션 저장소가 있다. */
    private boolean guardedKind(String sessionKind) {
        return ("LIVE".equals(sessionKind) && sessionRepository != null)
                || ("DYNAMIC".equals(sessionKind) && dynamicSessionRepository != null);
    }

    /**
     * 세션 행을 배타 잠금으로 읽어 상태만 돌려준다. 정지 쓰기와 이 판정이 같은 행에서 직렬화된다.
     *
     * <p>🔴 DYNAMIC 정지({@code DynamicTradingService.stopSession})는 상태를 별도 연결로 쓴다. 그 쓰기와
     * 이 잠금은 같은 행이므로 순서가 정해진다 — 정지가 먼저 커밋됐으면 여기서 {@code STOPPED} 를 본다.</p>
     */
    private String lockAndReadSessionStatus(String sessionKind, Long sessionId) {
        java.util.Optional<String> status = "DYNAMIC".equals(sessionKind)
                ? dynamicSessionRepository.lockAndReadStatus(sessionId)
                : sessionRepository.lockAndReadStatus(sessionId);
        return status.orElse(null);
    }

    /**
     * B — claim. {@code INTENT_READY} → {@code SUBMITTING} 조건부 전환.
     *
     * <p>🔴 <b>A 와 B 사이에 정지가 들어왔으면 여기서 멈추고 철회한다</b> — 아직 나간 적이 없으므로
     * 철회가 안전하다. 그 판단의 근거는 세션 행 잠금 안에서 읽은 상태다.</p>
     *
     * @return 이 호출이 claim 을 얻었으면 {@code true}
     */
    private Boolean claimForSubmission(Long orderId, OrderRequest request) {
        String sessionKind = request.getSessionKind() != null ? request.getSessionKind() : "LIVE";

        // 🔴 세션 상태를 **다시** 확인한다. 미해결 제출 수는 보지 않는다 — 방금 만든 이 주문의
        //    INTENT_READY 가 그 수에 포함되므로 자기 자신 때문에 영원히 막힌다.
        if ("BUY".equalsIgnoreCase(request.getSide()) && guardedKind(sessionKind)
                && request.getSessionId() != null) {
            String lockedStatus = lockAndReadSessionStatus(sessionKind, request.getSessionId());
            if (!"RUNNING".equals(lockedStatus)) {
                log.warn("의도 커밋과 claim 사이에 정지가 들어왔다 — 철회한다 "
                        + "(orderId={}, sessionId={}, status={})", orderId, request.getSessionId(),
                        lockedStatus == null ? "없음" : lockedStatus);
                // 🔴 조건부 전환이다 — 종전 무조건 쓰기는 다른 경로의 전이를 덮어쓸 수 있었다.
                withdrawIntent(orderId, WITHDRAWN_STOPPED, "claim 직전 정지 확인 — 제출하지 않았다");
                return Boolean.FALSE;
            }
        }

        // 🔴 단일 인스턴스 잠금을 claim 직전에 확인한다 (2026-10-14) — 감시 주기 사이에 잠금을 잃은 인스턴스가 주문을
        //    내지 않게. 잃었으면 여기서 프로세스가 멈추고 의도는 INTENT_READY 로 남는다(다음 인스턴스가 철회).
        com.cryptoautotrader.api.support.CrashPoint.pause("BEFORE_CLAIM_CHECK");
        if (singleInstanceGuard != null) {
            singleInstanceGuard.verifyBeforeSubmit();
        }
        int won = orderRepository.claimForSubmission(orderId, Instant.now());
        if (won != 1) {
            // 🔴 **제출하지 않는다.** 밀린 이유를 호출자가 알 수 있게 구분해 적는다 — 철회에
            //    졌으면 예약도 해제되므로 이 요청은 "없던 일" 이고, 다른 작업자가 claim 했으면
            //    그 작업자가 책임을 가져갔다.
            String nowState = orderRepository.findById(orderId)
                    .map(OrderEntity::getState).orElse("없음");
            log.warn("claim 을 얻지 못했다 — 제출하지 않는다 (orderId={}, 현재상태={}, 사유={})",
                    orderId, nowState,
                    "FAILED".equals(nowState) ? "철회가 먼저 성립했다" : "다른 작업자가 claim 했다");
            return Boolean.FALSE;
        }
        recordTradeLog(orderId, "STATE_CHANGE", "INTENT_READY", "SUBMITTING", "제출 권한 획득");
        return Boolean.TRUE;
    }

    /**
     * C·D — 거래소 호출(트랜잭션 밖)과 결과 기록(트랜잭션).
     *
     * <p>🔴 <b>응답을 잃으면 {@code UNKNOWN} 이다.</b> {@code FAILED} 가 아니다 — 시간 초과는
     * "거래소가 거절했다" 는 뜻이 아니고, 접수됐을 가능성을 배제하지 못한다. 그 상태에서 자금을
     * 복원하면 실제로 쓰인 돈을 되돌리고, 장부에 없는 코인이 거래소에 남는다.</p>
     */
    private void callExchangeAndRecord(Long orderId) {
        OrderEntity snapshot = submitTx.execute(st -> orderRepository.findById(orderId).orElse(null));
        if (snapshot == null) {
            return;
        }

        UpbitOrderClient.ExchangeResult exchangeResult;
        try {
            exchangeResult = submitToExchange(snapshot);
        } catch (Exception e) {
            // 🔴 거래소의 **명시적 거절**은 따로 본다 (2026-10-14). 상태 코드만으로 확정하지 않는다 — 오류 의미가 "주문이
            //    만들어지지 않는 검증·인증 거절" 이고, **식별자로 조회해 그 주문이 없음을 확인한 뒤에만** 거절로 닫는다.
            SubmitRejection rejection = SubmitRejection.classify(e);
            if (rejection.explicit() && resolveExplicitRejection(orderId, snapshot.getClientOrderId(), rejection)) {
                return;
            }
            // 🔴 여기서 갈린다. 접수 **전** 실패와 접수 **후** 응답 유실을 우리는 구분할 수 없다.
            //    구분할 수 없으므로 **더 위험한 쪽으로 가정한다** — 나갔을 수 있다고 본다.
            log.error("거래소 주문 제출의 결과가 불명이다 (orderId={}, 식별자={}, 분류={}): {}",
                    orderId, snapshot.getClientOrderId(), rejection, e.getMessage(), e);
            submitTx.execute(st -> {
                OrderEntity o = orderRepository.findById(orderId).orElse(null);
                if (o != null) {
                    transitionState(o, "UNKNOWN", null);
                    o.setUnknownReason("제출 결과 불명: " + e.getMessage());
                    orderRepository.save(o);
                }
                return null;
            });
            return;
        }

        com.cryptoautotrader.api.support.CrashPoint.hit("AFTER_EXCHANGE_ACCEPT");
        submitTx.execute(st -> {
            recordSubmitOutcome(orderId, exchangeResult);
            return null;
        });
    }

    /**
     * 제출 실패의 분류 (2026-10-14).
     *
     * <p>🔴 <b>명시적 거절</b>은 "응답의 오류 의미가 주문 생성 전 검증·인증 단계의 거절" 인 경우뿐이다 — 잔고 부족, 최소
     * 금액 미달, 수량·가격 형식, 인증 오류. 상태 코드가 4xx 라는 것만으로는 아니다. 오류 이름이 없거나 모르는 이름,
     * 생성 도중의 서버 오류({@code create_bid_error} 등), 5xx, 시간 초과·연결 끊김은 <b>결과 불명</b>이다.</p>
     */
    record SubmitRejection(boolean explicit, Integer status, String errorName, String errorMessage) {
        private static final java.util.Set<String> EXPLICIT_NAMES = java.util.Set.of(
                "insufficient_funds_bid", "insufficient_funds_ask",
                "under_min_total_bid", "under_min_total_ask",
                "invalid_volume_bid", "invalid_volume_ask", "invalid_price_bid", "invalid_price_ask",
                "validation_error", "invalid_parameter", "invalid_query_payload",
                "jwt_verification", "expired_access_key", "invalid_access_key", "nonce_used",
                "no_authorization_i_p", "no_authorization_ip", "out_of_scope");

        static SubmitRejection classify(Throwable e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof com.cryptoautotrader.exchange.upbit.UpbitApiException api) {
                    boolean explicit = api.getStatusCode() >= 400 && api.getStatusCode() < 500
                            && api.getErrorName() != null && EXPLICIT_NAMES.contains(api.getErrorName());
                    return new SubmitRejection(explicit, api.getStatusCode(), api.getErrorName(), api.getErrorMessage());
                }
            }
            return new SubmitRejection(false, null, null, null);
        }

        String describe() {
            return "HTTP " + status + " " + errorName + (errorMessage != null ? " — " + errorMessage : "");
        }
    }

    /**
     * 명시적 거절을 <b>식별자 조회로 확인</b>한다.
     *
     * @return 처리했으면 {@code true} — 없음 확인 → {@code FAILED/EXCHANGE_REJECTED}, 있음 → 주문번호를 붙여 추적.
     *         조회하지 못했으면 {@code false} — 호출자가 {@code UNKNOWN} 으로 둔다.
     */
    private boolean resolveExplicitRejection(Long orderId, String identifier, SubmitRejection rejection) {
        if (identifier == null || upbitOrderClient == null) {
            return false;
        }
        OrderResponse found;
        try {
            if (!rateLimiter.acquire()) {
                return false;
            }
            found = upbitOrderClient.getOrderByIdentifier(identifier);
        } catch (Exception ex) {
            log.warn("거절 응답 뒤 식별자 조회 실패 — 거절로 확정하지 않고 불명으로 둔다 (orderId={}): {}",
                    orderId, ex.getMessage());
            return false;
        }
        if (found != null && found.getUuid() != null) {
            // 🔴 거절 응답인데 주문이 있다 — 확정하지 않는다. 주문번호를 붙여 정상 추적에 넘긴다.
            submitTx.execute(st -> orderRepository.attachFoundSubmission(orderId, found.getUuid(), Instant.now()));
            log.error("🔴 거절 응답({})을 받았지만 식별자로 주문이 조회된다 — 거절로 닫지 않고 추적한다 (orderId={}, uuid={})",
                    rejection.describe(), orderId, found.getUuid());
            return true;
        }
        String reason = "거래소 거절(" + rejection.describe() + ") — 식별자 조회로 주문 없음 확인";
        Boolean closed = submitTx.execute(st -> {
            OrderEntity o = orderRepository.findByIdForUpdate(orderId).orElse(null);
            if (o == null || !"SUBMITTING".equals(o.getState())) {
                return Boolean.FALSE;
            }
            o.setFailureCode(EXCHANGE_REJECTED);
            transitionState(o, "FAILED", reason);
            return Boolean.TRUE;
        });
        log.warn("주문이 거래소에서 거절됐다 (orderId={}): {}", orderId, reason);
        return Boolean.TRUE.equals(closed);
    }

    /** D — 접수 응답을 기록한다. */
    private void recordSubmitOutcome(Long orderId, UpbitOrderClient.ExchangeResult exchangeResult) {
        OrderEntity order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return;
        }
        OrderResponse exchangeResponse = exchangeResult.response();
        order.setExchangeOrderId(exchangeResponse.getUuid());
        order.setSubmittedAt(Instant.now());
        order.setResponseJson(exchangeResult.rawBody());
        log.info("거래소 주문 제출 완료 (orderId={}, exchangeId={}, initialState={})",
                order.getId(), exchangeResponse.getUuid(), exchangeResponse.getState());

        // 🔴 LIVE·DYNAMIC 시장가 매수는 생성 응답이 이미 종결(done·체결 있는 cancel)이어도 **여기서 반영하지
        //    않는다** (2026-10-12). 반영은 원자적 회계 경로 하나로 모은다 — 여기서 `processFilledOrder`
        //    를 부르면 원금 차이 복원과 정산의 미사용 예약 복원이 **겹친다**(폴링 경로에서 실측 60원
        //    과복원). SUBMITTED 로 두면 폴러가 같은 응답을 원자 경로로 반영한다.
        String initialState = mapExchangeState(exchangeResponse.getState());
        boolean atomicMarketBuy = order.getFeeSource() != null
                && "BUY".equalsIgnoreCase(order.getSide())
                && "MARKET".equalsIgnoreCase(order.getOrderType())
                && OrderSettlementService.isSharedPoolKind(order.getSessionKind());
        if (atomicMarketBuy) {
            transitionState(order, "SUBMITTED", null);
            return;
        }
        // 🔴 실자금 세션(LIVE·DYNAMIC) 시장가 매도도 같다 (2026-10-14). 생성 응답이 이미 done 이면 아래 즉시 처리는
        //    응답의 보고 체결금액·수수료를 기록하지 않고 확정해 **추정 수수료(ESTIMATED)로 끝났고**, FILLED 는 다시 조회하지
        //    않으므로 보고값이 영영 반영되지 않았다(재시작 시험 S12: 9,997.06 — 보고 수수료면 9,997.35). SUBMITTED 로 두면
        //    폴러가 정산 경로로 보고값을 기록하고 그 값으로 확정한다(S7 과 같은 경로).
        boolean sharedPoolMarketSell = "SELL".equalsIgnoreCase(order.getSide())
                && "MARKET".equalsIgnoreCase(order.getOrderType())
                && OrderSettlementService.isSharedPoolKind(order.getSessionKind());
        if (sharedPoolMarketSell) {
            transitionState(order, "SUBMITTED", null);
            return;
        }

        // 주문 생성 응답에서 이미 최종 상태인 경우 즉시 처리 (폴러 대기 불필요)
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
    }

    /**
     * 클라이언트 식별자를 만든다.
     *
     * <p>업비트 제약: 최대 64자, 계정 전체 주문에서 고유, <b>재사용 불가</b>. UUID 로 충분하지만
     * 사람이 로그에서 알아보게 접두사를 둔다.</p>
     */
    private String newClientOrderId() {
        return "cat-" + java.util.UUID.randomUUID();
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

        // 🔴 LIVE 시장가 매수의 체결 회계는 **새 원자 경로**가 맡는다 (2026-10-12, ⓑ 결정).
        //    기존 경로는 체결 반영(포지션)을 먼저 커밋하고 정산을 따로 돌려 **중간 상태**를 만들었다.
        //    새 경로는 관측을 먼저 보존하고 주문·잔액·포지션을 한 트랜잭션에서 반영한다.
        //    🔴 지정가·매도는 기존 경로가 그대로 처리한다 — 한꺼번에 옮기면 부분 체결·
        //    취소 후 체결·매도 정산의 기존 보호 장치까지 다시 써야 한다.
        boolean atomicBuyPath = orderSettlementService != null
                && order.getFeeSource() != null
                && "BUY".equalsIgnoreCase(order.getSide())
                && "MARKET".equalsIgnoreCase(order.getOrderType())
                // 🔴 DYNAMIC 도 같은 경로다 (2026-10-12). 종전 경로의 `handleBuyFill` 은 원금 차이만
                //    복원하고(수수료 미반영) 포지션·잔액을 다른 트랜잭션에 썼다.
                && OrderSettlementService.isSharedPoolKind(order.getSessionKind())
                && ("FILLED".equals(mapExchangeState(exchangeStatus.getState()))
                    // 🔴 **부분 체결 후 취소도 같은 경로다** (2026-10-12). 업비트 시장가 매수는 남은
                    //    금액을 취소하며 `cancel` 로 끝나는 일이 흔하다. 종전에는 이 응답이 기존 경로로
                    //    가서 `handleBuyFill` 이 원금 차이(60)를 복원하고, 정산이 미사용 예약(58.53)을
                    //    **또** 복원했다 — 잔액 7,118.53, 없던 돈 60원(SnapshotBoundaryRaceTest ③).
                    //    체결 없는 취소는 기존 경로(CANCELLED → 고아 정리가 예약 전액 복원)에 둔다.
                    || ("CANCELLED".equals(mapExchangeState(exchangeStatus.getState()))
                        && exchangeStatus.getExecutedVolume() != null
                        && exchangeStatus.getExecutedVolume().signum() > 0));

        if (atomicBuyPath) {
            try {
                orderSettlementService.observeAndApplyBuyFill(orderId, exchangeStatus);
            } catch (Exception e) {
                // 🔴 관측과 대기 표시는 남는다 — 다음 조회에서 재처리된다.
                log.error("체결 회계 반영 실패 — 관측과 예약을 유지하고 재처리한다 (orderId={}): {}",
                        orderId, e.getMessage(), e);
                return;
            }
            // 🔴 **주문을 종결한다** (2026-10-12 보완).
            //
            //    종전에는 회계만 반영하고 그대로 돌아갔다 — 그래서 주문이 `SUBMITTED` 로 남아
            //    **자금·슬롯을 영구히 점유하고** 폴러가 영원히 다시 조회했다. 거래소는 종결
            //    (`done`)을 보고했는데 우리 장부만 열려 있는 상태다. 종단 간 시험
            //    (`IntentPrecommitAndOnceOnlyTest`)이 이 공백을 잡았다.
            //
            //    🔴 **`processFilledOrder` 를 부르지 않는다.** 포지션·잔액은 원자적 경로가 이미
            //    반영했다 — 여기서 다시 부르면 같은 체결이 두 번 반영된다. 이 단계는 **상태 전이와
            //    체결 시각만** 기록한다.
            //
            //    🔴 회계 대기가 남아 있으면 종결하지 않는다. 반영이 끝나지 않은 주문을 종결하면
            //    폴러의 추적 대상에서 빠지고 재처리 기회가 사라진다.
            finalizeAtomicFill(orderId);
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

    /** 원자 회계가 반영된 시장가 매수를 {@code FILLED} 로 종결한다 — 상태 전이와 체결 시각만 기록한다. */
    private void finalizeAtomicFill(Long orderId) {
        try {
            syncTx.executeWithoutResult(st -> {
                // 🔴 잠금으로 읽는다 (2026-10-12, 시험 10) — 아래 저장은 전 열을 쓰므로, 잠금 없이
                //    읽으면 그사이 다른 작업자가 커밋한 관측·대기 표시를 이전 값으로 되돌린다.
                OrderEntity fresh = orderRepository.findByIdForUpdate(orderId).orElse(null);
                if (fresh == null || "FILLED".equals(fresh.getState())) {
                    return;
                }
                if (Boolean.TRUE.equals(fresh.getFillAccountingPending())) {
                    log.warn("회계 반영이 끝나지 않아 주문을 종결하지 않는다 — 재처리 대상으로 "
                            + "남긴다 (orderId={})", orderId);
                    return;
                }
                if (fresh.getAppliedExecutedFunds() == null) {
                    // 회계가 한 번도 반영되지 않았다(체결 수량·금액 미보고 등) — 종결하지 않는다.
                    return;
                }
                if (fresh.getFilledAt() == null) {
                    fresh.setFilledAt(Instant.now());
                }
                transitionState(fresh, "FILLED", null);
            });
        } catch (Exception e) {
            // 종결 전이만 실패했다. 회계는 반영됐고 주문은 활성으로 남아 다음 조회에서
            // 다시 시도된다 — 차분이 0 이므로 회계가 두 번 반영되지는 않는다.
            log.error("체결 회계는 반영됐으나 종결 전이가 실패했다 — 활성으로 유지한다 "
                    + "(orderId={}): {}", orderId, e.getMessage(), e);
        }
    }

    /**
     * 정지된(실행 중이 아닌) 세션의 미종결 매수인가 — 취소를 요청할 대상.
     *
     * <p>{@code INTENT_READY} 는 대상이 아니다 — 나간 적이 없고, claim 이 정지를 보고 스스로 철회한다.</p>
     */
    private boolean isBuyOfStoppedSession(OrderEntity order, Map<String, String> cache) {
        if (!"BUY".equalsIgnoreCase(order.getSide()) || order.getSessionId() == null
                || !guardedKind(order.getSessionKind())) {
            return false;
        }
        String state = order.getState();
        if (!"SUBMITTING".equals(state) && !"UNKNOWN".equals(state)
                && !"SUBMITTED".equals(state) && !"PARTIAL_FILLED".equals(state)) {
            return false;
        }
        String key = order.getSessionKind() + "#" + order.getSessionId();
        String status = cache.computeIfAbsent(key, k -> {
            Optional<String> s = "DYNAMIC".equals(order.getSessionKind())
                    ? dynamicSessionRepository.findById(order.getSessionId()).map(d -> d.getStatus())
                    : sessionRepository.findById(order.getSessionId()).map(l -> l.getStatus());
            return s.orElse("MISSING");
        });
        return !"RUNNING".equals(status);
    }

    /**
     * 체결 여부가 확인되지 않은 {@code CANCELLED} 매수를 다시 조회한다 (V87).
     *
     * <p>🔴 V87 이전에는 취소 요청이 성공하면 체결량을 보지 않고 {@code CANCELLED} 로 기록했다. 포지션이
     * 아직 열려 있는 것만 본다 — 닫힌 포지션은 과거 기록이고 자동으로 재해석하지 않는다.</p>
     */
    void verifyUnconfirmedCancelledBuys() {
        if (upbitOrderClient == null) {
            return;
        }
        List<OrderEntity> due;
        try {
            due = syncTx.execute(st -> orderRepository.findUnconfirmedCancelledBuys(
                    Instant.now().minus(CANCEL_RECHECK_INTERVAL)));
        } catch (Exception e) {
            log.warn("확인되지 않은 취소 조회 실패: {}", e.getMessage());
            return;
        }
        if (due == null) {
            return;
        }
        for (OrderEntity o : due.subList(0, Math.min(due.size(), 20))) {
            try {
                verifyCancelledBuy(o.getId());
            } catch (Exception e) {
                log.warn("확인되지 않은 취소 재조회 실패 — 다음 회차에 다시 본다 (orderId={}): {}",
                        o.getId(), e.getMessage());
            }
        }
    }

    private void verifyCancelledBuy(Long orderId) {
        OrderEntity order = syncTx.execute(st -> {
            orderRepository.touchCancelCheck(orderId, Instant.now());
            return orderRepository.findById(orderId).orElse(null);
        });
        if (order == null || order.getExchangeOrderId() == null) {
            return;
        }
        OrderResponse latest;
        try {
            latest = queryExchangeOrder(order.getExchangeOrderId());
        } catch (Exception e) {
            log.warn("확인되지 않은 취소의 거래소 조회 실패 (orderId={}): {}", orderId, e.getMessage());
            return;
        }
        BigDecimal volume = latest.getExecutedVolume();
        if (volume != null && volume.signum() > 0) {
            if (!isAtomicMarketBuy(order)) {
                log.error("🔴 CANCELLED 로 기록된 매수에 체결이 있다 — 원자 회계 대상이 아니어서 자동 반영하지 "
                        + "않는다. 사람이 확인해야 한다 (orderId={}, executed={})", orderId, volume);
                return;
            }
            log.error("🔴 CANCELLED 로 기록된 매수에 체결이 있다 — 체결분을 회계에 반영하고 미사용 예약만 "
                    + "푼다 (orderId={}, executed={})", orderId, volume);
            orderSettlementService.observeAndApplyBuyFill(orderId, latest);
            finalizeAtomicFill(orderId);
            return;
        }
        if ("CANCELLED".equals(mapExchangeState(latest.getState())) && volume != null) {
            syncTx.execute(st -> orderRepository.confirmCancelledHadNoFill(orderId, Instant.now()));
            log.info("CANCELLED 매수의 체결 없음을 확인했다 (orderId={})", orderId);
        }
    }

    /**
     * 주문 취소 요청 — 사용자·API 진입점.
     *
     * <p>🔴 <b>반환된 주문이 {@code CANCELLED} 라는 보장은 없다</b> (2026-10-13). 거래소가 체결량 0 으로
     * 취소를 확인했을 때만 {@code CANCELLED} 이고, 아직 확인되지 않았으면 활성으로 남아 폴러가
     * 확인한다. 상세는 {@link #requestCancel}.</p>
     *
     * <p>🔴 내부 경로(정지·폴러·일괄 취소)는 이 메서드가 아니라 {@link #requestCancel} 을 쓴다 — 이
     * 메서드는 결과 불명에서 예외를 던지고, {@code @Transactional} 프록시를 지나 던져진 예외는 호출자의
     * 트랜잭션을 롤백 전용으로 만든다(정지 전체가 롤백된다).</p>
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

        // 거래소 주문번호가 없거나 거래소 클라이언트가 없다 — 거래소에 물을 수단이 없는 종전 경로.
        // 🔴 체결 없음을 확인한 것이 아니므로 `cancel_confirmed_at` 을 쓰지 않는다.
        if (upbitOrderClient == null || order.getExchangeOrderId() == null) {
            order.setCancelledAt(Instant.now());
            transitionState(order, "CANCELLED", "사용자 취소 요청");
            log.info("주문 취소(거래소 미조회): orderId={}", orderId);
            return order;
        }

        CancelOutcome outcome = requestCancel(orderId, true);
        if (outcome == CancelOutcome.UNKNOWN_RESULT) {
            throw new IllegalStateException("취소 결과 불명 — 주문을 활성으로 남긴다: orderId=" + orderId);
        }
        log.info("주문 취소 요청 결과: orderId={} → {}", orderId, outcome);
        return syncTx.execute(st -> orderRepository.findById(orderId).orElse(order));
    }

    /** 취소 요청의 결과 (2026-10-13). 🔴 요청과 확정을 구분한다. */
    public enum CancelOutcome {
        /** 거래소가 {@code cancel} 과 체결량 0 을 보고했다. {@code CANCELLED} 로 닫고 확인 시각을 남겼다. */
        CONFIRMED_UNFILLED,
        /** 체결이 확인됐다. 원자 회계 대상은 활성으로 두어 체결 반영 경로에 맡겼고, 그 밖은 종전 경로로 반영했다. */
        FILL_DETECTED,
        /** 요청은 보냈지만 취소가 아직 확인되지 않았다. 활성으로 남아 폴러가 확인한다. */
        REQUESTED,
        /** 거래소가 그 식별자의 주문이 없다고 답했다. 🔴 미접수로 단정하지 않는다 — 상태를 바꾸지 않았다. */
        NOT_FOUND,
        /** 요청과 확인 조회가 모두 실패했거나, 요청이 실패했는데 거래소에 주문이 살아 있다. 상태를 바꾸지 않았다. */
        UNKNOWN_RESULT,
        /** 취소 대상이 아니다(종결, 의도 단계, 물을 수단이 없음). */
        NOT_CANCELLABLE,
        /** 직전 요청이 아직 확인 대기 중이다. 다시 보내지 않았다. */
        RECENTLY_REQUESTED
    }

    /** 확인되지 않은 취소를 다시 요청하기까지의 간격. */
    static final Duration CANCEL_RETRY_INTERVAL = Duration.ofSeconds(30);
    /** 확인되지 않은 {@code CANCELLED} 매수를 다시 조회하기까지의 간격. */
    static final Duration CANCEL_RECHECK_INTERVAL = Duration.ofSeconds(30);

    private static final List<String> CANCELLABLE_STATES = List.of(
            "PENDING", "SUBMITTING", "UNKNOWN", "SUBMITTED", "PARTIAL_FILLED");

    /** 정지 후 미해결 주문의 취소를 요청한다 — 기본 간격 규칙을 따른다. */
    public CancelOutcome requestCancel(Long orderId) {
        return requestCancel(orderId, false);
    }

    /**
     * 취소를 <b>요청</b>하고, 거래소 조회로 <b>체결량과 함께 확인</b>한다 (2026-10-13).
     *
     * <p>🔴 <b>취소 요청의 성공은 취소 확정이 아니다.</b> 종전에는 요청이 예외 없이 돌아오면 체결량을
     * 보지 않고 {@code CANCELLED} 로 기록했고, 고아 정리는 그 주문의 예약을 <b>전액 복원</b>했다.
     * 시장가 매수가 그사이 체결됐다면 코인을 샀는데 돈도 돌아온다. 이제는:</p>
     * <ul>
     *   <li>체결량이 있으면 — 원자 회계 대상은 활성으로 두고 체결 반영 경로에 맡긴다(체결분은 회계에
     *       반영되고 미사용 예약만 풀린다). 그 밖은 종전 {@code syncOrderState} 가 반영한다.</li>
     *   <li>{@code cancel} 과 체결량 <b>0</b> 이 보고되면 — {@code CANCELLED} 로 닫고 확인 시각을 남긴다.
     *       전액 복원은 이 확인이 있을 때만 한다({@link #isConfirmedUnfilledBuy}).</li>
     *   <li>체결량이 보고되지 않았거나 아직 활성이면 — 활성으로 남긴다.</li>
     * </ul>
     *
     * <p>🔴 <b>응답을 잃은 주문({@code SUBMITTING}·{@code UNKNOWN})은 식별자로 취소한다.</b> 거래소가
     * 없다고 답하면(404) {@link CancelOutcome#NOT_FOUND} 이고 상태를 바꾸지 않는다 — 미접수의 증거가
     * 아니다. 찾으면 주문번호를 붙여 {@code SUBMITTED} 로 올리고 같은 확인을 한다.</p>
     *
     * <p>🔴 <b>트랜잭션을 들고 거래소를 부르지 않는다.</b> 쓰기는 모두 짧은 {@code REQUIRES_NEW}
     * 트랜잭션의 열 단위 조건부 문장이다 — 원자 체결 회계가 같은 행에 쓴 관측·반영값을 엔티티 저장으로
     * 되돌리지 않는다(시험 10). 예외를 던지지 않는다 — 호출자(정지)의 트랜잭션을 롤백 전용으로 만들지
     * 않는다.</p>
     *
     * @param force {@code true} 면 직전 요청 간격을 무시하고 보낸다(사용자 취소)
     */
    CancelOutcome requestCancel(Long orderId, boolean force) {
        try {
            return doRequestCancel(orderId, force);
        } catch (Exception e) {
            log.error("취소 요청 처리 중 예외 — 상태를 바꾸지 않는다 (orderId={}): {}", orderId, e.getMessage(), e);
            return CancelOutcome.UNKNOWN_RESULT;
        }
    }

    private CancelOutcome doRequestCancel(Long orderId, boolean force) {
        OrderEntity order = syncTx.execute(st -> orderRepository.findById(orderId).orElse(null));
        if (order == null || !CANCELLABLE_STATES.contains(order.getState()) || upbitOrderClient == null) {
            return CancelOutcome.NOT_CANCELLABLE;
        }
        String uuid = order.getExchangeOrderId();
        String identifier = order.getClientOrderId();
        boolean byIdentifier = uuid == null && identifier != null
                && ("SUBMITTING".equals(order.getState()) || "UNKNOWN".equals(order.getState()));
        if (uuid == null && !byIdentifier) {
            return CancelOutcome.NOT_CANCELLABLE;
        }
        if (!force && order.getCancelRequestedAt() != null
                && Duration.between(order.getCancelRequestedAt(), Instant.now()).compareTo(CANCEL_RETRY_INTERVAL) < 0) {
            return CancelOutcome.RECENTLY_REQUESTED;
        }

        // 먼저 묻는다 — 이미 종결(체결·취소)이면 취소를 보내지 않고 그 응답으로 확인한다. 이미 체결된 주문에
        // 취소를 보내는 것은 무의미하고, 종전에는 그 취소의 "성공" 이 CANCELLED 기록으로 이어졌다.
        OrderResponse before = null;
        try {
            if (uuid != null) {
                before = queryExchangeOrder(uuid);
            } else if (rateLimiter.acquire()) {
                before = upbitOrderClient.getOrderByIdentifier(identifier);
            }
        } catch (Exception e) {
            log.warn("취소 전 조회 실패 — 취소를 요청한다 (orderId={}): {}", orderId, e.getMessage());
        }
        if (before != null && before.getUuid() != null && uuid == null) {
            OrderResponse found = before;
            syncTx.execute(st -> orderRepository.attachFoundSubmission(orderId, found.getUuid(), Instant.now()));
            uuid = found.getUuid();
            log.info("식별자로 미해결 제출을 찾았다 — 주문번호로 취소한다 (orderId={}, 식별자={}, uuid={})",
                    orderId, identifier, uuid);
        }
        if (before != null && isTerminalExchangeState(before.getState())) {
            return confirmFromExchange(orderId, before);
        }

        // 요청을 먼저 적는다 — 요청 뒤에 프로세스가 죽어도 "취소를 요청했다" 가 남는다.
        syncTx.execute(st -> orderRepository.markCancelRequested(orderId, Instant.now()));

        boolean cancelByIdentifier = uuid == null;
        boolean requestFailed = false;
        OrderResponse cancelResponse = null;
        try {
            if (!rateLimiter.acquire()) {
                log.warn("요청 제한 대기 시간 초과 — 취소를 보내지 못했다 (orderId={})", orderId);
                return CancelOutcome.UNKNOWN_RESULT;
            }
            cancelResponse = cancelByIdentifier
                    ? upbitOrderClient.cancelOrderByIdentifier(identifier)
                    : upbitOrderClient.cancelOrder(uuid);
        } catch (Exception e) {
            // 이미 체결돼 취소할 수 없거나 통신 실패다. 어느 쪽인지는 아래 조회로 가른다.
            requestFailed = true;
            log.warn("취소 요청 실패 — 조회로 확인한다 (orderId={}, {}): {}",
                    orderId, cancelByIdentifier ? "식별자=" + identifier : "uuid=" + uuid, e.getMessage());
        }
        if (cancelByIdentifier && !requestFailed && cancelResponse == null) {
            log.warn("식별자로 취소할 주문이 거래소에 없다 — 미접수로 단정하지 않고 상태를 유지한다 "
                    + "(orderId={}, 식별자={})", orderId, identifier);
            return CancelOutcome.NOT_FOUND;
        }

        // 확인 조회 — 🔴 취소 응답 자체로 판정하지 않는다(요청 시점의 상태라 체결량이 바뀔 수 있다).
        String knownUuid = uuid != null ? uuid
                : (cancelResponse != null ? cancelResponse.getUuid() : null);
        OrderResponse latest;
        try {
            if (knownUuid != null) {
                latest = queryExchangeOrder(knownUuid);
            } else {
                if (!rateLimiter.acquire()) {
                    return requestFailed ? CancelOutcome.UNKNOWN_RESULT : CancelOutcome.REQUESTED;
                }
                latest = upbitOrderClient.getOrderByIdentifier(identifier);
            }
        } catch (Exception e) {
            log.warn("취소 확인 조회 실패 — 활성으로 남겨 폴러가 다시 확인한다 (orderId={}): {}",
                    orderId, e.getMessage());
            return requestFailed ? CancelOutcome.UNKNOWN_RESULT : CancelOutcome.REQUESTED;
        }
        if (latest == null) {
            return requestFailed ? CancelOutcome.NOT_FOUND : CancelOutcome.REQUESTED;
        }
        if (cancelByIdentifier && latest.getUuid() != null) {
            int attached = syncTx.execute(st -> orderRepository.attachFoundSubmission(
                    orderId, latest.getUuid(), Instant.now()));
            log.info("식별자 취소로 미해결 제출을 찾았다 (orderId={}, 식별자={}, uuid={}, 연결={})",
                    orderId, identifier, latest.getUuid(), attached == 1);
        }
        CancelOutcome outcome = confirmFromExchange(orderId, latest);
        if (outcome == CancelOutcome.REQUESTED && requestFailed) {
            // 요청이 실패했는데 거래소에 주문이 살아 있다 — 취소되지 않았다.
            log.error("취소 요청 실패 + 거래소에 주문이 살아 있다 — 활성으로 남긴다 (orderId={}, 거래소상태={})",
                    orderId, latest.getState());
            return CancelOutcome.UNKNOWN_RESULT;
        }
        return outcome;
    }

    private static boolean isTerminalExchangeState(String upbitState) {
        return "done".equalsIgnoreCase(upbitState) || "cancel".equalsIgnoreCase(upbitState);
    }

    /**
     * 거래소 응답 하나로 취소·체결을 <b>함께</b> 판정한다.
     *
     * <p>🔴 {@code cancel} 이라도 체결량이 있으면 체결이다. 체결량이 보고되지 않은 {@code cancel} 은
     * 확인이 아니다 — 활성으로 남긴다.</p>
     */
    private CancelOutcome confirmFromExchange(Long orderId, OrderResponse latest) {
        String exchangeState = mapExchangeState(latest.getState());
        BigDecimal volume = latest.getExecutedVolume();
        boolean executed = volume != null && volume.signum() > 0;

        if (executed || "FILLED".equals(exchangeState)) {
            OrderEntity current = syncTx.execute(st -> orderRepository.findById(orderId).orElse(null));
            if (current == null) {
                return CancelOutcome.NOT_CANCELLABLE;
            }
            if (isAtomicMarketBuy(current)) {
                // 🔴 여기서 반영하지 않는다 — 체결 반영은 원자 경로 하나다. 활성으로 남기면 폴러
                //    (또는 호출자)가 `checkOrderStatus` 로 같은 응답을 원자 경로에 넣는다.
                log.warn("취소 확인 중 체결 감지 — 원자 회계 경로에 맡긴다 (orderId={}, state={}, executed={})",
                        orderId, latest.getState(), volume);
                return CancelOutcome.FILL_DETECTED;
            }
            // 지정가·매도 — 종전 경로. 🔴 잠금으로 읽고 반영한다(전 열 저장이 다른 작업자의 쓰기를 되돌리지 않게).
            log.warn("취소 확인 중 체결 감지 — 종전 경로로 반영 (orderId={}, state={}, executed={})",
                    orderId, latest.getState(), volume);
            syncTx.executeWithoutResult(st -> {
                OrderEntity fresh = orderRepository.findByIdForUpdate(orderId).orElse(null);
                if (fresh != null && ACTIVE_STATES.contains(fresh.getState())) {
                    syncOrderState(fresh, latest);
                }
            });
            return CancelOutcome.FILL_DETECTED;
        }

        if ("CANCELLED".equals(exchangeState)) {
            if (volume == null) {
                log.warn("거래소가 취소를 보고했지만 체결량을 보고하지 않았다 — 확인으로 보지 않고 활성으로 "
                        + "남긴다 (orderId={})", orderId);
                return CancelOutcome.REQUESTED;
            }
            Integer closed = syncTx.execute(st -> {
                OrderEntity before = orderRepository.findByIdForUpdate(orderId).orElse(null);
                if (before == null) return 0;
                int n = orderRepository.confirmUnfilledCancel(orderId, Instant.now());
                if (n == 1) {
                    recordTradeLog(orderId, "STATE_CHANGE", before.getState(), "CANCELLED",
                            "취소 확인 — 거래소 체결량 0");
                }
                return n;
            });
            if (closed != null && closed == 1) {
                log.info("취소 확인 — 체결 없음 (orderId={})", orderId);
                return CancelOutcome.CONFIRMED_UNFILLED;
            }
            OrderEntity after = syncTx.execute(st -> orderRepository.findById(orderId).orElse(null));
            return after != null && after.getCancelConfirmedAt() != null
                    ? CancelOutcome.CONFIRMED_UNFILLED : CancelOutcome.REQUESTED;
        }

        // 거래소에 아직 활성 — 취소가 처리 중이다.
        return CancelOutcome.REQUESTED;
    }

    /**
     * 체결 없이 끝난 것이 <b>확인된</b> 매수인가 — 예약 <b>전액</b> 복원의 조건 (V87).
     *
     * <ul>
     *   <li>{@code FAILED} — 나간 적이 없다(철회·의도 단계 거절). V84 이후 제출 중 예외는 {@code UNKNOWN} 이다.</li>
     *   <li>{@code CANCELLED} — 거래소가 체결량 0 으로 확인한 취소({@code cancelConfirmedAt}).</li>
     *   <li>정산 경로에 참여하지 않은 V82 이전 주문({@code feeSource} 없음)은 종전 규칙 — 과거를 재해석하지 않는다.</li>
     * </ul>
     *
     * <p>🔴 확인되지 않은 {@code CANCELLED} 는 체결됐을 수 있다. 복원하지도 닫지도 않고 재조회
     * ({@code verifyUnconfirmedCancelledBuys})에 맡긴다.</p>
     */
    public static boolean isConfirmedUnfilledBuy(OrderEntity o) {
        if ("FAILED".equals(o.getState())) return true;
        if (!"CANCELLED".equals(o.getState())) return false;
        return o.getFeeSource() == null || o.getCancelConfirmedAt() != null;
    }

    /** 외부로 나가지 않았거나 거래소가 명시적으로 거절한 {@code FAILED} 의 근거 코드. */
    private static final java.util.Set<String> NOT_EXECUTED_CODES = java.util.Set.of(
            WITHDRAWN_STOPPED, WITHDRAWN_OWNER_LOST, WITHDRAWN_DISPATCH_LOST,
            RISK_REJECTED, CLIENT_UNAVAILABLE, EXCHANGE_REJECTED);

    /**
     * 종결됐지만 <b>체결 없음의 근거가 없는</b> 주문인가 (2026-10-13) — 정리 작업이 다시 매도해도 되는지의 기준.
     *
     * <p>🔴 다시 낼 수 있는 것은 ① 외부로 나가지 않았음이 확정된 {@code FAILED}(철회·의도 단계 거절)
     * ② 거래소의 명시적 거절({@code EXCHANGE_REJECTED} — 🔴 현재 제출 경로는 4xx 를 분류하지 않아 이 코드를 만들지
     * 않는다) ③ 체결량 0 이 확인된 {@code CANCELLED} 뿐이다. 근거 코드 없는 {@code FAILED}(종전 데이터·수동 종결)와
     * 확인 없는 {@code CANCELLED} 는 나갔다가 체결됐을 수 있으므로 다시 팔면 이중 매도가 될 수 있다.</p>
     */
    public static boolean isTerminalWithoutNoFillProof(OrderEntity o) {
        if ("FAILED".equals(o.getState())) {
            return o.getFailureCode() == null || !NOT_EXECUTED_CODES.contains(o.getFailureCode());
        }
        if ("CANCELLED".equals(o.getState())) {
            return o.getCancelConfirmedAt() == null;
        }
        return false;
    }

    /** 체결 여부가 확인되지 않은 {@code CANCELLED} 매수인가 (V87) — 열린 포지션을 닫지 않는 근거. */
    public static boolean isUnconfirmedCancelledBuy(OrderEntity o) {
        return "BUY".equalsIgnoreCase(o.getSide()) && "CANCELLED".equals(o.getState())
                && o.getFeeSource() != null && o.getCancelConfirmedAt() == null;
    }

    /**
     * 모든 활성 주문 취소 (비상 정지용)
     *
     * <p>🔴 "취소됨" 이 아니라 "취소를 요청했고 그 결과" 를 센다 (2026-10-13). 확인되지 않은 주문은
     * 활성으로 남아 폴러가 확인한다.</p>
     */
    @Transactional
    public int cancelAllActiveOrders() {
        List<OrderEntity> activeOrders = orderRepository.findByStateIn(ACTIVE_STATES);
        int confirmed = 0;
        int pending = 0;

        for (OrderEntity order : activeOrders) {
            if (!"BUY".equalsIgnoreCase(order.getSide())) {
                // 🔴 매도는 취소하지 않는다 (2026-10-13, 정지와 같은 규칙) — 위험 축소다. 실제 재시작 시험(S6)에서
                //    정지된 세션의 진행 중 청산 매도를 이 일괄 취소가 끊었다(체결분 4.9 반영 후 남은 4.9 를 다시 팔아
                //    초과 매도는 없었지만, 매도 중인 CLOSING 포지션이 취소 후 아무도 맡지 않는 공백이 생긴다).
                continue;
            }
            if (upbitOrderClient == null || (order.getExchangeOrderId() == null
                    && List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED").contains(order.getState()))) {
                // 거래소에 물을 수단이 없는 종전 경로 — `cancelOrder` 의 같은 분기와 같다(체결 없음 확인 아님).
                try {
                    cancelOrder(order.getId());
                    confirmed++;
                } catch (Exception e) {
                    log.error("활성 주문 취소 실패 (orderId={}): {}", order.getId(), e.getMessage());
                }
                continue;
            }
            CancelOutcome outcome = requestCancel(order.getId(), true);
            switch (outcome) {
                case CONFIRMED_UNFILLED -> confirmed++;
                case NOT_CANCELLABLE -> { }
                default -> pending++;
            }
        }

        log.info("활성 주문 일괄 취소 요청: 체결 없음 확인 {}건, 확인 대기·체결 감지 {}건", confirmed, pending);
        return confirmed;
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
        // 🔴 활성 주문이 없어도 돈다 — 확인되지 않은 CANCELLED 는 활성 주문이 아니다.
        verifyUnconfirmedCancelledBuys();

        List<OrderEntity> activeOrders = orderRepository.findByStateIn(ACTIVE_STATES);
        if (activeOrders.isEmpty()) {
            return;
        }

        Map<String, String> sessionStatusCache = new java.util.HashMap<>();
        for (OrderEntity order : activeOrders) {
            // 🔴 **정지된 세션의 매수는 취소를 요청한다** (2026-10-13). 정지는 신규 claim 만 막는다 — 이미
            //    나간 매수는 정지 후에도 체결될 수 있다. 응답을 잃은 주문은 UUID 가 없으므로 식별자로
            //    취소한다. 요청은 확정이 아니므로 추적(아래 조회·해소)은 그대로 계속한다.
            if (isBuyOfStoppedSession(order, sessionStatusCache)) {
                CancelOutcome outcome = requestCancel(order.getId());
                if (outcome != CancelOutcome.RECENTLY_REQUESTED) {
                    log.info("정지된 세션의 매수 취소 요청 (orderId={}, state={}) → {}",
                            order.getId(), order.getState(), outcome);
                }
                if (outcome == CancelOutcome.FILL_DETECTED) {
                    checkOrderStatus(order.getId());
                    continue;
                }
                if (outcome != CancelOutcome.RECENTLY_REQUESTED && outcome != CancelOutcome.NOT_CANCELLABLE) {
                    // 이번 회차에 행을 썼다 — 이 루프가 들고 있는 엔티티는 낡았다. 다음 회차가 다시 읽는다.
                    continue;
                }
            }

            // 🔴 미해결 제출은 **식별자로 조회**해 해소한다 (V84). 재제출하지 않는다.
            //    UUID 가 없으므로 아래 `checkOrderStatus` 경로로는 아무것도 할 수 없다.
            if ("INTENT_READY".equals(order.getState())
                    || "SUBMITTING".equals(order.getState())
                    || "UNKNOWN".equals(order.getState())) {
                try {
                    resolveUnresolvedSubmission(order.getId());
                } catch (Exception e) {
                    log.warn("미해결 제출 해소 실패 — 상태를 유지한다 (orderId={}): {}",
                            order.getId(), e.getMessage());
                }
                continue;
            }

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
                            // 🔴 원자 회계 대상 시장가 매수는 **여기서 반영하지 않는다** (2026-10-12).
                            //    아래 `syncOrderState` 는 종전 경로(원금 차이 복원)라, 원자 경로의 정산과
                            //    겹치면 미사용액이 두 번 복원된다. 체결이 있으면 — 전량이든 체결 후
                            //    취소든 — 같은 반영 경로 하나(`checkOrderStatus`)로 보낸다.
                            boolean executed = latest.getExecutedVolume() != null
                                    && latest.getExecutedVolume().signum() > 0;
                            if (isAtomicMarketBuy(order) && executed) {
                                log.warn("타임아웃 직전 체결 감지 — 원자 회계 경로로 반영 (orderId={}, state={}, executed={})",
                                        order.getId(), latest.getState(), latest.getExecutedVolume());
                                checkOrderStatus(order.getId());
                                continue;
                            }
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
                    // 🔴 취소는 **요청**이다 (2026-10-13). 종전에는 요청이 성공하면 CANCELLED 로 기록했다 —
                    //    그사이 체결이 있었는지 보지 않았다. 확정은 체결량과 함께 `requestCancel` 이 한다.
                    CancelOutcome outcome = requestCancel(order.getId());
                    if (outcome == CancelOutcome.RECENTLY_REQUESTED) {
                        // 직전 요청의 확인을 기다리는 중이다 — 다시 보내지 않고 조회만 한다.
                        checkOrderStatus(order.getId());
                        continue;
                    }
                    log.warn("주문 타임아웃 (orderId={}, {}분 경과) — 취소 요청 결과 {}",
                            order.getId(), elapsed.toMinutes(), outcome);
                    recordTradeLog(order.getId(), "TIMEOUT", "SUBMITTED",
                            outcome == CancelOutcome.CONFIRMED_UNFILLED ? "CANCELLED" : "SUBMITTED",
                            "제출 후 5분 경과 — 취소 요청: " + outcome);
                    if (outcome == CancelOutcome.FILL_DETECTED && isAtomicMarketBuy(order)) {
                        checkOrderStatus(order.getId());
                    }
                    continue;
                }
            }

            // 거래소 상태 동기화
            checkOrderStatus(order.getId());
        }
    }

    // ── 미해결 제출의 복구 — 🔴 조회만 한다 ───────────────────────

    /**
     * 미해결 제출을 <b>식별자로 조회</b>해 해소한다 (V84).
     *
     * <p>🔴 <b>재제출하지 않는다.</b> {@code SUBMITTING} 을 <b>읽은</b> 것과 그 전환에 <b>성공한</b>
     * 것은 다르다 — 상태는 재기동 후에도 보이지만 전환 성공은 한 번만 일어난다. 복구 작업자가
     * 그 상태를 보고 다시 제출하면 같은 의도가 두 번 나간다.</p>
     *
     * <p>🔴 <b>조회되지 않음을 "접수되지 않았다" 로 읽지 않는다.</b> 업비트 명세에 식별자 보존 기간이
     * 없고, 조회 실패는 통신 장애일 수도 있다. 찾지 못하면 상태를 그대로 두고 시도 횟수만 올린다 —
     * 자동으로 {@code FAILED} 로 바꾸지 않는다. 그 종결은 사람이 근거를 보고 한다.</p>
     *
     * @return 상태를 해소했으면 {@code true}
     */
    boolean resolveUnresolvedSubmission(Long orderId) {
        // 🔴 경계를 **여기서** 만든다. `@Modifying(flushAutomatically = true)` 질의는 활성
        //    트랜잭션을 요구하고, 트랜잭션 없이 부르면 `TransactionRequiredException` 이 난다 —
        //    2026-03-17 에 `executeStrategies()` 에서 `@Transactional` 이 빠지며 모든 청산이
        //    실패한 결함이 정확히 그 유형이다(`PositionStateTransition` 참조).
        //
        //    `@Transactional` 을 붙이지 않는 이유는 `pollActiveOrders` 가 **자기 호출**로 부르기
        //    때문이다 — 프록시를 우회해 조용히 무시된다. 템플릿은 REQUIRED 이므로 폴러의
        //    트랜잭션에 참여하고, 단독 호출에서는 새로 열린다.
        // 🔴 **조회를 커밋 뒤로 둔다** (2026-10-12 보완).
        //
        //    종전에는 이 트랜잭션 안에서 `SUBMITTED` 로 전이한 **직후** `checkOrderStatus` 를 불렀다.
        //    그런데 그 경로의 정산은 `REQUIRES_NEW` 라 **이 트랜잭션을 정지시키고** 아직 커밋되지
        //    않은 전이를 보지 못한다 — 자기 트랜잭션에서 `FILLED` 로 올려 커밋하고, 그 뒤 이
        //    트랜잭션이 `SUBMITTED` 를 커밋해 **종결을 덮어썼다.** 종단 간 시험이 이것을 잡았다.
        //
        //    전이를 먼저 커밋하고, 조회는 그다음에 한다.
        RESOLVED.set(Boolean.FALSE);
        // 🔴 **REQUIRES_NEW 다** (2026-10-13, 실제 PostgreSQL 재시작 시험에서 발견). 종전 REQUIRED 는 폴러의 바깥
        //    트랜잭션에 **참여**해 커밋되지 않았다 — 위의 "전이를 먼저 커밋" 이 폴러 안에서는 성립하지 않았다. 이어지는
        //    체결 반영(REQUIRES_NEW)이 같은 주문 행을 고치려다 **자기 바깥 트랜잭션의 행 잠금**을 기다렸고, lock_timeout
        //    10초에 실패해 다음 폴링으로 밀렸다(그동안 폴러 스레드 정지, 제한 시간이 없으면 영구 정지 — §8 의 같은 스레드
        //    중첩 대기). H2 는 이것을 오래된 읽기로 보여 "다음 회차 반영" 으로 오인했다.
        Boolean needsPoll = syncTx.execute(st -> resolveUnresolvedSubmissionInTx(orderId));
        if (!Boolean.TRUE.equals(needsPoll)) {
            return RESOLVED.get() == Boolean.TRUE;
        }
        // 🔴 조회도 새 트랜잭션에서 한다 — 폴러의 영속성 컨텍스트에는 전이 전(주문번호 없음) 엔티티가 남아 있어,
        //    그 안에서 부르면 옛 값을 읽고 그냥 돌아간다. 이 트랜잭션은 읽기만 하므로 안의 정산(REQUIRES_NEW)을 막지 않는다.
        syncTx.executeWithoutResult(st -> checkOrderStatus(orderId));
        return true;
    }

    /**
     * 전이만 커밋하고 조회는 바깥에서 한다는 신호를 담는다.
     *
     * <p>🔴 {@code resolveUnresolvedSubmissionInTx} 는 "해소됐다" 와 "거래소에서 찾았으니 이제
     * 조회하라" 를 구분해야 한다. 반환값 하나로는 부족해 스레드 지역 변수를 쓴다 — 복구는 폴러
     * 스레드에서 한 건씩 돌므로 공유되지 않는다.</p>
     */
    private static final ThreadLocal<Boolean> RESOLVED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private Boolean resolveUnresolvedSubmissionInTx(Long orderId) {
        OrderEntity order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return Boolean.FALSE;
        }

        // 🔴 INTENT_READY 는 **나간 적이 없다.** claim 이 제출의 유일한 관문이고 그 전환이 커밋된
        //    적이 없으므로, 외부 호출은 일어나지 않았다. 그래서 철회가 **안전**하다.
        //
        //    🔴 그러나 안전하다는 것과 **정당하다**는 것은 다르다 (V86). 종전에는 60초가 지나면
        //    철회했고, 조건부 전환이 claim 과의 경합을 가려 주었다. 그것은 경합의 해결이지 철회의
        //    근거가 아니다 — 오래 걸렸을 뿐 살아 있는 작업의 의도도 철회됐다.
        //
        //    이제 **claim 할 작업이 존재하지 않음이 확정될 때만** 철회한다. 시간은 경보에만 쓴다.
        if ("INTENT_READY".equals(order.getState())) {
            boolean ownedHere = INSTANCE_ID.equals(order.getIntentOwner());
            if (ownedHere && DISPATCHING.contains(order.getId())) {
                // 살아 있는 작업이 맡고 있다. 얼마나 오래됐든 **철회하지 않는다.**
                Instant committed = order.getIntentCommittedAt();
                if (committed != null && Duration.between(committed, Instant.now()).compareTo(STALE_INTENT_ALERT) > 0) {
                    log.warn("의도가 {}초째 claim 되지 않았다 — 작업이 살아 있으므로 철회하지 않는다 "
                                    + "(orderId={}). 실행기 적체 여부를 사람이 확인해야 한다.",
                            Duration.between(committed, Instant.now()).toSeconds(), order.getId());
                }
                return Boolean.FALSE;
            }
            // 🔴 정당한 사유: 소유 프로세스가 사라졌거나(재기동), 같은 프로세스에서 작업이 끝났다.
            String code = ownedHere ? WITHDRAWN_DISPATCH_LOST : WITHDRAWN_OWNER_LOST;
            String why = ownedHere
                    ? "claim 할 작업이 이 프로세스에 없다 — 제출하지 않았다"
                    : "의도를 만든 프로세스(" + order.getIntentOwner() + ")가 지금 프로세스가 아니다 — 제출하지 않았다";
            // 🔴 **조건부 전환으로 claim 과 경쟁한다.** 정확히 한쪽만 1행을 받는다.
            if (withdrawIntent(order.getId(), code, why)) {
                RESOLVED.set(Boolean.TRUE);   // 철회로 해소됐다 — 조회는 필요 없다
            }
            return Boolean.FALSE;
        }

        if (!"SUBMITTING".equals(order.getState()) && !"UNKNOWN".equals(order.getState())) {
            return Boolean.FALSE;
        }
        if (order.getClientOrderId() == null) {
            // 🔴 V84 이전 주문이다. 식별자가 없으므로 **물어볼 수가 없다** — 그것이 고치려던 결함이고,
            //    과거 데이터를 지금 재해석하지는 않는다. 사람이 보도록 남긴다.
            log.warn("식별자가 없어 조회할 수 없다 — 사람이 종결해야 한다 (orderId={}, state={})",
                    order.getId(), order.getState());
            orderRepository.touchUnknownProbe(order.getId(), Instant.now());
            return Boolean.FALSE;
        }
        if (upbitOrderClient == null) {
            return Boolean.FALSE;
        }

        OrderResponse found;
        try {
            if (!rateLimiter.acquire()) {
                return Boolean.FALSE;
            }
            found = upbitOrderClient.getOrderByIdentifier(order.getClientOrderId());
        } catch (Exception e) {
            // 묻지 못했다. 🔴 상태를 바꾸지 않는다 — 모른다는 사실이 바뀐 것이 없다.
            log.warn("식별자 조회 실패 — 상태를 유지한다 (orderId={}, 식별자={}): {}",
                    order.getId(), order.getClientOrderId(), e.getMessage());
            orderRepository.touchUnknownProbe(order.getId(), Instant.now());
            return Boolean.FALSE;
        }

        orderRepository.touchUnknownProbe(order.getId(), Instant.now());

        if (found == null) {
            // 거래소가 "없다" 고 답했다. 🔴 그래도 종결하지 않는다 — 보존 기간이 명세에 없으므로
            //    "없다" 가 "접수된 적 없다" 와 같지 않다. 불명으로 올려 두고 사람이 판단한다.
            if (!"UNKNOWN".equals(order.getState())) {
                transitionState(order, "UNKNOWN", null);
            }
            OrderEntity fresh = orderRepository.findById(orderId).orElse(order);
            fresh.setUnknownReason("식별자로 조회되지 않음 — 미접수로 단정하지 않는다 (시도 "
                    + fresh.getUnknownProbeCount() + "회)");
            orderRepository.save(fresh);
            return Boolean.FALSE;
        }

        // 찾았다. UUID 를 확보해 기존 추적 경로에 연결한다.
        order.setExchangeOrderId(found.getUuid());
        if (order.getSubmittedAt() == null) {
            order.setSubmittedAt(Instant.now());
        }
        order.setUnknownReason(null);
        orderRepository.save(order);
        log.info("불명 주문을 식별자로 확인했다 (orderId={}, 식별자={}, uuid={}, 거래소상태={})",
                order.getId(), order.getClientOrderId(), found.getUuid(), found.getState());

        // 🔴 상태를 SUBMITTED 로 올려 **기존 폴링·정산 경로에 넘긴다.** 체결 반영은 이 메서드가
        //    직접 하지 않는다 — V81~V83 의 회계 경로(차분·버전 가드·원자적 반영)를 한 곳으로
        //    모아 두었으므로, 여기서 따로 반영하면 그 경로를 두 벌 갖게 된다.
        //
        //    🔴 조회는 **이 트랜잭션이 커밋된 뒤** 호출자가 한다 — 여기서 부르면 정산의
        //    `REQUIRES_NEW` 가 이 트랜잭션을 정지시켜 전이를 보지 못하고, 뒤이은 이 커밋이
        //    종결을 덮어쓴다.
        transitionState(order, "SUBMITTED", null);
        return Boolean.TRUE;
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
        // 🔴 **식별자를 함께 보낸다** (V84). 응답을 잃어도 이 값으로 조회할 수 있다 — UUID 는
        //    응답에만 오므로 응답 유실과 함께 사라진다.
        return upbitOrderClient.createOrder(order.getCoinPair(), upbitSide, volume, price,
                upbitOrderType, order.getClientOrderId());
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

        if ("CANCELLED".equals(newState)) {
            // 🔴 체결량이 보고되지 않은 취소는 **확인이 아니다** (2026-10-13). CANCELLED 로 닫으면 고아
            //    정리가 예약을 전액 복원할 수 있는데, 그사이 체결이 있었는지 모른다. 활성으로 남겨 다음
            //    조회에서 체결량과 함께 확인한다.
            if (exchangeStatus.getExecutedVolume() == null) {
                log.warn("거래소가 취소를 보고했지만 체결량을 보고하지 않았다 — 활성으로 남긴다 (orderId={})",
                        order.getId());
                return;
            }
            // 여기 도달 = 체결량이 명시적으로 0 이다(양수는 위의 부분 체결 분기). 전액 복원의 근거를 남긴다.
            order.setCancelConfirmedAt(Instant.now());
            if (order.getCancelledAt() == null) {
                order.setCancelledAt(Instant.now());
            }
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
