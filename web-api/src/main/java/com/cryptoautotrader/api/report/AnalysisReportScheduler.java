package com.cryptoautotrader.api.report;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 12시간마다 분석 보고서를 생성하는 스케줄러.
 * 매일 0시, 12시(KST) 실행.
 * cron: "0 0 0,12 * * *" (초 분 시 일 월 요일)
 */
@Component
@RequiredArgsConstructor
public class AnalysisReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnalysisReportScheduler.class);

    private final ReportComposer reportComposer;

    @Scheduled(cron = "0 0 0,12 * * *", zone = "Asia/Seoul")
    public void generateReport() {
        log.info("[AnalysisReportScheduler] 12h 분석 보고서 생성 시작");
        Instant to   = Instant.now();
        Instant from = to.minus(12, ChronoUnit.HOURS);
        try {
            reportComposer.compose(from, to);
        } catch (Exception e) {
            log.error("[AnalysisReportScheduler] 보고서 생성 실패", e);
        }
    }
}
