package com.cryptoautotrader.core.model;

public record CoinPair(String value) {

    public static CoinPair of(String value) {
        return new CoinPair(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
