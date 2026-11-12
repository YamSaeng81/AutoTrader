package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.158 결정 1 — 일반 시험은 실제 Telegram 으로 아무것도 보내지 않는다.
 *
 * <p>시험 프로필(test, test+scheduler)의 유효 설정이 {@code telegram.enabled=false} 이고, 운영 기본 chat-id 나
 * 시험 프로세스에 상속된 토큰을 쓰지 않는지 본다. 빈 토큰/404 는 차단 장치가 아니다 — 목적지를 로컬 HTTP 계수기로
 * 돌려 놓고 실제 알림 경로를 불러 요청 0 을 센다(비활성화가 아니면 계수기가 받는다). 전송 계약 시험은 따로
 * {@code enabled=true}·127.0.0.1 대역을 명시한다(TelegramMarkdownFallbackTest·TelegramRiskLimitRetryHttpTest).</p>
 */
class TelegramTestIsolationTest {

    static final String PROD_DEFAULT_CHAT_ID = "855008981";

    static void assertIsolated(TelegramNotificationService bean, String label) throws Exception {
        Object target = AopTestUtils.getUltimateTargetObject(bean);
        boolean enabled = (boolean) ReflectionTestUtils.getField(target, "enabled");
        String chatId = (String) ReflectionTestUtils.getField(target, "chatId");
        String token = (String) ReflectionTestUtils.getField(target, "botToken");
        String api = (String) ReflectionTestUtils.getField(target, "telegramApi");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/", ex -> { hits.incrementAndGet(); ex.sendResponseHeaders(200, -1); ex.close(); });
        server.start();
        try {
            ReflectionTestUtils.setField(target, "telegramApi", "http://127.0.0.1:" + server.getAddress().getPort() + "/bot");
            bean.sendMarkdown("TG-ISOLATION 시험 메시지");
            bean.notifyStopLoss("KRW-ISO", -5.0, -1L);
            ((TelegramNotificationService) target).sendPlain("TG-ISOLATION 평문");
            Thread.sleep(300);   // 비동기 전송 실행기가 있다면 도달 시간 — 단언은 0 이므로 기다림이 결과를 바꾸지 않는다
        } finally {
            ReflectionTestUtils.setField(target, "telegramApi", api);
            server.stop(0);
        }
        System.out.println("[TG-ISOLATION] profile=" + label + " enabled=" + enabled + " chatIdIsProdDefault="
                + PROD_DEFAULT_CHAT_ID.equals(chatId) + " tokenBlank=" + (token == null || token.isBlank())
                + " envTelegramVars=" + System.getenv().keySet().stream().filter(k -> k.startsWith("TELEGRAM_")).toList()
                + " localHttpHits=" + hits.get());
        assertThat(enabled).as("시험 프로필 유효 telegram.enabled").isFalse();
        assertThat(chatId).as("운영 기본 chat-id 를 시험 대상으로 쓰지 않는다").isNotEqualTo(PROD_DEFAULT_CHAT_ID);
        assertThat(token).as("시험 프로세스는 운영 토큰을 상속하지 않는다").isNullOrEmpty();
        assertThat(hits.get()).as("일반 시험의 실제 전송 시도").isZero();
    }

    @Nested
    class TestProfile extends IntegrationTestBase {
        @Autowired TelegramNotificationService telegram;

        @Test
        void 시험_프로필은_알림을_보내지_않는다() throws Exception {
            assertIsolated(telegram, "test");
        }
    }

    @Nested
    @ActiveProfiles("scheduler")
    class SchedulerProfile extends IntegrationTestBase {
        @Autowired TelegramNotificationService telegram;

        @Test
        void 스케줄러_프로필도_알림을_보내지_않는다() throws Exception {
            assertIsolated(telegram, "test+scheduler");
        }
    }
}
