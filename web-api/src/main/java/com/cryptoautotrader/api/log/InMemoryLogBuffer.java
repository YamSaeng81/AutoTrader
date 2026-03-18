package com.cryptoautotrader.api.log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 최근 서버 로그를 메모리에 보관하는 원형 버퍼.
 * Logback AppenderBase 에서 직접 참조하므로 static 으로 구현.
 */
public class InMemoryLogBuffer {

    private static final int MAX_SIZE = 5000;
    private static final Deque<LogEntry> BUFFER = new ArrayDeque<>(MAX_SIZE + 1);

    public record LogEntry(String timestamp, String level, String logger, String message) {}

    public static synchronized void add(LogEntry entry) {
        if (BUFFER.size() >= MAX_SIZE) {
            BUFFER.pollFirst();
        }
        BUFFER.addLast(entry);
    }

    public static synchronized List<LogEntry> getAll() {
        return new ArrayList<>(BUFFER);
    }
}
