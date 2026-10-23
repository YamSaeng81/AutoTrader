package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-10-23 — <b>DYNAMIC PAPER 매수가 같은 틱의 워치리스트 갱신과 겹치면 커밋에서 깨진다</b>(운영 재현).
 *
 * <h3>운영 관측 (2026-10-22, DYNAMIC 85 — 페이퍼 모드)</h3>
 * <p>18:00:23Z·20:00:10Z 에 워치리스트 갱신(60분 주기)이 일어난 <b>같은 틱</b>에서 #85 가 BUY 신호로
 * {@code [Dynamic][PAPER] 매수 체결} 을 남긴 지 4ms 뒤 {@code ObjectOptimisticLockingFailureException
 * … DynamicSessionEntity#85}(원인 {@code StaleObjectStateException})로 {@code processTick} 이 깨졌다.
 * 10-16 부터 하루 0~9건.</p>
 *
 * <h3>메커니즘(이 시험이 생산 경로로 확인)</h3>
 * <ol>
 *   <li>틱 트랜잭션이 세션 행을 관리 엔티티로 읽는다(버전 v).</li>
 *   <li>{@code persistWatchlist} 가 {@code balanceUpdater}(REQUIRES_NEW, 별도 연결)로 같은 행을 써서 <b>v+1 로 커밋</b>.</li>
 *   <li>{@code executePaperBuy} 가 바깥 관리 엔티티(버전 v)에 잔액·상태를 쓰고 {@code save} — 커밋 때
 *       {@code where version = v} 가 0행 → <b>낙관적 잠금 실패, 포지션·주문·세션 변경 전부 롤백</b>.
 *       로그의 "매수 체결" 은 커밋 전에 찍히므로 <b>실제로는 매수가 남지 않는다.</b></li>
 * </ol>
 * <p>실자금(REAL) 매수 경로는 2026-10-12 에 "잠근 채 최신으로 다시 읽기"(refresh + PESSIMISTIC_WRITE)를 넣어
 * 같은 순서에서도 완료된다 — {@code DynamicBuyCompletesConsistentlyTest} 와 아래 대조 시험이 고정한다.
 * 🔴 이 시험은 <b>현재 결함의 재현</b>이다. PAPER 경로를 고치면 첫 시험의 기대를 "매수 완료" 로 바꾼다(지우지 않는다).
 * 🔴 고칠 때 평가 전체를 재시도하지 않는다(리뷰 2026-10-23) — 주문 제출·별도 트랜잭션 반영이 이미 끝났을 수 있다.</p>
 */
class DynamicPaperBuySameTickWatchlistTest extends IntegrationTestBase {

    private static final BigDecimal CAPITAL = new BigDecimal("10000.00");

    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private DynamicSessionRepository sessionRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PlatformTransactionManager txManager;

    /** 거래소 경계만 대역 — REAL 대조 시험의 주문 제출이 밖으로 나가지 않게. */
    @MockBean
    private com.cryptoautotrader.exchange.upbit.UpbitOrderClient upbitOrderClient;

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

    /** 생산 순서 — 틱 트랜잭션 안에서 (선택적으로) 워치리스트 기록 뒤 매수. 커밋 실패를 돌려준다. */
    private Throwable tick(Long id, boolean watchlistRefresh) {
        StrategySignal buy = StrategySignal.buy(new BigDecimal("80"), "같은 틱 워치리스트 재현");
        try {
            new TransactionTemplate(txManager).execute(status -> {
                DynamicSessionEntity s = sessionRepo.findById(id).orElseThrow();
                if (watchlistRefresh) {
                    dynamicTradingService.persistWatchlist(id, "[\"KRW-BTC\"]");
                }
                dynamicTradingService.executeBuy(s, "KRW-BTC", candles(), buy, BigDecimal.ONE);
                return null;
            });
            return null;
        } catch (Throwable t) {
            return t;
        }
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
    @DisplayName("🔴 [결함 재현] PAPER — 같은 틱 워치리스트 갱신 뒤 매수는 커밋에서 낙관적 잠금 실패, 매수 기록 전부 롤백")
    void paperBuyAfterSameTickWatchlist_failsAndRollsBack() {
        Long id = newRunningSession("PAPER");
        assertThat(kind(id)).isEqualTo("DYN_PAPER");

        Throwable failure = tick(id, true);

        assertThat(failure).as("운영 #85 와 같은 예외로 깨진다").isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(failure.getMessage()).contains("DynamicSessionEntity");
        DynamicSessionEntity after = sessionRepo.findById(id).orElseThrow();
        assertThat(openPositions(id)).as("'매수 체결' 로그와 달리 포지션은 남지 않는다").isEmpty();
        assertThat(orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind(id), id)).as("주문도 롤백").isEmpty();
        assertThat(after.getAvailableKrw()).as("잔액은 차감되지 않은 채").isEqualByComparingTo(CAPITAL);
        assertThat(after.getScanState()).as("계속 스캔 상태").isEqualTo("SCANNING");
        assertThat(after.getWatchlistJson()).as("워치리스트 기록(별도 트랜잭션)만 남는다").isEqualTo("[\"KRW-BTC\"]");
    }

    @Test
    @DisplayName("대조 — PAPER 도 같은 틱에 워치리스트 갱신이 없으면 매수가 완료된다(충돌 상대는 워치리스트 기록)")
    void paperBuyWithoutWatchlist_completes() {
        Long id = newRunningSession("PAPER");

        assertThat(tick(id, false)).isNull();
        DynamicSessionEntity after = sessionRepo.findById(id).orElseThrow();
        assertThat(openPositions(id)).hasSize(1);
        assertThat(after.getScanState()).isEqualTo("POSITION_MONITORING");
        assertThat(after.getAvailableKrw()).isLessThan(CAPITAL);
    }

    @Test
    @DisplayName("대조 — REAL 은 같은 순서에서도 매수가 완료된다(2026-10-12 잠근 채 다시 읽기)")
    void realBuyAfterSameTickWatchlist_completes() {
        Long id = newRunningSession("REAL");

        Throwable failure = tick(id, true);

        assertThat(failure).as("실자금 경로는 깨지지 않아야 한다").isNull();
        assertThat(openPositions(id)).hasSize(1);
        assertThat(sessionRepo.findById(id).orElseThrow().getScanState()).isEqualTo("POSITION_MONITORING");
    }
}
