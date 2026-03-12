package com.cryptoautotrader.strategy;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public abstract class StrategyConfig {

    public abstract String getStrategyType();

    public abstract Map<String, Object> toParamMap();
}
