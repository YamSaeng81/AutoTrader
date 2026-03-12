package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.PaperTradingStartRequest;
import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.entity.paper.PaperOrderEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.repository.paper.PaperOrderRepository;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import com.cryptoautotrader.exchange.upbit.UpbitCandleCollector;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaperTradingService {

    private static final int MAX_CONCURRENT_SESSIONS = 5;
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005");
    private static final BigDecimal INVEST_RATIO = new BigDecimal("0.80");
    private static final int CANDLE_LOOKBACK = 100;

    private final VirtualBalanceRepository balanceRepo;
    private final PaperPositionRepository positionRepo;
    private final PaperOrderRepository orderRepo;
    private final MarketDataCacheRepository marketDataCacheRepo;

    // ── 공개 API ──────────────────────────────────────────────

    @Transactional
    public VirtualBalanceEntity start(PaperTradingStartRequest req) {
        long runningCount = balanceRepo.countByStatus("RUNNING");
        if (runningCount >= MAX_CONCURRENT_SESSIONS) {
            throw new IllegalStateException("최대 " + MAX_CONCURRENT_SESSIONS + "개의 동시 모의투자만 가능합니다.");
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
                .build();

        log.info("모의투자 세션 시작: {} {} {} 초기자본={}",
                req.getStrategyType(), req.getCoinPair(), req.getTimeframe(), req.getInitialCapital());
        return balanceRepo.save(session);
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

        log.info("모의투자 세션 중단 (id={}). 최종 자산: {} KRW", sessionId, session.getTotalKrw());
        return balanceRepo.save(session);
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

    // ── 스케줄: MarketDataSyncService 실행(0s) 후 35초 뒤 전략 실행 ──

    @Scheduled(fixedDelay = 60_000, initialDelay = 35_000)
    @Transactional
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

        StrategySignal signal = StrategyRegistry.get(strategyName).evaluate(candles, Collections.emptyMap());
        log.info("모의투자 신호 (sessionId={}): {} {} → {} ({})",
                session.getId(), strategyName, coinPair, signal.getAction(), signal.getReason());

        BigDecimal currentPrice = candles.get(candles.size() - 1).getClose();
        Optional<PaperPositionEntity> openPos = positionRepo
                .findBySessionIdAndCoinPairAndStatus(session.getId(), coinPair, "OPEN");

        switch (signal.getAction()) {
            case BUY -> {
                if (openPos.isEmpty()) {
                    executeBuy(session.getId(), coinPair, currentPrice, session, signal.getReason());
                }
            }
            case SELL -> openPos.ifPresent(pos -> closePosition(pos, currentPrice, session, signal.getReason()));
            default -> { /* HOLD */ }
        }

        updateUnrealizedPnl(session.getId(), coinPair, currentPrice, session);
    }

    private void executeBuy(Long sessionId, String coinPair, BigDecimal price,
                             VirtualBalanceEntity session, String reason) {
        BigDecimal investAmount = session.getAvailableKrw().multiply(INVEST_RATIO);
        if (investAmount.compareTo(BigDecimal.valueOf(5000)) < 0) {
            log.warn("모의투자 매수 불가: 가용 자금 부족 ({}) sessionId={}", session.getAvailableKrw(), sessionId);
            return;
        }

        BigDecimal fee = investAmount.multiply(FEE_RATE);
        BigDecimal netAmount = investAmount.subtract(fee);
        BigDecimal quantity = netAmount.divide(price, 8, RoundingMode.DOWN);

        PaperPositionEntity pos = PaperPositionEntity.builder()
                .sessionId(sessionId)
                .coinPair(coinPair)
                .side("BUY")
                .entryPrice(price)
                .avgPrice(price)
                .size(quantity)
                .status("OPEN")
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
                .signalReason(reason)
                .filledAt(Instant.now())
                .build();
        orderRepo.save(order);

        session.setAvailableKrw(session.getAvailableKrw().subtract(investAmount));
        balanceRepo.save(session);

        log.info("모의 매수 체결 (sessionId={}): {} {}개 @ {} (수수료: {})", sessionId, coinPair, quantity, price, fee);
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
        balanceRepo.save(session);

        log.info("모의 매도 체결 (sessionId={}): {} {}개 @ {} 손익: {} KRW",
                pos.getSessionId(), pos.getCoinPair(), pos.getSize(), currentPrice, realizedPnl);
    }

    private void updateUnrealizedPnl(Long sessionId, String coinPair, BigDecimal currentPrice,
                                      VirtualBalanceEntity session) {
        positionRepo.findBySessionIdAndCoinPairAndStatus(sessionId, coinPair, "OPEN").ifPresent(pos -> {
            BigDecimal unrealized = currentPrice.subtract(pos.getAvgPrice()).multiply(pos.getSize());
            pos.setUnrealizedPnl(unrealized);
            positionRepo.save(pos);

            BigDecimal posValue = pos.getSize().multiply(currentPrice);
            session.setTotalKrw(session.getAvailableKrw().add(posValue));
            balanceRepo.save(session);
        });
    }

    private VirtualBalanceEntity getSession(Long sessionId) {
        return balanceRepo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다: id=" + sessionId));
    }

    private List<Candle> fetchRecentCandles(String coinPair, String timeframe) {
        Instant to = Instant.now();
        Instant from = to.minus(CANDLE_LOOKBACK * timeframeMinutes(timeframe), ChronoUnit.MINUTES);

        // MarketDataSyncService 가 미리 DB에 저장한 캔들만 사용
        return marketDataCacheRepo.findCandles(coinPair, timeframe, from, to).stream()
                .map(c -> Candle.builder()
                        .time(c.getTime()).open(c.getOpen()).high(c.getHigh())
                        .low(c.getLow()).close(c.getClose()).volume(c.getVolume())
                        .build())
                .toList();
    }

    private BigDecimal fetchCurrentPrice(String coinPair) {
        // DB에서 가장 최근 캔들(어떤 타임프레임이든) 조회
        Instant now = Instant.now();
        Instant from = now.minus(5, ChronoUnit.MINUTES);
        List<MarketDataCacheEntity> recent = marketDataCacheRepo.findCandles(coinPair, "M1", from, now);
        if (!recent.isEmpty()) {
            return recent.get(recent.size() - 1).getClose();
        }
        // M1 캔들이 없으면 Upbit API 직접 호출 (중단 시 폴백)
        try {
            UpbitRestClient restClient = new UpbitRestClient();
            UpbitCandleCollector collector = new UpbitCandleCollector(restClient);
            List<Candle> candles = collector.fetchCandles(coinPair, "M1", from, now);
            if (!candles.isEmpty()) {
                return candles.get(candles.size() - 1).getClose();
            }
        } catch (Exception e) {
            log.warn("현재가 조회 실패: {}", e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    private long timeframeMinutes(String timeframe) {
        return switch (timeframe) {
            case "M1" -> 1;
            case "M5" -> 5;
            case "H1" -> 60;
            case "D1" -> 1440;
            default -> 60;
        };
    }
}
