package com.cryptoautotrader.api.util;

/**
 * 타임프레임 문자열을 분(minute) 단위로 변환하는 유틸리티.
 */
public final class TimeframeUtils {

    private TimeframeUtils() {}

    /**
     * 타임프레임 코드를 분(minute) 수로 변환한다.
     *
     * @param timeframe "M1", "M5", "M15", "M30", "H1", "H4", "D1" 등
     * @return 해당 타임프레임의 분 수 (알 수 없는 값은 60으로 폴백)
     */
    public static long toMinutes(String timeframe) {
        if (timeframe == null) return 60;
        return switch (timeframe) {
            case "M1"  -> 1;
            case "M5"  -> 5;
            case "M15" -> 15;
            case "M30" -> 30;
            case "H1"  -> 60;
            case "H4"  -> 240;
            case "D1"  -> 1440;
            default    -> 60;
        };
    }
}
