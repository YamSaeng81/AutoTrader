package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/logs")
@RequiredArgsConstructor
public class LogController {

    private final StrategyLogRepository strategyLogRepo;

    @GetMapping("/strategy")
    public ApiResponse<Map<String, Object>> getStrategyLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Page<StrategyLogEntity> logs = strategyLogRepo.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
        List<Map<String, Object>> content = logs.getContent().stream().map(log -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", log.getId());
            m.put("strategyName", log.getStrategyName());
            m.put("coinPair", log.getCoinPair());
            m.put("signal", log.getSignal());
            m.put("reason", log.getReason());
            m.put("indicatorsJson", log.getIndicatorsJson());
            m.put("marketRegime", log.getMarketRegime());
            m.put("createdAt", log.getCreatedAt() != null ? log.getCreatedAt().toString() : null);
            return m;
        }).toList();
        return ApiResponse.ok(Map.of(
                "content", content,
                "totalElements", logs.getTotalElements(),
                "totalPages", logs.getTotalPages(),
                "number", logs.getNumber()
        ));
    }
}
