package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.discord.DiscordWebhookClient;
import com.cryptoautotrader.api.discord.MorningBriefingComposer;
import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.entity.DiscordChannelConfigEntity;
import com.cryptoautotrader.api.entity.DiscordSendLogEntity;
import com.cryptoautotrader.api.repository.DiscordChannelConfigRepository;
import com.cryptoautotrader.api.repository.DiscordSendLogRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discord 채널 관리 API.
 * GET/PUT    /api/v1/admin/discord/channels
 * POST       /api/v1/admin/discord/test/{channelType}  — 채널 테스트 메시지 전송
 * POST       /api/v1/admin/discord/briefing            — 모닝 브리핑 수동 전송
 * GET        /api/v1/admin/discord/logs                — 발송 이력 조회
 */
@RestController
@RequestMapping("/api/v1/admin/discord")
@RequiredArgsConstructor
public class DiscordController {

    private final DiscordChannelConfigRepository channelConfigRepo;
    private final DiscordSendLogRepository sendLogRepo;
    private final DiscordWebhookClient webhookClient;
    private final MorningBriefingComposer briefingComposer;

    // ── 채널 설정 ──────────────────────────────────────────────────────────────

    @GetMapping("/channels")
    public ApiResponse<List<Map<String, Object>>> getChannels() {
        List<Map<String, Object>> list = channelConfigRepo.findAll().stream()
                .map(c -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", c.getId());
                    m.put("channelType", c.getChannelType());
                    m.put("displayName", c.getDisplayName());
                    // Webhook URL 마스킹
                    m.put("webhookConfigured", c.getWebhookUrl() != null && !c.getWebhookUrl().isBlank());
                    m.put("enabled", c.isEnabled());
                    m.put("description", c.getDescription());
                    m.put("updatedAt", c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null);
                    return m;
                }).toList();
        return ApiResponse.ok(list);
    }

    @PutMapping("/channels/{channelType}")
    public ApiResponse<String> updateChannel(
            @PathVariable String channelType,
            @RequestBody Map<String, Object> body) {

        DiscordChannelConfigEntity entity = channelConfigRepo
                .findByChannelType(channelType.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("채널 없음: " + channelType));

        if (body.containsKey("webhookUrl")) {
            String url = (String) body.get("webhookUrl");
            if (url != null && !url.isBlank()) entity.setWebhookUrl(url);
        }
        if (body.containsKey("enabled"))     entity.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        if (body.containsKey("displayName")) entity.setDisplayName((String) body.get("displayName"));

        channelConfigRepo.save(entity);
        return ApiResponse.ok("업데이트 완료: " + channelType);
    }

    // ── 테스트 전송 ────────────────────────────────────────────────────────────

    @PostMapping("/test/{channelType}")
    public ApiResponse<Map<String, Object>> testChannel(@PathVariable String channelType) {
        ObjectNode embed = webhookClient.embed(
                "✅ 연결 테스트",
                "CryptoAutoTrader Discord 연동이 정상적으로 동작합니다.",
                DiscordWebhookClient.COLOR_GREEN);
        webhookClient.addField(embed, "채널", channelType, true);
        webhookClient.addField(embed, "상태", "연결 확인 완료", true);

        boolean ok = webhookClient.sendEmbed(channelType.toUpperCase(), embed, "MANUAL");
        Map<String, Object> result = new HashMap<>();
        result.put("channelType", channelType);
        result.put("success", ok);
        result.put("message", ok ? "전송 성공" : "전송 실패 — Webhook URL 및 활성화 여부 확인");
        return ApiResponse.ok(result);
    }

    // ── 수동 브리핑 전송 ───────────────────────────────────────────────────────

    @PostMapping("/briefing")
    public ApiResponse<String> sendBriefingNow(
            @RequestBody(required = false) Map<String, Object> body) {
        if (body != null && body.containsKey("channels")) {
            @SuppressWarnings("unchecked")
            List<String> channelList = (List<String>) body.get("channels");
            briefingComposer.sendSelected(new HashSet<>(channelList));
        } else {
            briefingComposer.sendAll();
        }
        return ApiResponse.ok("브리핑 전송 완료");
    }

    // ── 발송 이력 ──────────────────────────────────────────────────────────────

    @GetMapping("/logs")
    public ApiResponse<List<Map<String, Object>>> getLogs(
            @RequestParam(defaultValue = "50") int size) {
        List<Map<String, Object>> list = sendLogRepo.findRecent(PageRequest.of(0, size)).stream()
                .map(l -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", l.getId());
                    m.put("channelType", l.getChannelType());
                    m.put("messageType", l.getMessageType());
                    m.put("status", l.getStatus());
                    m.put("messagePreview", l.getMessagePreview());
                    m.put("errorMessage", l.getErrorMessage());
                    m.put("createdAt", l.getCreatedAt().toString());
                    return m;
                }).toList();
        return ApiResponse.ok(list);
    }
}
