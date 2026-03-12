package com.cryptoautotrader.exchange.adapter;

import com.cryptoautotrader.strategy.Candle;

import java.time.Instant;
import java.util.List;

/**
 * 거래소 추상화 인터페이스
 * 확장 규칙: 타 거래소 추가 시 이 인터페이스 구현체만 추가.
 * core-engine은 ExchangeAdapter에만 의존하므로 변경 없음.
 */
public interface ExchangeAdapter {

    String getExchangeName();

    List<Candle> fetchCandles(String coinPair, String timeframe, Instant from, Instant to);

    List<String> getSupportedCoins();
}
