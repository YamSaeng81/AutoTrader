package com.cryptoautotrader.api.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-10-23 — <b>DYNAMIC PAPER 매수가 같은 틱의 워치리스트 갱신과 겹쳐도 완료된다</b>(운영 결함 재현 → 수정 회귀).
 *
 * <h3>운영 관측 (2026-10-22, DYNAMIC 85 — 페이퍼 모드)</h3>
 * <p>18:00:23Z·20:00:10Z 에 워치리스트 갱신(60분 주기)이 일어난 <b>같은 틱</b>에서 #85 가 BUY 신호로
 * {@code [Dynamic][PAPER] 매수 체결} 을 남긴 지 4ms 뒤 {@code ObjectOptimisticLockingFailureException
 * … DynamicSessionEntity#85}(원인 {@code StaleObjectStateException})로 {@code processTick} 이 깨졌다.
 * 10-16 부터 하루 0~9건.</p>
 *
 * <h3>메커니즘</h3>
 * <ol>
 *   <li>틱 트랜잭션이 세션 행을 관리 엔티티로 읽는다(버전 v).</li>
 *   <li>{@code persistWatchlist} 가 {@code balanceUpdater}(REQUIRES_NEW, 별도 연결)로 같은 행을 써서 <b>v+1 로 커밋</b>.</li>
 *   <li>(수정 전) {@code executePaperBuy} 가 바깥 관리 엔티티(버전 v)에 잔액·상태를 쓰고 {@code save} — 커밋 때
 *       {@code where version = v} 가 0행 → 포지션·주문·세션 변경 전부 롤백. 로그의 "매수 체결" 은 커밋 전에 찍혀
 *       실제로는 매수가 남지 않았다.</li>
 * </ol>
 *
 * <h3>수정 (2026-10-23, 리뷰 동의 범위)</h3>
 * <p>PAPER 매수도 실자금 경로처럼 <b>세션을 잠근 채 최신으로 다시 읽고</b>, 그 최신 상태에서 정지 여부·가용 자금·중복 보유를
 * 확인한 뒤에만 반영한다(낡은 엔티티를 고친 다음 다시 읽지 않는다). 체결 완료 로그는 <b>커밋 성공 뒤에만</b> 남긴다.
 * 평가 전체 재시도·PAPER 에 공유 풀 규칙 소급은 하지 않는다.</p>
 * <p>🔴 이것으로 좁혀진 것은 "같은 틱 워치리스트 갱신에 의한 충돌은 PAPER 매수 경로의 결함" 까지다 — 실자금의 다른 동시 쓰기
 * (WS 매도·정지 요청 등)까지 안전하다는 판정이 아니다.</p>
 * <p>시험 이력: 처음(수정 전) 첫 시험은 결함 재현(낙관적 잠금 실패·포지션 0)을 기대했다. 수정 뒤 기대를 매수 완료로 바꿨다.</p>
 */
class DynamicPaperBuySameTickWatchlistTest extends IntegrationTestBase {

    private static final BigDecimal CAPITAL = new BigDecimal("10000.00");
    private static final String FILLED_LOG = "[Dynamic][PAPER] 매수 체결";

    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private DynamicSessionRepository sessionRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PlatformTransactionManager txManager;

    /** 거래소 경계만 대역 — REAL 대조 시험의 주문 제출이 밖으로 나가지 않게. */
    @MockBean
    private com.cryptoautotrader.exchange.upbit.UpbitOrderClient upbitOrderClient;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger serviceLogger;
    private Level savedLevel;

    /** 시험 프로필은 com.cryptoautotrader 를 WARN 으로 둔다 — 체결 로그(INFO)를 보려면 이 시험 동안만 INFO 로 올린다. */
    @BeforeEach
    void captureLogs() {
        serviceLogger = (Logger) LoggerFactory.getLogger(DynamicTradingService.class);
        savedLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.INFO);
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        serviceLogger.detachAppender(logs);
        serviceLogger.setLevel(savedLevel);
    }

    private long filledLogs() {
        return logs.list.stream().filter(e -> e.getFormattedMessage().startsWith(FILLED_LOG)).count();
    }

    private Long newRunningSession(String mode) {
        return sessionRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(CAPITAL).availableKrw(CAPITAL).totalAssetKrw(CAPITAL)
                .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("SCANNING").tradingMode(mode)
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

    /**
     * 생산 순서 — 틱 트랜잭션 안에서 세션을 읽고, (선택) 다른 트랜잭션이 끼어든 뒤 매수.
     * {@code between} 은 틱이 세션을 읽은 뒤·매수 전에 실행된다(워치리스트 기록 또는 다른 쓰기 흉내).
     * {@code rollback} 이면 매수 뒤 틱을 롤백시킨다. 결과 문자열(null = 매수 성공)과 커밋 실패를 돌려준다.
     */
    private Throwable tick(Long id, Runnable between, boolean rollback, AtomicReference<String> result) {
        StrategySignal buy = StrategySignal.buy(new BigDecimal("80"), "같은 틱 워치리스트 재현");
        try {
            new TransactionTemplate(txManager).execute(status -> {
                DynamicSessionEntity s = sessionRepo.findById(id).orElseThrow();
                if (between != null) between.run();
                String r = dynamicTradingService.executeBuy(s, "KRW-BTC", candles(), buy, BigDecimal.ONE);
                if (result != null) result.set(r);
                if (rollback) status.setRollbackOnly();
                return null;
            });
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private Runnable watchlist(Long id) {
        return () -> dynamicTradingService.persistWatchlist(id, "[\"KRW-BTC\"]");
    }

    /** 틱 바깥(별도 트랜잭션)에서 세션 행을 고친다 — 틱이 읽은 뒤에 일어난 다른 쓰기를 흉내. */
    private Runnable otherWrite(Long id, Consumer<DynamicSessionEntity> change) {
        return () -> {
            TransactionTemplate t = new TransactionTemplate(txManager);
            t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            t.executeWithoutResult(st -> {
                DynamicSessionEntity s = sessionRepo.findById(id).orElseThrow();
                change.accept(s);
                sessionRepo.save(s);
            });
        };
    }

    /** 🔴 세션 구분값은 모드마다 다르다 — PAPER 는 "DYN_PAPER", REAL 은 "DYNAMIC"({@link DynamicTradingService#sessionKind}).
     *  처음에 "DYNAMIC" 으로만 조회해 PAPER 의 "포지션 없음" 이 늘 참인 빈 확인이 됐었다(2026-10-23 바로잡음). */
    private String kind(Long id) {
        return DynamicTradingService.sessionKind(sessionRepo.findById(id).orElseThrow());
    }

    private List<PositionEntity> openPositions(Long id) {
        return positionRepository.findBySessionKindAndSessionIdAndStatus(kind(id), id, "OPEN");
    }

    @Test
    @DisplayName("🔴 PAPER — 같은 틱 워치리스트 갱신 뒤에도 매수가 완료된다(잠근 채 최신으로 다시 읽기) · 체결 로그 1건")
    void paperBuyAfterSameTickWatchlist_completes() {
        Long id = newRunningSession("PAPER");
        assertThat(kind(id)).isEqualTo("DYN_PAPER");

        AtomicReference<String> result = new AtomicReference<>("미실행");
        Throwable failure = tick(id, watchlist(id), false, result);

        assertThat(failure).as("운영 #85 의 낙관적 잠금 실패가 더는 나지 않는다").isNull();
        assertThat(result.get()).as("매수 성공(차단 사유 없음)").isNull();
        DynamicSessionEntity after = sessionRepo.findById(id).orElseThrow();
        assertThat(openPositions(id)).hasSize(1);
        assertThat(orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind(id), id)).hasSize(1);
        assertThat(after.getAvailableKrw()).as("투자금(가용 × 0.8)만큼 정확히 한 번 차감")
                .isEqualByComparingTo(CAPITAL.subtract(CAPITAL.multiply(new BigDecimal("0.8000"))));
        assertThat(after.getScanState()).isEqualTo("POSITION_MONITORING");
        assertThat(after.getCurrentPositionId()).isEqualTo(openPositions(id).get(0).getId());
        assertThat(after.getWatchlistJson()).as("같은 틱 워치리스트 기록도 보존").isEqualTo("[\"KRW-BTC\"]");
        assertThat(filledLogs()).as("커밋 성공 뒤 체결 로그 1건").isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 틱이 롤백되면 '매수 체결' 로그가 남지 않는다(종전엔 커밋 전에 찍혀 롤백된 매수도 체결로 남았다)")
    void rolledBackBuy_leavesNoFilledLog() {
        Long id = newRunningSession("PAPER");

        AtomicReference<String> result = new AtomicReference<>("미실행");
        tick(id, watchlist(id), true, result);

        assertThat(result.get()).as("매수 로직 자체는 성공까지 갔다").isNull();
        assertThat(openPositions(id)).as("롤백 — 포지션 없음").isEmpty();
        assertThat(sessionRepo.findById(id).orElseThrow().getAvailableKrw()).isEqualByComparingTo(CAPITAL);
        assertThat(filledLogs()).as("롤백된 매수는 체결 로그를 남기지 않는다").isZero();
    }

    @Test
    @DisplayName("최신 상태 확인 ① — 틱이 읽은 뒤 세션이 정지됐으면 매수하지 않는다")
    void staleRunning_butStoppedNow_noBuy() {
        Long id = newRunningSession("PAPER");

        AtomicReference<String> result = new AtomicReference<>();
        Throwable failure = tick(id, otherWrite(id, s -> s.setStatus("STOPPED")), false, result);

        assertThat(failure).isNull();
        assertThat(result.get()).contains("실행 중이 아님");
        assertThat(openPositions(id)).isEmpty();
        assertThat(sessionRepo.findById(id).orElseThrow().getStatus()).isEqualTo("STOPPED");
        assertThat(filledLogs()).isZero();
    }

    @Test
    @DisplayName("최신 상태 확인 ② — 틱이 읽은 뒤 가용 자금이 줄어 투자금보다 적으면 매수하지 않는다")
    void staleBalance_insufficientNow_noBuy() {
        Long id = newRunningSession("PAPER");

        AtomicReference<String> result = new AtomicReference<>();
        Throwable failure = tick(id, otherWrite(id, s -> s.setAvailableKrw(new BigDecimal("6000.00"))), false, result);

        assertThat(failure).isNull();
        assertThat(result.get()).as("투자금 8,000 > 최신 가용 6,000").contains("가용 KRW 부족(최신 상태)");
        assertThat(openPositions(id)).isEmpty();
        assertThat(sessionRepo.findById(id).orElseThrow().getAvailableKrw()).isEqualByComparingTo("6000.00");
    }

    @Test
    @DisplayName("최신 상태 확인 ③ — 틱이 읽은 뒤 이미 보유 상태가 됐으면 중복 매수하지 않는다")
    void staleScanning_butMonitoringNow_noBuy() {
        Long id = newRunningSession("PAPER");

        AtomicReference<String> result = new AtomicReference<>();
        Throwable failure = tick(id, otherWrite(id, s -> s.setScanState("POSITION_MONITORING")), false, result);

        assertThat(failure).isNull();
        assertThat(result.get()).contains("이미 보유 중");
        assertThat(openPositions(id)).isEmpty();
        assertThat(sessionRepo.findById(id).orElseThrow().getAvailableKrw()).isEqualByComparingTo(CAPITAL);
    }

    @Test
    @DisplayName("대조 — PAPER 도 같은 틱에 워치리스트 갱신이 없으면 매수가 완료된다")
    void paperBuyWithoutWatchlist_completes() {
        Long id = newRunningSession("PAPER");

        assertThat(tick(id, null, false, null)).isNull();
        DynamicSessionEntity after = sessionRepo.findById(id).orElseThrow();
        assertThat(openPositions(id)).hasSize(1);
        assertThat(after.getScanState()).isEqualTo("POSITION_MONITORING");
        assertThat(after.getAvailableKrw()).isLessThan(CAPITAL);
    }

    @Test
    @DisplayName("대조 — REAL 은 같은 순서에서도 매수가 완료된다(2026-10-12 잠근 채 다시 읽기)")
    void realBuyAfterSameTickWatchlist_completes() {
        Long id = newRunningSession("REAL");

        Throwable failure = tick(id, watchlist(id), false, null);

        assertThat(failure).as("실자금 경로는 깨지지 않아야 한다").isNull();
        assertThat(openPositions(id)).hasSize(1);
        assertThat(sessionRepo.findById(id).orElseThrow().getScanState()).isEqualTo("POSITION_MONITORING");
    }
}
