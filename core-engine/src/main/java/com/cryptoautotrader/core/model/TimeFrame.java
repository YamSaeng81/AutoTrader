package com.cryptoautotrader.core.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TimeFrame {
    MINUTE_1("M1", 1),
    MINUTE_5("M5", 5),
    MINUTE_15("M15", 15),
    MINUTE_30("M30", 30),
    HOUR_1("H1", 60),
    HOUR_4("H4", 240),
    DAY_1("D1", 1440);

    private final String code;
    private final int minutes;

    public static TimeFrame fromCode(String code) {
        for (TimeFrame tf : values()) {
            if (tf.code.equalsIgnoreCase(code)) return tf;
        }
        throw new IllegalArgumentException("알 수 없는 타임프레임: " + code);
    }
}
