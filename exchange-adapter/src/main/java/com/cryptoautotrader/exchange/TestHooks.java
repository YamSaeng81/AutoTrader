package com.cryptoautotrader.exchange;

import java.util.List;
import java.util.Locale;

/**
 * 시험 전용 설정의 <b>기동 거부 가드</b> (2026-10-14).
 *
 * <p>실제 프로세스 재시작 시험은 JVM 시스템 속성으로 강제 종료 지점({@code crashpoint})과 거래소 주소
 * ({@code upbit.api.base-url}, {@code upbit.health-check-url})를 바꾼다. 🔴 운영에서 이 값이 설정되면 주문이 엉뚱한 곳으로
 * 가거나 프로세스가 스스로 멈춘다 — 경고로는 배포 차단이 보장되지 않으므로 <b>기동을 거부</b>한다.</p>
 *
 * <p>허용 조건(둘 다): ① 시험 진입점 클래스가 클래스패스에 있다 — 그 클래스는 {@code restartHarness} 소스 세트에만 있고
 * 운영 jar 에는 없다 ② {@code restart-harness} 프로필이 켜져 있다. 그러므로 운영 jar 에서는 이 속성을 주면 항상 거부된다.</p>
 */
public final class TestHooks {

    public static final String HARNESS_MARKER = "com.cryptoautotrader.api.restartharness.RestartHarnessController";
    public static final String HARNESS_PROFILE = "restart-harness";
    public static final List<String> PROPERTIES = List.of("crashpoint", "pausepoint", "upbit.api.base-url", "upbit.health-check-url");

    private TestHooks() {}

    /**
     * 시험 전용 속성을 읽는다. 설정돼 있는데 허용 조건이 아니면 예외 — 정적 초기화에서 부르면 빈 생성이 실패해 기동이 멈춘다.
     *
     * @return 값, 설정되지 않았으면 {@code null}
     */
    public static String require(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!harnessPresent() || !profileActive()) {
            throw new IllegalStateException("🔴 시험 전용 설정 '" + property + "=" + value + "' 이 주어졌지만 시험 빌드가 아니다 "
                    + "(진입점 클래스 " + (harnessPresent() ? "있음" : "없음") + ", 프로필 '" + HARNESS_PROFILE + "' "
                    + (profileActive() ? "켜짐" : "꺼짐") + ") — 기동을 거부한다");
        }
        return value;
    }

    /** 기동 첫 단계에서 모든 시험 전용 속성을 확인한다. */
    public static void verifyAtStartup() {
        PROPERTIES.forEach(TestHooks::require);
    }

    static boolean harnessPresent() {
        try {
            Class.forName(HARNESS_MARKER, false, TestHooks.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static boolean profileActive() {
        String p = System.getProperty("spring.profiles.active");
        if (p == null) {
            p = System.getenv("SPRING_PROFILES_ACTIVE");
        }
        if (p == null) {
            return false;
        }
        for (String s : p.split(",")) {
            if (HARNESS_PROFILE.equals(s.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
