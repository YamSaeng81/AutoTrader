package com.cryptoautotrader.api.report;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 오전 5시(KST) 텔레그램 아침 시황 브리핑 스케줄러.
 *
 * <p>Discord 07:00 브리핑({@code MorningBriefingScheduler})과 별개로,
 * 대형/중형 코인 48h 추세 + 시스템 자기진단 + 뉴스 이슈를 텔레그램으로 발송한다.
 */
@Component
@RequiredArgsConstructor
public class TelegramBriefingScheduler {

    private static final Logger log = LoggerFactory.getLogger(TelegramBriefingScheduler.class);

    private final TelegramBriefingComposer composer;

    @Scheduled(cron = "0 0 5 * * *", zone = "Asia/Seoul")
    public void sendMorningBriefing() {
        log.info("[TelegramBriefingScheduler] 05:00 아침 시황 브리핑 전송 시작");
        try {
            composer.sendMorningBriefing();
        } catch (Exception e) {
            log.error("[TelegramBriefingScheduler] 브리핑 전송 실패", e);
        }
    }
}
