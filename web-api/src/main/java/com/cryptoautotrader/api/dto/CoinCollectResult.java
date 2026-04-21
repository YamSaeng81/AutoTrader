package com.cryptoautotrader.api.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 배치 수집 시 코인 1개의 결과 */
@Getter
@RequiredArgsConstructor
public class CoinCollectResult {
    private final String coinPair;
    private final boolean success;
    private final int candleCount;
    private final long durationMs;
    private final String errorMessage;
}
