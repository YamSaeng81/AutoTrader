package com.cryptoautotrader.api.discord;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 오전 7시(KST) Discord 모닝 브리핑 스케줄러.
 * 채널: TRADING_REPORT / CRYPTO_NEWS / ECONOMY_NEWS
 */
@Component
@RequiredArgsConstructor
public class MorningBriefingScheduler {

    private static final Logger log = LoggerFactory.getLogger(MorningBriefingScheduler.class);

    private final MorningBriefingComposer briefingComposer;

    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Seoul")
    public void sendMorningBriefing() {
        log.info("[MorningBriefingScheduler] 모닝 브리핑 전송 시작");
        try {
            briefingComposer.sendAll();
        } catch (Exception e) {
            log.error("[MorningBriefingScheduler] 브리핑 전송 실패", e);
        }
    }
}
