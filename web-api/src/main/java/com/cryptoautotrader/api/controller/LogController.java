package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.entity.RegimeChangeLogEntity;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.RegimeChangeLogRepository;
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
    private final RegimeChangeLogRepository regimeChangeLogRepo;

    @GetMapping("/strategy")
    public ApiResponse<Map<String, Object>> getStrategyLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sessionType,
            @RequestParam(required = false) Long sessionId) {
        boolean hasType = sessionType != null && !sessionType.isBlank() && !"ALL".equalsIgnoreCase(sessionType);
        PageRequest pageReq = PageRequest.of(page, size);
        Page<StrategyLogEntity> logs;
        if (sessionId != null && hasType) {
            logs = strategyLogRepo.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc(sessionType.toUpperCase(), sessionId, pageReq);
        } else if (sessionId != null) {
            logs = strategyLogRepo.findAllBySessionIdOrderByCreatedAtDesc(sessionId, pageReq);
        } else if (hasType) {
            logs = strategyLogRepo.findAllBySessionTypeOrderByCreatedAtDesc(sessionType.toUpperCase(), pageReq);
        } else {
            logs = strategyLogRepo.findAllByOrderByCreatedAtDesc(pageReq);
        }
        List<Map<String, Object>> content = logs.getContent().stream().map(log -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", log.getId());
            m.put("strategyName", log.getStrategyName());
            m.put("coinPair", log.getCoinPair());
            m.put("signal", log.getSignal());
            m.put("reason", log.getReason());
            m.put("indicatorsJson", log.getIndicatorsJson());
            m.put("marketRegime", log.getMarketRegime());
            m.put("sessionType", log.getSessionType());
            m.put("sessionId", log.getSessionId());
            m.put("createdAt", log.getCreatedAt() != null ? log.getCreatedAt().toString() : null);
            // 신호 품질 지표
            m.put("signalPrice", log.getSignalPrice());
            m.put("wasExecuted", log.isWasExecuted());
            m.put("blockedReason", log.getBlockedReason());
            m.put("priceAfter4h", log.getPriceAfter4h());
            m.put("priceAfter24h", log.getPriceAfter24h());
            m.put("return4hPct", log.getReturn4hPct());
            m.put("return24hPct", log.getReturn24hPct());
            return m;
        }).toList();
        return ApiResponse.ok(Map.of(
                "content", content,
                "totalElements", logs.getTotalElements(),
                "totalPages", logs.getTotalPages(),
                "number", logs.getNumber()
        ));
    }

    /** 레짐 전환 이력 조회 (최신순, 기본 100건) */
    @GetMapping("/regime-history")
    public ApiResponse<List<Map<String, Object>>> getRegimeHistory(
            @RequestParam(defaultValue = "100") int size) {
        List<RegimeChangeLogEntity> history = regimeChangeLogRepo.findRecent(PageRequest.of(0, size));
        List<Map<String, Object>> content = history.stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", r.getId());
            m.put("coinPair", r.getCoinPair());
            m.put("timeframe", r.getTimeframe());
            m.put("fromRegime", r.getFromRegime());
            m.put("toRegime", r.getToRegime());
            m.put("strategyChangesJson", r.getStrategyChangesJson());
            m.put("detectedAt", r.getDetectedAt() != null ? r.getDetectedAt().toString() : null);
            return m;
        }).toList();
        return ApiResponse.ok(content);
    }
}
