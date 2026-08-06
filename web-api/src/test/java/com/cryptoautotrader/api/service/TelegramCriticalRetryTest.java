package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-06 회귀 테스트 — <b>중요 알림 전송 재시도</b>.
 *
 * <p><b>사고 재현</b>: 08-04 18:32:00 세션 41의 {@code STOP_LOSS} 알림이 유실됐다
 * ({@code telegram_notification_log.success=false}). 같은 급락으로 세션 43도 동시에
 * 손절됐고 그쪽 알림은 <b>1초 뒤 성공</b>했다 — 두 건이 1초 안에 몰려 레이트리밋에 걸린
 * 것으로 보이며, 재시도가 없어 앞의 한 건이 조용히 사라졌다.</p>
 *
 * <p>동적 세션 7개가 워치리스트를 공유해 같은 코인을 동시에 들고 있으므로(같은 날 39·45가
 * DOGE 동시 진입) 손절이 여러 건 동시에 터지는 것은 예외가 아니라 기본 패턴이다.</p>
 */
class TelegramCriticalRetryTest {

    /** 실제 HTTP 대신 호출 횟수만 세는 테스트용 서비스 — 백오프는 0으로 낮춘다. */
    private static class CountingService extends TelegramNotificationService {
        final AtomicInteger calls = new AtomicInteger();
        private final int failuresBeforeSuccess;

        CountingService(int failuresBeforeSuccess) {
            super(null);
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public boolean sendMarkdown(String text) {
            return calls.incrementAndGet() > failuresBeforeSuccess;
        }

        @Override
        long backoffMs(int attemptIndex) {
            return 0L;
        }
    }

    @Test
    @DisplayName("STOP_LOSS 는 첫 전송이 실패하면 재시도해 결국 전달된다 — 세션 41 유실 재발 방지")
    void 손절알림_재시도로_전달() {
        CountingService svc = new CountingService(1);   // 1회 실패 후 성공

        assertThat(svc.sendWithRetry("손절", "STOP_LOSS")).isTrue();
        assertThat(svc.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("SESSION_STOP 도 재시도 대상이다 — 세션이 멈춘 사실은 유실되면 안 된다")
    void 세션중단알림_재시도() {
        CountingService svc = new CountingService(2);

        assertThat(svc.sendWithRetry("중단", "SESSION_STOP")).isTrue();
        assertThat(svc.calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("재시도는 3회에서 멈춘다 — 무한 재시도로 알림 큐를 막지 않는다")
    void 재시도_상한() {
        CountingService svc = new CountingService(Integer.MAX_VALUE);   // 항상 실패

        assertThat(svc.sendWithRetry("손절", "STOP_LOSS")).isFalse();
        assertThat(svc.calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("정기 요약은 재시도하지 않는다 — 다음 주기에 다시 오므로 큐를 점유할 이유가 없다")
    void 요약알림은_1회만() {
        CountingService svc = new CountingService(Integer.MAX_VALUE);

        assertThat(svc.sendWithRetry("요약", "TRADE_SUMMARY")).isFalse();
        assertThat(svc.calls.get()).isEqualTo(1);
    }
}
