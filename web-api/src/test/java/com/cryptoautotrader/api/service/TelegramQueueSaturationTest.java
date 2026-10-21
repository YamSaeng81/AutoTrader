package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.config.AsyncConfig;
import com.cryptoautotrader.api.entity.TelegramNotificationLogEntity;
import com.cryptoautotrader.api.repository.TelegramNotificationLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 2026-10-21 리뷰 — <b>알림 큐가 차면 경보가 버려진다</b>. 운영 설정 그대로의 실행기({@link AsyncConfig#telegramExecutor()}:
 * 코어 1·최대 2·큐 50)를 일부러 채워 확인한다.
 *
 * <p>종전에는 실행기의 거절 처리기가 WARN 한 줄만 남기고 버렸다 — 전송 함수에 닿지 않아 이력(Q10)에도 실패가 없었다.
 * 이제 거절이 호출한 쪽으로 오고, 재시도 유형(중요 경보)은 ERROR 와 이력 {@code success=false}(본문 머리 표시)로 남는다.</p>
 */
class TelegramQueueSaturationTest {

    private ThreadPoolTaskExecutor executor;
    private CountDownLatch release;
    private TelegramNotificationLogRepository repo;
    private TelegramNotificationService service;
    private final AtomicInteger sent = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        executor = (ThreadPoolTaskExecutor) new AsyncConfig().telegramExecutor();
        release = new CountDownLatch(1);
        CountDownLatch busy = new CountDownLatch(2);
        // 일꾼 2개를 붙잡고(코어 1 + 큐가 찬 뒤 늘어나는 1) 큐 50 을 채운다
        executor.execute(() -> { busy.countDown(); await(); });
        for (int i = 0; i < 50; i++) executor.execute(this::await);
        executor.execute(() -> { busy.countDown(); await(); });
        assertThat(busy.await(5, TimeUnit.SECONDS)).as("두 일꾼이 붙잡혀야 큐가 꽉 찬 상태다").isTrue();
        assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();

        repo = mock(TelegramNotificationLogRepository.class);
        service = new TelegramNotificationService(repo) {
            @Override
            Attempt sendAttempt(String text) {
                sent.incrementAndGet();
                return Attempt.sent();
            }
        };
        ReflectionTestUtils.setField(service, "telegramExecutor", executor);
    }

    private void await() {
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        executor.shutdown();
    }

    @Test
    @DisplayName("🔴 큐가 차면 RISK_LIMIT 은 버려지지만 '큐 포화 — 전송 안 함' 으로 유형과 함께 이력에 success=false 로 남는다")
    void 위험한도_큐포화_기록() {
        service.notifyRiskLimitBreached("공유 풀 누적 손실 한도 도달");

        ArgumentCaptor<TelegramNotificationLogEntity> c = ArgumentCaptor.forClass(TelegramNotificationLogEntity.class);
        verify(repo, times(1)).save(c.capture());
        assertThat(c.getValue().getType()).isEqualTo("RISK_LIMIT");
        assertThat(c.getValue().isSuccess()).isFalse();
        assertThat(c.getValue().getMessageText()).startsWith(TelegramNotificationService.QUEUE_REJECTED_MARK);
        assertThat(sent.get()).as("전송 함수에는 닿지 않았다").isZero();
    }

    @Test
    @DisplayName("🔴 세 사람 대응 경보·손절도 큐 진입 실패가 각각 자기 유형으로 남는다")
    void 중요경보_큐포화_유형별() {
        service.notifyExchangeDown("끊김");
        service.notifyCleanupIncomplete(1L, 1L);
        service.notifyCleanupNeedsHuman("DYNAMIC", 2L, 3L, "사유");
        service.notifyStopLoss("KRW-BTC", -3.0, 4L);

        ArgumentCaptor<TelegramNotificationLogEntity> c = ArgumentCaptor.forClass(TelegramNotificationLogEntity.class);
        verify(repo, times(4)).save(c.capture());
        assertThat(c.getAllValues()).extracting(TelegramNotificationLogEntity::getType)
                .containsExactly("EXCHANGE_DOWN", "CLEANUP_INCOMPLETE", "CLEANUP_NEEDS_HUMAN", "STOP_LOSS");
        assertThat(c.getAllValues()).allSatisfy(e -> assertThat(e.isSuccess()).isFalse());
    }

    @Test
    @DisplayName("대조 — 재시도 대상이 아닌 유형은 큐 포화 때 WARN 만(이력 없음), 호출한 쪽에 예외를 던지지 않는다")
    void 비대상_큐포화() {
        service.sendCustomNotification("요약");

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("큐가 풀리면 정상 경로 — 같은 경보가 실행기에서 전송되고 이력은 성공 한 줄")
    void 큐_풀린_뒤_정상() throws Exception {
        release.countDown();
        executor.getThreadPoolExecutor().getQueue().clear();
        long deadline = System.currentTimeMillis() + 5000;
        while (executor.getActiveCount() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);

        service.notifyRiskLimitBreached("공유 풀 누적 손실 한도 도달");

        deadline = System.currentTimeMillis() + 5000;
        while (sent.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        Thread.sleep(100);
        ArgumentCaptor<TelegramNotificationLogEntity> c = ArgumentCaptor.forClass(TelegramNotificationLogEntity.class);
        verify(repo, times(1)).save(c.capture());
        assertThat(c.getValue().isSuccess()).isTrue();
        assertThat(c.getValue().getMessageText()).doesNotStartWith(TelegramNotificationService.QUEUE_REJECTED_MARK);
    }
}
