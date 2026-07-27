package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.report.TelegramBriefingComposer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 아침 브리핑 수동 트리거 API (테스트·검증용).
 * POST /api/v1/admin/briefing/telegram/trigger — 텔레그램 아침 시황 브리핑 즉시 발송
 */
@RestController
@RequestMapping("/api/v1/admin/briefing")
@RequiredArgsConstructor
public class BriefingController {

    private static final Logger log = LoggerFactory.getLogger(BriefingController.class);

    private final TelegramBriefingComposer telegramBriefingComposer;

    @PostMapping("/telegram/trigger")
    public ApiResponse<Map<String, Object>> triggerTelegramBriefing() {
        log.info("[BriefingController] 텔레그램 아침 브리핑 수동 트리거");
        telegramBriefingComposer.sendMorningBriefing();
        return ApiResponse.ok(Map.of("sent", true, "channel", "telegram"));
    }
}
