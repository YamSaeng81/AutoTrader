package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.TelegramNotificationLogEntity;
import com.cryptoautotrader.api.repository.TelegramNotificationLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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

    /**
     * 실제 HTTP 대신 호출 횟수만 세는 테스트용 서비스 — 백오프는 0으로 낮춘다.
     * (2026-10-21: 재시도가 실패 종류를 보도록 {@code sendAttempt} 를 바꿔 끼운다 — 실패는 거부(500)로 돌려준다.)
     */
    private static class CountingService extends TelegramNotificationService {
        final AtomicInteger calls = new AtomicInteger();
        private final int failuresBeforeSuccess;

        CountingService(int failuresBeforeSuccess) {
            this(failuresBeforeSuccess, null);
        }

        CountingService(int failuresBeforeSuccess, TelegramNotificationLogRepository repo) {
            super(repo);
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        Attempt sendAttempt(String text) {
            return calls.incrementAndGet() > failuresBeforeSuccess ? Attempt.sent() : Attempt.rejected(500);
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

    // ── RISK_LIMIT (2026-10-21 리뷰) — 자동 정지 기본 OFF 에서 운영자에게 집행을 맡기는 경보 ─────────────────────

    @Test
    @DisplayName("RISK_LIMIT 은 실패 뒤 재시도해 전달된다 — 한도 도달 경보가 1회 실패로 사라지지 않는다")
    void 위험한도_실패후_성공() {
        CountingService svc = new CountingService(2);   // 2회 실패 후 3번째 성공

        assertThat(svc.sendWithRetry("한도", "RISK_LIMIT")).isTrue();
        assertThat(svc.calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("RISK_LIMIT 계속 실패 — 3회에서 멈추고 실패를 돌려준다(제한된 재시도)")
    void 위험한도_계속실패_상한() {
        CountingService svc = new CountingService(Integer.MAX_VALUE);

        assertThat(svc.sendWithRetry("한도", "RISK_LIMIT")).isFalse();
        assertThat(svc.calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("RISK_LIMIT 실패 후 성공 — 이력은 재시도가 끝난 뒤 성공 한 줄만 남는다(중간 실패를 따로 적지 않는다)")
    void 위험한도_실패후성공_이력() {
        TelegramNotificationLogRepository repo = mock(TelegramNotificationLogRepository.class);
        CountingService svc = new CountingService(1, repo);

        assertThat(svc.doSendMarkdownAndLog("한도", "RISK_LIMIT", null)).isTrue();

        ArgumentCaptor<TelegramNotificationLogEntity> saved = ArgumentCaptor.forClass(TelegramNotificationLogEntity.class);
        verify(repo, times(1)).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo("RISK_LIMIT");
        assertThat(saved.getValue().isSuccess()).isTrue();
        assertThat(svc.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("RISK_LIMIT 계속 실패 — 최종 실패가 이력에 success=false 한 줄로 남는다(정기 점검이 찾는 표시)")
    void 위험한도_최종실패_이력() {
        TelegramNotificationLogRepository repo = mock(TelegramNotificationLogRepository.class);
        CountingService svc = new CountingService(Integer.MAX_VALUE, repo);

        assertThat(svc.doSendMarkdownAndLog("한도", "RISK_LIMIT", null)).isFalse();

        ArgumentCaptor<TelegramNotificationLogEntity> saved = ArgumentCaptor.forClass(TelegramNotificationLogEntity.class);
        verify(repo, times(1)).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo("RISK_LIMIT");
        assertThat(saved.getValue().isSuccess()).isFalse();
        assertThat(svc.calls.get()).isEqualTo(3);
    }

    // ── 사람 대응 경보 세 유형 (2026-10-21 리뷰 동의) ────────────────────────────────────────────────

    @Test
    @DisplayName("CLEANUP_NEEDS_HUMAN·CLEANUP_INCOMPLETE·EXCHANGE_DOWN 도 실패 뒤 재시도해 전달된다")
    void 사람대응경보_재시도() {
        for (String type : new String[]{"CLEANUP_NEEDS_HUMAN", "CLEANUP_INCOMPLETE", "EXCHANGE_DOWN"}) {
            CountingService svc = new CountingService(2);

            assertThat(svc.sendWithRetry("경보", type)).as(type).isTrue();
            assertThat(svc.calls.get()).as(type).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("세 유형도 계속 실패하면 3회에서 멈추고 이력에 최종 실패 한 줄")
    void 사람대응경보_최종실패() {
        for (String type : new String[]{"CLEANUP_NEEDS_HUMAN", "CLEANUP_INCOMPLETE", "EXCHANGE_DOWN"}) {
            TelegramNotificationLogRepository repo = mock(TelegramNotificationLogRepository.class);
            CountingService svc = new CountingService(Integer.MAX_VALUE, repo);

            assertThat(svc.doSendMarkdownAndLog("경보", type, null)).as(type).isFalse();
            ArgumentCaptor<TelegramNotificationLogEntity> saved = ArgumentCaptor.forClass(TelegramNotificationLogEntity.class);
            verify(repo, times(1)).save(saved.capture());
            assertThat(saved.getValue().getType()).isEqualTo(type);
            assertThat(saved.getValue().isSuccess()).isFalse();
            assertThat(svc.calls.get()).as(type).isEqualTo(3);
        }
    }

    // ── 최종 실패 문구 (2026-10-21 리뷰) — 응답 미수신이 끼면 "유실" 이 아니라 확인 실패 ─────────────────

    @Test
    @DisplayName("응답 미수신이 한 번이라도 있으면 '전송 성공 확인 실패 — 중복 도착 가능'")
    void 최종문구_미수신() {
        assertThat(TelegramNotificationService.finalFailureNote(true, TelegramNotificationService.Attempt.rateLimited(1000)))
                .contains("전송 성공 확인 실패 — 중복 도착 가능")
                .doesNotContain("유실");
    }

    @Test
    @DisplayName("모든 시도가 거부면 '전달되지 않음' — 마지막 상태를 함께 적는다")
    void 최종문구_거부() {
        assertThat(TelegramNotificationService.finalFailureNote(false, TelegramNotificationService.Attempt.rateLimited(1000)))
                .contains("전달되지 않음").contains("429").doesNotContain("중복");
    }

    @Test
    @DisplayName("정기 요약은 재시도하지 않는다 — 다음 주기에 다시 오므로 큐를 점유할 이유가 없다")
    void 요약알림은_1회만() {
        CountingService svc = new CountingService(Integer.MAX_VALUE);

        assertThat(svc.sendWithRetry("요약", "TRADE_SUMMARY")).isFalse();
        assertThat(svc.calls.get()).isEqualTo(1);
    }
}
