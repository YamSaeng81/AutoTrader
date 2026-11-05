package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시험 격리 확인 탐침(뒤) — 로컬 근거 전용, 커밋하지 않는다. 클래스 이름 순서로 DailySma20 뒤에 돈다.
 * 앞 탐침이 닫은 공유 컨텍스트의 회차 빈이 그대로 남아 있음(같은 빈, 닫힌 키 거절)을 확인한다 —
 * 그 사이 DailySma20 가 통과했다면 그 클래스는 이 빈을 쓰지 않은 것이다.
 */
class ZzzEntryRoundsSharedContextProbeTest extends IntegrationTestBase {

    @Autowired private EntryPriorityRounds rounds;
    @Autowired private ApplicationContext ctx;

    @MockBean private MarketDataSyncService marketDataSyncService;
    @MockBean private UpbitRestClient upbitRestClient;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitOrderClient upbitOrderClient;
    @MockBean private TelegramNotificationService telegramService;
    @MockBean private PortfolioSyncService portfolioSyncService;
    @SpyBean private RiskManagementService riskManagementService;

    @Test
    void sharedRoundsStillPoisoned() {
        Instant k = Instant.now().minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS).minusSeconds(120);
        String r = rounds.submit(new EntryPriorityRounds.Candidate("LIVE", -3L, "KRW-PROBE",
                new EntryPriorityRounds.RoundKey("H1", k), () -> null), Instant.now());
        System.out.println("[격리 탐침 뒤] context=" + ctx.getId() + " rounds@" + Integer.toHexString(System.identityHashCode(rounds))
                + " 키 " + k + " 접수 결과=" + r);
        assertThat(r).contains("회차 마감 뒤 도착");
    }
}
