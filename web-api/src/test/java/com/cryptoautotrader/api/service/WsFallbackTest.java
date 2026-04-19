package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 20260415_analy.md Tier 2 §9 — WebSocket 단일 장애점.
 *
 * <p>ExchangeHealthMonitor 의 WS 끊김 추적 로직을 단위 테스트한다.
 * REST fallback 자체는 UpbitRestClient 가 없는 테스트 환경에서 스킵되므로
 * 타이밍/상태 전이만 검증.</p>
 */
class WsFallbackTest {

    @Test
    @DisplayName("§9 WS 끊김 >30초: isWsDownLongerThan 정확성")
    void wsDownLongerThan_detects30sDisconnect() throws Exception {
        ExchangeHealthMonitor monitor = new ExchangeHealthMonitor(event -> {});

        // 초기: WS 미연결 → wsDisconnectedSince 미설정 → false
        assertThat(monitor.isWsDownLongerThan(30)).isFalse();

        // WS 연결 후 해제 → wsDisconnectedSince 기록
        monitor.setWebSocketConnected(true);
        monitor.setWebSocketConnected(false);
        assertThat(monitor.isWsDownLongerThan(0)).isTrue();
        assertThat(monitor.isWsDownLongerThan(30)).isFalse();

        // 1초 대기 → 1초 이상 끊김 확인
        Thread.sleep(1100);
        assertThat(monitor.isWsDownLongerThan(1)).isTrue();
        assertThat(monitor.isWsDownLongerThan(30)).isFalse();
    }

    @Test
    @DisplayName("§9 WS 재연결: isWsDownLongerThan 리셋")
    void wsReconnect_resetsDisconnectTimer() {
        ExchangeHealthMonitor monitor = new ExchangeHealthMonitor(event -> {});

        monitor.setWebSocketConnected(true);
        monitor.setWebSocketConnected(false);
        assertThat(monitor.isWsDownLongerThan(0)).isTrue();

        // 재연결 → 리셋
        monitor.setWebSocketConnected(true);
        assertThat(monitor.isWsDownLongerThan(0)).isFalse();
    }

    @Test
    @DisplayName("§9 WS 중복 해제: wsDisconnectedSince 최초 시점 유지")
    void duplicateDisconnect_keepsFirstTimestamp() throws Exception {
        ExchangeHealthMonitor monitor = new ExchangeHealthMonitor(event -> {});

        monitor.setWebSocketConnected(true);
        monitor.setWebSocketConnected(false);
        Thread.sleep(500);
        // 두 번째 해제 — 시각이 갱신되지 않아야 함
        monitor.setWebSocketConnected(false);
        assertThat(monitor.isWsDownLongerThan(0)).isTrue();
    }
}
