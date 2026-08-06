package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.entity.DailyHealthSnapshotEntity;
import com.cryptoautotrader.api.repository.DailyHealthSnapshotRepository;
import com.cryptoautotrader.api.service.OperationalHealthCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 운영 건전성 점검 API.
 * <ul>
 *   <li>{@code POST /trigger} — 4대 점검(잔고 정합성·주문 시퀀스 갭·유령 포지션·무출구 고착 포지션)을
 *       즉시 실행하고 이력에 저장한다. 매일 08:30 KST 자동 실행과 동일한 로직(배포 직후 즉시 확인용).</li>
 *   <li>{@code GET /history} — 저장된 점검 이력 최신순 조회(화면 표시용).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/health-check")
@RequiredArgsConstructor
public class HealthCheckController {

    private final OperationalHealthCheckService healthCheckService;
    private final DailyHealthSnapshotRepository snapshotRepository;

    @PostMapping("/trigger")
    public ApiResponse<Map<String, Object>> trigger() {
        healthCheckService.runDailyCheck();
        return ApiResponse.ok(Map.of("triggered", true));
    }

    @GetMapping("/history")
    public ApiResponse<List<Map<String, Object>>> history(
            @RequestParam(defaultValue = "20") int limit) {
        List<Map<String, Object>> rows = snapshotRepository
                .findAllByOrderByCheckedAtDesc(PageRequest.of(0, Math.min(limit, 100)))
                .stream()
                .map(this::toMap)
                .toList();
        return ApiResponse.ok(rows);
    }

    private Map<String, Object> toMap(DailyHealthSnapshotEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("checkedAt", s.getCheckedAt());
        m.put("balanceMismatchCount", s.getBalanceMismatchCount());
        m.put("balanceMismatchDetail", s.getBalanceMismatchDetail());
        m.put("orderSequenceGap", s.getOrderSequenceGap());
        m.put("sequenceGapChecked", s.isSequenceGapChecked());
        m.put("ghostPositionCount", s.getGhostPositionCount());
        m.put("ghostPositionDetail", s.getGhostPositionDetail());
        m.put("stuckPositionCount", s.getStuckPositionCount());
        m.put("stuckPositionDetail", s.getStuckPositionDetail());
        return m;
    }
}
