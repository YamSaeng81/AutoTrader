package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "candle_data")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(CandleDataId.class)
public class CandleDataEntity {

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

    // ── 명시적 getter/setter (IDE Lombok 인식 문제 회피) ──────────────

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
