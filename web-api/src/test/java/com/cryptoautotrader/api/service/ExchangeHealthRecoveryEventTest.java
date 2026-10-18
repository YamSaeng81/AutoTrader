package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 거래소 상태 전이와 복구 이벤트 (2026-10-19) — DOWN 뒤 UP 이 되면 <b>중간에 DEGRADED 를 거쳐도</b> 복구 이벤트가 한 번 난다.
 */
class ExchangeHealthRecoveryEventTest {

    private final List<Object> events = new ArrayList<>();
    private final ExchangeHealthMonitor monitor = new ExchangeHealthMonitor((ApplicationEventPublisher) events::add);

    private long recovered() {
        return events.stream().filter(e -> e instanceof ExchangeRecoveredEvent).count();
    }

    private long down() {
        return events.stream().filter(e -> e instanceof ExchangeDownEvent).count();
    }

    @Test
    @DisplayName("DOWN → UP 직행 — 복구 1회")
    void directRecovery() {
        monitor.updateStatus("DOWN");
        monitor.updateStatus("UP");
        assertThat(down()).isEqualTo(1);
        assertThat(recovered()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 DOWN → DEGRADED → UP — 종전에는 복구 이벤트가 나지 않았다(점검 재개 직후의 느린 응답)")
    void recoveryThroughDegraded() {
        monitor.updateStatus("DOWN");
        monitor.updateStatus("DEGRADED");
        assertThat(recovered()).as("느린 응답은 아직 복구가 아니다").isZero();
        monitor.updateStatus("UP");
        assertThat(recovered()).isEqualTo(1);
    }

    @Test
    @DisplayName("DOWN 이 없었으면 DEGRADED → UP 은 복구가 아니고, 복구 뒤 다시 UP 이어도 한 번뿐")
    void noSpuriousRecovery() {
        monitor.updateStatus("DEGRADED");
        monitor.updateStatus("UP");
        assertThat(recovered()).isZero();

        monitor.updateStatus("DOWN");
        monitor.updateStatus("UP");
        monitor.updateStatus("DEGRADED");
        monitor.updateStatus("UP");
        assertThat(recovered()).isEqualTo(1);
    }
}
