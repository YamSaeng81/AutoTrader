package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.PaperTradingStartRequest;
import com.cryptoautotrader.api.dto.PerformanceSummaryResponse;
import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.entity.paper.PaperOrderEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.repository.paper.PaperOrderRepository;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import com.cryptoautotrader.api.util.TimeframeUtils;
import com.cryptoautotrader.exchange.upbit.UpbitCandleCollector;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import org.springframework.beans.factory.annotation.Autowired;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaperTradingService {

    private static final int MAX_CONCURRENT_SESSIONS = 10;
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005");
    private static final BigDecimal INVEST_RATIO = new BigDecimal("0.80");
    private static final int CANDLE_LOOKBACK = 100;

    /** 기본 손절 비율 3% (전략이 suggestedStopLoss를 제공하지 않을 때 사용) */
    private static final BigDecimal DEFAULT_SL_RATE = new BigDecimal("0.03");
    /** 기본 익절 비율 6% — R:R = 1:2 */
    private static final BigDecimal DEFAULT_TP_RATE = new BigDecimal("0.06");

    /** Stateful 전략 세션별 인스턴스 (COMPOSITE, COMPOSITE_MOMENTUM 등 상태 보유 전략) */
    private final Map<Long, com.cryptoautotrader.strategy.Strategy> sessionStatefulStrategies = new ConcurrentHashMap<>();

    private final VirtualBalanceRepository balanceRepo;
    private final PaperPositionRepository positionRepo;
    private final PaperOrderRepository orderRepo;
    private final MarketDataCacheRepository marketDataCacheRepo;
    private final TelegramNotificationService telegramService;
    private final StrategyLogRepository strategyLogRepo;

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

    @Transactional
    public VirtualBalanceEntity stop(Long sessionId) {
        VirtualBalanceEntity session = getSession(sessionId);
        if (!"RUNNING".equals(session.getStatus())) {
            throw new IllegalStateException("모의투자가 실행 중이 아닙니다.");
        }

        List<PaperPositionEntity> openPositions = positionRepo.findBySessionIdAndStatus(sessionId, "OPEN");
        openPositions.forEach(pos -> {
            BigDecimal currentPrice = fetchCurrentPrice(pos.getCoinPair());
            closePosition(pos, currentPrice, session, "모의투자 중단 - 강제 청산");
        });

        session.setStatus("STOPPED");
        session.setStoppedAt(Instant.now());
        sessionStatefulStrategies.remove(sessionId);

        log.info("모의투자 세션 중단 (id={}). 최종 자산: {} KRW", sessionId, session.getTotalKrw());
        VirtualBalanceEntity stopped = balanceRepo.save(session);

        if (Boolean.TRUE.equals(session.getTelegramEnabled())) {
            BigDecimal initial = session.getInitialCapital() != null ? session.getInitialCapital() : session.getTotalKrw();
            double returnPct = initial.compareTo(BigDecimal.ZERO) > 0
                    ? stopped.getTotalKrw().subtract(initial)
                              .divide(initial, 4, RoundingMode.HALF_UP)
                              .multiply(BigDecimal.valueOf(100)).doubleValue()
                    : 0;
            telegramService.notifyPaperSessionStopped(
                    sessionId, session.getStrategyName(), session.getCoinPair(),
                    stopped.getTotalKrw(), returnPct);
        }
        return stopped;
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
                .sessions(sessionPerfs)
                .build();
    }

    // ── 스케줄: MarketDataSyncService 실행(0s) 후 35초 뒤 전략 실행 ──

    @Scheduled(fixedDelay = 60_000, initialDelay = 35_000)
    public void runStrategy() {
        List<VirtualBalanceEntity> runningSessions = balanceRepo.findByStatusOrderByStartedAtAsc("RUNNING");
        for (VirtualBalanceEntity session : runningSessions) {
            try {
                runSessionStrategy(session);
            } catch (Exception e) {
                log.error("모의투자 전략 실행 오류 (sessionId={}): {}", session.getId(), e.getMessage(), e);
            }
        }
    }

    // ── 내부 메서드 ───────────────────────────────────────────

    private void runSessionStrategy(VirtualBalanceEntity session) {
        String coinPair = session.getCoinPair();
        String timeframe = session.getTimeframe();
        String strategyName = session.getStrategyName();

        List<Candle> candles = fetchRecentCandles(coinPair, timeframe);
        if (candles.size() < 10) {
            log.warn("모의투자 캔들 부족: {} {}건 (sessionId={})", coinPair, candles.size(), session.getId());
            return;
        }

        com.cryptoautotrader.strategy.Strategy strategyInstance =
                StrategyRegistry.isStateful(strategyName)
                        ? sessionStatefulStrategies.computeIfAbsent(session.getId(),
                                id -> StrategyRegistry.createNew(strategyName))
                        : StrategyRegistry.get(strategyName);
        StrategySignal signal = strategyInstance.evaluate(candles, Collections.emptyMap());
        log.info("모의투자 신호 (sessionId={}): {} {} → {} ({})",
                session.getId(), strategyName, coinPair, signal.getAction(), signal.getReason());

        // 전략 로그 DB 저장
        try {
            StrategyLogEntity logEntity = StrategyLogEntity.builder()
                    .strategyName(strategyName)
                    .coinPair(coinPair)
                    .signal(signal.getAction().name())
                    .reason(signal.getReason())
                    .marketRegime(null)
                    .sessionType("PAPER")
                    .sessionId(session.getId())
                    .build();
            strategyLogRepo.save(logEntity);
        } catch (Exception e) {
            log.warn("전략 로그 저장 실패: {}", e.getMessage());
        }

        BigDecimal currentPrice = candles.get(candles.size() - 1).getClose();
        Optional<PaperPositionEntity> openPos = positionRepo
                .findBySessionIdAndCoinPairAndStatus(session.getId(), coinPair, "OPEN");

        // ── 손절/익절 체크 (전략 신호보다 우선) ──────────────────
        if (openPos.isPresent()) {
            PaperPositionEntity pos = openPos.get();
            if (pos.getStopLossPrice() != null
                    && currentPrice.compareTo(pos.getStopLossPrice()) <= 0) {
                log.warn("모의투자 손절 발동 (sessionId={}): {} 현재가={} 손절가={}",
                        session.getId(), coinPair, currentPrice, pos.getStopLossPrice());
                closePosition(pos, currentPrice, session,
                        "손절 발동 — 현재가 " + currentPrice + " ≤ 손절가 " + pos.getStopLossPrice());
                return;
            }
            if (pos.getTakeProfitPrice() != null
                    && currentPrice.compareTo(pos.getTakeProfitPrice()) >= 0) {
                log.info("모의투자 익절 발동 (sessionId={}): {} 현재가={} 익절가={}",
                        session.getId(), coinPair, currentPrice, pos.getTakeProfitPrice());
                closePosition(pos, currentPrice, session,
                        "익절 발동 — 현재가 " + currentPrice + " ≥ 익절가 " + pos.getTakeProfitPrice());
                return;
            }
        }

        final StrategySignal finalSignal = signal;
        switch (signal.getAction()) {
            case BUY -> {
                if (openPos.isEmpty()) {
                    executeBuy(session.getId(), coinPair, currentPrice, session, finalSignal);
                }
            }
            case SELL -> openPos.ifPresent(pos -> closePosition(pos, currentPrice, session, finalSignal.getReason()));
            default -> { /* HOLD */ }
        }

        updateUnrealizedPnl(session.getId(), coinPair, currentPrice, session);
    }

    private void executeBuy(Long sessionId, String coinPair, BigDecimal price,
                             VirtualBalanceEntity session, StrategySignal signal) {
        BigDecimal investAmount = session.getAvailableKrw().multiply(INVEST_RATIO);
        if (investAmount.compareTo(BigDecimal.valueOf(5000)) < 0) {
            log.warn("모의투자 매수 불가: 가용 자금 부족 ({}) sessionId={}", session.getAvailableKrw(), sessionId);
            return;
        }

        BigDecimal fee = investAmount.multiply(FEE_RATE);
        BigDecimal netAmount = investAmount.subtract(fee);
        BigDecimal quantity = netAmount.divide(price, 8, RoundingMode.DOWN);

        // avgPrice = 수수료 포함 실제 취득단가 (investAmount / quantity)
        // 이렇게 해야 closePosition 에서 costBasis = investAmount 가 되어 정확한 실현손익 계산
        BigDecimal avgPriceWithFee = investAmount.divide(quantity, 8, RoundingMode.HALF_UP);

        // 손절/익절가 계산: 전략 제안값 우선, 없으면 기본 비율 적용
        BigDecimal stopLossPrice = (signal.getSuggestedStopLoss() != null)
                ? signal.getSuggestedStopLoss()
                : price.multiply(BigDecimal.ONE.subtract(DEFAULT_SL_RATE)).setScale(8, RoundingMode.HALF_DOWN);
        BigDecimal takeProfitPrice = (signal.getSuggestedTakeProfit() != null)
                ? signal.getSuggestedTakeProfit()
                : price.multiply(BigDecimal.ONE.add(DEFAULT_TP_RATE)).setScale(8, RoundingMode.HALF_UP);

        PaperPositionEntity pos = PaperPositionEntity.builder()
                .sessionId(sessionId)
                .coinPair(coinPair)
                .side("BUY")
                .entryPrice(price)
                .avgPrice(avgPriceWithFee)
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
    }

    private void closePosition(PaperPositionEntity pos, BigDecimal currentPrice,
                               VirtualBalanceEntity session, String reason) {
        BigDecimal proceeds = pos.getSize().multiply(currentPrice);
        BigDecimal fee = proceeds.multiply(FEE_RATE);
        BigDecimal netProceeds = proceeds.subtract(fee);

        BigDecimal costBasis = pos.getSize().multiply(pos.getAvgPrice());
        BigDecimal realizedPnl = netProceeds.subtract(costBasis);

        pos.setRealizedPnl(realizedPnl);
        pos.setUnrealizedPnl(BigDecimal.ZERO);
        pos.setPositionFee(pos.getPositionFee() != null
                ? pos.getPositionFee().add(fee) : fee);
        pos.setStatus("CLOSED");
        pos.setClosedAt(Instant.now());
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
