package com.cryptoautotrader.core.risk;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class RiskCheckResult {
    private final boolean approved;
    private final String reason;

    public static RiskCheckResult approve() {
        return new RiskCheckResult(true, null);
    }

    public static RiskCheckResult reject(String reason) {
        return new RiskCheckResult(false, reason);
    }
}
