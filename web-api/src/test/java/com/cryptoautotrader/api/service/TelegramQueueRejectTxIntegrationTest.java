package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 2026-10-21 리뷰 — <b>큐 진입 실패 기록의 별도 트랜잭션(REQUIRES_NEW)</b>을 실제 트랜잭션 관리자·실제 DB(H2)·실제 저장소로 확인한다.
 *
 * <p>{@link TelegramQueueSaturationTest} 는 저장소를 목으로 써서 트랜잭션 경로를 거치지 않는다. 여기서는 스프링 컨텍스트의 진짜
 * {@link TelegramNotificationService} 빈에, 실행기만 "항상 거절"(운영 설정의 큐 포화 때 {@code AbortPolicy} 가 하는 것)로 바꿔 끼운다.</p>
 *
 * <ul>
 *   <li>바깥 트랜잭션(거래 처리를 흉내 — 같은 트랜잭션에서 표시 행을 쓴다) 안에서 큐 포화 경보 → 바깥을 롤백해도 실패 이력은 남는다.</li>
 *   <li>이력 저장이 실제로 실패해도(세션 표시 100자 초과 — DB 제약 위반) 바깥 트랜잭션은 예외 없이 커밋된다(롤백 전용으로 표시되지 않는다).</li>
 * </ul>
 *
 * <p>🔴 범위: 확인하는 것은 바깥 트랜잭션의 <b>결과(커밋·롤백)</b>다. 기록은 호출한 스레드에서 새 연결을 잡아 쓰므로, 연결 풀을 기다리는
 * 지연은 바깥 처리에 그대로 더해진다 — 예외를 잡는다고 그 지연이 없어지지는 않는다.</p>
 */
class TelegramQueueRejectTxIntegrationTest extends IntegrationTestBase {

    private static final String OUTER_MARK = "OUTER_WORK";

    @Autowired private TelegramNotificationService telegram;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private Object originalExecutor;

    @BeforeEach
    void rejectAll() {
        jdbc.update("DELETE FROM telegram_notification_log");
        originalExecutor = ReflectionTestUtils.getField(telegram, "telegramExecutor");
        Executor rejecting = r -> { throw new RejectedExecutionException("큐 포화(시험)"); };
        ReflectionTestUtils.setField(telegram, "telegramExecutor", rejecting);
    }

    @AfterEach
    void restore() {
        ReflectionTestUtils.setField(telegram, "telegramExecutor", originalExecutor);
        jdbc.update("DELETE FROM telegram_notification_log");
    }

    private int rows(String where, Object... args) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM telegram_notification_log WHERE " + where, Integer.class, args);
    }

    /** 바깥 트랜잭션이 쓴 표시 행 — 바깥이 커밋됐는지 롤백됐는지를 이걸로 본다. */
    private void outerWork() {
        jdbc.update("INSERT INTO telegram_notification_log (type, session_label, message_text, success) VALUES (?, NULL, '바깥 처리', TRUE)", OUTER_MARK);
    }

    @Test
    @DisplayName("🔴 바깥 트랜잭션 안에서 큐 포화 경보 → 바깥을 롤백해도 RISK_LIMIT 실패 이력은 남는다(REQUIRES_NEW)")
    void 바깥롤백_실패이력_유지() {
        assertThat(ReflectionTestUtils.getField(telegram, "transactionManager")).as("실제 트랜잭션 관리자가 주입돼 있어야 이 시험이 의미가 있다").isNotNull();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outerWork();
            telegram.notifyRiskLimitBreached("공유 풀 누적 손실 한도 도달");
            status.setRollbackOnly();
        });

        assertThat(rows("type = ?", OUTER_MARK)).as("바깥 처리는 롤백됐다").isZero();
        assertThat(rows("type = 'RISK_LIMIT' AND success = FALSE AND message_text LIKE ?",
                TelegramNotificationService.QUEUE_REJECTED_MARK + "%")).as("큐 진입 실패 이력은 남는다").isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 이력 저장이 실제로 실패해도(DB 제약 위반) 바깥 트랜잭션은 예외 없이 커밋된다")
    void 이력저장실패_바깥커밋() {
        String longKind = "K".repeat(120);   // session_label = kind + "#" + id → VARCHAR(100) 초과

        assertThatCode(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outerWork();
            telegram.notifyCleanupNeedsHuman(longKind, 1L, 2L, "정리 중단");
        })).as("바깥 트랜잭션이 롤백 전용으로 표시되면 커밋에서 UnexpectedRollbackException 이 난다").doesNotThrowAnyException();

        assertThat(rows("type = ?", OUTER_MARK)).as("바깥 처리는 커밋됐다").isEqualTo(1);
        assertThat(rows("type = 'CLEANUP_NEEDS_HUMAN'")).as("이력 저장은 실패했다 — 제약 위반이 실제로 일어났는지").isZero();
    }

    @Test
    @DisplayName("대조 — 같은 경보를 트랜잭션 밖에서 보내도 실패 이력이 남는다")
    void 트랜잭션밖_기록() {
        telegram.notifyExchangeDown("끊김");

        assertThat(rows("type = 'EXCHANGE_DOWN' AND success = FALSE")).isEqualTo(1);
    }
}
