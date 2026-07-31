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
                    posMap.put("status",           pos.getStatus());
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
                positionRepository.findBySessionKindAndSessionIdOrderByOpenedAtDesc("DYNAMIC", id);
        if (positions.isEmpty()) {
            return ApiResponse.ok(List.of());
        }

        // N+1 방지: 전 포지션의 주문을 한 번에 읽어 매수/매도 사유를 붙인다.
        Map<Long, List<OrderEntity>> ordersByPosition = orderRepository
                .findByPositionIdIn(positions.stream().map(PositionEntity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(OrderEntity::getPositionId));

        List<Map<String, Object>> result = positions.stream()
                .map(pos -> toHistoryMap(pos, ordersByPosition.getOrDefault(pos.getId(), List.of())))
                .toList();
        return ApiResponse.ok(result);
    }

    /**
     * 세션 손익 내역 분해 — {@code returnPct}(=total_asset_krw 기반)가 맞는지 대조하기 위한 근거.
     *
     * <p>{@code total_asset_krw} 는 매수 시점 취득원가로만 갱신되고 보유 중 시세 변동을 반영하지
     * 않으므로, 포지션을 들고 있는 동안 {@code returnPct} 는 미실현손익만큼 어긋난다. 여기서
     * <b>이 세션의 포지션만</b>({@code session_kind='DYNAMIC'} + {@code session_id}) 집계해
     * 실현/미실현을 분리해 내려보내면 화면에서 실제 손익을 정확히 표시할 수 있다.</p>
     */
    private Map<String, Object> buildPnlBreakdown(Long sessionId) {
        List<PositionEntity> positions =
                positionRepository.findBySessionKindAndSessionIdOrderByOpenedAtDesc("DYNAMIC", sessionId);

        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        int closedCount = 0;
        int winCount = 0;

        for (PositionEntity pos : positions) {
            if (pos.getRealizedPnl() != null) {
                realized = realized.add(pos.getRealizedPnl());
            }
            if ("CLOSED".equals(pos.getStatus())) {
                // 고아(미체결) 포지션은 size=0·손익 null 이라 승률 통계에서 제외
                if (pos.getSize() != null && pos.getSize().compareTo(BigDecimal.ZERO) > 0
                        && pos.getRealizedPnl() != null) {
                    closedCount++;
                    if (pos.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0) {
                        winCount++;
                    }
                }
            } else if (pos.getUnrealizedPnl() != null) {
                unrealized = unrealized.add(pos.getUnrealizedPnl());
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("realizedPnl",      realized);
        m.put("unrealizedPnl",    unrealized);
        m.put("totalPnl",         realized.add(unrealized));
        m.put("positionCount",    positions.size());
        m.put("closedTradeCount", closedCount);
        m.put("winCount",         winCount);
        m.put("winRatePct", closedCount > 0
                ? BigDecimal.valueOf(winCount)
                        .divide(BigDecimal.valueOf(closedCount), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP)
                : null);
        return m;
    }

    private Map<String, Object> toHistoryMap(PositionEntity pos, List<OrderEntity> orders) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",               pos.getId());
        m.put("coinPair",         pos.getCoinPair());
        m.put("status",           pos.getStatus());
        m.put("entryPrice",       pos.getEntryPrice());
        m.put("avgPrice",         pos.getAvgPrice());
        m.put("size",             pos.getSize());
        m.put("investedKrw",      pos.getInvestedKrw());
        m.put("stopLossPrice",    pos.getStopLossPrice());
        m.put("takeProfitPrice",  pos.getTakeProfitPrice());
        m.put("realizedPnl",      pos.getRealizedPnl());
        m.put("unrealizedPnl",    pos.getUnrealizedPnl());
        m.put("positionFee",      pos.getPositionFee());
        m.put("marketRegime",     pos.getMarketRegime());
        m.put("openedAt",         pos.getOpenedAt() != null ? pos.getOpenedAt().toString() : null);
        m.put("closedAt",         pos.getClosedAt() != null ? pos.getClosedAt().toString() : null);
        m.put("returnPct",        calcPositionReturnPct(pos));
        m.put("holdMinutes",      calcHoldMinutes(pos));

        // 매수/매도 사유 — 체결(FILLED) 주문을 우선 채택, 없으면 가장 최근 주문
        m.put("buyReason",  pickReason(orders, "BUY"));
        m.put("sellReason", pickReason(orders, "SELL"));
        m.put("orderCount", orders.size());
        return m;
    }

    /** 해당 방향 주문 중 체결건의 사유를 우선 반환 (체결 없으면 최근 주문 사유) */
    private String pickReason(List<OrderEntity> orders, String side) {
        List<OrderEntity> sideOrders = orders.stream()
                .filter(o -> side.equalsIgnoreCase(o.getSide()))
                .sorted(Comparator.comparing(OrderEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
        return sideOrders.stream()
                .filter(o -> "FILLED".equalsIgnoreCase(o.getState()))
                .findFirst()
                .or(() -> sideOrders.stream().findFirst())
                .map(OrderEntity::getSignalReason)
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
