package com.cryptoautotrader.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * 애플리케이션 진입점.
 * - @EnableAsync    → AsyncConfig 로 위임
 * - @EnableScheduling → SchedulerConfig 로 위임
 */
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class CryptoAutoTraderApplication {

    public static void main(String[] args) {
        SpringApplication.run(CryptoAutoTraderApplication.class, args);
    }
}
