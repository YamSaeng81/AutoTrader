package com.cryptoautotrader.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * 애플리케이션 진입점.
 * - @EnableAsync    → AsyncConfig 로 위임
 * - @EnableScheduling → SchedulerConfig 로 위임
 */
@SpringBootApplication(
        exclude = {UserDetailsServiceAutoConfiguration.class},
        scanBasePackages = "com.cryptoautotrader"
)
public class CryptoAutoTraderApplication {

    public static void main(String[] args) {
        // 🔴 시험 전용 설정(강제 종료 지점·거래소 주소 재정의)이 시험 빌드 밖에서 주어지면 기동을 거부한다 (2026-10-14).
        com.cryptoautotrader.exchange.TestHooks.verifyAtStartup();
        SpringApplication.run(CryptoAutoTraderApplication.class, args);
    }
}
