package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.dto.DynamicSessionRequest;
import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.service.DynamicTradingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 동적 멀티코인 세션 REST API.
 *
 * <pre>
 * POST   /api/v1/dynamic-sessions           세션 생성
 * POST   /api/v1/dynamic-sessions/{id}/start 세션 시작
 * POST   /api/v1/dynamic-sessions/{id}/stop  세션 정지
 * POST   /api/v1/dynamic-sessions/{id}/emergency-stop 비상 정지
 * GET    /api/v1/dynamic-sessions           세션 목록
 * GET    /api/v1/dynamic-sessions/{id}      세션 상세
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/dynamic-sessions")
@RequiredArgsConstructor
public class DynamicSessionController {

    private final DynamicTradingService dynamicTradingService;
    private final PositionRepository positionRepository;

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
            return ApiResponse.ok(m);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("NOT_FOUND", e.getMessage());
        }
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
