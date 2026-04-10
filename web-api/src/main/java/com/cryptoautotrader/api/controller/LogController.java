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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

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

    /**
     * 신호 품질 통계 집계
     * - 전략별, 레짐별 4h/24h 적중률 및 평균 수익률
     *
     * @param days        최근 N일 데이터 (기본 30)
     * @param sessionType LIVE / PAPER / ALL (기본 ALL)
     */
    @GetMapping("/signal-stats")
    public ApiResponse<Map<String, Object>> getSignalStats(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String sessionType) {

        Instant from = Instant.now().minus(days, ChronoUnit.DAYS);
        boolean hasType = sessionType != null && !sessionType.isBlank() && !"ALL".equalsIgnoreCase(sessionType);

        List<StrategyLogEntity> signals = hasType
                ? strategyLogRepo.findEvaluatedSignalsBySessionType(sessionType.toUpperCase(), from)
                : strategyLogRepo.findEvaluatedSignals(from);

        return ApiResponse.ok(Map.of(
                "overall",    buildOverallStats(signals),
                "byStrategy", buildByStrategy(signals),
                "byRegime",   buildByRegime(signals)
        ));
    }

    private Map<String, Object> buildOverallStats(List<StrategyLogEntity> signals) {
        List<StrategyLogEntity> eval4h  = signals.stream().filter(l -> l.getReturn4hPct()  != null).toList();
        List<StrategyLogEntity> eval24h = signals.stream().filter(l -> l.getReturn24hPct() != null).toList();
        return Map.of(
                "totalSignals",    signals.size(),
                "evaluated4h",     eval4h.size(),
                "winRate4h",       winRate(eval4h, true),
                "avgReturn4h",     avgReturn(eval4h, true),
                "evaluated24h",    eval24h.size(),
                "winRate24h",      winRate(eval24h, false),
                "avgReturn24h",    avgReturn(eval24h, false)
        );
    }

    private List<Map<String, Object>> buildByStrategy(List<StrategyLogEntity> signals) {
        // 전략명 + 코인페어 조합으로 그룹핑
        Map<String, List<StrategyLogEntity>> grouped = signals.stream()
                .collect(Collectors.groupingBy(
                        l -> l.getStrategyName() + "|" + l.getCoinPair()
                ));

        return grouped.entrySet().stream()
                .map(e -> {
                    String[] parts = e.getKey().split("\\|", 2);
                    List<StrategyLogEntity> group = e.getValue();
                    List<StrategyLogEntity> eval4h  = group.stream().filter(l -> l.getReturn4hPct()  != null).toList();
                    List<StrategyLogEntity> eval24h = group.stream().filter(l -> l.getReturn24hPct() != null).toList();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("strategyName",  parts[0]);
                    m.put("coinPair",      parts.length > 1 ? parts[1] : "");
                    m.put("totalSignals",  group.size());
                    m.put("evaluated4h",   eval4h.size());
                    m.put("winRate4h",     winRate(eval4h, true));
                    m.put("avgReturn4h",   avgReturn(eval4h, true));
                    m.put("evaluated24h",  eval24h.size());
                    m.put("winRate24h",    winRate(eval24h, false));
                    m.put("avgReturn24h",  avgReturn(eval24h, false));
                    return m;
                })
                .sorted(Comparator.comparingInt((Map<String, Object> m) -> (int) m.get("totalSignals")).reversed())
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildByRegime(List<StrategyLogEntity> signals) {
        Map<String, List<StrategyLogEntity>> grouped = signals.stream()
                .collect(Collectors.groupingBy(
                        l -> l.getMarketRegime() != null ? l.getMarketRegime() : "UNKNOWN"
                ));

        return grouped.entrySet().stream()
                .map(e -> {
                    List<StrategyLogEntity> group = e.getValue();
                    List<StrategyLogEntity> eval4h  = group.stream().filter(l -> l.getReturn4hPct()  != null).toList();
                    List<StrategyLogEntity> eval24h = group.stream().filter(l -> l.getReturn24hPct() != null).toList();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("regime",       e.getKey());
                    m.put("totalSignals", group.size());
                    m.put("evaluated4h",  eval4h.size());
                    m.put("winRate4h",    winRate(eval4h, true));
                    m.put("avgReturn4h",  avgReturn(eval4h, true));
                    m.put("evaluated24h", eval24h.size());
                    m.put("winRate24h",   winRate(eval24h, false));
                    m.put("avgReturn24h", avgReturn(eval24h, false));
                    return m;
                })
                .sorted(Comparator.comparingInt((Map<String, Object> m) -> (int) m.get("totalSignals")).reversed())
                .collect(Collectors.toList());
    }

    /** return > 0 이면 적중 */
    private double winRate(List<StrategyLogEntity> list, boolean use4h) {
        if (list.isEmpty()) return 0.0;
        long wins = 0;
        for (StrategyLogEntity l : list) {
            BigDecimal v = use4h ? l.getReturn4hPct() : l.getReturn24hPct();
            if (v != null && v.compareTo(BigDecimal.ZERO) > 0) wins++;
        }
        return BigDecimal.valueOf(wins)
                .divide(BigDecimal.valueOf(list.size()), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private double avgReturn(List<StrategyLogEntity> list, boolean use4h) {
        if (list.isEmpty()) return 0.0;
        double sum = 0.0;
        int count = 0;
        for (StrategyLogEntity l : list) {
            BigDecimal v = use4h ? l.getReturn4hPct() : l.getReturn24hPct();
            if (v != null) { sum += v.doubleValue(); count++; }
        }
        return count == 0 ? 0.0 : sum / count;
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
