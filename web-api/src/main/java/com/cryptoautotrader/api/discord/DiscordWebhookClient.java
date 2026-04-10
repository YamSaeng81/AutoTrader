package com.cryptoautotrader.api.discord;

import com.cryptoautotrader.api.entity.DiscordChannelConfigEntity;
import com.cryptoautotrader.api.entity.DiscordSendLogEntity;
import com.cryptoautotrader.api.repository.DiscordChannelConfigRepository;
import com.cryptoautotrader.api.repository.DiscordSendLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Discord Webhook 클라이언트.
 *
 * <p>채널별 Webhook URL은 discord_channel_config 테이블에서 런타임 로드.
 * Embed 형식으로 구조화된 메시지를 전송한다.
 */
@Component
@RequiredArgsConstructor
public class DiscordWebhookClient {

    private static final Logger log = LoggerFactory.getLogger(DiscordWebhookClient.class);

    // Discord embed 색상 코드
    public static final int COLOR_BLUE   = 0x5865F2;
    public static final int COLOR_GREEN  = 0x57F287;
    public static final int COLOR_YELLOW = 0xFEE75C;
    public static final int COLOR_RED    = 0xED4245;
    public static final int COLOR_GRAY   = 0x95A5A6;

    // 전송 상태 상수
    static final String STATUS_SUCCESS = "SUCCESS";
    static final String STATUS_FAILED  = "FAILED";
    static final String STATUS_PENDING = "PENDING";

    private final DiscordChannelConfigRepository channelConfigRepo;
    private final DiscordSendLogRepository sendLogRepo;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /**
     * 특정 채널에 Embed 메시지를 전송한다.
     *
     * @param channelType 채널 타입 (TRADING_REPORT / CRYPTO_NEWS 등)
     * @param embed       Discord Embed 객체
     * @param messageType 메시지 분류 (MORNING_BRIEFING / ALERT / MANUAL)
     * @return 성공 여부
     */
    public boolean sendEmbed(String channelType, ObjectNode embed, String messageType) {
        DiscordChannelConfigEntity channel = channelConfigRepo.findByChannelType(channelType).orElse(null);

        if (channel == null || !channel.isEnabled()) {
            log.debug("[Discord] 채널 비활성화 또는 미존재: {}", channelType);
            return false;
        }

        String webhookUrl = channel.getWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("[Discord] Webhook URL 미설정: {}", channelType);
            return false;
        }

        DiscordSendLogEntity sendLog = DiscordSendLogEntity.builder()
                .channelType(channelType)
                .messageType(messageType)
                .status(STATUS_PENDING)
                .build();
        sendLogRepo.save(sendLog);

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.putArray("embeds").add(embed);

            // embed description 앞 100자를 preview로 저장
            String desc = embed.path("description").asText("");
            sendLog.setMessagePreview(desc.length() > 100 ? desc.substring(0, 100) + "..." : desc);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Discord Webhook 성공: 204 No Content
            if (response.statusCode() == 204 || response.statusCode() == 200) {
                sendLog.setStatus(STATUS_SUCCESS);
                sendLogRepo.save(sendLog);
                log.info("[Discord] 전송 완료: channel={} type={}", channelType, messageType);
                return true;
            } else {
                sendLog.setStatus(STATUS_FAILED);
                sendLog.setErrorMessage("HTTP " + response.statusCode() + ": " + response.body());
                sendLogRepo.save(sendLog);
                log.warn("[Discord] 전송 실패: channel={} status={} body={}",
                        channelType, response.statusCode(), response.body());
                return false;
            }

        } catch (Exception e) {
            sendLog.setStatus(STATUS_FAILED);
            sendLog.setErrorMessage(e.getMessage());
            sendLogRepo.save(sendLog);
            log.error("[Discord] 전송 오류: channel={}", channelType, e);
            return false;
        }
    }

    // ── Embed 빌더 헬퍼 ───────────────────────────────────────────────────────

    /**
     * 기본 Embed 생성.
     *
     * @param title       제목
     * @param description 본문
     * @param color       사이드바 색상 (COLOR_* 상수 사용)
     */
    // Discord 글자수 제한
    private static final int MAX_TITLE       = 256;
    private static final int MAX_DESCRIPTION = 4096;
    private static final int MAX_FIELD_NAME  = 256;
    private static final int MAX_FIELD_VALUE = 1024;

    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public ObjectNode embed(String title, String description, int color) {
        ObjectNode embed = objectMapper.createObjectNode();
        embed.put("title", truncate(title, MAX_TITLE));
        embed.put("description", truncate(description, MAX_DESCRIPTION));
        embed.put("color", color);
        embed.putObject("footer").put("text", "CryptoAutoTrader");
        return embed;
    }

    /**
     * Embed에 Field 추가 (인라인 여부 선택).
     *
     * @param embed  대상 embed
     * @param name   필드 제목
     * @param value  필드 값
     * @param inline 가로 배치 여부
     */
    public void addField(ObjectNode embed, String name, String value, boolean inline) {
        ArrayNode fields = (ArrayNode) embed.get("fields");
        if (fields == null) {
            fields = embed.putArray("fields");
        }
        ObjectNode field = fields.addObject();
        field.put("name",  name  != null && !name.isBlank()  ? truncate(name,  MAX_FIELD_NAME)  : "\u200b");
        field.put("value", value != null && !value.isBlank() ? truncate(value, MAX_FIELD_VALUE) : "\u200b");
        field.put("inline", inline);
    }

    /**
     * Embed에 썸네일 추가.
     */
    public void setThumbnail(ObjectNode embed, String imageUrl) {
        if (imageUrl != null && !imageUrl.isBlank()) {
            embed.putObject("thumbnail").put("url", imageUrl);
        }
    }

    /**
     * 여러 Embed를 순서대로 전송 (Discord 제한: 한 메시지 최대 10개 embed).
     */
    public boolean sendEmbeds(String channelType, List<ObjectNode> embeds, String messageType) {
        DiscordChannelConfigEntity channel = channelConfigRepo.findByChannelType(channelType).orElse(null);
        if (channel == null || !channel.isEnabled() || channel.getWebhookUrl() == null) {
            return false;
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode embedArr = body.putArray("embeds");
            embeds.stream().limit(10).forEach(embedArr::add);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(channel.getWebhookUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() == 204 || response.statusCode() == 200;

            DiscordSendLogEntity sendLog = DiscordSendLogEntity.builder()
                    .channelType(channelType)
                    .messageType(messageType)
                    .status(ok ? STATUS_SUCCESS : STATUS_FAILED)
                    .messagePreview("embeds " + embeds.size() + "개")
                    .errorMessage(ok ? null : "HTTP " + response.statusCode())
                    .build();
            sendLogRepo.save(sendLog);

            return ok;
        } catch (Exception e) {
            log.error("[Discord] 다중 embed 전송 오류: channel={}", channelType, e);
            return false;
        }
    }
}
