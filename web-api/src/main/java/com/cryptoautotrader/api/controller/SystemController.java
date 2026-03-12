package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class SystemController {

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "status", "UP",
                "version", "0.1.0"
        ));
    }

    /** 전략 타입 목록 — 프론트 select용 배열 반환 */
    @GetMapping("/strategies/types")
    public ApiResponse<List<Map<String, Object>>> getStrategyTypes() {
        List<Map<String, Object>> list = StrategyRegistry.getAll().entrySet().stream()
                .map(e -> {
                    Strategy s = e.getValue();
                    return Map.<String, Object>of(
                            "type", e.getKey(),
                            "name", e.getKey()
                    );
                })
                .toList();
        return ApiResponse.ok(list);
    }
}
