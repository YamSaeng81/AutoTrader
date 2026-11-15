package com.cryptoautotrader.api.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** 탐침 전용(총괄 v1.168 §3-3, 커밋하지 않음) — 시험 사례마다 실제 벽시계 시작·종료와 결과를 남긴다. */
public class ProbeTimeExtension implements BeforeEachCallback, AfterEachCallback {
    private static final Path OUT = Path.of("build", "test-results", "probe-times.tsv");

    @Override
    public void beforeEach(ExtensionContext c) {
        c.getStore(ExtensionContext.Namespace.create(ProbeTimeExtension.class)).put("t0", Instant.now());
    }

    @Override
    public void afterEach(ExtensionContext c) {
        Instant t0 = c.getStore(ExtensionContext.Namespace.create(ProbeTimeExtension.class)).get("t0", Instant.class);
        String line = c.getRequiredTestClass().getSimpleName() + "\t" + c.getDisplayName().replace('\t', ' ') + "\t" + t0 + "\t" + Instant.now()
                + "\t" + c.getExecutionException().map(e -> "FAIL " + String.valueOf(e.getMessage()).replace('\n', ' ').replace('\t', ' ')).orElse("OK") + "\n";
        try {
            Files.createDirectories(OUT.getParent());
            Files.writeString(OUT, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
