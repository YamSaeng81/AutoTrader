package com.cryptoautotrader.api.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 모든 로그 이벤트를 InMemoryLogBuffer 에 기록하는 Logback Appender.
 * logback-spring.xml 에 등록하여 전체 로거에 연결.
 */
public class InMemoryLogAppender extends AppenderBase<ILoggingEvent> {

    private static final DateTimeFormatter FMT = DateTimeFormatter
            .ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.of("Asia/Seoul"));

    @Override
    protected void append(ILoggingEvent event) {
        String timestamp = FMT.format(Instant.ofEpochMilli(event.getTimeStamp()));
        String level = event.getLevel().toString();
        String logger = shortenLogger(event.getLoggerName());
        String message = event.getFormattedMessage();

        if (event.getThrowableProxy() != null) {
            var tp = event.getThrowableProxy();
            message = message + " | " + tp.getClassName() + ": " + tp.getMessage();
        }

        InMemoryLogBuffer.add(new InMemoryLogBuffer.LogEntry(timestamp, level, logger, message));
    }

    /** com.cryptoautotrader.api.service.Foo → c.c.a.s.Foo */
    private String shortenLogger(String loggerName) {
        String[] parts = loggerName.split("\\.");
        if (parts.length <= 1) return loggerName;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            sb.append(parts[i].charAt(0)).append('.');
        }
        sb.append(parts[parts.length - 1]);
        return sb.toString();
    }
}
