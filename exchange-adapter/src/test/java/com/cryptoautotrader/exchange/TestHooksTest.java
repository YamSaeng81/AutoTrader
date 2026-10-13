package com.cryptoautotrader.exchange;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 시험 전용 설정의 기동 거부 가드 — 이 모듈의 클래스패스에는 시험 진입점이 없다(운영 jar 와 같다). */
class TestHooksTest {

    @AfterEach
    void clear() {
        TestHooks.PROPERTIES.forEach(System::clearProperty);
        System.clearProperty("spring.profiles.active");
    }

    @Test
    void 설정이_없으면_통과한다() {
        TestHooks.verifyAtStartup();
        assertThat(TestHooks.require("upbit.api.base-url")).isNull();
    }

    @Test
    void 시험_빌드가_아니면_어떤_시험_속성이든_기동을_거부한다() {
        for (String p : TestHooks.PROPERTIES) {
            System.setProperty(p, "x");
            assertThatThrownBy(TestHooks::verifyAtStartup).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(p).hasMessageContaining("기동을 거부한다");
            System.clearProperty(p);
        }
    }

    @Test
    void 프로필만_켜도_진입점_클래스가_없으면_거부한다() {
        System.setProperty("spring.profiles.active", "prod,restart-harness");
        System.setProperty("upbit.api.base-url", "http://127.0.0.1:18080/v1");
        assertThatThrownBy(TestHooks::verifyAtStartup).hasMessageContaining("진입점 클래스 없음");
    }
}
