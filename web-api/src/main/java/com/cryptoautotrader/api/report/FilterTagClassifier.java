package com.cryptoautotrader.api.report;

/**
 * 전략 로그 {@code reason} 문자열에서 필터 태그를 분류한다.
 *
 * <p>두 종류로 나뉜다:
 * <ul>
 *   <li><b>통과 태그</b>({@link #passTag}) — 필터를 통과한 BUY/SELL 신호에 붙는 태그.
 *       해당 신호는 forward return(4h/24h)이 채워지므로 승률·수익률 측정이 가능하다.
 *       예: {@code [H4:중립]} 통과 신호의 실제 손익.</li>
 *   <li><b>차단 태그</b>({@link #blockTag}) — 필터가 신호를 HOLD로 바꿔 진입을 막은 경우의 사유.
 *       HOLD 로그라 forward return이 없으므로 <b>건수만</b> 집계 가능하다
 *       (진입했다면 어땠을지는 반사실이라 측정 불가).</li>
 * </ul>
 *
 * <p>레짐 태그({@code [VOLATILITY]}/{@code [TREND]}/{@code [TRANSITIONAL]})는 별도 byRegime
 * 집계에서 다루므로 여기서는 분류하지 않는다.
 */
public final class FilterTagClassifier {

    private FilterTagClassifier() {
    }

    /**
     * 통과한 BUY/SELL 신호의 reason에서 필터 통과 태그를 분류한다.
     * 해당 없으면 null (필터 무관 일반 신호).
     */
    public static String passTag(String reason) {
        if (reason == null) {
            return null;
        }
        if (reason.contains("[H4:중립]"))        return "H4_중립통과";
        if (reason.contains("[H4:데이터부족]"))   return "H4_데이터부족통과";
        if (reason.contains("[H4:BUY]") || reason.contains("[H4:SELL]")) return "H4_추세확인";
        return null;
    }

    /**
     * HOLD 신호의 reason에서 진입을 막은 필터 사유를 분류한다.
     * 해당 없으면 null (필터와 무관한 일반 HOLD — 예: 신호 없음·닫힌 캔들 미갱신).
     *
     * <p>우선순위: strict 차단을 RANGE보다 먼저 검사한다("중립·strict"가
     * 일반 중립과 구분되도록).
     */
    public static String blockTag(String reason) {
        if (reason == null) {
            return null;
        }
        if (reason.contains("Veto"))        return "RSI_Veto차단";
        if (reason.contains("EMA200"))      return "EMA200차단";
        if (reason.contains("MTF불일치"))    return "MTF불일치차단";
        if (reason.contains("·strict"))     return "H4_strict차단";
        if (reason.contains("진입 금지"))    return "RANGE차단";
        return null;
    }
}
