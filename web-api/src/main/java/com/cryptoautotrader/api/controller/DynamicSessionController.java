package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.dto.DynamicSessionRequest;
import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.service.DynamicTradingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 동적 멀티코인 세션 REST API.
 *
 * <pre>
 * POST   /api/v1/dynamic-sessions           세션 생성
 * POST   /api/v1/dynamic-sessions/{id}/start 세션 시작
 * POST   /api/v1/dynamic-sessions/{id}/stop  세션 정지
 * POST   /api/v1/dynamic-sessions/{id}/emergency-stop 비상 정지
 * DELETE /api/v1/dynamic-sessions/{id}      세션 삭제 (soft, RUNNING 거부)
 * GET    /api/v1/dynamic-sessions           세션 목록 (DELETED 제외)
 * GET    /api/v1/dynamic-sessions/{id}      세션 상세 (현재 포지션 + 손익 분해)
 * GET    /api/v1/dynamic-sessions/{id}/positions 보유 코인 이력 (매수/매도 사유·손익)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/dynamic-sessions")
@RequiredArgsConstructor
public class DynamicSessionController {

    /** 거래소 수수료율 — 체결 경로와 단일 출처를 쓴다(값이 갈라지면 화면 수수료가 틀린다). */
    private static final BigDecimal FEE_RATE = DynamicTradingService.FEE_RATE;

    private final DynamicTradingService dynamicTradingService;
    private final PositionRepository positionRepository;
    private final OrderRepository orderRepository;

    /** 세션 생성 */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody DynamicSessionRequest req) {
        try {
            DynamicSessionEntity session = dynamicTradingService.createSession(req);
            return ApiResponse.ok(toMap(session));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("INVALID_REQUEST", e.getMessage());
        }
    }

    /** 세션 시작 */
    @PostMapping("/{id}/start")
    public ApiResponse<Map<String, Object>> start(@PathVariable Long id) {
        try {
            DynamicSessionEntity session = dynamicTradingService.startSession(id);
            return ApiResponse.ok(toMap(session));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error("INVALID_REQUEST", e.getMessage());
        }
    }

    /** 세션 정지 */
    @PostMapping("/{id}/stop")
    public ApiResponse<Map<String, Object>> stop(@PathVariable Long id) {
        try {
            DynamicSessionEntity session = dynamicTradingService.stopSession(id);
            return ApiResponse.ok(toMap(session));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error("INVALID_REQUEST", e.getMessage());
        }
    }

    /** 비상 정지 */
    @PostMapping("/{id}/emergency-stop")
    public ApiResponse<Map<String, Object>> emergencyStop(@PathVariable Long id) {
        try {
            DynamicSessionEntity session = dynamicTradingService.emergencyStop(id);
            return ApiResponse.ok(toMap(session));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("NOT_FOUND", e.getMessage());
        }
    }

    /** 세션 삭제 (soft) — RUNNING 상태는 거부, DELETED 상태로 전환해 목록에서 제외 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        try {
            dynamicTradingService.deleteSession(id);
            return ApiResponse.ok(null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("NOT_FOUND", e.getMessage());
        } catch (IllegalStateException e) {
            return ApiResponse.error("INVALID_STATE", e.getMessage());
        }
    }

    /** 세션 목록 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Map<String, Object>> result = dynamicTradingService.listSessions()
                .stream().map(this::toMap).toList();
        return ApiResponse.ok(result);
    }

    /** 세션 상세 (포지션 정보 포함) */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        try {
            DynamicSessionEntity session = dynamicTradingService.getSession(id);
            Map<String, Object> m = toMap(session);
            if (session.getCurrentPositionId() != null) {
                positionRepository.findById(session.getCurrentPositionId()).ifPresent(pos -> {
                    Map<String, Object> posMap = new LinkedHashMap<>();
                    posMap.put("id",              pos.getId());
                    posMap.put("coinPair",         pos.getCoinPair());
                    posMap.put("entryPrice",       pos.getEntryPrice());
                    posMap.put("avgPrice",         pos.getAvgPrice());
                    posMap.put("size",             pos.getSize());
                    posMap.put("investedKrw",      pos.getInvestedKrw());
                    posMap.put("stopLossPrice",    pos.getStopLossPrice());
                    posMap.put("takeProfitPrice",  pos.getTakeProfitPrice());
                    posMap.put("unrealizedPnl",    pos.getUnrealizedPnl());
                    posMap.put("entryFee",         entryFeeOf(pos));
                    posMap.put("status",           pos.getStatus());
                    posMap.put("rulesetHash",      pos.getRulesetHash());
                    posMap.put("marketRegime",     pos.getMarketRegime());
                    posMap.put("openedAt",         pos.getOpenedAt() != null ? pos.getOpenedAt().toString() : null);
                    m.put("currentPosition", posMap);
                });
            }
            m.putAll(buildPnlBreakdown(id));
            return ApiResponse.ok(m);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("NOT_FOUND", e.getMessage());
        }
    }

    /**
     * 세션 보유 코인 이력 — 이 세션이 거쳐온 모든 포지션을 최신순으로 반환한다.
     *
     * <p>동적 세션은 워치리스트를 돌며 보유 코인이 계속 바뀌므로, 현재 포지션 하나만으로는
     * 세션 성과를 읽을 수 없다. 각 이력에는 매수/매도 사유(주문의 signal_reason)와
     * 실현손익·수익률·보유시간을 함께 싣는다.</p>
     *
     * <p>⚠️ {@code (session_kind, session_id)} 쌍으로 조회한다 — position 테이블은 실전매매와
     * 공용이고 두 세션 테이블의 id가 겹칠 수 있다.</p>
     */
    @GetMapping("/{id}/positions")
    public ApiResponse<List<Map<String, Object>>> positions(@PathVariable Long id) {
        try {
            dynamicTradingService.getSession(id); // 존재 확인
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("NOT_FOUND", e.getMessage());
        }

        List<PositionEntity> positions =
                positionRepository.findBySessionKindAndSessionIdOrderByOpenedAtDesc(kindOf(id), id);
        if (positions.isEmpty()) {
            return ApiResponse.ok(List.of());
        }

        Map<Long, List<OrderEntity>> ordersByPosition = ordersOf(positions);

        List<Map<String, Object>> result = positions.stream()
                .map(pos -> toHistoryMap(pos, ordersByPosition.getOrDefault(pos.getId(), List.of())))
                .toList();
        return ApiResponse.ok(result);
    }

    /**
     * 이 세션의 포지션이 저장된 {@code session_kind}.
     *
     * <p><b>2026-09-01 P0</b>: 여기가 {@code "DYNAMIC"} 으로 하드코딩돼 있었다. 그런데 PAPER
     * 세션의 포지션은 {@code session_kind='DYN_PAPER'} 로 저장된다({@code V67}). 그래서
     * <b>모의 세션 상세 화면은 손익 분해가 전부 0, 보유 코인 이력이 통째로 비어 있었다</b> —
     * 실제로는 거래가 있었는데도. 현재 포지션 패널만 {@code currentPositionId} 로 직접 조회해
     * 정상 표시되니, 화면 안에서 서로 모순되는 숫자가 나왔다.</p>
     *
     * <p>{@link DynamicTradingService#sessionKind}를 그대로 쓴다 — 문자열을 다시 적으면
     * 같은 실수가 반복된다.</p>
     */
    private String kindOf(Long sessionId) {
        return DynamicTradingService.sessionKind(dynamicTradingService.getSession(sessionId));
    }

    /**
     * 세션 손익 내역 분해 — {@code returnPct}(=total_asset_krw 기반)가 맞는지 대조하기 위한 근거.
     *
     * <p>{@code total_asset_krw} 는 매수 시점 취득원가로만 갱신되고 보유 중 시세 변동을 반영하지
     * 않으므로, 포지션을 들고 있는 동안 {@code returnPct} 는 미실현손익만큼 어긋난다. 여기서
     * <b>이 세션의 포지션만</b> 집계해 실현/미실현을 분리해 내려보내면 화면에서 실제 손익을
     * 정확히 표시할 수 있다.</p>
     *
     * <p><b>수수료에 대해</b>: {@code realizedPnl} 은 이미 <b>매수·매도 수수료를 모두 뺀 순손익</b>이다
     * — 매수 시 {@code avgPrice} 를 수수료 포함 취득단가로 잡고({@code investedKrw / quantity}),
     * 청산 시 순수취액에서 그 원가를 빼기 때문이다. 화면에서 "손익 − 수수료" 를 또 빼면
     * 이중 차감이 된다. 그래서 여기서는 수수료를 <b>따로 합산해 참고용으로</b> 내려보내고,
     * 수수료를 더한 {@code grossPnl}(= 수수료가 없었다면 얼마였을까)을 함께 준다.</p>
     */
    private Map<String, Object> buildPnlBreakdown(Long sessionId) {
        List<PositionEntity> positions =
                positionRepository.findBySessionKindAndSessionIdOrderByOpenedAtDesc(kindOf(sessionId), sessionId);

        Map<Long, List<OrderEntity>> ordersByPosition = ordersOf(positions);

        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        BigDecimal bestPnl = null;
        BigDecimal worstPnl = null;
        String bestCoin = null;
        String worstCoin = null;
        long holdMinutesSum = 0;
        int closedCount = 0;
        int winCount = 0;
        int openCount = 0;

        // 청산 사유별 건수 — "손절 대 익절 대 시간초과" 를 화면에서 바로 읽기 위한 축.
        Map<String, Integer> exitReasonCounts = new LinkedHashMap<>();

        for (PositionEntity pos : positions) {
            totalFee = totalFee.add(totalFeeOf(pos, ordersByPosition.getOrDefault(pos.getId(), List.of())));

            if (pos.getRealizedPnl() != null) {
                realized = realized.add(pos.getRealizedPnl());
            }
            if ("CLOSED".equals(pos.getStatus())) {
                String reason = pos.getExitReason() != null ? pos.getExitReason().name() : "UNKNOWN";
                exitReasonCounts.merge(reason, 1, Integer::sum);

                // 고아(미체결) 포지션은 size=0·손익 null 이라 승률 통계에서 제외
                if (pos.getSize() != null && pos.getSize().compareTo(BigDecimal.ZERO) > 0
                        && pos.getRealizedPnl() != null) {
                    closedCount++;
                    BigDecimal pnl = pos.getRealizedPnl();
                    if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                        winCount++;
                    }
                    if (bestPnl == null || pnl.compareTo(bestPnl) > 0) {
                        bestPnl = pnl;
                        bestCoin = pos.getCoinPair();
                    }
                    if (worstPnl == null || pnl.compareTo(worstPnl) < 0) {
                        worstPnl = pnl;
                        worstCoin = pos.getCoinPair();
                    }
                    Long held = calcHoldMinutes(pos);
                    if (held != null) holdMinutesSum += held;
                }
            } else {
                openCount++;
                if (pos.getUnrealizedPnl() != null) {
                    unrealized = unrealized.add(pos.getUnrealizedPnl());
                }
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("realizedPnl",      realized);
        m.put("unrealizedPnl",    unrealized);
        m.put("totalPnl",         realized.add(unrealized));
        // 수수료는 이미 realizedPnl 에서 빠져 있다. grossPnl 은 "수수료가 없었다면" 의 가정값이라
        // 마찰비용이 성과를 얼마나 갉아먹는지 보여주는 용도지, 실제 손익이 아니다.
        m.put("totalFee",         totalFee);
        m.put("grossPnl",         realized.add(unrealized).add(totalFee));
        m.put("feeNote",          "실현손익은 매수·매도 수수료를 모두 뺀 순손익입니다");
        m.put("positionCount",    positions.size());
        m.put("openPositionCount", openCount);
        m.put("closedTradeCount", closedCount);
        m.put("winCount",         winCount);
        m.put("winRatePct", closedCount > 0
                ? BigDecimal.valueOf(winCount)
                        .divide(BigDecimal.valueOf(closedCount), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP)
                : null);
        m.put("avgPnl", closedCount > 0
                ? realized.divide(BigDecimal.valueOf(closedCount), 0, RoundingMode.HALF_UP) : null);
        m.put("avgHoldMinutes", closedCount > 0 ? holdMinutesSum / closedCount : null);
        m.put("bestPnl",   bestPnl);
        m.put("bestCoin",  bestCoin);
        m.put("worstPnl",  worstPnl);
        m.put("worstCoin", worstCoin);
        m.put("exitReasonCounts", exitReasonCounts);
        return m;
    }

    /** N+1 방지 — 전 포지션의 주문을 한 번에 읽어 포지션별로 묶는다. */
    private Map<Long, List<OrderEntity>> ordersOf(List<PositionEntity> positions) {
        if (positions.isEmpty()) return Map.of();
        return orderRepository
                .findByPositionIdIn(positions.stream().map(PositionEntity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(OrderEntity::getPositionId));
    }

    private Map<String, Object> toHistoryMap(PositionEntity pos, List<OrderEntity> orders) {
        OrderEntity buy  = pickOrder(orders, "BUY");
        OrderEntity sell = pickOrder(orders, "SELL");
        BigDecimal exitPrice = sell != null ? sell.getPrice() : null;
        BigDecimal entryFee = entryFeeOf(pos);
        BigDecimal exitFee  = exitFeeOf(pos, exitPrice);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",               pos.getId());
        m.put("coinPair",         pos.getCoinPair());
        m.put("status",           pos.getStatus());
        m.put("entryPrice",       pos.getEntryPrice());
        m.put("avgPrice",         pos.getAvgPrice());
        m.put("exitPrice",        exitPrice);
        m.put("size",             pos.getSize());
        m.put("investedKrw",      pos.getInvestedKrw());
        m.put("stopLossPrice",    pos.getStopLossPrice());
        m.put("takeProfitPrice",  pos.getTakeProfitPrice());
        m.put("realizedPnl",      pos.getRealizedPnl());
        m.put("unrealizedPnl",    pos.getUnrealizedPnl());

        // 수수료 3종 — positionFee 컬럼은 청산 시 매도 수수료로 덮어써지므로(총합이 아니다)
        // 화면에 그대로 내보내면 매수 수수료가 사라진 것처럼 보인다. 여기서 양쪽을 각각 환산한다.
        m.put("entryFee",         entryFee);
        m.put("exitFee",          exitFee);
        m.put("totalFee",         entryFee.add(exitFee));
        m.put("positionFee",      pos.getPositionFee());   // 원본 컬럼값 (참고용)
        // 수수료가 없었다면 얼마였을까 — realizedPnl 은 이미 순손익이므로 되더한 값이다.
        m.put("grossPnl", pos.getRealizedPnl() != null
                ? pos.getRealizedPnl().add(entryFee).add(exitFee) : null);

        m.put("exitReason",       pos.getExitReason() != null ? pos.getExitReason().name() : null);
        m.put("countsTowardPerformance",
                pos.getExitReason() != null && pos.getExitReason().countsTowardStrategyPerformance());
        m.put("marketRegime",     pos.getMarketRegime());
        m.put("rulesetHash",      pos.getRulesetHash());
        m.put("openedAt",         pos.getOpenedAt() != null ? pos.getOpenedAt().toString() : null);
        m.put("closedAt",         pos.getClosedAt() != null ? pos.getClosedAt().toString() : null);
        m.put("returnPct",        calcPositionReturnPct(pos));
        m.put("holdMinutes",      calcHoldMinutes(pos));

        // 매수/매도 사유 — 체결(FILLED) 주문을 우선 채택, 없으면 가장 최근 주문
        m.put("buyReason",  buy  != null ? buy.getSignalReason()  : null);
        m.put("sellReason", sell != null ? sell.getSignalReason() : null);
        m.put("buyAt",      buy  != null && buy.getFilledAt()  != null ? buy.getFilledAt().toString()  : null);
        m.put("sellAt",     sell != null && sell.getFilledAt() != null ? sell.getFilledAt().toString() : null);
        m.put("buySignalPrice",  buy  != null ? buy.getSignalPrice()  : null);
        m.put("sellSignalPrice", sell != null ? sell.getSignalPrice() : null);
        m.put("orderCount", orders.size());
        return m;
    }

    /**
     * 매수 수수료 — 체결 시 {@code investedKrw × FEE_RATE} 로 계산되어 {@code avgPrice} 에 녹아 있다.
     * ({@code avgPrice = investedKrw / quantity} 이므로 취득원가 = investedKrw.)
     */
    private BigDecimal entryFeeOf(PositionEntity pos) {
        BigDecimal invested = pos.getInvestedKrw();
        if (invested == null) return BigDecimal.ZERO;
        return invested.multiply(FEE_RATE).setScale(2, RoundingMode.HALF_UP);
    }

    /** 매도 수수료 — 청산가를 알아야 계산된다(= 매도 총액 × FEE_RATE). 미청산이면 0. */
    private BigDecimal exitFeeOf(PositionEntity pos, BigDecimal exitPrice) {
        if (!"CLOSED".equals(pos.getStatus())) return BigDecimal.ZERO;
        if (exitPrice == null || pos.getSize() == null) {
            // 매도 주문을 못 찾은 경우 — positionFee 컬럼이 청산 시 매도 수수료로 덮어써지므로 그것을 쓴다.
            return pos.getPositionFee() != null ? pos.getPositionFee() : BigDecimal.ZERO;
        }
        return pos.getSize().multiply(exitPrice).multiply(FEE_RATE).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal totalFeeOf(PositionEntity pos, List<OrderEntity> orders) {
        OrderEntity sell = pickOrder(orders, "SELL");
        return entryFeeOf(pos).add(exitFeeOf(pos, sell != null ? sell.getPrice() : null));
    }

    /** 해당 방향 주문 중 체결건을 우선 반환 (체결 없으면 최근 주문) */
    private OrderEntity pickOrder(List<OrderEntity> orders, String side) {
        List<OrderEntity> sideOrders = orders.stream()
                .filter(o -> side.equalsIgnoreCase(o.getSide()))
                .sorted(Comparator.comparing(OrderEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
        return sideOrders.stream()
                .filter(o -> "FILLED".equalsIgnoreCase(o.getState()))
                .findFirst()
                .or(() -> sideOrders.stream().findFirst())
                .orElse(null);
    }

    /** 포지션 수익률(%) — 실현손익(청산) 또는 미실현손익(보유중)을 투입금 대비로 환산 */
    private BigDecimal calcPositionReturnPct(PositionEntity pos) {
        BigDecimal invested = pos.getInvestedKrw();
        if (invested == null || invested.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        BigDecimal pnl = "CLOSED".equals(pos.getStatus()) ? pos.getRealizedPnl() : pos.getUnrealizedPnl();
        if (pnl == null) {
            return null;
        }
        return pnl.divide(invested, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 보유 시간(분) — 청산됐으면 확정 구간, 보유 중이면 현재까지 */
    private Long calcHoldMinutes(PositionEntity pos) {
        if (pos.getOpenedAt() == null) {
            return null;
        }
        Instant end = pos.getClosedAt() != null ? pos.getClosedAt() : Instant.now();
        return Duration.between(pos.getOpenedAt(), end).toMinutes();
    }

    private Map<String, Object> toMap(DynamicSessionEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                s.getId());
        m.put("strategyType",      s.getStrategyType());
        m.put("timeframe",         s.getTimeframe());
        m.put("status",            s.getStatus());
        m.put("tradingMode",       s.getTradingMode());
        m.put("scanState",         s.getScanState());
        m.put("currentCoinPair",   s.getCurrentCoinPair());
        m.put("initialCapital",    s.getInitialCapital());
        m.put("availableKrw",      s.getAvailableKrw());
        m.put("totalAssetKrw",     s.getTotalAssetKrw());
        m.put("returnPct",         calcReturnPct(s));
        m.put("investRatio",       s.getInvestRatio());
        m.put("stopLossPct",       s.getStopLossPct());
        m.put("maxCandidateSize",  s.getMaxCandidateSize());
        m.put("targetWatchSize",   s.getTargetWatchSize());
        m.put("minAtrPct",         s.getMinAtrPct());
        m.put("maxSpreadPct",      s.getMaxSpreadPct());
        m.put("watchlistRefreshMin", s.getWatchlistRefreshMin());
        m.put("maxHoldHours",      s.getMaxHoldHours());
        m.put("watchlistRefreshedAt", s.getWatchlistRefreshedAt() != null
                ? s.getWatchlistRefreshedAt().toString() : null);
        m.put("watchlistJson",     s.getWatchlistJson());
        m.put("currentPositionId", s.getCurrentPositionId());
        m.put("startedAt",         s.getStartedAt() != null ? s.getStartedAt().toString() : null);
        m.put("stoppedAt",         s.getStoppedAt() != null ? s.getStoppedAt().toString() : null);
        m.put("createdAt",         s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        return m;
    }

    private BigDecimal calcReturnPct(DynamicSessionEntity s) {
        if (s.getInitialCapital() == null || s.getInitialCapital().compareTo(BigDecimal.ZERO) == 0
                || s.getTotalAssetKrw() == null) {
            return BigDecimal.ZERO;
        }
        return s.getTotalAssetKrw().subtract(s.getInitialCapital())
                .divide(s.getInitialCapital(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
