package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionCleanupEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionCleanupRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.api.support.OrderFlowObservation;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 스케줄러가 <b>실제로</b> 정리 대기를 가져가는가 (2026-10-13).
 *
 * <p>시험 기반은 스케줄러를 끈다. 다른 시험은 정리 작업을 직접 부르므로, 운영처럼 {@code @Scheduled} 로
 * 돌 때 대기 행을 가져가는지는 그 시험들이 보이지 않는다. 이 클래스만 {@code scheduler} 프로필로 켠다.</p>
 *
 * <p>🔴 {@code @DirtiesContext} — 이 컨텍스트의 스케줄러는 공유 DB 를 바꾼다. 캐시에 남으면 뒤에 도는 다른
 * 시험 클래스의 데이터를 건드리므로 클래스가 끝나면 닫는다.</p>
 *
 * <p>🔬 <b>관측 (총괄 v1.160 §3, 2026-11-13)</b> — 802b691a 전체 회귀에서 LIVE 가 60초 안에 거래소 주문 ID 가 붙은 매도를 보지 못했다
 * (매도 의도 생성 뒤 제출 미확인, 원인 미확정). 같은 주문의 CLOSING·의도 저장 → 호출자 커밋 → 실행기 접수·시작 → claim → 거래소 대역
 * 호출 → 결과 저장·커밋을 {@link OrderFlowObservation} 으로 잇는다. 예약 실행 진입점·60초 기준·제출 1회·중복 방지·미완료 단언은
 * 그대로다. 관측 조회가 실패해도 기록만 남기고 원래 단언으로 간다.</p>
 *
 * <p>관측이 만드는 컨텍스트 차이: {@code @Import(OrderFlowObservation.Config)} 가 {@code orderExecutor} 초기화 콜백 전에 작업 꾸밈 장치를
 * 설정하고(빈 종류·프록시·풀 설정 불변), {@code @TestPropertySource} 가 Hibernate 문장 관찰기를 등록한다(SQL 불변). 둘 다 이 클래스의
 * 컨텍스트 키를 바꾼다 — 이 클래스는 원래 자체 프로필·대역으로 별도 컨텍스트다.</p>
 */
@ActiveProfiles("scheduler")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(OrderFlowObservation.Config.class)
@TestPropertySource(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.cryptoautotrader.api.support.OrderFlowObservation$Inspector")
@DisplayName("scheduler 프로필 — 정리 작업이 대기 행을 가져간다")
class SchedulerProfileCleanupTest extends IntegrationTestBase {

    private static final String TAG = "[관측 정리시험]";

    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private PositionCleanupRepository cleanupRepository;
    @Autowired private PositionCleanupRegistrar cleanupRegistrar;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private ApplicationContext applicationContext;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;

    private final List<Long> liveSessions = new ArrayList<>();
    private final List<Long> dynamicSessions = new ArrayList<>();

    @BeforeEach
    void exchange() {
        Mockito.when(upbitOrderClient.getOrder(Mockito.anyString())).thenAnswer(inv -> {
            OrderResponse r = new OrderResponse();
            r.setUuid(inv.getArgument(0));
            r.setState("wait");
            return r;
        });
        Mockito.when(upbitOrderClient.createOrder(Mockito.any(), Mockito.eq("ask"), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any())).thenAnswer(inv -> {
            // 🔬 거래소 대역 호출 — 식별자(우리 clientOrderId)로 주문 행과 잇는다. 응답 원문은 적지 않는다.
            OrderFlowObservation.record("EXCHANGE_CALL", "identifier=" + inv.getArgument(5));
            OrderResponse r = new OrderResponse();
            r.setUuid("uuid-sched-sell-" + System.nanoTime());
            r.setState("wait");
            r.setSide("ask");
            OrderFlowObservation.record("EXCHANGE_RETURN", "identifier=" + inv.getArgument(5) + " uuid=" + r.getUuid());
            return new UpbitOrderClient.ExchangeResult(r, "{}");
        });
    }

    @AfterEach
    void cleanUp() {
        for (Long id : liveSessions) {
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(p -> cleanupRepository.deleteById(p.getId()));
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            liveRepo.deleteById(id);
        }
        for (Long id : dynamicSessions) {
            positionRepository.findBySessionKindAndSessionId("DYNAMIC", id).forEach(p -> cleanupRepository.deleteById(p.getId()));
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("DYNAMIC", id).forEach(positionRepository::delete);
            dynamicRepo.deleteById(id);
        }
        liveSessions.clear();
        dynamicSessions.clear();
    }

    private Long stoppedSession(String kind) {
        if ("DYNAMIC".equals(kind)) {
            Long id = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                    .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                    .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("7000"))
                    .totalAssetKrw(new BigDecimal("10000"))
                    .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00"))
                    .status("STOPPED").scanState("POSITION_MONITORING").tradingMode("REAL")
                    .maxCandidateSize(30).targetWatchSize(10)
                    .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                    .watchlistRefreshMin(60).build()).getId();
            dynamicSessions.add(id);
            return id;
        }
        Long id = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE").timeframe("M5")
                .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("7000"))
                .totalAssetKrw(new BigDecimal("10000"))
                .status("STOPPED").startedAt(Instant.now()).build()).getId();
        liveSessions.add(id);
        return id;
    }

    private static boolean await(BooleanSupplier cond, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(250);
        }
        return cond.getAsBoolean();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("정지 세션의 보유 포지션 — 예약된 정리 작업이 청산 매도를 한 번 낸다")
    void scheduledWorkerPicksUpPendingCleanup(String kind) throws Exception {
        long mark = OrderFlowObservation.mark("시험 시작 " + kind);
        long base = System.nanoTime();
        observeStart(kind);

        Long sid = stoppedSession(kind);
        PositionEntity pos = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE").side("BUY")
                .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(new BigDecimal("9.8")).investedKrw(new BigDecimal("3000"))
                .status("OPEN").sessionKind(kind).sessionId(sid)
                .openedAt(Instant.now()).build());
        cleanupRegistrar.register(pos.getId(), kind, sid, PositionCleanupEntity.REASON_LATE_FILL);
        OrderFlowObservation.record("TEST_REGISTERED",
                "sessionKind=" + kind + " sessionId=" + sid + " positionId=" + pos.getId());

        boolean picked = await(() -> orderRepository.findByPositionIdOrderByCreatedAtDesc(pos.getId()).stream()
                .anyMatch(o -> "SELL".equalsIgnoreCase(o.getSide()) && o.getExchangeOrderId() != null), 60_000);
        OrderFlowObservation.record("TEST_AWAIT_END", "picked=" + picked);
        // 🔬 정리(@AfterEach) 전에 남긴다 — 실패면 전체, 통과면 이 주문에 이어진 기록만.
        observeOutcome(kind, sid, pos.getId(), picked, mark, base);

        PositionCleanupEntity c = cleanupRepository.findById(pos.getId()).orElseThrow();
        System.out.println("[스케줄러 " + kind + "] 가져감=" + picked + " 시도=" + c.getAttempts()
                + " 메모=" + c.getLastNote() + " 마지막시도=" + c.getLastAttemptAt()
                + " 포지션=" + positionRepository.findById(pos.getId()).orElseThrow().getStatus()
                + " 대기행(" + kind + ")=" + cleanupRepository.findPending(kind).size());
        assertThat(picked).as("scheduler 프로필에서 @Scheduled 정리 작업이 60초 안에 대기 행을 가져가야 한다").isTrue();

        // 한 주기(10초) 이상 더 지켜도 두 번째 매도가 나가지 않는다.
        Thread.sleep(12_000);
        long sells = orderRepository.findByPositionIdOrderByCreatedAtDesc(pos.getId()).stream()
                .filter(o -> "SELL".equalsIgnoreCase(o.getSide())).count();
        System.out.println("[스케줄러 " + kind + "] 12초 뒤 매도=" + sells
                + " 메모=" + cleanupRepository.findById(pos.getId()).orElseThrow().getLastNote());
        assertThat(sells).isEqualTo(1);
        Mockito.verify(upbitOrderClient, Mockito.times(1)).createOrder(Mockito.any(), Mockito.eq("ask"),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        assertThat(cleanupRepository.findById(pos.getId()).orElseThrow().getDoneAt())
                .as("CLOSED 확인 전에는 완료가 아니다").isNull();
    }

    // ---- 🔬 관측 (원래 단언과 분리 — 어떤 실패도 기록만 남긴다) ----------------------------------------

    private void observeStart(String kind) {
        try {
            String url;
            try (Connection cn = dataSource.getConnection()) {
                url = cn.getMetaData().getURL();
            }
            System.out.println(TAG + " 시작 " + kind + " context=" + applicationContext.getId()
                    + "@" + Integer.toHexString(System.identityHashCode(applicationContext))
                    + " startup=" + Instant.ofEpochMilli(applicationContext.getStartupDate())
                    + " db=" + url + " 관측장치=" + OrderFlowObservation.executorObserved());
            System.out.println(TAG + " 시작 " + OrderFlowObservation.executorStats());
            System.out.println(TAG + " 시작 DB 부하 " + OrderFlowObservation.kv(Map.of(
                    "활성주문", jdbc.queryForObject("SELECT COUNT(*) FROM \"order\" WHERE state IN "
                            + "('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED')", Long.class),
                    "INTENT_READY", jdbc.queryForObject("SELECT COUNT(*) FROM \"order\" WHERE state = 'INTENT_READY'", Long.class),
                    "정리대기", jdbc.queryForObject("SELECT COUNT(*) FROM position_cleanup WHERE done_at IS NULL", Long.class),
                    "OPEN/CLOSING포지션", jdbc.queryForObject(
                            "SELECT COUNT(*) FROM position WHERE status IN ('OPEN','CLOSING')", Long.class),
                    "RUNNING_LIVE", jdbc.queryForObject(
                            "SELECT COUNT(*) FROM live_trading_session WHERE status = 'RUNNING'", Long.class),
                    "RUNNING_DYNAMIC", jdbc.queryForObject(
                            "SELECT COUNT(*) FROM dynamic_session WHERE status = 'RUNNING'", Long.class))));
        } catch (Throwable t) {
            System.out.println(TAG + " 시작 관측 실패: " + t);
        }
    }

    private void observeOutcome(String kind, Long sid, Long posId, boolean picked, long mark, long base) {
        try {
            System.out.println(TAG + " 결과 " + kind + " picked=" + picked + " sessionId=" + sid + " positionId=" + posId
                    + " 관측누락=" + OrderFlowObservation.dropped());
            System.out.println(TAG + " 결과 " + OrderFlowObservation.executorStats());

            List<Map<String, Object>> orders = jdbc.queryForList(
                    "SELECT id, side, state, failure_code, client_order_id, exchange_order_id, created_at, "
                            + "intent_committed_at, claimed_at, submitted_at, unknown_reason FROM \"order\" "
                            + "WHERE position_id = ? ORDER BY id", posId);
            Set<String> identifiers = new HashSet<>();
            for (Map<String, Object> o : orders) {
                System.out.println(TAG + " 주문 " + OrderFlowObservation.kv(o));
                if (o.get("CLIENT_ORDER_ID") != null) identifiers.add(o.get("CLIENT_ORDER_ID").toString());
                for (Map<String, Object> l : jdbc.queryForList(
                        "SELECT id, event_type, old_state, new_state, created_at FROM trade_log WHERE order_id = ? ORDER BY id",
                        o.get("ID"))) {
                    System.out.println(TAG + "   거래로그 " + OrderFlowObservation.kv(l));
                }
            }
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT position_id, session_kind, session_id, attempts, last_attempt_at, last_note, done_at "
                            + "FROM position_cleanup WHERE position_id = ?", posId)) {
                System.out.println(TAG + " 정리행 " + OrderFlowObservation.kv(r));
            }
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT id, status, size, closing_at, closed_at FROM position WHERE id = ?", posId)) {
                System.out.println(TAG + " 포지션 " + OrderFlowObservation.kv(r));
            }

            List<OrderFlowObservation.Event> events = OrderFlowObservation.since(mark);
            // 이 주문에 이어진 실행기 작업: 거래소 대역 호출의 식별자가 이 주문의 것인 작업
            Set<Long> tasks = new HashSet<>();
            for (OrderFlowObservation.Event e : events) {
                if (e.task() != null && e.kind().startsWith("EXCHANGE_") && e.detail() != null
                        && identifiers.stream().anyMatch(id -> e.detail().contains("identifier=" + id))) {
                    tasks.add(e.task());
                }
            }
            System.out.println(TAG + " 이 주문의 실행기 작업=" + tasks + " (거래소 대역 호출 식별자 기준)");
            List<String> dump = picked ? List.of() : OrderFlowObservation.threadDump();
            System.out.println(TAG + " 분류 보조: " + classify(orders, events, tasks, dump));

            // 통과: 시험 등록 뒤 첫 TX_COMMIT 부터 이 주문의 작업 끝까지 중 이 주문과 이어진 줄 + 스케줄러 쓰기. 실패: 전체.
            int printed = 0;
            for (OrderFlowObservation.Event e : events) {
                boolean related = !picked
                        || e.kind().equals("MARK") || e.kind().startsWith("TEST_")
                        || (e.task() != null && tasks.contains(e.task()))
                        || e.thread().startsWith("scheduler-") && !e.kind().equals("SQL_R")
                        || e.kind().equals("EXEC_SUBMIT");
                if (!related) continue;
                if (++printed > 3000) {
                    System.out.println(TAG + " 기록 3000줄 초과 — 이후 생략 (전체 " + events.size() + "줄)");
                    break;
                }
                System.out.println(TAG + " " + e.format(base));
            }
            if (!picked) {
                System.out.println(TAG + " 스레드 덤프 " + dump.size() + "개 (60초 초과, 정리 전)");
                for (String s : dump) {
                    System.out.println(TAG + " " + s);
                }
            }
        } catch (Throwable t) {
            System.out.println(TAG + " 결과 관측 실패: " + t);
        }
    }

    /** 관측으로 본 멈춘 단계 — 판정이 아니라 다음 조사의 입력이다. */
    private static String classify(List<Map<String, Object>> orders, List<OrderFlowObservation.Event> events,
                                   Set<Long> tasks, List<String> dump) {
        Map<String, Object> sell = orders.stream().filter(o -> "SELL".equalsIgnoreCase(String.valueOf(o.get("SIDE"))))
                .reduce((a, b) -> b).orElse(null);
        Set<Long> submitted = new HashSet<>();
        Set<Long> started = new HashSet<>();
        Set<Long> ended = new HashSet<>();
        for (OrderFlowObservation.Event e : events) {
            if (e.kind().equals("EXEC_SUBMIT") && e.detail() != null) {
                submitted.add(Long.parseLong(e.detail().substring(5, e.detail().indexOf(' '))));
            } else if (e.kind().equals("EXEC_START")) {
                started.add(e.task());
            } else if (e.kind().equals("EXEC_END") || e.kind().equals("EXEC_ERROR")) {
                ended.add(e.task());
            }
        }
        Set<Long> waiting = new HashSet<>(submitted);
        waiting.removeAll(started);
        Set<Long> running = new HashSet<>(started);
        running.removeAll(ended);
        String hints = OrderFlowObservation.waitHints(dump).toString();
        boolean called = !tasks.isEmpty();
        boolean returned = events.stream().anyMatch(e -> e.kind().equals("EXCHANGE_RETURN") && tasks.contains(e.task()));
        String ex = " (접수후미시작=" + waiting + " 진행중=" + running + " 대역호출=" + called + " 대역반환=" + returned
                + " 스택=" + hints + ")";
        if (sell == null) return "매도 의도 행 없음" + ex;
        String state = String.valueOf(sell.get("STATE"));
        return switch (state) {
            case "INTENT_READY" -> (waiting.isEmpty() ? (running.isEmpty() ? "의도 커밋 뒤 실행기 접수 기록 없음 또는 claim 전 종료"
                    : "실행기 작업 진행 중, claim 전") : "실행기 대기(접수 뒤 미시작)") + ex;
            case "SUBMITTING" -> (!called ? "claim 뒤 거래소 대역 호출 전" : !returned ? "거래소 대역 호출 중"
                    : "거래소 대역 반환 뒤 결과 저장 전") + ex;
            case "FAILED" -> "FAILED failure_code=" + sell.get("FAILURE_CODE") + ex;
            case "UNKNOWN" -> "UNKNOWN(제출 결과 불명)" + ex;
            default -> (sell.get("EXCHANGE_ORDER_ID") != null ? "제출 기록됨 state=" + state : "state=" + state + " 거래소 주문 ID 없음") + ex;
        };
    }
}
