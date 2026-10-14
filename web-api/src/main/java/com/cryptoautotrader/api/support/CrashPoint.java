package com.cryptoautotrader.api.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 강제 종료 지점 — <b>실제 프로세스 재시작 시험 전용</b> (2026-10-13).
 *
 * <p>JVM 시스템 속성 {@code crashpoint} 에 지점 이름을 주면 그 지점에서 {@link Runtime#halt} 로 프로세스를 즉시
 * 멈춘다. 종료 훅·트랜잭션 정리·커넥션 반납이 일어나지 않는다 — {@code kill -9} 와 같다. 커밋되지 않은 작업은
 * DB 가 롤백한다.</p>
 *
 * <p>🔴 속성이 없으면(운영) 아무 일도 하지 않는다. 운영 기동 스크립트는 이 속성을 주지 않는다.</p>
 */
public final class CrashPoint {

    private static final Logger log = LoggerFactory.getLogger(CrashPoint.class);
    // 🔴 시험 빌드가 아닌데 설정돼 있으면 초기화가 실패한다 — 기동 거부 (TestHooks, 2026-10-14)
    private static final Set<String> ARMED = Arrays.stream(
                    java.util.Objects.requireNonNullElse(com.cryptoautotrader.exchange.TestHooks.require("crashpoint"), "").split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());

    static {
        if (!ARMED.isEmpty()) {
            log.warn("🔴 강제 종료 지점이 설정돼 있다: {} — 시험 전용 설정이다", ARMED);
        }
    }

    private CrashPoint() {}

    /** {@code pausepoint=NAME:밀리초} — 그 지점에서 잠시 멈춘다(시험이 그 사이에 외부 상태를 바꾼다). 시험 빌드 전용. */
    private static final String PAUSE = com.cryptoautotrader.exchange.TestHooks.require("pausepoint");

    public static void pause(String name) {
        if (PAUSE == null || !PAUSE.startsWith(name + ":")) {
            return;
        }
        long ms = Long.parseLong(PAUSE.substring(name.length() + 1).trim());
        System.err.println("PAUSEPOINT " + name + " — " + ms + "ms 멈춘다");
        System.err.flush();
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static boolean armed(String name) {
        return ARMED.contains(name);
    }

    public static void hit(String name) {
        if (ARMED.isEmpty() || !ARMED.contains(name)) {
            return;
        }
        System.err.println("CRASHPOINT " + name + " — 프로세스를 즉시 멈춘다");
        System.err.flush();
        Runtime.getRuntime().halt(137);
    }
}
