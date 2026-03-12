package com.cryptoautotrader.api.entity;

import lombok.*;

import java.io.Serializable;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class CandleDataId implements Serializable {
    private Instant time;
    private String coinPair;
    private String timeframe;
}
