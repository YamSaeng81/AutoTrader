package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.PaperTradingStartRequest;
import com.cryptoautotrader.api.dto.PerformanceSummaryResponse;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.core.risk.ExitRuleConfig;
import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.entity.paper.PaperOrderEntity;
import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.repository.paper.PaperOrderRepository;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import com.cryptoautotrader.api.util.TimeframeUtils;
import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.core.regime.MarketRegimeDetector;
import com.cryptoautotrader.core.selector.BlackSwanGuard;
import com.cryptoautotrader.core.selector.BtcMarketGuard;
import com.cryptoautotrader.core.selector.Ema200RegimeGate;
import com.cryptoautotrader.core.selector.RangeRegimeGate;
import com.cryptoautotrader.exchange.upbit.UpbitCandleCollector;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import org.springframework.beans.factory.annotation.Autowired;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import com.cryptoautotrader.api.util.TradingConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cryptoautotrader.api.dto.MultiStrategyPaperRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.cryptoautotrader.core.risk.RiskCheckResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import com.cryptoautotrader.core.risk.SignalExitGate;
import com.cryptoautotrader.api.util.TickCandleCache;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaperTradingService {

    /**
     * 동시 실행 가능한 모의투자 세션 수.
     *
     * <p>2026-08-06 20 → 120으로 상향. 목적은 <b>코인 N개 × 전략 M개 격자 실험</b>이다
     * (예: 10코인 × 10전략 = 100세션). 실자본 매매가 시장 대비 알파 음수인 상태에서,
     * 실전 진입 빈도(6일 8거래)로는 통계적 유의성에 영원히 도달하지 못한다는 판단에 따른 것.</p>
     *
     * <p>세션 수가 늘어도 캔들 조회는 {@code (coinPair, timeframe)} 조합 수만큼만 발생한다
     * (아래 틱 단위 캐시). 100세션이 10코인을 쓰면 조회는 11회(코인 10 + BTC 가드 1)다.</p>
     */
    private static final int MAX_CONCURRENT_SESSIONS = 120;
    private static final BigDecimal FEE_RATE = TradingConstants.FEE_RATE;
    // 백테스트(BacktestEngine.MAX_LOOKBACK)·실거래(LiveTradingService.CANDLE_LOOKBACK)와 동일하게
    // 맞춰야 페이퍼 승격 판단이 실거래 신호와 같은 조건에서 검증된다.
    private static final int CANDLE_LOOKBACK = TradingConstants.CANDLE_LOOKBACK;

    // ── LIVE 정렬 상수 (2026-08-06) ───────────────────────────────────────────
    // 아래 3개는 LiveTradingService의 동일 이름 상수와 값이 반드시 같아야 한다.
    // 하나라도 어긋나면 "페이퍼에서 검증하고 실전에 올린다"는 절차가 다시 성립하지 않는다.

    /** 전략 SELL 최소 보유시간(분) — 진입 직후 동가 청산 패턴 차단. SL/TP는 이 게이트와 무관하게 항상 동작. */
    private static final long MIN_HOLD_MINUTES_FOR_SIGNAL_EXIT =
            ExitRuleConfig.defaults().getMinHoldMinutesForSignalExit();
    /** 본전 청산 차단 하한(%) — 이 미만 수익에서의 전략 SELL은 무시한다. */
    private static final BigDecimal MIN_PNL_PCT_FOR_SIGNAL_EXIT =
            ExitRuleConfig.defaults().getMinPnlPctForSignalExit();
    /**
     * 손실 탈출 임계(%) — 이보다 더 잃고 있으면 본전 청산 차단을 풀어 전략 SELL을 허용한다.
     * 2026-08-18: −1.00 → −0.30. 단일 출처는 {@link ExitRuleConfig} (근거는 그 필드 javadoc).
     */
    private static final BigDecimal LOSS_ESCAPE_THRESHOLD =
            ExitRuleConfig.defaults().getLossEscapeThresholdPct();

    /** 업비트 최소 주문금액(KRW) — 실거래에서 못 넣는 주문을 페이퍼가 체결하면 모집단이 어긋난다. */
    private static final BigDecimal MIN_ORDER_KRW = new BigDecimal("5000");

    /**
     * 체결 슬리피지(0.001 = 0.1%) — 매수는 불리하게 높게, 매도는 불리하게 낮게 체결시킨다.
     *
     * <p>기존 페이퍼는 캔들 종가에 <b>정확히</b> 체결시켜 실거래에 없는 이점을 누렸다.
     * 백테스트(`BacktestEngine`)가 쓰는 0.1%와 같은 값으로 맞춰 세 엔진(백테스트·페이퍼·실전)의
     * 체결 가정을 통일한다. 실측 슬리피지는 LIVE BTC 기준 0.1% 수준이었다.</p>
     */
    private static final BigDecimal SLIPPAGE_PCT = TradingConstants.PAPER_SLIPPAGE_PCT;

    /** Stateful 전략 세션별 인스턴스 (COMPOSITE, COMPOSITE_MOMENTUM 등 상태 보유 전략) */
    private final Map<Long, com.cryptoautotrader.strategy.Strategy> sessionStatefulStrategies = new ConcurrentHashMap<>();

    /**
     * 세션별 마지막으로 평가한 <b>닫힌</b> 캔들 시각 — LIVE의 {@code lastEvaluatedClosedCandle}와 동일 목적.
     *
     * <p>이게 없으면 60초 스케줄이 같은 미완성 캔들을 반복 평가한다. 실전은 캔들이 닫힐 때
     * 1회만 평가하는데 페이퍼가 같은 캔들을 60번 다시 보면서 그때그때 변하는 종가로 판단하면,
     * 실전에 존재하지 않는 미세한 정보 이점이 생겨 성과가 부풀려진다.</p>
     */
    private final Map<Long, Instant> lastEvaluatedClosedCandle = new ConcurrentHashMap<>();

    private final VirtualBalanceRepository balanceRepo;
    private final PaperPositionRepository positionRepo;
    private final PaperOrderRepository orderRepo;
    private final MarketDataCacheRepository marketDataCacheRepo;
    private final TelegramNotificationService telegramService;
    /** 정지 중 동시 쓰기와 충돌하면 트랜잭션 단위로 재시도한다 (2026-10-07). */
    private final OptimisticRetry optimisticRetry;
    private final RulesetRegistry rulesetRegistry;
    private final StrategyLogRepository strategyLogRepo;
    private final RiskManagementService riskManagementService;
    private final StrategyEnablementGate strategyEnablementGate;

    /** DB에서 ExitRuleChecker를 동적 로드하는 헬퍼 */
    private com.cryptoautotrader.core.risk.ExitRuleChecker exitChecker() {
        return riskManagementService.getExitRuleChecker();
    }

    // exchange-adapter 모듈 Bean이 없을 때 null 허용 (테스트/개발 환경 대비)
    @Autowired(required = false)
    private UpbitRestClient upbitRestClient;

    // ── 공개 API ──────────────────────────────────────────────

    @Transactional
    public VirtualBalanceEntity start(PaperTradingStartRequest req) {
        long runningCount = balanceRepo.countByStatus("RUNNING");
        if (runningCount >= MAX_CONCURRENT_SESSIONS) {
            throw new IllegalStateException("최대 " + MAX_CONCURRENT_SESSIONS + "개의 동시 모의투자만 가능합니다.");
        }
        return createSession(req);
    }

    /**
     * 동일 조건(코인/타임프레임/투자금)으로 여러 전략을 한 번에 모의투자 등록.
     * 세션 한도 초과 여부를 일괄 사전 검증한 뒤 각 전략마다 독립 세션을 생성한다.
     */
    @Transactional
    public List<VirtualBalanceEntity> startMulti(MultiStrategyPaperRequest req) {
        int count = req.getStrategyTypes().size();
        long running = balanceRepo.countByStatus("RUNNING");
        if (running + count > MAX_CONCURRENT_SESSIONS) {
            throw new IllegalStateException(
                    "세션 한도 초과: 현재 " + running + "개 실행 중, " + count + "개 추가 시 최대 "
                            + MAX_CONCURRENT_SESSIONS + "개 초과합니다.");
        }
        List<VirtualBalanceEntity> sessions = new ArrayList<>();
        for (String strategyType : req.getStrategyTypes()) {
            PaperTradingStartRequest single = new PaperTradingStartRequest();
            single.setStrategyType(strategyType);
            single.setCoinPair(req.getCoinPair());
            single.setTimeframe(req.getTimeframe());
            single.setInitialCapital(req.getInitialCapital());
            single.setEnableTelegram(req.isEnableTelegram());
            sessions.add(createSession(single));
        }
        log.info("다중 전략 모의투자 {} 세션 생성: {} {} {}",
                count, req.getCoinPair(), req.getTimeframe(), req.getStrategyTypes());
        return sessions;
    }

    private VirtualBalanceEntity createSession(PaperTradingStartRequest req) {
        // 비활성 전략 차단 (2026-08-18) — start/startMulti 양쪽이 여기를 거치므로 한 곳에서 막는다.
        // 페이퍼에도 거는 이유: 페이퍼에서 죽은 전략을 페이퍼로 다시 돌릴 이유가 없다.
        // 타임프레임까지 확인한다 (2026-09-08 V80) — 폐기된 (전략, 타임프레임) 재생성 차단.
        strategyEnablementGate.assertEnabled(req.getStrategyType(), req.getTimeframe());
        // 1회 매수 상한·최소 원금 (2026-11-10, PaperMaxInvestment) — DTO 검증을 거치지 않는 호출도 같은 조건으로 막는다.
        String capitalViolation = PaperMaxInvestment.capitalViolation(req.getInitialCapital(), req.getStrategyParams());
        if (capitalViolation != null) {
            throw new IllegalStateException(capitalViolation);
        }
        // 실행 모드(2026-11-10, PaperExecutionMode) — 잘못된 값은 만들지 않는다.
        String modeViolation = PaperExecutionMode.violation(req.getStrategyParams());
        if (modeViolation != null) {
            throw new IllegalStateException(modeViolation);
        }

        VirtualBalanceEntity session = VirtualBalanceEntity.builder()
                .totalKrw(req.getInitialCapital())
                .availableKrw(req.getInitialCapital())
                .initialCapital(req.getInitialCapital())
                .strategyName(req.getStrategyType())
                .coinPair(req.getCoinPair())
                .timeframe(req.getTimeframe())
                .status("RUNNING")
                .startedAt(Instant.now())
                .telegramEnabled(req.isEnableTelegram())
                // LIVE 세션과 동일 조건으로 돌리기 위한 설정 (V66) — 미지정이면 risk_config 기본값으로 폴백
                .stopLossPct(req.getStopLossPct())
                .investRatio(req.getInvestRatio())
                .maxHoldHours(req.getMaxHoldHours() != null
                        ? req.getMaxHoldHours()
                        : LiveTradingSessionEntity.DEFAULT_MAX_HOLD_HOURS)
                // A/B 파라미터 (2026-08-24 누락 발견) — LIVE·DYNAMIC 은 처음부터 넘기고 있었는데
                // PAPER 만 빌더에서 빠져 있었다. 그 결과 strategyParams 를 실어 만든 세션이
                // 조용히 기본 규칙으로 돌고 **대조군과 구별 불가능한 중복 표본**이 됐다
                // (손절폭 A/B 1차 시도에서 40세션이 그렇게 만들어졌다).
                .strategyParams(req.getStrategyParams())
                .build();

        log.info("모의투자 세션 시작: {} {} {} 초기자본={}",
                req.getStrategyType(), req.getCoinPair(), req.getTimeframe(), req.getInitialCapital());
        VirtualBalanceEntity saved = balanceRepo.save(session);

        if (req.isEnableTelegram()) {
            telegramService.notifyPaperSessionStarted(
                    saved.getId(), req.getStrategyType(), req.getCoinPair(),
                    req.getTimeframe(), req.getInitialCapital());
        }
        return saved;
    }

    /**
     * 모의투자 세션 정지 — 열린 포지션을 청산하고 {@code STOPPED} 로 전환한다.
     *
     * <p>🔴 <b>{@code @Transactional} 을 떼고 {@link OptimisticRetry} 로 감쌌다 (2026-10-07).</b>
     * 종전에는 이 메서드 전체가 한 트랜잭션이어서, 트랜잭션이 열려 있는 동안 60초 평가 루프가
     * 같은 세션 행에 쓰면 커밋에서 낙관적 잠금으로 터지고 <b>청산까지 전부 롤백</b>됐다 —
     * 2026-10-02 01:53Z 의 {@code PAPER#331} 이 그 사례이며 사람이 재호출해서 풀었다(개입 #3).
     * 자동정지가 OFF 라 위험 경보 대응은 사람이 이 호출을 하므로, <b>실패를 사람이 기억해서
     * 재시도하는 설계는 신뢰할 수 없다.</b> 재시도는 호출 안에서 흡수한다.</p>
     *
     * <p>재시도가 안전하려면 <b>트랜잭션에 참여하지 않는 부수 효과가 성공 뒤로 빠져 있어야
     * 한다.</b> 그래서 텔레그램 발송을 트랜잭션 밖으로 옮겼다 — 종전에는 커밋 <b>전에</b> 보냈으므로
     * <b>롤백된 정지에 대해서도 "중단됨" 알림이 나갈 수 있는 구조였다.</b> 🔴 다만 {@code PAPER#331}
     * 에서 그 알림이 실제로 나갔는지는 <b>기록이 없어 미확인</b>이다 — 구조상 가능했다는 것까지만
     * 말한다. 옮긴 것으로 끝이 아니라 <b>최종 커밋이 성공한 뒤에만 한 번</b> 나가야 하므로,
     * 그것을 {@code PaperStopUnderConcurrentWriteTest} 에서 검증한다(재시도 후 성공 시 1회 이하,
     * 재시도를 모두 소진해 실패하면 0회이고 세션은 {@code RUNNING} 으로 남는다).</p>
     *
     * <p>🔴 <b>왜 모의투자는 전체 재시도가 안전하고 실전은 아닌가.</b> 여기서 청산은
     * {@code positionRepo/orderRepo/balanceRepo.save()} 뿐이라 롤백이 전부 되돌린다. 반면
     * {@code LiveTradingService.stopSession} 의 청산은 {@code orderExecutionEngine.submitOrder}
     * ({@code @Async} — 별도 스레드·별도 트랜잭션)를 거치므로 <b>이미 거래소로 나간 시장가 매도가
     * 롤백되지 않는다.</b> 거기서 전체를 재시도하면 <b>이중 매도</b>가 된다(2026-08-18 LIVE 198 의
     * SELL 8724·8725, DYNAMIC 세션 49 의 대금 중복 지급이 실측 사례다). 그래서 실전·동적은
     * <b>상태 전환만</b> 재시도 경로로 돌린다.</p>
     *
     * <p>⚠️ 모의투자에도 롤백되지 않는 부수 효과가 하나 있다 — {@code closePosition} 안의
     * {@code telegramService.bufferTradeEvent}. 재시도가 일어나면 <b>거래 이벤트가 중복
     * 버퍼링될 수 있다.</b> 돈이나 상태가 아니라 알림·기록의 중복이며, 정지 자체가 실패하는 것보다
     * 낫다고 판단해 받아들인다. 여기 적어 두는 이유는 나중에 중복 알림을 보고 원인을 다시
     * 추적하지 않게 하려는 것이다.</p>
     *
     * <p>🔴 <b>이 한계를 남기는 데는 조건이 있다: 버퍼된 이벤트를 확정 체결 기록으로 쓰지 않는다.</b>
     * 롤백된 시도의 이벤트도 버퍼에 남으므로, 그것을 집계·성과·판정의 근거로 삼으면 <b>일어나지
     * 않은 거래가 섞인다.</b> 확정 기록은 {@code paper_trading.position}·{@code "order"} 의 커밋된
     * 행이며, 버퍼는 사람에게 알리는 용도로만 쓴다.</p>
     */
    public VirtualBalanceEntity stop(Long sessionId) {
        VirtualBalanceEntity stopped;
        if (balanceRepo.findById(sessionId).map(PaperTradingService::isAligned).orElse(false)) {
            // LIVE_ALIGNED — 평가·승인·실시간 처리기와 같은 세션 잠금 안에서 정지한다 (2026-11-11, §3-2). 승인이 매수를 쓰는 중이면
            // 그 트랜잭션이 끝난 뒤 정지가 그 포지션까지 강제 청산한다. 종전 PAPER 는 잠금 없이 그대로다.
            synchronized (alignedLock(sessionId)) {
                stopped = optimisticRetry.inNewTransaction("모의투자 정지 #" + sessionId, st -> stopOnce(sessionId));
            }
        } else {
            stopped = optimisticRetry.inNewTransaction("모의투자 정지 #" + sessionId, st -> stopOnce(sessionId));
        }
        alignedPendingTimeExit.keySet().removeIf(posId -> positionRepo.findById(posId)
                .map(x -> sessionId.equals(x.getSessionId())).orElse(true));
        alignedPeakTotalKrw.remove(sessionId);

        // 🔴 트랜잭션 밖 — 커밋이 끝난 뒤에만 알린다. 재시도가 있어도 한 번만 나간다.
        if (Boolean.TRUE.equals(stopped.getTelegramEnabled())) {
            BigDecimal initial = stopped.getInitialCapital() != null
                    ? stopped.getInitialCapital() : stopped.getTotalKrw();
            double returnPct = initial.compareTo(BigDecimal.ZERO) > 0
                    ? stopped.getTotalKrw().subtract(initial)
                              .divide(initial, 4, RoundingMode.HALF_UP)
                              .multiply(BigDecimal.valueOf(100)).doubleValue()
                    : 0;
            telegramService.notifyPaperSessionStopped(
                    sessionId, stopped.getStrategyName(), stopped.getCoinPair(),
                    stopped.getTotalKrw(), returnPct);
        }
        return stopped;
    }

    /**
     * 정지 1회 시도 — {@link OptimisticRetry} 가 제공한 트랜잭션 안에서 실행된다.
     *
     * <p>🔴 이미 {@code STOPPED} 면 {@code IllegalStateException} 을 던지므로 <b>재시도가 이중
     * 청산으로 이어지지 않는다</b> — 롤백된 시도는 상태를 되돌려 놓으므로 다시 시도할 때
     * {@code RUNNING} 이고, 커밋에 성공한 뒤에는 더 호출되지 않는다.</p>
     */
    private VirtualBalanceEntity stopOnce(Long sessionId) {
        VirtualBalanceEntity session = getSession(sessionId);
        if (!"RUNNING".equals(session.getStatus())) {
            throw new IllegalStateException("모의투자가 실행 중이 아닙니다.");
        }

        List<PaperPositionEntity> openPositions = positionRepo.findBySessionIdAndStatus(sessionId, "OPEN");
        final boolean alignedStop = isAligned(session);
        openPositions.forEach(pos -> {
            if (alignedStop) {
                // LIVE_ALIGNED (2026-11-11, §3-2 정지 경계): LIVE 정지는 시장가 매도라 가격이 늘 정해진다. 종전 PAPER 는 M1·REST 가 없으면
                // 0 원에 강제 청산한다(B05 관측). 선택 모드는 실시간 현재가 → M1/REST → 그 세션 주기의 최근 봉 종가 순으로 쓰고 출처를 남긴다.
                String[] source = new String[1];
                BigDecimal p = alignedStopPrice(pos.getCoinPair(), session.getTimeframe(), source);
                closePosition(pos, p, session, "모의투자 중단 - 강제 청산 (가격 출처: " + source[0] + ")", ExitReason.FORCED_STOP);
                return;
            }
            BigDecimal currentPrice = fetchCurrentPrice(pos.getCoinPair());
            closePosition(pos, currentPrice, session, "모의투자 중단 - 강제 청산", ExitReason.FORCED_STOP);
        });

        session.setStatus("STOPPED");
        session.setStoppedAt(Instant.now());
        // ⚠️ 인메모리 상태 제거는 롤백되지 않는다. 재시도에는 안전하지만(멱등), 모든 시도가
        //    실패하면 세션은 RUNNING 인데 전략 상태만 버려진 채 남는다 — 그 경우 다음 평가에서
        //    새로 만들어진다.
        sessionStatefulStrategies.remove(sessionId);

        log.info("모의투자 세션 중단 (id={}). 최종 자산: {} KRW", sessionId, session.getTotalKrw());
        return balanceRepo.save(session);
    }

    @Transactional
    public List<VirtualBalanceEntity> stopAll() {
        List<VirtualBalanceEntity> runningSessions = balanceRepo.findAllByOrderByIdDesc().stream()
                .filter(s -> "RUNNING".equals(s.getStatus()))
                .toList();
        List<VirtualBalanceEntity> stopped = new ArrayList<>();
        for (VirtualBalanceEntity session : runningSessions) {
            try {
                stopped.add(stop(session.getId()));
            } catch (Exception e) {
                log.warn("세션 일괄 정지 중 오류 (id={}): {}", session.getId(), e.getMessage());
            }
        }
        log.info("모의투자 일괄 정지 완료. 정지된 세션 수: {}", stopped.size());
        return stopped;
    }

    @Transactional(readOnly = true)
    public VirtualBalanceEntity getSessionBalance(Long sessionId) {
        return getSession(sessionId);
    }

    @Transactional(readOnly = true)
    public List<VirtualBalanceEntity> listSessions() {
        return balanceRepo.findAllByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public List<MarketDataCacheEntity> getChartCandles(Long sessionId) {
        VirtualBalanceEntity session = getSession(sessionId);
        Instant from = session.getStartedAt() != null ? session.getStartedAt() : Instant.now().minus(7, ChronoUnit.DAYS);
        Instant to = session.getStoppedAt() != null ? session.getStoppedAt() : Instant.now();
        return marketDataCacheRepo.findCandles(session.getCoinPair(), session.getTimeframe(), from, to);
    }

    @Transactional(readOnly = true)
    public List<PaperOrderEntity> getAllOrders(Long sessionId) {
        return orderRepo.findBySessionIdOrderByCreatedAtDesc(sessionId, Pageable.unpaged()).getContent();
    }

    @Transactional(readOnly = true)
    public List<PaperPositionEntity> getOpenPositions(Long sessionId) {
        return positionRepo.findBySessionIdAndStatus(sessionId, "OPEN");
    }

    @Transactional(readOnly = true)
    public List<PaperPositionEntity> getAllPositions(Long sessionId) {
        return positionRepo.findBySessionId(sessionId);
    }

    @Transactional(readOnly = true)
    public Page<PaperOrderEntity> getOrders(Long sessionId, Pageable pageable) {
        return orderRepo.findBySessionIdOrderByCreatedAtDesc(sessionId, pageable);
    }

    /**
     * 모의투자 세션 이력 단건 삭제.
     * 진행 중인 세션(RUNNING)은 삭제 불가 → IllegalStateException.
     * 존재하지 않는 세션 → IllegalArgumentException.
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        VirtualBalanceEntity session = balanceRepo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다: id=" + sessionId));
        if ("RUNNING".equals(session.getStatus())) {
            throw new IllegalStateException("진행 중인 세션은 삭제할 수 없습니다. 먼저 중단하세요.");
        }
        orderRepo.deleteBySessionId(sessionId);
        positionRepo.deleteBySessionId(sessionId);
        balanceRepo.deleteById(sessionId);
        sessionStatefulStrategies.remove(sessionId);
        log.info("모의투자 세션 이력 삭제 완료: id={}", sessionId);
    }

    /**
     * 모의투자 세션 이력 다건 삭제.
     * 진행 중인 세션이 포함되어 있으면 해당 세션은 건너뛰고 완료/중단 세션만 삭제한다.
     */
    @Transactional
    public void bulkDeleteSessions(List<Long> ids) {
        List<Long> deletableIds = balanceRepo.findAllById(ids).stream()
                .filter(s -> !"RUNNING".equals(s.getStatus()))
                .map(VirtualBalanceEntity::getId)
                .toList();
        if (deletableIds.isEmpty()) {
            log.info("다건 삭제 대상 없음 (모두 RUNNING 이거나 존재하지 않는 ID): ids={}", ids);
            return;
        }
        orderRepo.deleteBySessionIdIn(deletableIds);
        positionRepo.deleteBySessionIdIn(deletableIds);
        balanceRepo.deleteAllByIdInBatch(deletableIds);
        log.info("모의투자 세션 이력 다건 삭제 완료: ids={}", deletableIds);
    }

    // ── 성과 요약 ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PerformanceSummaryResponse getOverallPerformance() {
        List<VirtualBalanceEntity> sessions = balanceRepo.findAllByOrderByIdDesc();

        BigDecimal totalRealizedPnl = BigDecimal.ZERO;
        BigDecimal totalUnrealizedPnl = BigDecimal.ZERO;
        BigDecimal totalInitialCapital = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        int totalTrades = 0;
        int totalWins = 0;

        List<PerformanceSummaryResponse.SessionPerformance> sessionPerfs = new java.util.ArrayList<>();

        for (VirtualBalanceEntity session : sessions) {
            List<PaperPositionEntity> positions = positionRepo.findBySessionId(session.getId());
            List<PaperPositionEntity> closed = positions.stream().filter(p -> "CLOSED".equals(p.getStatus())).toList();
            List<PaperPositionEntity> open   = positions.stream().filter(p -> "OPEN".equals(p.getStatus())).toList();

            BigDecimal sessionUnrealized = open.stream()
                    .map(p -> p.getUnrealizedPnl() != null ? p.getUnrealizedPnl() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal sessionRealized = session.getRealizedPnl() != null ? session.getRealizedPnl() : BigDecimal.ZERO;
            BigDecimal sessionFee = session.getTotalFee() != null ? session.getTotalFee() : BigDecimal.ZERO;
            int wins = (int) closed.stream()
                    .filter(p -> p.getRealizedPnl() != null && p.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0)
                    .count();

            BigDecimal sessionPnl = sessionRealized.add(sessionUnrealized);
            BigDecimal sessionReturn = session.getInitialCapital().compareTo(BigDecimal.ZERO) > 0
                    ? sessionPnl.divide(session.getInitialCapital(), 6, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal sessionWinRate = closed.isEmpty() ? BigDecimal.ZERO
                    : new BigDecimal(wins).divide(new BigDecimal(closed.size()), 6, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP);

            RiskMetrics sessionRisk = computeRiskMetrics(closed, session.getInitialCapital());

            sessionPerfs.add(PerformanceSummaryResponse.SessionPerformance.builder()
                    .sessionId(session.getId())
                    .strategyType(session.getStrategyName())
                    .coinPair(session.getCoinPair())
                    .timeframe(session.getTimeframe())
                    .status(session.getStatus())
                    .initialCapital(session.getInitialCapital())
                    .currentAsset(session.getTotalKrw())
                    .realizedPnl(sessionRealized)
                    .unrealizedPnl(sessionUnrealized)
                    .totalPnl(sessionPnl)
                    .returnRatePct(sessionReturn)
                    .totalFee(sessionFee)
                    .totalTrades(closed.size())
                    .winCount(wins)
                    .winRatePct(sessionWinRate)
                    .startedAt(session.getStartedAt() != null ? session.getStartedAt().toString() : null)
                    .stoppedAt(session.getStoppedAt() != null ? session.getStoppedAt().toString() : null)
                    .mddPct(sessionRisk.mddPct)
                    .sharpeRatio(sessionRisk.sharpeRatio)
                    .sortinoRatio(sessionRisk.sortinoRatio)
                    .winLossRatio(sessionRisk.winLossRatio)
                    .avgProfitPct(sessionRisk.avgProfitPct)
                    .avgLossPct(sessionRisk.avgLossPct)
                    .maxConsecutiveLoss(sessionRisk.maxConsecutiveLoss)
                    .build());

            totalRealizedPnl = totalRealizedPnl.add(sessionRealized);
            totalUnrealizedPnl = totalUnrealizedPnl.add(sessionUnrealized);
            totalInitialCapital = totalInitialCapital.add(session.getInitialCapital());
            totalFee = totalFee.add(sessionFee);
            totalTrades += closed.size();
            totalWins += wins;
        }

        BigDecimal totalPnl = totalRealizedPnl.add(totalUnrealizedPnl);
        BigDecimal returnRate = totalInitialCapital.compareTo(BigDecimal.ZERO) > 0
                ? totalPnl.divide(totalInitialCapital, 6, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal winRatePct = totalTrades > 0
                ? new BigDecimal(totalWins).divide(new BigDecimal(totalTrades), 6, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // 전체 포트폴리오 리스크 지표: 모든 세션 거래를 합산
        List<PaperPositionEntity> allClosed = sessions.stream()
                .flatMap(s -> positionRepo.findBySessionId(s.getId()).stream())
                .filter(p -> "CLOSED".equals(p.getStatus()))
                .toList();
        RiskMetrics overallRisk = computeRiskMetrics(allClosed, totalInitialCapital);

        return PerformanceSummaryResponse.builder()
                .totalRealizedPnl(totalRealizedPnl)
                .totalUnrealizedPnl(totalUnrealizedPnl)
                .totalPnl(totalPnl)
                .totalInitialCapital(totalInitialCapital)
                .returnRatePct(returnRate)
                .totalFee(totalFee)
                .totalTrades(totalTrades)
                .winCount(totalWins)
                .lossCount(totalTrades - totalWins)
                .winRatePct(winRatePct)
                .mddPct(overallRisk.mddPct)
                .sharpeRatio(overallRisk.sharpeRatio)
                .sortinoRatio(overallRisk.sortinoRatio)
                .calmarRatio(overallRisk.calmarRatio)
                .winLossRatio(overallRisk.winLossRatio)
                .avgProfitPct(overallRisk.avgProfitPct)
                .avgLossPct(overallRisk.avgLossPct)
                .maxConsecutiveLoss(overallRisk.maxConsecutiveLoss)
                .sessions(sessionPerfs)
                .build();
    }

    // ── 리스크 지표 계산 ──────────────────────────────────────────────────────────

    private static class RiskMetrics {
        BigDecimal mddPct;
        BigDecimal sharpeRatio;
        BigDecimal sortinoRatio;
        BigDecimal calmarRatio;
        BigDecimal winLossRatio;
        BigDecimal avgProfitPct;
        BigDecimal avgLossPct;
        int maxConsecutiveLoss;
    }

    /**
     * 종료된 포지션 목록으로 MDD·Sharpe·Sortino·승패비 등 리스크 지표를 계산한다.
     * 3건 미만이면 통계적으로 의미 없으므로 null 반환.
     */
    private RiskMetrics computeRiskMetrics(List<PaperPositionEntity> closed, BigDecimal initialCapital) {
        RiskMetrics m = new RiskMetrics();
        if (closed.isEmpty()) return m;

        // closedAt 오름차순 정렬 (null이면 제외)
        List<PaperPositionEntity> sorted = closed.stream()
                .filter(p -> p.getClosedAt() != null && p.getRealizedPnl() != null)
                .sorted(java.util.Comparator.comparing(PaperPositionEntity::getClosedAt))
                .toList();
        if (sorted.isEmpty()) return m;

        // 거래별 수익률 (%)
        List<Double> returns = new ArrayList<>();
        List<Double> profits = new ArrayList<>();
        List<Double> losses  = new ArrayList<>();
        for (PaperPositionEntity pos : sorted) {
            if (pos.getEntryPrice() == null || pos.getSize() == null) continue;
            BigDecimal invested = pos.getEntryPrice().multiply(pos.getSize());
            if (invested.compareTo(BigDecimal.ZERO) <= 0) continue;
            double ret = pos.getRealizedPnl()
                    .divide(invested, 8, RoundingMode.HALF_UP)
                    .doubleValue() * 100.0;
            returns.add(ret);
            if (ret > 0) profits.add(ret);
            else if (ret < 0) losses.add(Math.abs(ret));
        }

        // MDD: 누적 자산 곡선으로 계산
        double equity = initialCapital.doubleValue();
        double peak   = equity;
        double minDD  = 0.0;
        for (PaperPositionEntity pos : sorted) {
            equity += pos.getRealizedPnl().doubleValue();
            if (equity > peak) peak = equity;
            if (peak > 0) {
                double dd = (equity - peak) / peak * 100.0;
                if (dd < minDD) minDD = dd;
            }
        }
        m.mddPct = BigDecimal.valueOf(minDD).setScale(2, RoundingMode.HALF_UP);

        // Sharpe·Sortino: 3건 이상부터 계산
        if (returns.size() >= 3) {
            double avg = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double std = stdDevPopulation(returns, avg);
            if (std > 0) {
                m.sharpeRatio = BigDecimal.valueOf(avg / std).setScale(4, RoundingMode.HALF_UP);
            }
            double downStd = losses.isEmpty() ? 0.0 : stdDevPopulation(
                    losses.stream().map(v -> -v).toList(), 0.0);
            if (downStd > 0) {
                m.sortinoRatio = BigDecimal.valueOf(avg / downStd).setScale(4, RoundingMode.HALF_UP);
            }
        }

        // Calmar = 평균수익률 / |MDD|
        if (m.sharpeRatio != null && minDD < 0) {
            double avgRet = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            m.calmarRatio = BigDecimal.valueOf(avgRet / Math.abs(minDD)).setScale(4, RoundingMode.HALF_UP);
        }

        // 승패비·평균 수익/손실
        if (!profits.isEmpty() && !losses.isEmpty()) {
            double avgProfit = profits.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double avgLoss   = losses.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            m.avgProfitPct   = BigDecimal.valueOf(avgProfit).setScale(2, RoundingMode.HALF_UP);
            m.avgLossPct     = BigDecimal.valueOf(-avgLoss).setScale(2, RoundingMode.HALF_UP);
            if (avgLoss > 0) {
                m.winLossRatio = BigDecimal.valueOf(avgProfit / avgLoss).setScale(4, RoundingMode.HALF_UP);
            }
        }

        // 최대 연속 손실
        int maxCons = 0, cons = 0;
        for (PaperPositionEntity pos : sorted) {
            if (pos.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0) {
                maxCons = Math.max(maxCons, ++cons);
            } else {
                cons = 0;
            }
        }
        m.maxConsecutiveLoss = maxCons;
        return m;
    }

    private double stdDevPopulation(List<Double> values, double mean) {
        if (values.size() < 2) return 0.0;
        double variance = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / values.size();
        return Math.sqrt(variance);
    }

    // ── 스케줄: MarketDataSyncService 실행(0s) 후 35초 뒤 전략 실행 ──

    @Scheduled(fixedDelay = 60_000, initialDelay = 35_000)
    public void runStrategy() {
        List<VirtualBalanceEntity> runningSessions = balanceRepo.findByStatusOrderByStartedAtAsc("RUNNING");
        if (runningSessions.isEmpty()) return;

        // 이번 틱 동안만 유효한 캔들 캐시 — 격자 실험(코인 N × 전략 M)에서 같은 (코인,타임프레임)을
        // 세션마다 다시 조회하는 낭비를 없앤다. 100세션이 10코인을 쓰면 500행 쿼리가 200회 → 11회
        // (코인 10 + BTC 가드 1)로 줄어든다. 틱마다 새로 만들므로 stale 데이터 위험은 없다.
        //
        // 2026-09-08: 여기 있던 로컬 Map 을 TickCandleCache 로 옮겼다 — LIVE·DYNAMIC 에는 이 캐시가
        // 없어 08-19 감사가 "PAPER 에만 있는 것" 으로 기록했던 항목이다. 이제 셋이 같은 구현을 쓴다.
        TickCandleCache.begin();
        try {
            for (VirtualBalanceEntity session : runningSessions) {
                try {
                    if (isAligned(session)) {
                        // LIVE_ALIGNED — 실시간 처리기·회차 승인과 같은 세션 잠금 안에서, 잠금 뒤 다시 읽은 행으로 평가한다.
                        synchronized (alignedLock(session.getId())) {
                            VirtualBalanceEntity fresh = balanceRepo.findById(session.getId()).orElse(null);
                            if (fresh != null && "RUNNING".equals(fresh.getStatus())) runSessionStrategy(fresh);
                        }
                    } else {
                        runSessionStrategy(session);
                    }
                } catch (Exception e) {
                    log.error("모의투자 전략 실행 오류 (sessionId={}): {}", session.getId(), e.getMessage(), e);
                }
            }
        } finally {
            TickCandleCache.end();
        }
        refreshAlignedCoins(runningSessions);
    }

    /** 틱 캐시를 경유한 캔들 조회 — 같은 (코인, 타임프레임)은 틱당 1회만 DB를 친다. */
    private List<Candle> candlesFor(String coinPair, String timeframe) {
        return TickCandleCache.get(coinPair, timeframe, () -> fetchRecentCandles(coinPair, timeframe));
    }

    // ── 내부 메서드 ───────────────────────────────────────────

    /**
     * 세션 1회 평가 — <b>{@code LiveTradingService.processSessionTick}과 동일한 순서·게이트</b>로 구성한다.
     *
     * <p><b>2026-08-06 LIVE 정렬</b>: 이전 구현은 게이트가 하나도 없이 신호가 나오는 대로 매매했다.
     * 실전 로그에서 "BUY 신호 86건 전량 진입 게이트 차단"처럼 대부분의 BUY가 걸러지는데도
     * 페이퍼는 그걸 전부 체결해, 두 엔진의 <b>거래 모집단 자체가 달랐다</b>. 그 상태의 페이퍼 성적은
     * 실전 예측에 쓸 수 없다. 아래 순서·상수는 LIVE와 1:1로 맞춰야 하며, 한쪽만 바꾸면 안 된다.</p>
     *
     * <p><b>의도적으로 적용하지 않는 LIVE 로직</b>(자본 배정 게이트라 페이퍼의 목적과 상충):
     * {@code StrategyLiveStatusRegistry.isBlocked}, {@code WalkForwardValidationGate},
     * {@code §8 cross-session 잔고 가드}. 페이퍼는 <b>미검증 전략을 검증하기 위한</b> 도구이므로
     * "검증되지 않아 차단" 규칙을 적용하면 존재 이유가 사라진다.</p>
     */
    private void runSessionStrategy(VirtualBalanceEntity session) {
        Long sessionId = session.getId();
        String coinPair = session.getCoinPair();
        String timeframe = session.getTimeframe();
        String strategyName = session.getStrategyName();

        List<Candle> candles = candlesFor(coinPair, timeframe);
        final boolean aligned = PaperExecutionMode.isLiveAligned(session.getStrategyParams());
        if (aligned) {
            // LIVE 틱 시작의 세션 서킷 브레이커와 같은 자리·같은 기준 — 발동이면 정지(강제 청산)하고 이 틱은 끝낸다. 입력은 이 모의 계좌다.
            CircuitBreakerResult cb = alignedCircuitBreaker(session);
            if (cb.isTriggered()) {
                log.error("모의투자[LIVE_ALIGNED] 서킷 브레이커 발동 — 정지 (sessionId={}): {}", sessionId, cb.getReason());
                stop(sessionId);
                return;
            }
        }
        if (aligned) {
            // LIVE freshCandles 와 같은 판정(최신 완료 봉·결측·공식 점검 공백). LIVE 의 공유 상태(stale 지도·경보)는 건드리지 않는다.
            String problem = alignedCandleProblem(coinPair, timeframe, candles);
            if (problem != null) {
                log.warn("모의투자[LIVE_ALIGNED] 캔들 판정 불가 — 진입·캔들 기반 청산 안 함 (sessionId={}, {} {}): {}",
                        sessionId, coinPair, timeframe, problem);
                candles = List.of();
            }
        }

        // 🔴 절대 하한 — 현재가조차 못 잡으면 청산 판정도 불가하므로 사이클을 건너뛴다.
        if (candles.size() < 10) {
            log.warn("모의투자 캔들 부족: {} {}건 (sessionId={})", coinPair, candles.size(), sessionId);
            if (aligned) alignedExitsWithoutCandles(session, coinPair);
            return;
        }

        // ⚠️ 2026-09-22 (Wave 4-N): 전략이 선언한 최소 캔들 수를 읽어만 두고,
        //    미달이면 아래에서 **전략 평가만** 건너뛴다. 사이클을 return 하지 않는다 —
        //    이 메서드는 뒤에서 손절·익절·타임스톱을 처리하므로 return 하면
        //    **열린 포지션이 방치된다.** (닫힌 캔들 게이트와 같은 층위의 처리다.)
        //
        // 🔴 엔진 패리티: BacktestEngine:92 · DynamicTradingService:844 는 진작부터
        //    getMinimumCandleCount() 를 쓴다. LIVE·PAPER 만 하드코딩 10 이었다.
        //    10 은 어떤 전략의 요구량도 아니다 — HEIKIN_ASHI_STOCH 205 · PULLBACK_MTF 201 · GRID 100.
        //    **PAPER 는 함대 표본을 만드는 엔진이라 특히 문제였다** — 장기 지표가 조용히
        //    비활성된 채로 쌓인 표본은 그 전략의 성과가 아니다.
        int minCandles = TradingConstants.minimumCandlesFor(strategyName);
        boolean candlesShortOfStrategy = candles.size() < minCandles;
        if (candlesShortOfStrategy) {
            log.warn("모의투자 전략 요구 캔들 미달 — 신호 평가만 건너뜀 (청산은 계속): "
                            + "{} {}건 < {}건 필요 ({}, sessionId={})",
                    coinPair, candles.size(), minCandles, strategyName, sessionId);
        }

        BigDecimal currentPrice = candles.get(candles.size() - 1).getClose();

        // 전략 평가 전 시장 레짐 선감지 — RANGE 게이트 판정에 사용.
        // detectRaw() 사용 이유는 LIVE와 동일(매번 새 detector라 hysteresis가 항상 RANGE로 오판됨).
        MarketRegime preEvalRegime = null;
        if (candles.size() >= MarketRegimeDetector.MIN_CANDLE_COUNT) {
            try {
                preEvalRegime = new MarketRegimeDetector().detectRaw(candles);
            } catch (Exception e) {
                log.warn("레짐 선감지 실패 (sessionId={}): {}", sessionId, e.getMessage());
            }
        }

        // 안전장치 2종은 닫힌 캔들 게이팅과 무관하게 매 tick 최신 캔들로 평가한다 (LIVE 동일).
        BlackSwanGuard.Result blackSwanGuard = BlackSwanGuard.check(candles);
        List<Candle> btcCandles = "KRW-BTC".equals(coinPair)
                ? candles : candlesFor("KRW-BTC", timeframe);
        BtcMarketGuard.Result btcMarketGuard = aligned
                ? alignedBtcGate(coinPair, timeframe, candles)
                : BtcMarketGuard.check(btcCandles);

        // ── 닫힌 캔들 게이팅 ──────────────────────────────────────────────────
        long periodMin = TimeframeUtils.toMinutes(timeframe);
        Instant lastCandleTime = candles.get(candles.size() - 1).getTime();
        boolean lastCandleClosed = !lastCandleTime.plus(periodMin, ChronoUnit.MINUTES).isAfter(Instant.now());
        List<Candle> evalCandles = (lastCandleClosed || candles.size() < 2)
                ? candles : candles.subList(0, candles.size() - 1);
        Instant closedCandleTime = evalCandles.get(evalCandles.size() - 1).getTime();
        Instant prevEvaluated = lastEvaluatedClosedCandle.get(sessionId);
        boolean newClosedCandle = prevEvaluated == null || closedCandleTime.isAfter(prevEvaluated);

        StrategySignal signal;
        StrategyLogEntity savedSignalLog = null;
        if (candlesShortOfStrategy) {
            // 전략 요구 캔들 미달 (Wave 4-N) — 신호 평가만 건너뛴다.
            // 손절/익절/타임스톱은 아래에서 그대로 돌아간다.
            signal = StrategySignal.hold(String.format(
                    "전략 요구 캔들 미달: %d < %d (%s)", candles.size(), minCandles, strategyName));
        } else if (!newClosedCandle) {
            // 이미 평가한 닫힌 캔들 — 전략 평가 스킵. 손절/익절/타임스톱 감시는 아래에서 계속된다.
            signal = StrategySignal.hold("닫힌 캔들 미갱신 — 전략 평가 스킵");
        } else {
            lastEvaluatedClosedCandle.put(sessionId, closedCandleTime);

            com.cryptoautotrader.strategy.Strategy strategyInstance =
                    StrategyRegistry.hasFactory(strategyName)
                            ? sessionStatefulStrategies.computeIfAbsent(sessionId,
                                    id -> StrategyRegistry.createNew(strategyName))
                            : StrategyRegistry.get(strategyName);

            Map<String, Object> params = new java.util.HashMap<>();
            params.put("coinPair", coinPair);
            // 타임프레임도 전략에 알린다 (2026-09-08) — COMPOSITE 계열이 레짐별 가중치를
            // 조회할 때 이 값으로 키를 좁힌다. 같은 전략·코인·레짐이라도 H1/M15 성적은
            // 부호가 반대인 경우가 있어, 합치면 가중치가 서로 상쇄된다.
            params.put("timeframe", session.getTimeframe());
            if (session.getStartedAt() != null) {
                params.put("sessionStartedAt", session.getStartedAt().toEpochMilli());
            }
            // 세션 오버라이드(V74) — 지문에 실리므로 A/B 두 팔이 다른 표본으로 갈린다.
            if (session.getStrategyParams() != null) {
                params.putAll(session.getStrategyParams());
            }
            signal = strategyInstance.evaluate(evalCandles, params);
            log.info("모의투자 신호 (sessionId={}): {} {} → {} ({})",
                    sessionId, strategyName, coinPair, signal.getAction(), signal.getReason());

            // ── 진입 게이트 4종 (LIVE와 동일 순서) ──────────────────────────
            boolean ema200Pass = Ema200RegimeGate.isExempt(strategyName)
                    || Ema200RegimeGate.allowsBuy(evalCandles, coinPair);
            if (signal.getAction() == StrategySignal.Action.BUY && !ema200Pass) {
                signal = StrategySignal.hold("EMA200 레짐 필터 — 현재가 EMA200 이하");
            }

            if (preEvalRegime == MarketRegime.RANGE
                    && signal.getAction() == StrategySignal.Action.BUY
                    && RangeRegimeGate.isBlocked(strategyName)) {
                signal = StrategySignal.hold("RANGE 레짐 — 추세 추종 전략 횡보장 신규 진입 차단");
            }

            if (signal.getAction() == StrategySignal.Action.BUY && blackSwanGuard.triggered()) {
                signal = StrategySignal.hold("BLACK_SWAN_GUARD 발동 — " + blackSwanGuard.reason());
            }

            if (aligned) {
                // LIVE 와 같다 — 판정 불가도 진입을 막는다(blocksEntry).
                if (signal.getAction() == StrategySignal.Action.BUY && btcMarketGuard.blocksEntry()) {
                    String what = btcMarketGuard.unknown() ? "판정 불가" : "발동";
                    signal = StrategySignal.hold("BTC_MARKET_GUARD " + what + " — " + btcMarketGuard.reason());
                }
            } else if (signal.getAction() == StrategySignal.Action.BUY && btcMarketGuard.triggered()) {
                signal = StrategySignal.hold("BTC_MARKET_GUARD 발동 — " + btcMarketGuard.reason());
            }

            // 전략 로그 DB 저장
            try {
                BigDecimal conf = (signal.getAction() != StrategySignal.Action.HOLD)
                        ? signal.getStrength().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                        : null;
                StrategyLogEntity logEntity = StrategyLogEntity.builder()
                        .rulesetHash(rulesetRegistry.hashFor(session))
                        .strategyName(strategyName)
                        .coinPair(coinPair)
                        .timeframe(session.getTimeframe())
                        .signal(signal.getAction().name())
                        .reason(signal.getReason())
                        .marketRegime(preEvalRegime != null ? preEvalRegime.name() : null)
                        // 2026-08-19: PAPER 로그 7,238건이 전부 indicators_json NULL 이었다.
                        .indicatorsJson(com.cryptoautotrader.api.util.IndicatorSnapshot.of(
                                evalCandles, lastCandleClosed, closedCandleTime, null))
                        .sessionType("PAPER")
                        .sessionId(sessionId)
                        // signalPrice 누락 시 SignalQualityService 사후 평가(4h/24h)에서 영구 제외된다
                        // — 2026-07-15 운영 DB 분석: PAPER 로그 24,820건 전량 signal_price NULL의 원인.
                        .signalPrice(currentPrice)
                        .confidenceScore(conf)
                        .build();
                savedSignalLog = strategyLogRepo.save(logEntity);
            } catch (Exception e) {
                log.warn("전략 로그 저장 실패: {}", e.getMessage());
            }
        }

        Optional<PaperPositionEntity> openPos = positionRepo
                .findBySessionIdAndCoinPairAndStatus(sessionId, coinPair, "OPEN");

        // ── 손절/익절/타임스톱 (전략 신호보다 우선) ──────────────────────────
        if (openPos.isPresent() && aligned) {
            // LIVE 평가 회차와 같은 순서·조건 — 익절 → 시간 초과 → 손절. 트레일링은 실시간 처리기에서만(LIVE 와 같다).
            if (alignedPriceExits(session, openPos.get(), currentPrice, "")) return;
        } else if (openPos.isPresent()) {
            PaperPositionEntity pos = openPos.get();

            // 트레일링 스탑 갱신 (ExitRuleChecker 공통 로직)
            if (pos.getStopLossPrice() != null && pos.getTakeProfitPrice() != null && pos.getEntryPrice() != null) {
                var updatedLevels = exitChecker().updateTrailingStops(
                        currentPrice, pos.getEntryPrice(),
                        pos.getStopLossPrice(), pos.getTakeProfitPrice());
                if (updatedLevels.getStopLossPrice().compareTo(pos.getStopLossPrice()) != 0
                        || updatedLevels.getTakeProfitPrice().compareTo(pos.getTakeProfitPrice()) != 0) {
                    pos.setStopLossPrice(updatedLevels.getStopLossPrice());
                    pos.setTakeProfitPrice(updatedLevels.getTakeProfitPrice());
                    positionRepo.save(pos);
                }
            }

            var exitCheck = exitChecker().checkPriceExit(
                    currentPrice, pos.getStopLossPrice(), pos.getTakeProfitPrice());
            if (exitCheck.isShouldExit()) {
                log.warn("모의투자 {} (sessionId={}): {} 현재가={}",
                        exitCheck.getReason(), sessionId, coinPair, currentPrice);
                // ExitType 은 SL/TP 를 이미 구분하고 있었는데 여기서 버려지고 있었다 (V73)
                closePosition(pos, currentPrice, session, exitCheck.getReason(),
                        ExitReason.from(exitCheck.getType()));
                return;
            }

            // time stop — LIVE와 동일하게 ExitRuleCalculator로 판정. 기본 비활성(0)이라
            // 명시적으로 켠 세션에만 영향을 준다.
            if (ExitRuleCalculator.shouldTimeStop(session.getMaxHoldHours(), pos.getOpenedAt(), Instant.now())) {
                long heldHours = Duration.between(pos.getOpenedAt(), Instant.now()).toHours();
                log.warn("모의투자 시간 초과 청산 (sessionId={}): {} 보유 {}h ≥ {}h",
                        sessionId, coinPair, heldHours, session.getMaxHoldHours());
                closePosition(pos, currentPrice, session, String.format(
                        "시간 초과 청산 — 보유 %d시간 ≥ %d시간", heldHours, session.getMaxHoldHours()),
                        ExitReason.TIME_STOP);
                return;
            }
        }

        final StrategySignal finalSignal = signal;
        switch (signal.getAction()) {
            case BUY -> {
                if (openPos.isEmpty() && aligned && entryPriorityRounds != null && entryPriorityRounds.isActive()) {
                    // LIVE 와 같은 회차 — 접수만 하고, 마감 D 뒤 승인 차례에 다시 확인해 그 시각 가격으로 체결한다(만료 E 이상이면 없음).
                    final String regimeAtSignal = preEvalRegime != null ? preEvalRegime.name() : null;
                    final List<Candle> candlesAtSignal = evalCandles;
                    final StrategyLogEntity logRef = savedSignalLog;
                    final EntryPriorityRounds.RoundKey roundKey = new EntryPriorityRounds.RoundKey(
                            timeframe, candlesAtSignal.get(candlesAtSignal.size() - 1).getTime());
                    final Instant expiresAt = EntryPriorityRounds.expiry(roundKey);
                    String notQueued = entryPriorityRounds.submit(new EntryPriorityRounds.Candidate(
                            EntryPriorityRounds.KIND_PAPER, sessionId, coinPair, roundKey,
                            () -> approveAlignedEntry(sessionId, coinPair, finalSignal, regimeAtSignal, candlesAtSignal, logRef, expiresAt),
                            expired -> saveSignalQuality(logRef, false, expired)),
                            Instant.now());
                    if (notQueued != null) saveSignalQuality(savedSignalLog, false, notQueued);
                    return;
                } else if (openPos.isEmpty()) {
                    if (aligned) {
                        RiskCheckResult risk = alignedRiskCheck(session);
                        if (!risk.isApproved()) {
                            log.warn("모의투자[LIVE_ALIGNED] 리스크 한도로 매수 차단 (sessionId={}): {}", sessionId, risk.getReason());
                            saveSignalQuality(savedSignalLog, false, "리스크 한도: " + risk.getReason());
                            return;
                        }
                    }
                    boolean bought = executeBuy(sessionId, coinPair, currentPrice, session, finalSignal, evalCandles,
                            preEvalRegime != null ? preEvalRegime.name() : null);
                    saveSignalQuality(savedSignalLog, bought, bought ? null : "매수 실행 실패(최소주문/잔고 미달)");
                } else {
                    saveSignalQuality(savedSignalLog, false, "이미 포지션 보유 중");
                }
            }
            case SELL -> {
                if (openPos.isPresent()) {
                    PaperPositionEntity pos = openPos.get();
                    long heldMinutes = pos.getOpenedAt() != null
                            ? Duration.between(pos.getOpenedAt(), Instant.now()).toMinutes()
                            : Long.MAX_VALUE;
                    BigDecimal heldPnlPct = (pos.getAvgPrice() != null
                            && pos.getAvgPrice().compareTo(BigDecimal.ZERO) > 0)
                            ? currentPrice.subtract(pos.getAvgPrice())
                                    .divide(pos.getAvgPrice(), 6, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100))
                                    .setScale(3, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;

                    // 판정은 SignalExitGate 공용 (2026-09-08) — LIVE·DYNAMIC 과 같은 함수다.
                    SignalExitGate.Decision sellGate = SignalExitGate.decide(
                            heldMinutes, heldPnlPct,
                            MIN_HOLD_MINUTES_FOR_SIGNAL_EXIT, MIN_PNL_PCT_FOR_SIGNAL_EXIT,
                            LOSS_ESCAPE_THRESHOLD);
                    if (!sellGate.allowed()) {
                        log.info("모의투자 SELL 차단 (sessionId={}): {} {}", sessionId, coinPair, sellGate.reason());
                        saveSignalQuality(savedSignalLog, false, sellGate.reason());
                    } else {
                        closePosition(pos, currentPrice, session, String.format(
                                "전략 신호: %s -- %s (pnl=%s%%)",
                                strategyName, finalSignal.getReason(), heldPnlPct.toPlainString()),
                                ExitReason.STRATEGY_SIGNAL);
                        saveSignalQuality(savedSignalLog, true, null);
                    }
                } else {
                    saveSignalQuality(savedSignalLog, false, "청산할 포지션 없음");
                }
            }
            default -> { /* HOLD — 신호 품질 추적 불필요 */ }
        }

        updateUnrealizedPnl(sessionId, coinPair, currentPrice, session);
    }

    // ── LIVE_ALIGNED 실행 모드 (2026-11-10, PaperExecutionMode) ─────────────────────
    //
    // 선택한 새 검증 세션만 쓴다. 공통 판단(전략·관문·회차·만료·청산 조건)은 LIVE 와 같은 함수/빈을 쓰고, 모의 잔액·포지션·체결
    // (슬리피지·수수료)만 PAPER 어댑터다.
    //
    // 2026-11-11 (총괄 v1.145 §3-2) 보완:
    //  - 판단 평균가는 체결가(LIVE 체결 평균가와 같은 관례), 비용 원가는 investedKrw(수수료 포함). 종전 PAPER 는 그대로다.
    //  - 실시간 손절 확인 간격: 평상시 5초, 급변(30초 −1.5%/+2.0%) 1초 — LIVE 와 같은 상수.
    //  - 위험 관문: 세션 서킷 브레이커(MDD·연속 손실)와 전역 한도(일/주/월 손실·포지션 수·자본 사용률·드로우다운)를 **이 모의 세션의
    //    계좌 상태**를 입력으로, 같은 risk_config 임계값으로 판단한다. REAL 계좌·공유 풀·§8 예약은 읽지도 쓰지도 않는다.
    //    공유 풀 한도(CapitalPoolLimits)·§8 예약은 실자금 배정 장치라 모의 계좌 대응물이 없다(남은 차이).
    //  - 가격 없는 시간 청산: 판단 시각을 기록해 두고 첫 유효 현재가에 체결한다(LIVE 는 그 자리에서 시장가 의도).
    //  - 승인 차례는 세션 행을 다시 읽는 한 트랜잭션이고, 정지는 같은 세션 잠금을 잡는다.

    @Autowired(required = false)
    private CandleFreshnessGuard candleFreshnessGuard;

    @Autowired(required = false)
    private MarkPriceBook markPriceBook;

    @Autowired(required = false)
    private EntryPriorityRounds entryPriorityRounds;

    @Autowired(required = false)
    private EntryClock entryClock;

    @Autowired(required = false)
    private WsSubscriptionManager wsSubscriptionManager;

    /** WebSocket 구독 출처 이름 — LIVE·DYNAMIC 과 합집합으로 구독한다. LIVE_ALIGNED 세션이 없으면 출처 자체가 없다. */
    static final String WS_SOURCE = "PAPER_ALIGNED";

    private final Map<Long, Object> alignedLocks = new ConcurrentHashMap<>();
    private final Map<String, Long> alignedRtLastCheckMs = new ConcurrentHashMap<>();
    /** LIVE_ALIGNED RUNNING 세션의 코인 — 실시간 처리기가 해당 없는 시세를 DB 조회 없이 버리는 데 쓴다. */
    private volatile java.util.Set<String> alignedCoins = java.util.Set.of();

    private static final long ALIGNED_RT_CHECK_INTERVAL_MS = 5_000;   // LIVE RT_STOPLOSS_CHECK_INTERVAL_MS 와 같다

    /**
     * 경합 시험 훅 (2026-11-11, 총괄 v1.145 §3-2 비동기 경계) — 승인·실시간 처리기의 쓰기 직전을 latch 로 고정하는 데만 쓴다.
     * 운영은 아무것도 하지 않는 기본값이다({@code CrashPoint.pause} 와 같은 성격, 시험 전용).
     */
    static volatile java.util.function.Consumer<String> alignedRaceHook = name -> { };

    /** 실시간 처리기의 시계 — 시험이 간격 경계를 고정하는 데만 바꾼다. 운영은 시스템 시계다. */
    java.util.function.LongSupplier alignedNowMs = System::currentTimeMillis;

    // LIVE 급변 감지와 같은 상수 (SPIKE_*) — 평상시 5초, 급변 1초.
    private final Map<String, java.util.Deque<long[]>> alignedPriceHistory = new ConcurrentHashMap<>();
    private final Map<String, Long> alignedSpikeLastCheckMs = new ConcurrentHashMap<>();
    private static final long ALIGNED_SPIKE_WINDOW_MS = 30_000;
    private static final long ALIGNED_SPIKE_HISTORY_TTL_MS = 60_000;
    private static final long ALIGNED_SPIKE_CHECK_INTERVAL_MS = 1_000;
    private static final BigDecimal ALIGNED_SPIKE_DOWN_THRESHOLD = new BigDecimal("-1.5");
    private static final BigDecimal ALIGNED_SPIKE_UP_THRESHOLD = new BigDecimal("2.0");

    /** 가격 없는 회차의 시간 초과 청산 판단 시각 (포지션 id → 판단 시각). 첫 유효 현재가에 체결하며 지운다. 재기동 뒤에는 조건이 다시 판단된다. */
    private final Map<Long, Instant> alignedPendingTimeExit = new ConcurrentHashMap<>();

    /** 세션 총자산 피크 (MDD 분모) — LIVE mddPeakCapital 대응. 모의 세션 표에는 열이 없어 메모리에만 두고, 재기동 뒤에는 초기 자본부터 다시 본다. */
    private final Map<Long, BigDecimal> alignedPeakTotalKrw = new ConcurrentHashMap<>();

    private Object alignedLock(Long sessionId) {
        return alignedLocks.computeIfAbsent(sessionId, k -> new Object());
    }

    private static boolean isAligned(VirtualBalanceEntity s) {
        try {
            return PaperExecutionMode.isLiveAligned(s.getStrategyParams());
        } catch (IllegalStateException e) {
            return false;   // 평가 경로가 같은 예외로 그 세션만 실패시킨다(루프의 오류 로그)
        }
    }

    /** LIVE {@code freshCandles} 와 같은 판정 — 판정 불가면 사유, 쓸 수 있으면 {@code null}. 공유 stale 지도·경보는 건드리지 않는다. */
    private String alignedCandleProblem(String coinPair, String timeframe, List<Candle> candles) {
        Instant now = Instant.now();
        return CandleFreshnessGuard.problem(candles, timeframe, now,
                candleFreshnessGuard == null ? null : candleFreshnessGuard.maintenanceExemption(coinPair, timeframe, now));
    }

    /** LIVE {@code btcMarketGate} 와 같은 판정 — BTC 세션은 최신성을 거른 자기 캔들, 아니면 BTC 캔들(진행 중 봉 포함). */
    private BtcMarketGuard.Result alignedBtcGate(String coinPair, String timeframe, List<Candle> ownCandles) {
        List<Candle> btc = "KRW-BTC".equals(coinPair) ? ownCandles : candlesFor("KRW-BTC", timeframe);
        Instant now = Instant.now();
        return CandleFreshnessGuard.btcGate(timeframe, btc, btc, now,
                candleFreshnessGuard == null ? null : candleFreshnessGuard.maintenanceExemption("KRW-BTC", timeframe, now));
    }

    /**
     * LIVE 평가 회차의 가격 청산과 같은 순서·조건(익절 → 시간 초과 → 손절). 손절가가 없으면 세션 손절률로 본다.
     *
     * @return 청산했으면 true
     */
    private boolean alignedPriceExits(VirtualBalanceEntity session, PaperPositionEntity pos, BigDecimal price, String note) {
        if (pos.getTakeProfitPrice() != null && price.compareTo(pos.getTakeProfitPrice()) >= 0) {
            closeAligned(pos, price, session, "익절 발동" + note + " — 현재가 " + price.toPlainString() + " ≥ 익절가 "
                    + pos.getTakeProfitPrice().toPlainString(), ExitReason.TAKE_PROFIT);
            return true;
        }
        if (ExitRuleCalculator.shouldTimeStop(session.getMaxHoldHours(), pos.getOpenedAt(), Instant.now())) {
            long heldHours = Duration.between(pos.getOpenedAt(), Instant.now()).toHours();
            closeAligned(pos, price, session, String.format("시간 초과 청산%s — 보유 %d시간 ≥ %d시간%s",
                    note, heldHours, session.getMaxHoldHours(), pendingTimeExitNote(pos)), ExitReason.TIME_STOP);
            return true;
        }
        BigDecimal pnlPct = price.subtract(pos.getAvgPrice()).divide(pos.getAvgPrice(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        BigDecimal rawStopLoss = session.getStopLossPct() != null
                ? session.getStopLossPct() : exitChecker().getConfig().getStopLossPct();
        boolean sl = pos.getStopLossPrice() != null
                ? price.compareTo(pos.getStopLossPrice()) <= 0
                : pnlPct.compareTo(rawStopLoss.negate()) <= 0;
        if (sl) {
            closeAligned(pos, price, session, "손절 발동" + note + " -- 손익률 " + pnlPct + "%", ExitReason.STOP_LOSS);
            return true;
        }
        return false;
    }

    /** 정지 강제 청산 가격 — 실시간 현재가 → M1/REST → 최근 봉 종가. 모두 없으면 종전과 같은 값(0)을 쓰고 오류로 남긴다. */
    private BigDecimal alignedStopPrice(String coinPair, String timeframe, String[] source) {
        Instant now = Instant.now();
        BigDecimal p = markPriceBook == null ? null : markPriceBook.freshPrice(coinPair, now);
        if (MarkPriceBook.isPrice(p)) {
            source[0] = "실시간 현재가";
            return p;
        }
        p = fetchCurrentPrice(coinPair);
        if (MarkPriceBook.isPrice(p)) {
            source[0] = "M1/REST 현재가";
            return p;
        }
        List<Candle> cs = fetchRecentCandles(coinPair, timeframe);
        if (!cs.isEmpty() && MarkPriceBook.isPrice(cs.get(cs.size() - 1).getClose())) {
            Candle last = cs.get(cs.size() - 1);
            source[0] = "최근 " + timeframe + " 봉 종가(봉 시작 " + last.getTime() + ")";
            return last.getClose();
        }
        log.error("모의투자[LIVE_ALIGNED] 정지 강제 청산 가격 없음 — 종전 값으로 청산한다 ({}): {}", coinPair, p);
        source[0] = "가격 없음(종전 값 " + p + ")";
        return p;
    }

    /** 판단 시각(가격 없는 회차)과 체결가 시각을 나눠 적는다. 보류가 없으면 빈 문자열. */
    private String pendingTimeExitNote(PaperPositionEntity pos) {
        Instant decided = alignedPendingTimeExit.remove(pos.getId());
        return decided == null ? "" : " [청산 판단 " + decided + " / 체결가 시각 " + Instant.now() + "]";
    }

    /**
     * LIVE_ALIGNED 청산 — 포지션·주문·잔액 쓰기를 한 트랜잭션으로 묶고, 안에서 다시 읽은 행이 OPEN 일 때만 닫는다 (2026-11-11).
     * 세션 잠금이 같은 프로세스의 경합을 막고, 트랜잭션이 부분 반영을 막는다. 종전 PAPER 의 {@link #closePosition} 은 그대로다.
     */
    private void closeAligned(PaperPositionEntity pos, BigDecimal signalPrice, VirtualBalanceEntity session,
                              String reason, ExitReason exitReason) {
        optimisticRetry.inNewTransaction("모의투자[LIVE_ALIGNED] 청산 #" + pos.getId(), st -> {
            PaperPositionEntity fresh = positionRepo.findById(pos.getId()).orElse(null);
            VirtualBalanceEntity s = balanceRepo.findById(session.getId()).orElse(null);
            if (fresh == null || s == null || !"OPEN".equals(fresh.getStatus())) {
                log.info("모의투자[LIVE_ALIGNED] 청산 생략 — 이미 닫힘 (posId={})", pos.getId());
                return null;
            }
            closePosition(fresh, signalPrice, s, reason, exitReason);
            return null;
        });
        alignedPendingTimeExit.remove(pos.getId());
    }

    /**
     * 캔들을 쓸 수 없는 회차 — LIVE {@code exitsWithoutCandles} 와 같이 받은 지 기준 이내의 실시간 현재가만 쓴다. 없으면 가격 청산을
     * 판정하지 않는다. LIVE 는 가격 없이 시간 초과 시장가 매도를 내지만 모의 체결에는 가격이 필요해, 그 경우 **판단 시각을 기록**하고
     * 첫 유효 현재가에 체결한다(판단·체결가 시각을 사유에 나눠 남긴다).
     */
    private void alignedExitsWithoutCandles(VirtualBalanceEntity session, String coinPair) {
        Optional<PaperPositionEntity> open = positionRepo.findBySessionIdAndCoinPairAndStatus(session.getId(), coinPair, "OPEN");
        if (open.isEmpty()) return;
        BigDecimal price = markPriceBook == null ? null : markPriceBook.freshPrice(coinPair, Instant.now());
        if (price == null) {
            PaperPositionEntity pos = open.get();
            if (ExitRuleCalculator.shouldTimeStop(session.getMaxHoldHours(), pos.getOpenedAt(), Instant.now())) {
                // LIVE 는 여기서 가격 없이 시장가 매도 의도를 낸다. 모의 체결에는 가격이 필요하므로 **판단 시각만 지금** 기록하고,
                // 첫 유효 현재가(실시간 처리기 또는 다음 회차)에 체결한다. 판단과 체결가 시각은 청산 사유에 나눠 남는다.
                Instant decided = alignedPendingTimeExit.computeIfAbsent(pos.getId(), k -> Instant.now());
                log.warn("모의투자[LIVE_ALIGNED] 시간 초과 청산 판단 {} — 캔들 판정 불가 + 유효한 현재가 없음, 첫 유효 현재가에 체결 "
                        + "(sessionId={}, {}, posId={}, 보유 {}h ≥ {}h)", decided, session.getId(), coinPair, pos.getId(),
                        Duration.between(pos.getOpenedAt(), Instant.now()).toHours(), session.getMaxHoldHours());
                return;
            }
            log.warn("모의투자[LIVE_ALIGNED] 캔들 판정 불가 + 유효한 현재가 없음 — 가격 청산 판정 안 함 (sessionId={}, {})",
                    session.getId(), coinPair);
            return;
        }
        alignedPriceExits(session, open.get(), price, "(캔들 판정 불가 회차, 실시간 현재가 " + price.toPlainString() + ")");
    }

    // ── 모의 계좌 입력의 위험 관문 (2026-11-11, §3-2) ─────────────────────────────────────────

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** LIVE 세션 서킷 브레이커와 같은 기준(MDD·연속 손실) — 입력은 이 모의 세션의 총자산 피크·초기 자본·총자산·청산 손익. */
    private CircuitBreakerResult alignedCircuitBreaker(VirtualBalanceEntity s) {
        BigDecimal total = nz(s.getTotalKrw());
        BigDecimal peak = alignedPeakTotalKrw.merge(s.getId(), total.max(nz(s.getInitialCapital())), BigDecimal::max);
        List<BigDecimal> pnlNewestFirst = positionRepo.findBySessionIdAndStatus(s.getId(), "CLOSED").stream()
                .sorted(java.util.Comparator.comparing((PaperPositionEntity p) -> p.getClosedAt() == null ? Instant.EPOCH : p.getClosedAt())
                        .reversed())
                .map(PaperPositionEntity::getRealizedPnl)
                .toList();
        return riskManagementService.checkCircuitBreakerFromValues(peak, s.getInitialCapital(), total, pnlNewestFirst);
    }

    /**
     * LIVE 전역 위험 한도 {@code checkRisk} 와 같은 식(일/주/월 총손실률·열린 포지션 수·자본 사용률·드로우다운)을 **이 모의 세션 하나**를
     * 포트폴리오로 보고 판단한다. 분모는 세션 초기 자본, 손실은 이 세션의 청산 손익 중 손실만 합산(LIVE 와 같은 gross loss).
     */
    private RiskCheckResult alignedRiskCheck(VirtualBalanceEntity s) {
        if (!riskManagementService.hasRiskConfig()) {
            return RiskCheckResult.reject("risk_config 행이 없다 — 신규 진입 차단(V88 기본 행 확인 필요)");
        }
        BigDecimal cap = nz(s.getInitialCapital());
        if (cap.signum() <= 0) {
            return RiskCheckResult.reject("초기 자본이 0 — 신규 진입 차단");
        }
        List<PaperPositionEntity> closed = positionRepo.findBySessionIdAndStatus(s.getId(), "CLOSED");
        Instant now = Instant.now();
        BigDecimal daily = alignedGrossLossPct(closed, now.minus(1, ChronoUnit.DAYS), cap);
        BigDecimal weekly = alignedGrossLossPct(closed, now.minus(7, ChronoUnit.DAYS), cap);
        BigDecimal monthly = alignedGrossLossPct(closed, now.minus(30, ChronoUnit.DAYS), cap);
        int openCount = positionRepo.findBySessionIdAndStatus(s.getId(), "OPEN").size();
        BigDecimal util = cap.subtract(nz(s.getAvailableKrw())).max(BigDecimal.ZERO)
                .divide(cap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
        BigDecimal drawdown = cap.subtract(nz(s.getTotalKrw())).max(BigDecimal.ZERO)
                .divide(cap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
        return riskManagementService.riskEngineForPaper().check(daily, weekly, monthly, openCount, util, drawdown);
    }

    private static BigDecimal alignedGrossLossPct(List<PaperPositionEntity> closed, Instant since, BigDecimal cap) {
        BigDecimal loss = closed.stream()
                .filter(p -> p.getClosedAt() != null && !p.getClosedAt().isBefore(since))
                .map(PaperPositionEntity::getRealizedPnl).filter(v -> v != null && v.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return loss.abs().divide(cap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
    }

    /**
     * 회차 승인 차례 — LIVE {@code approveQueuedEntry}·{@code approvalTimeGate} 와 같은 재확인(실행 중·보유·최신 캔들·블랙스완·BTC),
     * 그 시각 최신 종가로 체결, 만료 E 이상이면 체결하지 않는다.
     *
     * @return 진입했으면 {@code null}, 아니면 사유
     */
    String approveAlignedEntry(Long sessionId, String coinPair, StrategySignal signal, String marketRegime,
                               List<Candle> evalCandles, StrategyLogEntity signalLog, Instant expiresAt) {
        synchronized (alignedLock(sessionId)) {
            // 🔴 세션 재확인부터 포지션·주문·차감 저장까지 한 트랜잭션이다 (2026-11-11, §3-2 비동기 경계). 종전에는 트랜잭션 밖이라
            //    E검사 뒤 정지가 커밋되면 포지션·주문은 저장되고 세션 차감만 낙관적 잠금으로 실패해 정지된 세션에 열린 포지션이 남았다
            //    (B05 재현). 충돌이면 다시 읽어 다시 판단한다 — 정지됐으면 매수 없음.
            return optimisticRetry.inNewTransaction("모의투자[LIVE_ALIGNED] 승인 #" + sessionId, st -> {
                String result;
                VirtualBalanceEntity session = balanceRepo.findById(sessionId).orElse(null);
                if (session == null || !"RUNNING".equals(session.getStatus())) {
                    result = "승인 차례에 세션이 실행 중이 아니다";
                } else if (positionRepo.findBySessionIdAndCoinPairAndStatus(sessionId, coinPair, "OPEN").isPresent()) {
                    result = "승인 차례에 이미 포지션이 있다";
                } else {
                    List<Candle> latest = fetchRecentCandles(coinPair, session.getTimeframe());
                    if (alignedCandleProblem(coinPair, session.getTimeframe(), latest) != null) latest = List.of();
                    result = alignedApprovalGate(session, coinPair, session.getTimeframe(), latest);
                    if (result == null && expiresAt != null && entryClock != null
                            && EntryPriorityRounds.isExpired(expiresAt, entryClock.now())) {
                        result = "승인 실행 만료(만료 " + expiresAt + ") — 모의 매수 없음";
                    }
                    if (result == null) {
                        BigDecimal priceNow = latest.get(latest.size() - 1).getClose();
                        alignedRaceHook.accept("APPROVE_BEFORE_BUY");
                        boolean bought = executeBuy(sessionId, coinPair, priceNow, session, signal, evalCandles, marketRegime);
                        result = bought ? null : "매수 실행 실패(최소주문/잔고 미달)";
                    }
                }
                saveSignalQuality(signalLog, result == null, result);
                return result;
            });
        }
    }

    /** LIVE {@code approvalTimeGate} 와 같은 순서 — 서킷 브레이커 → 위험 한도 → 최신 캔들 → 블랙스완 → BTC. 처음 둘은 모의 계좌 입력이다. */
    private String alignedApprovalGate(VirtualBalanceEntity session, String coinPair, String timeframe, List<Candle> latest) {
        CircuitBreakerResult cb = alignedCircuitBreaker(session);
        if (cb.isTriggered()) {
            return "승인 차례에 서킷 브레이커 조건: " + cb.getReason();
        }
        RiskCheckResult risk = alignedRiskCheck(session);
        if (!risk.isApproved()) {
            return "리스크 한도: " + risk.getReason();
        }
        if (latest.size() < 10) {
            return "승인 차례에 최신 캔들을 못 읽었다(" + latest.size() + "건)";
        }
        BlackSwanGuard.Result blackSwan = BlackSwanGuard.check(latest);
        if (blackSwan.triggered()) {
            return "승인 차례에 BLACK_SWAN_GUARD 발동 — " + blackSwan.reason();
        }
        BtcMarketGuard.Result btc = alignedBtcGate(coinPair, timeframe, latest);
        if (btc.blocksEntry()) {
            return "승인 차례에 BTC_MARKET_GUARD " + (btc.unknown() ? "판정 불가" : "발동") + " — " + btc.reason();
        }
        return null;
    }

    /** RUNNING LIVE_ALIGNED 세션의 코인을 실시간 처리기·WebSocket 구독에 반영한다. 없으면 출처를 지운다(변화 없으면 아무것도 안 함). */
    private void refreshAlignedCoins(List<VirtualBalanceEntity> running) {
        java.util.Set<String> coins = running.stream().filter(PaperTradingService::isAligned)
                .map(VirtualBalanceEntity::getCoinPair)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        if (coins.equals(alignedCoins)) return;
        alignedCoins = java.util.Set.copyOf(coins);
        if (wsSubscriptionManager != null) wsSubscriptionManager.updateSource(WS_SOURCE, List.copyOf(coins));
    }

    /**
     * 실시간 시세 — LIVE {@code onRealtimePriceEvent} 와 같은 조건(낡은 시세·가격 아님 무시, 코인별 평상시 5초·급변 1초 간격, 손절가
     * 이하면 손절, 아니면 익절 트레일링). LIVE_ALIGNED 세션이 없는 코인은 DB 를 보지 않고 버린다. 가격 없는 회차에 판단한 시간 초과 청산은
     * 여기 첫 유효 현재가에 체결한다.
     */
    @org.springframework.context.event.EventListener
    @org.springframework.scheduling.annotation.Async("marketDataExecutor")
    public void onRealtimePriceEvent(RealtimePriceEvent event) {
        try {
            doOnAlignedRealtimePrice(event);
        } catch (Exception e) {
            log.error("모의투자[LIVE_ALIGNED] 실시간 처리 오류 — coin={}", event.getCoinCode(), e);
        }
    }

    void doOnAlignedRealtimePrice(RealtimePriceEvent event) {
        String coin = event.getCoinCode();
        if (!alignedCoins.contains(coin)) return;
        BigDecimal price = event.getPrice();
        if (markPriceBook != null && markPriceBook.isStale(event.getObservedAt(), Instant.now())) return;
        if (!MarkPriceBook.isPrice(price)) return;
        long now = alignedNowMs.getAsLong();

        // LIVE 와 같은 순서 — 이력은 간격 전에 항상 기록, 급변(30초 −1.5%/+2.0%)이면 1초, 아니면 5초 간격.
        java.util.Deque<long[]> history = alignedPriceHistory.computeIfAbsent(coin, k -> new java.util.ArrayDeque<>());
        BigDecimal spikeRate;
        synchronized (history) {
            history.addLast(new long[] {now, price.multiply(new BigDecimal("100000000")).longValue()});
            long[] first;
            while ((first = history.peekFirst()) != null && now - first[0] > ALIGNED_SPIKE_HISTORY_TTL_MS) history.pollFirst();
            spikeRate = alignedSpikeRate(history, now);
        }
        boolean spike = spikeRate.compareTo(ALIGNED_SPIKE_DOWN_THRESHOLD) <= 0 || spikeRate.compareTo(ALIGNED_SPIKE_UP_THRESHOLD) >= 0;
        if (spike) {
            Long lastSpike = alignedSpikeLastCheckMs.get(coin);
            if (lastSpike != null && now - lastSpike < ALIGNED_SPIKE_CHECK_INTERVAL_MS) return;
            alignedSpikeLastCheckMs.put(coin, now);
        } else {
            Long last = alignedRtLastCheckMs.get(coin);
            if (last != null && now - last < ALIGNED_RT_CHECK_INTERVAL_MS) return;
        }
        alignedRtLastCheckMs.put(coin, now);

        for (VirtualBalanceEntity s : balanceRepo.findByStatusOrderByStartedAtAsc("RUNNING")) {
            if (!coin.equals(s.getCoinPair()) || !isAligned(s)) continue;
            synchronized (alignedLock(s.getId())) {
                VirtualBalanceEntity session = balanceRepo.findById(s.getId()).orElse(null);
                if (session == null || !"RUNNING".equals(session.getStatus())) continue;
                Optional<PaperPositionEntity> open = positionRepo.findBySessionIdAndCoinPairAndStatus(session.getId(), coin, "OPEN");
                if (open.isEmpty()) continue;
                PaperPositionEntity pos = open.get();
                if (alignedPendingTimeExit.containsKey(pos.getId())) {
                    // 가격 없는 회차에 이미 내린 시간 초과 청산 판단 — 이 가격이 첫 유효 현재가다.
                    long heldHours = Duration.between(pos.getOpenedAt(), Instant.now()).toHours();
                    closeAligned(pos, price, session, String.format("시간 초과 청산(실시간 현재가 %s) — 보유 %d시간 ≥ %d시간%s",
                            price.toPlainString(), heldHours, session.getMaxHoldHours(), pendingTimeExitNote(pos)), ExitReason.TIME_STOP);
                    continue;
                }
                if (pos.getAvgPrice() == null || pos.getAvgPrice().signum() <= 0) continue;
                BigDecimal pnlPct = price.subtract(pos.getAvgPrice()).divide(pos.getAvgPrice(), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                BigDecimal rawStopLoss = session.getStopLossPct() != null
                        ? session.getStopLossPct() : exitChecker().getConfig().getStopLossPct();
                boolean sl = pos.getStopLossPrice() != null
                        ? price.compareTo(pos.getStopLossPrice()) <= 0
                        : pnlPct.compareTo(rawStopLoss.negate()) <= 0;
                if (sl) {
                    alignedRaceHook.accept("RT_BEFORE_CLOSE");
                    closeAligned(pos, price, session, "실시간 손절 — 손익률 " + pnlPct + "%", ExitReason.STOP_LOSS);
                    continue;
                }
                if (pos.getEntryPrice() != null && pos.getTakeProfitPrice() != null) {
                    var updated = exitChecker().updateTrailingStops(price, pos.getEntryPrice(),
                            pos.getStopLossPrice(), pos.getTakeProfitPrice());
                    if (updated.getTakeProfitPrice().compareTo(pos.getTakeProfitPrice()) != 0) {
                        pos.setTakeProfitPrice(updated.getTakeProfitPrice());
                        positionRepo.save(pos);
                    }
                }
            }
        }
    }

    /** LIVE {@code calcSpikeRate} 와 같은 식 — 30초 창의 첫 관측 대비 변화율(%). 이력은 [시각ms, 가격×1e8]. */
    private static BigDecimal alignedSpikeRate(java.util.Deque<long[]> history, long nowMs) {
        if (history.size() < 2) return BigDecimal.ZERO;
        long[] latest = history.peekLast();
        long[] windowStart = null;
        for (long[] snap : history) {
            if (nowMs - snap[0] <= ALIGNED_SPIKE_WINDOW_MS) {
                windowStart = snap;
                break;
            }
        }
        if (windowStart == null || windowStart == latest || windowStart[1] <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(latest[1]).subtract(BigDecimal.valueOf(windowStart[1]))
                .divide(BigDecimal.valueOf(windowStart[1]), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /** 신호 품질(실행 여부/차단 사유) 기록 — LIVE의 동명 메서드와 동일 목적. */
    private void saveSignalQuality(StrategyLogEntity logEntity, boolean wasExecuted, String blockedReason) {
        if (logEntity == null) return;
        try {
            logEntity.setWasExecuted(wasExecuted);
            logEntity.setBlockedReason(blockedReason);
            strategyLogRepo.save(logEntity);
        } catch (Exception e) {
            log.warn("신호 품질 로그 업데이트 실패: {}", e.getMessage());
        }
    }

    /**
     * 모의 매수 — LIVE {@code executeSessionBuy}와 동일한 투자금 산정·SL/TP 공식·최소주문 제약을 쓴다.
     *
     * @param evalCandles ATR 기반 손절폭 산정용 (닫힌 캔들 기준 — LIVE와 동일)
     * @return 실제로 체결됐으면 true (신호 품질 로그의 wasExecuted에 그대로 반영)
     */
    /**
     * @param marketRegime 진입 시점 시장 레짐 (V73). 틱에서 이미 계산한 값을 넘긴다 —
     *                     여기서 다시 구하면 같은 틱 안에서 값이 갈릴 수 있다.
     */
    private boolean executeBuy(Long sessionId, String coinPair, BigDecimal signalPrice,
                                VirtualBalanceEntity session, StrategySignal signal,
                                List<Candle> evalCandles, String marketRegime) {
        // 투자금: LIVE와 동일하게 availableKrw × investRatio (세션값 우선, 없으면 risk_config 기본값)
        BigDecimal ratio = session.getInvestRatio() != null
                ? session.getInvestRatio() : exitChecker().getConfig().getInvestRatio();
        // 1회 매수 상한 (2026-11-10, PaperMaxInvestment) — LIVE executeSessionBuy 와 같은 min(가용 × 비율, 상한) 뒤 둘째 자리 버림.
        //   키가 없는 세션은 종전과 같은 식이다.
        BigDecimal baseAmount = session.getAvailableKrw().multiply(ratio);
        BigDecimal maxInvestment = PaperMaxInvestment.from(session.getStrategyParams());
        BigDecimal investAmount = (maxInvestment != null ? baseAmount.min(maxInvestment) : baseAmount)
                .setScale(2, RoundingMode.DOWN);

        // 업비트 최소 주문금액 — 실거래에서 못 넣는 주문은 페이퍼도 넣지 않는다.
        if (investAmount.compareTo(MIN_ORDER_KRW) < 0) {
            log.warn("모의투자 매수 불가: 최소주문 미달 (투자가능 {}원 < {}원) sessionId={}",
                    investAmount, MIN_ORDER_KRW, sessionId);
            return false;
        }

        // 체결 슬리피지 — 매수는 신호가보다 불리하게(높게) 체결된다.
        BigDecimal price = signalPrice.multiply(BigDecimal.ONE.add(SLIPPAGE_PCT))
                .setScale(8, RoundingMode.HALF_UP);

        BigDecimal fee = investAmount.multiply(FEE_RATE);
        BigDecimal netAmount = investAmount.subtract(fee);
        BigDecimal quantity = netAmount.divide(price, 8, RoundingMode.DOWN);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        // avgPrice = 수수료 포함 실제 취득단가 (investAmount / quantity)
        // 이렇게 해야 closePosition 에서 costBasis = investAmount 가 되어 정확한 실현손익 계산
        BigDecimal avgPriceWithFee = investAmount.divide(quantity, 8, RoundingMode.HALF_UP);
        // LIVE_ALIGNED (2026-11-11, §3-2): 판단 평균가는 **체결가**다 — LIVE 정산의 평균단가(체결금액 ÷ 수량, 수수료 제외)와 같은 관례.
        //   손절률·전략 SELL(+0.30%) 경계가 LIVE 와 같아진다. 비용 원가는 investedKrw(수수료 포함)로 따로 들고 가며 청산 손익은 그것으로
        //   계산한다 — 수수료는 매수 1회·매도 1회 그대로다. 종전 PAPER·66 세션은 수수료 포함 평균가 그대로.
        boolean alignedMode = isAligned(session);
        BigDecimal avgPriceForDecision = alignedMode ? price : avgPriceWithFee;

        // ── SL/TP: ATR 기반 (LIVE·DYNAMIC과 ExitRuleCalculator 공유) ──────────
        // 기존에는 ExitRuleChecker의 고정 % 공식을 써서 실전과 손절폭이 달랐다.
        // 세션 strategy_params 의 slAtrMultiplier/tpRrMultiplier 오버라이드를 적용한다(손절폭 A/B).
        ExitRuleOverrides exitOverrides = ExitRuleOverrides.from(session.getStrategyParams());
        BigDecimal slPct = ExitRuleCalculator.resolveStopLossPct(
                session.getStopLossPct(), evalCandles, price, exitOverrides);
        BigDecimal atrStopLossPrice = price.multiply(BigDecimal.ONE.subtract(
                        slPct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
                .setScale(8, RoundingMode.HALF_DOWN);

        // 전략 제안 SL은 존중하되 더 넓은 쪽을 채택 (타이트한 제안은 휩쏘로 이어진다 — LIVE 동일)
        // exitOverrides 가 걸린 세션(손절폭 A/B)은 예외 — suggestedStopLoss 가 세션 오버라이드를
        // 모른 채 arm 과 무관하게 동일해서, min() 을 타면 오버라이드가 조용히 무효화된다(2026-08-24).
        BigDecimal stopLossPrice = (!exitOverrides.isPresent() && signal != null && signal.getSuggestedStopLoss() != null)
                ? signal.getSuggestedStopLoss().min(atrStopLossPrice)
                : atrStopLossPrice;

        BigDecimal takeProfitPrice = ExitRuleCalculator.resolveTakeProfitPrice(
                price, stopLossPrice, signal != null ? signal.getSuggestedTakeProfit() : null,
                exitOverrides);

        String rulesetHash = rulesetRegistry.hashFor(session);
        PaperPositionEntity pos = PaperPositionEntity.builder()
                .rulesetHash(rulesetHash)
                // V73: public.position 과 컬럼을 맞춘다 — 페이퍼와 동적 세션을 한 쿼리로 보기 위함.
                .sessionKind("PAPER")
                .marketRegime(marketRegime)
                .investedKrw(investAmount)
                .sessionId(sessionId)
                .coinPair(coinPair)
                .side("BUY")
                .entryPrice(price)
                .avgPrice(avgPriceForDecision)
                .size(quantity)
                .positionFee(fee)
                .status("OPEN")
                .stopLossPrice(stopLossPrice)
                .takeProfitPrice(takeProfitPrice)
                .build();
        pos = positionRepo.save(pos);

        PaperOrderEntity order = PaperOrderEntity.builder()
                .sessionId(sessionId)
                .positionId(pos.getId())
                .coinPair(coinPair)
                .side("BUY")
                .orderType("MARKET")
                .price(price)
                .quantity(quantity)
                .filledQuantity(quantity)
                .state("FILLED")
                .exchangeOrderId("PAPER-" + pos.getId())
                .signalReason(signal.getReason())
                .filledAt(Instant.now())
                .build();
        orderRepo.save(order);

        session.setAvailableKrw(session.getAvailableKrw().subtract(investAmount));
        session.setTotalFee(session.getTotalFee().add(fee));
        balanceRepo.save(session);

        log.info("모의 매수 체결 (sessionId={}): {} {}개 @ {} SL={} TP={} (수수료: {})",
                sessionId, coinPair, quantity, price, stopLossPrice, takeProfitPrice, fee);

        if (Boolean.TRUE.equals(session.getTelegramEnabled())) {
            telegramService.bufferTradeEvent(
                    "[모의투자] 세션#" + sessionId, coinPair, "BUY",
                    price, quantity, fee, null, signal.getReason());
        }
        return true;
    }

    /**
     * @param exitReason 집계용 청산 사유 (V73). {@code reason} 자유 텍스트는 사람이 읽을
     *                   정보를, 이 값은 GROUP BY 가능한 축을 담당한다 — 둘 다 필요하다.
     */
    private void closePosition(PaperPositionEntity pos, BigDecimal signalPrice,
                               VirtualBalanceEntity session, String reason,
                               ExitReason exitReason) {
        // 체결 슬리피지 — 매도는 신호가보다 불리하게(낮게) 체결된다 (매수 반대 방향).
        BigDecimal currentPrice = signalPrice.multiply(BigDecimal.ONE.subtract(SLIPPAGE_PCT))
                .setScale(8, RoundingMode.HALF_DOWN);

        BigDecimal proceeds = pos.getSize().multiply(currentPrice);
        BigDecimal fee = proceeds.multiply(FEE_RATE);
        BigDecimal netProceeds = proceeds.subtract(fee);

        // LIVE_ALIGNED 는 평균가가 체결가라 원가는 investedKrw(수수료 포함)다. 종전 PAPER 는 size × 수수료 포함 평균가 그대로.
        BigDecimal costBasis = (isAligned(session) && pos.getInvestedKrw() != null)
                ? pos.getInvestedKrw() : pos.getSize().multiply(pos.getAvgPrice());
        BigDecimal realizedPnl = netProceeds.subtract(costBasis);

        pos.setRealizedPnl(realizedPnl);
        pos.setUnrealizedPnl(BigDecimal.ZERO);
        pos.setPositionFee(pos.getPositionFee() != null
                ? pos.getPositionFee().add(fee) : fee);
        pos.setStatus("CLOSED");
        pos.setClosedAt(Instant.now());
        pos.setExitReason(exitReason != null ? exitReason : ExitReason.UNKNOWN);
        positionRepo.save(pos);

        PaperOrderEntity order = PaperOrderEntity.builder()
                .sessionId(pos.getSessionId())
                .positionId(pos.getId())
                .coinPair(pos.getCoinPair())
                .side("SELL")
                .orderType("MARKET")
                .price(currentPrice)
                .quantity(pos.getSize())
                .filledQuantity(pos.getSize())
                .state("FILLED")
                .exchangeOrderId("PAPER-SELL-" + pos.getId())
                .signalReason(reason)
                .filledAt(Instant.now())
                .build();
        orderRepo.save(order);

        session.setAvailableKrw(session.getAvailableKrw().add(netProceeds));
        session.setTotalKrw(session.getAvailableKrw());
        session.setRealizedPnl(session.getRealizedPnl().add(realizedPnl));
        session.setTotalFee(session.getTotalFee().add(fee));
        balanceRepo.save(session);

        log.info("모의 매도 체결 (sessionId={}): {} {}개 @ {} 손익: {} KRW",
                pos.getSessionId(), pos.getCoinPair(), pos.getSize(), currentPrice, realizedPnl);

        if (Boolean.TRUE.equals(session.getTelegramEnabled())) {
            telegramService.bufferTradeEvent(
                    "[모의투자] 세션#" + pos.getSessionId(), pos.getCoinPair(), "SELL",
                    currentPrice, pos.getSize(), fee, realizedPnl, reason);
        }
    }

    private void updateUnrealizedPnl(Long sessionId, String coinPair, BigDecimal currentPrice,
                                      VirtualBalanceEntity session) {
        // 현재 코인 포지션 미실현손익 갱신
        positionRepo.findBySessionIdAndCoinPairAndStatus(sessionId, coinPair, "OPEN").ifPresent(pos -> {
            BigDecimal unrealized = currentPrice.subtract(pos.getAvgPrice()).multiply(pos.getSize());
            pos.setUnrealizedPnl(unrealized);
            positionRepo.save(pos);
        });

        // totalKrw = 가용 KRW + 세션 내 모든 오픈 포지션 평가금액 합산
        // (다중 코인 지원 시에도 정확한 총자산 계산)
        BigDecimal openPositionsValue = positionRepo.findBySessionIdAndStatus(sessionId, "OPEN")
                .stream()
                .map(pos -> {
                    BigDecimal price = pos.getCoinPair().equals(coinPair)
                            ? currentPrice
                            : fetchCurrentPrice(pos.getCoinPair());
                    return pos.getSize().multiply(price);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // executeBuy/closePosition이 session을 저장했을 수 있으므로 최신 버전으로 재조회
        VirtualBalanceEntity freshSession = balanceRepo.findById(sessionId).orElse(session);
        freshSession.setTotalKrw(freshSession.getAvailableKrw().add(openPositionsValue));
        balanceRepo.save(freshSession);
    }

    private VirtualBalanceEntity getSession(Long sessionId) {
        return balanceRepo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다: id=" + sessionId));
    }

    private List<Candle> fetchRecentCandles(String coinPair, String timeframe) {
        Instant to = Instant.now();
        Instant from = to.minus(CANDLE_LOOKBACK * TimeframeUtils.toMinutes(timeframe), ChronoUnit.MINUTES);

        // MarketDataSyncService 가 미리 DB에 저장한 캔들만 사용
        return marketDataCacheRepo.findCandles(coinPair, timeframe, from, to).stream()
                .map(c -> Candle.builder()
                        .time(c.getTime()).open(c.getOpen()).high(c.getHigh())
                        .low(c.getLow()).close(c.getClose()).volume(c.getVolume())
                        .build())
                .toList();
    }

    private BigDecimal fetchCurrentPrice(String coinPair) {
        // DB에서 가장 최근 M1 캔들 조회
        Instant now = Instant.now();
        Instant from = now.minus(5, ChronoUnit.MINUTES);
        List<MarketDataCacheEntity> recent = marketDataCacheRepo.findCandles(coinPair, "M1", from, now);
        if (!recent.isEmpty()) {
            return recent.get(recent.size() - 1).getClose();
        }
        // M1 캔들이 없으면 주입된 UpbitRestClient 통해 폴백 조회
        if (upbitRestClient != null) {
            try {
                UpbitCandleCollector collector = new UpbitCandleCollector(upbitRestClient);
                List<Candle> candles = collector.fetchCandles(coinPair, "M1", from, now);
                if (!candles.isEmpty()) {
                    return candles.get(candles.size() - 1).getClose();
                }
            } catch (Exception e) {
                log.warn("현재가 조회 실패: {}", e.getMessage());
            }
        }
        return BigDecimal.ZERO;
    }


}
