package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 실시간 시장 데이터 캐시 엔티티.
 * MarketDataSyncService 가 RUNNING 세션 대상으로 자동 싱크하는 최신 캔들을 저장.
 * 백테스트용 수동 수집 데이터(candle_data)와 분리된 별도 테이블(market_data_cache).
 */
@Entity
@Table(name = "market_data_cache")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(CandleDataId.class)
public class MarketDataCacheEntity {

    @Id
    @Column(name = "time", nullable = false)
    private Instant time;

    @Id
    @Column(name = "coin_pair", nullable = false, length = 20)
    private String coinPair;

    @Id
    @Column(name = "timeframe", nullable = false, length = 10)
    private String timeframe;

    @Column(name = "open", nullable = false)
    private BigDecimal open;

    @Column(name = "high", nullable = false)
    private BigDecimal high;

    @Column(name = "low", nullable = false)
    private BigDecimal low;

    @Column(name = "close", nullable = false)
    private BigDecimal close;

    @Column(name = "volume", nullable = false)
    private BigDecimal volume;

    public Instant getTime()       { return time; }
    public void setTime(Instant time) { this.time = time; }

    public String getCoinPair()    { return coinPair; }
    public void setCoinPair(String coinPair) { this.coinPair = coinPair; }

    public String getTimeframe()   { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }

    public BigDecimal getOpen()    { return open; }
    public void setOpen(BigDecimal open) { this.open = open; }

    public BigDecimal getHigh()    { return high; }
    public void setHigh(BigDecimal high) { this.high = high; }

    public BigDecimal getLow()     { return low; }
    public void setLow(BigDecimal low) { this.low = low; }

    public BigDecimal getClose()   { return close; }
    public void setClose(BigDecimal close) { this.close = close; }

    public BigDecimal getVolume()  { return volume; }
    public void setVolume(BigDecimal volume) { this.volume = volume; }
}
