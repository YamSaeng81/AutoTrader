package com.cryptoautotrader.api.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 새 DYNAMIC 세션 전용 매회 상한·신규 매수 제외 코인 — {@code strategy_params} 의 {@code maxOrderKrw}·{@code excludeCoins}
 * (총괄 v1.187 §3-4, 독립 후보). DDL 없이 기존 JSONB 열에 저장한다.
 *
 * <ul>
 *   <li><b>생성 계약</b> — 두 키는 함께 명시한다. 상한은 정수 원 단위 숫자이며 최소 주문(5,000) 이상·상한 기준 이하,
 *       제외 목록은 {@code KRW-XXX} 문자열 목록(중복 거절, 빈 목록은 "제외 없음"의 명시)이다. 저장 전에 거절한다.</li>
 *   <li><b>정규화</b> — 제외 목록은 정렬해 저장하고 상한은 정수로 저장한다. 같은 값이면 같은 규칙 지문이 된다.
 *       두 키가 없으면 입력 맵을 그대로 돌려준다(기존 세션·지문 불변).</li>
 *   <li><b>실행 시 읽기</b> — 저장값이 계약을 어기면(직접 DB 수정 등) 신규 매수를 막는 쪽으로 판단한다({@link #invalidReason()}).</li>
 * </ul>
 * 금액 숫자는 운영 결정 전이다. 이 클래스는 상한을 줄이는 쪽으로만 쓰고 예약 서비스의 최종 보호선은 바꾸지 않는다.
 */
public final class DynamicEntryLimits {

    public static final String MAX_ORDER_KRW = "maxOrderKrw";
    public static final String EXCLUDE_COINS = "excludeCoins";
    public static final BigDecimal MIN_ORDER_KRW = BigDecimal.valueOf(5000);
    private static final Pattern COIN = Pattern.compile("^KRW-[A-Z0-9]+$");
    private static final DynamicEntryLimits NONE = new DynamicEntryLimits(null, Set.of(), null);

    private final BigDecimal maxOrderKrw;
    private final Set<String> excludeCoins;
    private final String invalidReason;

    private DynamicEntryLimits(BigDecimal maxOrderKrw, Set<String> excludeCoins, String invalidReason) {
        this.maxOrderKrw = maxOrderKrw;
        this.excludeCoins = excludeCoins;
        this.invalidReason = invalidReason;
    }

    /**
     * 새 세션 생성 요청의 검사·정규화. 두 키가 없으면 {@code params} 를 그대로 돌려준다.
     *
     * @param upperBound 상한의 최댓값(실자금은 포지션당 상한). {@code null} 이면 상한 검사를 할 수 없어 거절한다.
     * @throws IllegalArgumentException 계약 위반 — 세션을 저장하지 않는다.
     */
    public static Map<String, Object> normalizeForCreate(Map<String, Object> params, BigDecimal upperBound) {
        if (params == null || !(params.containsKey(MAX_ORDER_KRW) || params.containsKey(EXCLUDE_COINS))) {
            return params;
        }
        if (!(params.containsKey(MAX_ORDER_KRW) && params.containsKey(EXCLUDE_COINS))) {
            throw new IllegalArgumentException("maxOrderKrw·excludeCoins 는 함께 명시해야 한다");
        }
        BigDecimal max = parseMax(params.get(MAX_ORDER_KRW));
        if (upperBound == null) {
            throw new IllegalArgumentException("maxOrderKrw 상한 기준(포지션당 상한)을 확인할 수 없어 거절");
        }
        if (max.compareTo(upperBound) > 0) {
            throw new IllegalArgumentException("maxOrderKrw " + max.toPlainString() + " > 상한 기준 " + upperBound.toPlainString());
        }
        List<String> coins = parseCoins(params.get(EXCLUDE_COINS));
        Map<String, Object> out = new LinkedHashMap<>(params);
        out.put(MAX_ORDER_KRW, max.longValueExact());
        out.put(EXCLUDE_COINS, coins);
        return out;
    }

    /** 실행 시 읽기 — 예외를 던지지 않는다. 두 키가 없으면 제한 없음. */
    public static DynamicEntryLimits of(Map<String, Object> params) {
        if (params == null || !(params.containsKey(MAX_ORDER_KRW) || params.containsKey(EXCLUDE_COINS))) {
            return NONE;
        }
        try {
            if (!(params.containsKey(MAX_ORDER_KRW) && params.containsKey(EXCLUDE_COINS))) {
                throw new IllegalArgumentException("두 키 중 하나만 있음");
            }
            return new DynamicEntryLimits(parseMax(params.get(MAX_ORDER_KRW)),
                    Collections.unmodifiableSet(new HashSet<>(parseCoins(params.get(EXCLUDE_COINS)))), null);
        } catch (IllegalArgumentException | ArithmeticException e) {
            return new DynamicEntryLimits(null, Set.of(), "저장된 maxOrderKrw/excludeCoins 가 계약 밖(" + e.getMessage() + ")");
        }
    }

    private static BigDecimal parseMax(Object v) {
        if (v == null) throw new IllegalArgumentException("maxOrderKrw null");
        if (!(v instanceof Integer || v instanceof Long || v instanceof BigInteger || v instanceof BigDecimal
                || v instanceof Double || v instanceof Float || v instanceof Short)) {
            throw new IllegalArgumentException("maxOrderKrw 는 숫자여야 한다(" + v.getClass().getSimpleName() + ")");
        }
        BigDecimal d;
        try {
            d = new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("maxOrderKrw 숫자 아님(" + v + ")");
        }
        d = d.stripTrailingZeros();
        if (d.scale() > 0) throw new IllegalArgumentException("maxOrderKrw 는 원 단위 정수여야 한다(" + d.toPlainString() + ")");
        if (d.compareTo(MIN_ORDER_KRW) < 0) {
            throw new IllegalArgumentException("maxOrderKrw " + d.toPlainString() + " < 최소 주문 " + MIN_ORDER_KRW);
        }
        return d.setScale(0);
    }

    private static List<String> parseCoins(Object v) {
        if (!(v instanceof List<?> list)) {
            throw new IllegalArgumentException("excludeCoins 는 목록이어야 한다");
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof String s) || !COIN.matcher(s).matches()) {
                throw new IllegalArgumentException("excludeCoins 항목은 KRW-XXX 대문자 문자열(" + o + ")");
            }
            if (out.contains(s)) throw new IllegalArgumentException("excludeCoins 중복 " + s);
            out.add(s);
        }
        Collections.sort(out);
        return out;
    }

    /** 매회 상한 — 없으면 {@code null}. */
    public BigDecimal maxOrderKrw() { return maxOrderKrw; }

    public boolean excludes(String coinPair) { return excludeCoins.contains(coinPair); }

    /** 저장값이 계약 밖이면 사유, 아니면 {@code null}. 사유가 있으면 신규 매수를 막는다. */
    public String invalidReason() { return invalidReason; }

    /** 신규 매수 차단 사유(손상된 설정 또는 제외 코인) — 없으면 {@code null}. */
    public String entryBlockReason(String coinPair) {
        if (invalidReason != null) return "진입 설정 손상 — " + invalidReason;
        if (excludes(coinPair)) return "제외 코인(excludeCoins) — " + coinPair + " 신규 매수 안 함";
        return null;
    }

    /** 감시 목록에서 제외 코인을 뺀다. 손상된 설정이면 목록은 그대로 두고 진입 단계에서 막는다. */
    public List<String> filter(List<String> watchlist) {
        if (excludeCoins.isEmpty() || watchlist == null) return watchlist;
        return watchlist.stream().filter(c -> !excludeCoins.contains(c)).toList();
    }
}
