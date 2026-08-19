package com.cryptoautotrader.core.risk;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 매매 규칙 지문 — 2026-08-19 신설.
 *
 * <h3>왜 필요한가</h3>
 * <p>이 프로젝트의 전제는 "모의 데이터로 실전을 판단한다" 이다. 그 전제는 <b>데이터가 어떤
 * 규칙 아래 만들어졌는지 알 수 있을 때만</b> 성립한다. 그런데 08-19 시점까지
 * {@code position} 314행 전부 {@code strategy_config_id} 가 NULL 이었고 {@code strategy_log}
 * 에는 설정 컬럼이 아예 없었다 — <b>어떤 규칙의 산물인지 기록이 없었다.</b></p>
 *
 * <p>그 결과 규칙이 바뀐 사실을 나중에 알면 어디까지가 옛 규칙인지 구분할 수 없어
 * <b>데이터를 통째로 버리는 것 말고는 방법이 없었다.</b> 실제로 그 사이클이 반복됐다:</p>
 * <ul>
 *   <li>07-09·07-31 생성 세션은 워치리스트 필터가 완화돼 있었다(ATR 0.30 / 스프레드 0.15 / 후보 50)</li>
 *   <li>08-07 세션 재생성 때 <b>코드 기본값으로 조용히 되돌아갔다</b>(0.50 / 0.10 / 30)</li>
 *   <li>감시 코인이 주당 62종 → 10종으로 붕괴했는데 <b>아무 기록도 남지 않았다</b></li>
 * </ul>
 *
 * <h3>무엇을 바꾸는가</h3>
 * <p>지문을 데이터 행에 찍으면 규칙 변경이 <b>폐기가 아니라 분할</b>이 된다. 과거 데이터는
 * "다른 조건의 관측" 으로 남고, 같은 지문끼리만 합산하면 표본이 오염되지 않는다.
 * 규칙 A와 B를 비교하는 것도 그때부터 가능해진다.</p>
 *
 * <h3>설계</h3>
 * <p>매매 거동에 영향을 주는 파라미터만 담는다. 담기지 않은 값을 바꾸면 지문이 그대로라
 * <b>다른 규칙의 데이터가 같은 표본에 섞인다</b> — 새 파라미터를 도입하면 여기에 반드시 추가할 것.
 * 반대로 로깅·알림처럼 거동과 무관한 값은 넣지 않는다(무의미한 지문 분할을 막는다).</p>
 *
 * <p>엔진 이름을 키에 포함한다. LIVE·DYNAMIC·PAPER 는 종목 선정과 체결 방식이 달라
 * 같은 파라미터라도 <b>같은 규칙이 아니다.</b></p>
 */
public final class RulesetFingerprint {

    /** 지문 길이(16진 문자). 충돌 확률보다 로그 가독성을 우선한 값. */
    private static final int HASH_LENGTH = 12;

    private final SortedMap<String, String> params;
    private final String hash;

    private RulesetFingerprint(SortedMap<String, String> params) {
        this.params = Collections.unmodifiableSortedMap(params);
        this.hash = sha256Prefix(canonical(params));
    }

    public static Builder builder(String engine) {
        return new Builder(engine);
    }

    /** 짧은 16진 지문 — 데이터 행에 찍는 값. */
    public String hash() {
        return hash;
    }

    /** 지문이 가리키는 실제 파라미터. {@code ruleset_snapshot} 에 1회 저장해 역참조한다. */
    public Map<String, String> params() {
        return params;
    }

    /** 정규 직렬화 — 키 정렬 + 개행 구분. 같은 값이면 항상 같은 문자열이 나와야 한다. */
    public String toCanonicalString() {
        return canonical(params);
    }

    private static String canonical(SortedMap<String, String> p) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    private static String sha256Prefix(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
                if (sb.length() >= HASH_LENGTH) break;
            }
            return sb.substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
        }
    }

    @Override
    public String toString() {
        return hash;
    }

    // ── 빌더 ──────────────────────────────────────────────────────────────────

    public static final class Builder {
        private final TreeMap<String, String> p = new TreeMap<>();

        private Builder(String engine) {
            p.put("engine", engine == null ? "?" : engine);
        }

        public Builder put(String key, String value) {
            p.put(key, value == null ? "null" : value);
            return this;
        }

        /** {@code BigDecimal} 은 스케일 차이로 지문이 갈리지 않도록 정규화한다 (0.30 == 0.3). */
        public Builder put(String key, BigDecimal value) {
            return put(key, value == null ? null : value.stripTrailingZeros().toPlainString());
        }

        public Builder put(String key, Integer value) {
            return put(key, value == null ? null : value.toString());
        }

        public Builder put(String key, Long value) {
            return put(key, value == null ? null : value.toString());
        }

        public Builder put(String key, boolean value) {
            return put(key, Boolean.toString(value));
        }

        /** 청산 규칙 전체를 한 번에 담는다 — 이 설정의 어떤 값이 바뀌어도 지문이 갈린다. */
        public Builder putExitRules(ExitRuleConfig c) {
            put("exit.stopLossPct", c.getStopLossPct());
            put("exit.takeProfitMultiplier", c.getTakeProfitMultiplier());
            put("exit.atrStopLossEnabled", c.isAtrStopLossEnabled());
            put("exit.atrMultiplier", c.getAtrMultiplier());
            put("exit.minAtrStopLossPct", c.getMinAtrStopLossPct());
            put("exit.maxAtrStopLossPct", c.getMaxAtrStopLossPct());
            put("exit.trailingEnabled", c.isTrailingEnabled());
            put("exit.trailingTpMargin", c.getTrailingTpMargin());
            put("exit.trailingSlMargin", c.getTrailingSlMargin());
            put("exit.investRatio", c.getInvestRatio());
            put("exit.minInvestAmount", c.getMinInvestAmount());
            put("exit.riskBasedSizingEnabled", c.isRiskBasedSizingEnabled());
            put("exit.riskPerTradePct", c.getRiskPerTradePct());
            put("exit.minHoldMinutesForSignalExit", c.getMinHoldMinutesForSignalExit());
            put("exit.minPnlPctForSignalExit", c.getMinPnlPctForSignalExit());
            put("exit.lossEscapeThresholdPct", c.getLossEscapeThresholdPct());
            return this;
        }

        public RulesetFingerprint build() {
            return new RulesetFingerprint(new TreeMap<>(p));
        }
    }
}
