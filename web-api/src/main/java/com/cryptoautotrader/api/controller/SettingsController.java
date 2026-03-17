package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.entity.TelegramNotificationLogEntity;
import com.cryptoautotrader.api.service.TelegramNotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
}
