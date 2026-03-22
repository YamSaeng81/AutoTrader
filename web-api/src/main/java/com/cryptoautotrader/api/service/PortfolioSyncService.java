package com.cryptoautotrader.api.service;

import com.cryptoautotrader.core.portfolio.PortfolioManager;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.dto.AccountResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * PortfolioManager.totalCapital을 거래소 실시간 KRW 잔고와 주기적으로 동기화한다.
 *
 * 동기화 기준:
 *   totalCapital = KRW 가용 잔고 + KRW 주문 잠금액 (총 KRW 보유액)
 *   → 코인 평가금액은 제외 (KRW로 즉시 할당 불가하므로)
 *
 * Upbit API Key가 설정되지 않은 경우 동기화를 건너뛴다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioSyncService {

    private final PortfolioManager portfolioManager;

    @Autowired(required = false)
    private UpbitOrderClient upbitOrderClient;

    /** 애플리케이션 기동 직후 1회 즉시 동기화 */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        syncBalance();
    }

    /** 5분마다 거래소 KRW 잔고를 totalCapital에 반영 */
    @Scheduled(fixedDelay = 300_000)
    public void scheduledSync() {
        syncBalance();
    }

    private void syncBalance() {
        if (upbitOrderClient == null) {
            log.debug("Upbit API Key 미설정 — PortfolioManager 잔고 동기화 건너뜀");
            return;
        }

        try {
            List<AccountResponse> accounts = upbitOrderClient.getAccounts();
            BigDecimal totalKrw = accounts.stream()
                    .filter(a -> "KRW".equals(a.getCurrency()))
                    .findFirst()
                    .map(a -> a.getBalance().add(a.getLocked()))
                    .orElse(BigDecimal.ZERO);

            BigDecimal before = portfolioManager.getTotalCapital();
            portfolioManager.syncTotalCapital(totalKrw);
            log.info("PortfolioManager totalCapital 동기화: {} → {} KRW", before, totalKrw);
        } catch (Exception e) {
            log.warn("PortfolioManager 잔고 동기화 실패 (이전 값 유지): {}", e.getMessage());
        }
    }
}
