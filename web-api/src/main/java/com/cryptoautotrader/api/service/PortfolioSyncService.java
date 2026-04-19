package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
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
    private final LiveTradingSessionRepository sessionRepository;

    /** drift 경고 임계값 — 세션 배정 합산이 계좌 잔고 대비 이 비율을 넘기면 경고 */
    private static final BigDecimal DRIFT_WARN_RATIO = new BigDecimal("1.05"); // 5% 초과

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

            // §8 drift 감지: 활성 세션 initialCapital 합이 계좌 잔고를 초과하면 경고
            detectCapitalDrift(totalKrw);
        } catch (Exception e) {
            log.warn("PortfolioManager 잔고 동기화 실패 (이전 값 유지): {}", e.getMessage());
        }
    }

    /**
     * §8 — 세션 배정 자본 vs 거래소 실잔고 drift 감지.
     * 세션 initialCapital 합이 계좌 KRW 의 105% 를 초과하면 WARN 로그를 남긴다.
     */
    private void detectCapitalDrift(BigDecimal upbitKrw) {
        if (upbitKrw.compareTo(BigDecimal.ZERO) <= 0) return;
        List<String> activeStatuses = List.of("RUNNING", "CREATED");
        BigDecimal committedCapital = sessionRepository.sumInitialCapitalByStatusIn(activeStatuses);
        if (committedCapital.compareTo(upbitKrw.multiply(DRIFT_WARN_RATIO)) > 0) {
            log.warn("[§8 drift] 세션 배정 자본({}) > 계좌 잔고({}) × {}. "
                            + "다중 세션이 동시 매수 시 잔고 부족 가능성 있음. "
                            + "세션을 줄이거나 투자금을 조정하세요.",
                    committedCapital.toPlainString(),
                    upbitKrw.toPlainString(),
                    DRIFT_WARN_RATIO.toPlainString());
        }
    }
}
