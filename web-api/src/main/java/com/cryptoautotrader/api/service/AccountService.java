package com.cryptoautotrader.api.service;

import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.AccountResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AccountService {

    @Autowired(required = false)
    private UpbitOrderClient upbitOrderClient;

    @Autowired
    private UpbitRestClient upbitRestClient;

    /**
     * Upbit 계좌 종합 현황 조회
     * API Key 없으면 apiKeyConfigured=false 반환
     */
    public Map<String, Object> getAccountSummary() {
        Map<String, Object> result = new HashMap<>();

        if (upbitOrderClient == null) {
            result.put("apiKeyConfigured", false);
            result.put("message", "Upbit API Key가 설정되지 않았습니다. UPBIT_ACCESS_KEY, UPBIT_SECRET_KEY 환경변수를 설정하세요.");
            return result;
        }

        result.put("apiKeyConfigured", true);

        try {
            List<AccountResponse> accounts = upbitOrderClient.getAccounts();

            // KRW 잔고
            AccountResponse krwAccount = accounts.stream()
                    .filter(a -> "KRW".equals(a.getCurrency()))
                    .findFirst().orElse(null);
            BigDecimal availableKrw = krwAccount != null ? krwAccount.getBalance() : BigDecimal.ZERO;
            BigDecimal lockedKrw = krwAccount != null ? krwAccount.getLocked() : BigDecimal.ZERO;
            BigDecimal totalKrwBalance = availableKrw.add(lockedKrw);

            // 코인 목록 (KRW 제외)
            List<AccountResponse> coinAccounts = accounts.stream()
                    .filter(a -> !"KRW".equals(a.getCurrency()))
                    .filter(a -> a.getBalance().add(a.getLocked()).compareTo(BigDecimal.ZERO) > 0)
                    .collect(Collectors.toList());

            // 현재가 조회
            Map<String, BigDecimal> currentPrices = new HashMap<>();
            if (!coinAccounts.isEmpty()) {
                String markets = coinAccounts.stream()
                        .map(a -> "KRW-" + a.getCurrency())
                        .collect(Collectors.joining(","));
                try {
                    List<Map<String, Object>> tickers = upbitRestClient.getTicker(markets);
                    for (Map<String, Object> ticker : tickers) {
                        String market = (String) ticker.get("market");
                        Object tradePrice = ticker.get("trade_price");
                        if (market != null && tradePrice != null) {
                            String currency = market.replace("KRW-", "");
                            currentPrices.put(currency, new BigDecimal(tradePrice.toString()));
                        }
                    }
                } catch (Exception e) {
                    log.warn("현재가 조회 실패: {}", e.getMessage());
                }
            }

            // 코인별 평가 정보 계산
            BigDecimal totalCoinValueKrw = BigDecimal.ZERO;
            BigDecimal totalBuyCostKrw = BigDecimal.ZERO;
            List<Map<String, Object>> holdings = new ArrayList<>();

            for (AccountResponse coin : coinAccounts) {
                BigDecimal totalQty = coin.getBalance().add(coin.getLocked());
                BigDecimal currentPrice = currentPrices.getOrDefault(coin.getCurrency(), BigDecimal.ZERO);
                BigDecimal evalValue = totalQty.multiply(currentPrice).setScale(0, RoundingMode.HALF_UP);
                BigDecimal avgBuyPrice = coin.getAvgBuyPrice() != null ? coin.getAvgBuyPrice() : BigDecimal.ZERO;
                BigDecimal buyCost = totalQty.multiply(avgBuyPrice).setScale(0, RoundingMode.HALF_UP);
                BigDecimal unrealizedPnl = evalValue.subtract(buyCost);
                BigDecimal unrealizedPnlPct = buyCost.compareTo(BigDecimal.ZERO) > 0
                        ? unrealizedPnl.divide(buyCost, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                        : BigDecimal.ZERO;

                totalCoinValueKrw = totalCoinValueKrw.add(evalValue);
                totalBuyCostKrw = totalBuyCostKrw.add(buyCost);

                Map<String, Object> holding = new HashMap<>();
                holding.put("currency", coin.getCurrency());
                holding.put("market", "KRW-" + coin.getCurrency());
                holding.put("balance", coin.getBalance());
                holding.put("locked", coin.getLocked());
                holding.put("totalQuantity", totalQty);
                holding.put("avgBuyPrice", avgBuyPrice);
                holding.put("currentPrice", currentPrice);
                holding.put("evalValue", evalValue);
                holding.put("buyCost", buyCost);
                holding.put("unrealizedPnl", unrealizedPnl);
                holding.put("unrealizedPnlPct", unrealizedPnlPct.setScale(2, RoundingMode.HALF_UP));
                holdings.add(holding);
            }

            // 평가순 정렬
            holdings.sort((a, b) -> ((BigDecimal) b.get("evalValue")).compareTo((BigDecimal) a.get("evalValue")));

            BigDecimal totalAssetKrw = totalKrwBalance.add(totalCoinValueKrw);
            BigDecimal totalUnrealizedPnl = totalCoinValueKrw.subtract(totalBuyCostKrw);
            BigDecimal totalUnrealizedPnlPct = totalBuyCostKrw.compareTo(BigDecimal.ZERO) > 0
                    ? totalUnrealizedPnl.divide(totalBuyCostKrw, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;

            result.put("totalAssetKrw", totalAssetKrw.setScale(0, RoundingMode.HALF_UP));
            result.put("availableKrw", availableKrw.setScale(0, RoundingMode.HALF_UP));
            result.put("lockedKrw", lockedKrw.setScale(0, RoundingMode.HALF_UP));
            result.put("totalKrwBalance", totalKrwBalance.setScale(0, RoundingMode.HALF_UP));
            result.put("totalCoinValueKrw", totalCoinValueKrw.setScale(0, RoundingMode.HALF_UP));
            result.put("totalBuyCostKrw", totalBuyCostKrw.setScale(0, RoundingMode.HALF_UP));
            result.put("totalUnrealizedPnl", totalUnrealizedPnl.setScale(0, RoundingMode.HALF_UP));
            result.put("totalUnrealizedPnlPct", totalUnrealizedPnlPct.setScale(2, RoundingMode.HALF_UP));
            result.put("holdings", holdings);
            result.put("fetchedAt", java.time.Instant.now().toString());

        } catch (Exception e) {
            log.error("계좌 정보 조회 실패: {}", e.getMessage(), e);
            result.put("apiKeyConfigured", true);
            result.put("error", "계좌 정보 조회 실패: " + e.getMessage());
        }

        return result;
    }
}
