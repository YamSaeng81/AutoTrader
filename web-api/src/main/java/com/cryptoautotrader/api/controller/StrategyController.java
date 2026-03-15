package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.entity.StrategyConfigEntity;
import com.cryptoautotrader.api.entity.StrategyTypeEnabledEntity;
import com.cryptoautotrader.api.repository.StrategyConfigRepository;
import com.cryptoautotrader.api.repository.StrategyTypeEnabledRepository;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/strategies")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyConfigRepository configRepo;
    private final StrategyTypeEnabledRepository enabledRepo;

    /**
     * 전략 목록 + 상태 조회
     * GET /api/v1/strategies
     */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> getStrategies() {
        Map<String, Boolean> activeMap = enabledRepo.findAll().stream()
                .collect(Collectors.toMap(
                        StrategyTypeEnabledEntity::getStrategyName,
                        e -> Boolean.TRUE.equals(e.getIsActive())
                ));
        List<Map<String, Object>> strategies = StrategyRegistry.getAll().entrySet().stream()
                .map(e -> buildStrategyInfo(e.getKey(), e.getValue(), activeMap.getOrDefault(e.getKey(), true)))
                .toList();
        return ApiResponse.ok(strategies);
    }

    /**
     * 전략 단건 상세 조회
     * GET /api/v1/strategies/{name}
     */
    @GetMapping("/{name}")
    public ApiResponse<Map<String, Object>> getStrategy(@PathVariable String name) {
        try {
            Strategy strategy = StrategyRegistry.get(name);
            boolean isActive = enabledRepo.findById(name)
                    .map(e -> Boolean.TRUE.equals(e.getIsActive()))
                    .orElse(true);
            return ApiResponse.ok(buildStrategyInfo(name, strategy, isActive));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("NOT_FOUND", "전략을 찾을 수 없습니다: " + name);
        }
    }

    /**
     * 전략 타입 활성/비활성 토글
     * PATCH /api/v1/strategies/{name}/active
     */
    @PatchMapping("/{name}/active")
    public ApiResponse<Map<String, Object>> toggleActive(@PathVariable String name) {
        try {
            Strategy strategy = StrategyRegistry.get(name);
            StrategyTypeEnabledEntity entity = enabledRepo.findById(name)
                    .orElseGet(() -> {
                        StrategyTypeEnabledEntity e = new StrategyTypeEnabledEntity();
                        e.setStrategyName(name);
                        e.setIsActive(true);
                        return e;
                    });
            entity.setIsActive(!Boolean.TRUE.equals(entity.getIsActive()));
            enabledRepo.save(entity);
            return ApiResponse.ok(buildStrategyInfo(name, strategy, entity.getIsActive()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("NOT_FOUND", "전략을 찾을 수 없습니다: " + name);
        }
    }

    /**
     * 전략 설정 생성
     * POST /api/v1/strategies
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> createConfig(@RequestBody Map<String, Object> body) {
        StrategyConfigEntity entity = StrategyConfigEntity.builder()
                .name((String) body.getOrDefault("name", ""))
                .strategyType((String) body.getOrDefault("strategyType", ""))
                .coinPair((String) body.getOrDefault("coinPair", "KRW-BTC"))
                .timeframe((String) body.getOrDefault("timeframe", "M15"))
                .configJson(body.containsKey("configJson") ? (Map<String, Object>) body.get("configJson") : Map.of())
                .maxInvestment(body.containsKey("maxInvestment") ? new BigDecimal(body.get("maxInvestment").toString()) : null)
                .stopLossPct(body.containsKey("stopLossPct") ? new BigDecimal(body.get("stopLossPct").toString()) : null)
                .reinvestPct(body.containsKey("reinvestPct") ? new BigDecimal(body.get("reinvestPct").toString()) : null)
                .isActive(true)
                .build();
        StrategyConfigEntity saved = configRepo.save(entity);
        return ApiResponse.ok(toConfigMap(saved));
    }

    /**
     * 전략 설정 수정
     * PUT /api/v1/strategies/{id}
     */
    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> updateConfig(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return configRepo.findById(id)
                .map(entity -> {
                    if (body.containsKey("name")) entity.setName((String) body.get("name"));
                    if (body.containsKey("strategyType")) entity.setStrategyType((String) body.get("strategyType"));
                    if (body.containsKey("coinPair")) entity.setCoinPair((String) body.get("coinPair"));
                    if (body.containsKey("timeframe")) entity.setTimeframe((String) body.get("timeframe"));
                    if (body.containsKey("configJson")) entity.setConfigJson((Map<String, Object>) body.get("configJson"));
                    if (body.containsKey("maxInvestment")) entity.setMaxInvestment(new BigDecimal(body.get("maxInvestment").toString()));
                    if (body.containsKey("stopLossPct")) entity.setStopLossPct(new BigDecimal(body.get("stopLossPct").toString()));
                    if (body.containsKey("reinvestPct")) entity.setReinvestPct(new BigDecimal(body.get("reinvestPct").toString()));
                    StrategyConfigEntity saved = configRepo.save(entity);
                    return ApiResponse.ok(toConfigMap(saved));
                })
                .orElse(ApiResponse.error("NOT_FOUND", "전략 설정을 찾을 수 없습니다: " + id));
    }

    /**
     * 전략 활성/비활성 토글 (수동 오버라이드 설정)
     * PATCH /api/v1/strategies/{id}/toggle
     *
     * 사용자가 직접 호출한 경우이므로 manualOverride=true 로 설정한다.
     * 이후 MarketRegimeAwareScheduler 의 자동 스위칭 대상에서 제외된다.
     */
    @PatchMapping("/{id}/toggle")
    public ApiResponse<Map<String, Object>> toggleConfig(@PathVariable Long id) {
        return configRepo.findById(id)
                .map(entity -> {
                    entity.setIsActive(!Boolean.TRUE.equals(entity.getIsActive()));
                    entity.setManualOverride(Boolean.TRUE); // 수동 조작 → 자동 스위칭 제외
                    StrategyConfigEntity saved = configRepo.save(entity);
                    return ApiResponse.ok(toConfigMap(saved));
                })
                .orElse(ApiResponse.error("NOT_FOUND", "전략 설정을 찾을 수 없습니다: " + id));
    }

    /**
     * 수동 오버라이드 해제 — 이후 MarketRegimeAwareScheduler 가 자동 관리
     * PATCH /api/v1/strategies/{id}/toggle-override
     */
    @PatchMapping("/{id}/toggle-override")
    public ApiResponse<Map<String, Object>> toggleOverride(@PathVariable Long id) {
        return configRepo.findById(id)
                .map(entity -> {
                    boolean current = Boolean.TRUE.equals(entity.getManualOverride());
                    entity.setManualOverride(!current);
                    StrategyConfigEntity saved = configRepo.save(entity);
                    return ApiResponse.ok(toConfigMap(saved));
                })
                .orElse(ApiResponse.error("NOT_FOUND", "전략 설정을 찾을 수 없습니다: " + id));
    }

    private Map<String, Object> toConfigMap(StrategyConfigEntity e) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", e.getId());
        map.put("name", e.getName());
        map.put("strategyType", e.getStrategyType());
        map.put("coinPair", e.getCoinPair());
        map.put("timeframe", e.getTimeframe());
        map.put("configJson", e.getConfigJson());
        map.put("isActive", e.getIsActive());
        map.put("manualOverride", e.getManualOverride());
        map.put("maxInvestment", e.getMaxInvestment());
        map.put("stopLossPct", e.getStopLossPct());
        map.put("reinvestPct", e.getReinvestPct());
        map.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        map.put("updatedAt", e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null);
        return map;
    }

    // ── 전략 타입(레지스트리) 조회 ──────────────────────────────

    private Map<String, Object> buildStrategyInfo(String name, Strategy strategy, boolean isActive) {
        boolean isImplemented = isStrategyImplemented(name);
        Map<String, Object> map = new HashMap<>();
        map.put("name", name);
        map.put("minimumCandleCount", strategy.getMinimumCandleCount());
        map.put("status", isImplemented ? "AVAILABLE" : "SKELETON");
        map.put("description", getDescription(name));
        map.put("isActive", isActive);
        return map;
    }

    /**
     * 구현 완료 전략 여부 판별
     */
    private boolean isStrategyImplemented(String name) {
        return switch (name) {
            // Phase 1: 완전 구현
            case "VWAP", "EMA_CROSS", "BOLLINGER", "GRID" -> true;
            // Phase 3: 로직 구현 완료
            case "RSI", "MACD", "SUPERTREND", "ATR_BREAKOUT", "ORDERBOOK_IMBALANCE", "STOCHASTIC_RSI" -> true;
            default -> false;
        };
    }

    private String getDescription(String name) {
        return switch (name) {
            case "VWAP"                -> "거래량 가중 평균 가격 기반 역추세 매매";
            case "EMA_CROSS"           -> "단기/장기 EMA 골든·데드크로스 추세 추종";
            case "BOLLINGER"           -> "볼린저 밴드 %B 기반 평균 회귀 매매";
            case "GRID"                -> "가격 그리드 레벨 근접 시 매매";
            case "RSI"                 -> "RSI 과매수/과매도 기반 역추세 매매";
            case "MACD"                -> "MACD/Signal 크로스 기반 추세 추종";
            case "SUPERTREND"          -> "ATR 기반 동적 지지/저항 추세 추종";
            case "ATR_BREAKOUT"        -> "ATR 변동성 돌파 모멘텀 매매";
            case "ORDERBOOK_IMBALANCE" -> "호가 불균형 기반 단기 방향성 매매 (Phase 4 WebSocket 연동 필요)";
            case "STOCHASTIC_RSI"      -> "RSI 에 Stochastic 적용, RANGE/VOLATILITY 시장 민감 감지";
            default -> "설명 없음";
        };
    }
}
