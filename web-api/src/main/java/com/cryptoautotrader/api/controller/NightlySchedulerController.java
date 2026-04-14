package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.dto.NightlySchedulerConfigDto;
import com.cryptoautotrader.api.service.NightlySchedulerConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/scheduler/nightly")
@RequiredArgsConstructor
public class NightlySchedulerController {

    private final NightlySchedulerConfigService service;

    /** 현재 설정 조회 */
    @GetMapping
    public ApiResponse<NightlySchedulerConfigDto> getConfig() {
        return ApiResponse.ok(service.getConfig());
    }

    /** 설정 저장 (부분 업데이트 가능) */
    @PutMapping
    public ApiResponse<NightlySchedulerConfigDto> updateConfig(
            @RequestBody NightlySchedulerConfigDto dto) {
        return ApiResponse.ok(service.updateConfig(dto));
    }

    /** 즉시 수동 실행 */
    @PostMapping("/trigger")
    public ApiResponse<Map<String, Object>> triggerNow() {
        return ApiResponse.ok(service.triggerNow());
    }
}
