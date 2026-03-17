package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.entity.TelegramNotificationLogEntity;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.service.AccountService;
import com.cryptoautotrader.api.service.TelegramNotificationService;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private static final DateTimeFormatter KST_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Seoul"));

    @Autowired
    private TelegramNotificationService telegramService;

    @Autowired(required = false)
    private UpbitOrderClient upbitOrderClient;

    @Autowired
    private AccountService accountService;

    @Autowired
    private MarketDataCacheRepository marketDataCacheRepository;

    /** 텔레그램 전송 이력 조회 (최신순, 페이지네이션) */
    @GetMapping("/telegram/logs")
    public ApiResponse<Map<String, Object>> getTelegramLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Page<TelegramNotificationLogEntity> result = telegramService.getLogs(page, size);

        List<Map<String, Object>> items = result.getContent().stream()
                .map(e -> Map.<String, Object>of(
                        "id",           e.getId(),
                        "type",         e.getType(),
                        "sessionLabel", e.getSessionLabel() != null ? e.getSessionLabel() : "",
                        "messageText",  e.getMessageText(),
                        "success",      e.isSuccess(),
                        "sentAt",       KST_FMT.format(e.getSentAt())
                ))
                .toList();

        return ApiResponse.ok(Map.of(
                "items",      items,
                "totalCount", result.getTotalElements(),
                "totalPages", result.getTotalPages(),
                "page",       page,
                "size",       size
        ));
    }

    /** 텔레그램 테스트 메시지 전송 */
    @PostMapping("/telegram/test")
    public ApiResponse<Map<String, Object>> sendTestMessage() {
        boolean ok = telegramService.sendTestMessage();
        return ApiResponse.ok(Map.of("success", ok));
    }

    /**
     * Upbit 연동 상태 종합 점검
     * - API 키 설정 여부 (UpbitOrderClient Bean 존재 여부)
     * - 잔고 조회 성공 여부
     * - market_data_cache 캔들 현황 (실전매매 캔들 싱크 확인용)
     */
    @GetMapping("/upbit/status")
    public ApiResponse<Map<String, Object>> getUpbitStatus() {
        Map<String, Object> result = new HashMap<>();

        // 1. API Key 설정 여부
        boolean apiKeyConfigured = upbitOrderClient != null;
        result.put("apiKeyConfigured", apiKeyConfigured);

        // 2. 잔고 조회 테스트 (AccountService 재사용)
        if (apiKeyConfigured) {
            try {
                Map<String, Object> accountSummary = accountService.getAccountSummary();
                boolean accountOk = accountSummary.containsKey("totalAssetKrw");
                result.put("accountQueryOk", accountOk);
                if (accountOk) {
                    result.put("totalAssetKrw", accountSummary.get("totalAssetKrw"));
                } else if (accountSummary.containsKey("error")) {
                    result.put("accountError", accountSummary.get("error"));
                }
            } catch (Exception e) {
                result.put("accountQueryOk", false);
                result.put("accountError", e.getMessage());
            }
        } else {
            result.put("accountQueryOk", false);
            result.put("accountError", "UPBIT_ACCESS_KEY / UPBIT_SECRET_KEY 환경변수 미설정");
        }

        // 3. market_data_cache 캔들 현황 (코인+타임프레임별 건수)
        try {
            List<Object[]> summary = marketDataCacheRepository.findDataSummary();
            List<Map<String, Object>> candleSummary = new ArrayList<>();
            for (Object[] row : summary) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("coinPair",  row[0]);
                entry.put("timeframe", row[1]);
                entry.put("from",      row[2] != null ? row[2].toString() : null);
                entry.put("to",        row[3] != null ? row[3].toString() : null);
                entry.put("count",     row[4]);
                candleSummary.add(entry);
            }
            result.put("candleSummary", candleSummary);
            result.put("candleQueryOk", true);
        } catch (Exception e) {
            result.put("candleQueryOk", false);
            result.put("candleError", e.getMessage());
        }

        return ApiResponse.ok(result);
    }
}
