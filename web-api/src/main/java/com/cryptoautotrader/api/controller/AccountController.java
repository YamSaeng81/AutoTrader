package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.service.AccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Upbit 계좌 현황 API
 */
@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
@Slf4j
public class AccountController {

    private final AccountService accountService;

    /**
     * 계좌 종합 현황 조회
     * - API Key 미설정 시 apiKeyConfigured=false 응답
     * - 잔고, 보유 코인, 평가금액, 수익률 포함
     */
    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> getAccountSummary() {
        return ApiResponse.ok(accountService.getAccountSummary());
    }
}
