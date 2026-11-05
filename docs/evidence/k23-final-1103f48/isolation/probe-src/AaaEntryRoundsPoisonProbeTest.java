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
 * 시험 격리 재현 탐침 — 로컬 근거 전용, 저장소에 커밋하지 않는다.
 *
 * <p>DailySma20LiveOrderPathTest(수정 전)·BtcGateUnknownEntryTest 와 같은 {@code @MockBean}/{@code @SpyBean} 묶음이라
 * 같은 캐시 컨텍스트(같은 {@link EntryPriorityRounds} 빈)를 쓴다. 그 빈에서 DailySma20 가 쓸 수 있는 초 단위 H1 회차 키
 * 범위(지금−64분30초 ~ 지금−50분)를 모두 정상 경로(접수 → 마감 처리)로 닫는다. 앞 클래스가 같은 키를 닫았던 상황을
 * 시각·순서 우연 없이 고정 재현한다.</p>
 */
class AaaEntryRoundsPoisonProbeTest extends IntegrationTestBase {

    @Autowired private EntryPriorityRounds rounds;
    @Autowired private ApplicationContext ctx;

    @MockBean private MarketDataSyncService marketDataSyncService;
    @MockBean private UpbitRestClient upbitRestClient;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitOrderClient upbitOrderClient;
    @MockBean private TelegramNotificationService telegramService;
    @MockBean private PortfolioSyncService portfolioSyncService;
    @SpyBean private RiskManagementService riskManagementService;

    @Test
    void closeEveryCandidateKey() {
        Instant now = Instant.now();
        Instant from = now.minus(64, ChronoUnit.MINUTES).minusSeconds(30).truncatedTo(ChronoUnit.SECONDS);
        Instant to = now.minus(50, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        int n = 0;
        for (Instant k = from; !k.isAfter(to); k = k.plusSeconds(1)) {
            String r = rounds.submit(new EntryPriorityRounds.Candidate("LIVE", -1L, "KRW-PROBE",
                    new EntryPriorityRounds.RoundKey("H1", k), () -> "탐침 — 진입 없음"), now);
            assertThat(r).as("탐침 접수 %s", k).isNull();
            n++;
        }
        var out = rounds.processDue(EntryPriorityRounds.deadline(new EntryPriorityRounds.RoundKey("H1", to)));
        assertThat(out).hasSize(n);
        String again = rounds.submit(new EntryPriorityRounds.Candidate("LIVE", -2L, "KRW-PROBE",
                new EntryPriorityRounds.RoundKey("H1", from.plusSeconds(60)), () -> null), Instant.now());
        assertThat(again).contains("회차 마감 뒤 도착");
        System.out.println("[격리 탐침] context=" + ctx.getId() + " rounds@" + Integer.toHexString(System.identityHashCode(rounds))
                + " 닫은 H1 키 " + n + "개 " + from + " ~ " + to);
    }
}
