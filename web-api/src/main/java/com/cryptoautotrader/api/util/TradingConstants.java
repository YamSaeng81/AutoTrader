package com.cryptoautotrader.api.util;

import java.math.BigDecimal;

/** 매매 관련 공통 상수. */
public final class TradingConstants {

    private TradingConstants() {}

    /**
     * 업비트 왕복 수수료 임계값 (매수 0.05% + 매도 0.05% = 0.10%).
     * 이 값을 초과해야 수수료 차감 후 실질 수익으로 판정한다.
     */
    public static final BigDecimal FEE_THRESHOLD = new BigDecimal("0.10");
}
