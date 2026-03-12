package com.cryptoautotrader.exchange.upbit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpbitCandleResponse {

    @JsonProperty("market")
    private String market;

    @JsonProperty("candle_date_time_utc")
    private String candleDateTimeUtc;

    @JsonProperty("opening_price")
    private BigDecimal openingPrice;

    @JsonProperty("high_price")
    private BigDecimal highPrice;

    @JsonProperty("low_price")
    private BigDecimal lowPrice;

    @JsonProperty("trade_price")
    private BigDecimal tradePrice;

    @JsonProperty("candle_acc_trade_volume")
    private BigDecimal candleAccTradeVolume;
}
