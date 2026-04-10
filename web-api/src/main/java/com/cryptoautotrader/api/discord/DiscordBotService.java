package com.cryptoautotrader.api.discord;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Discord Bot 서비스.
 * JDA를 초기화하고 CommandHandler를 이벤트 리스너로 등록한다.
 * DISCORD_BOT_TOKEN 환경변수가 없으면 봇 기능을 비활성화한다.
 */
@Service
@RequiredArgsConstructor
public class DiscordBotService {

    private static final Logger log = LoggerFactory.getLogger(DiscordBotService.class);

    @Value("${discord.bot-token:}")
    private String botToken;

    private final DiscordCommandHandler commandHandler;

    private JDA jda;

    @PostConstruct
    public void init() {
        if (botToken == null || botToken.isBlank()) {
            log.info("[DiscordBot] DISCORD_BOT_TOKEN 미설정 — 봇 비활성화");
            return;
        }
        try {
            jda = JDABuilder.createDefault(botToken)
                    .enableIntents(
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT,
                            GatewayIntent.DIRECT_MESSAGES
                    )
                    .addEventListeners(commandHandler)
                    .build();
            jda.awaitReady();
            log.info("[DiscordBot] 연결 완료 — Bot: {}", jda.getSelfUser().getAsTag());
        } catch (Exception e) {
            log.error("[DiscordBot] 초기화 실패", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (jda != null) {
            jda.shutdown();
            log.info("[DiscordBot] 종료");
        }
    }
}
