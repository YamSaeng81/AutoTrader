package com.cryptoautotrader.api.service;

import java.util.Map;

/**
 * 모의투자 실행 모드 — {@code strategy_params.paperExecutionMode} (2026-11-10, 총괄 v1.143/v1.144 C).
 *
 * <p>키가 없으면 종전 PAPER 와 완전히 같다(기존 PAPER·사전 등록 66세션·REAL 동작 불변). {@value #LIVE_ALIGNED} 를 지정한 <b>새 검증
 * 세션만</b> LIVE 고정 코인과 같은 판단 흐름을 탄다 — 캔들 최신성 판정 불가 시 진입·캔들 청산 안 함, BTC 판정 불가 진입 차단,
 * 진입 회차 접수 → 마감 승인(승인 시각 재확인·가격·만료 E), LIVE 청산 순서(익절 → 시간 → 손절), 실시간 시세 손절·익절 트레일링,
 * 캔들을 못 쓰는 회차는 유효한 실시간 현재가로만 가격 청산. 모의 잔액·포지션·체결(슬리피지 0.1%·수수료)은 종전 PAPER 어댑터다.</p>
 *
 * <p>규칙 지문({@code strategy.params})에 실리므로 이 키가 있는 세션은 기존 PAPER 표본과 섞이지 않는다.
 * 청산 오버라이드({@link ExitRuleOverrides})는 모르는 키를 무시한다. 값이 null·문자열 아님·다른 문자열이면 세션을 만들지 않는다.</p>
 */
public final class PaperExecutionMode {

    public static final String KEY = "paperExecutionMode";
    public static final String LIVE_ALIGNED = "LIVE_ALIGNED";

    private PaperExecutionMode() {
    }

    /** @return 위반 사유. 키 없음·정상이면 {@code null}. 입력 원문을 붙이지 않는다. */
    public static String violation(Map<String, Object> strategyParams) {
        if (strategyParams == null || !strategyParams.containsKey(KEY)) return null;
        Object raw = strategyParams.get(KEY);
        if (raw == null) return KEY + " 가 null 이다 — 종전 모드는 키 자체를 빼야 한다";
        if (!(raw instanceof CharSequence) || !LIVE_ALIGNED.equals(raw.toString())) {
            return KEY + " 의 허용 값은 " + LIVE_ALIGNED + " 뿐이다";
        }
        return null;
    }

    /** @throws IllegalStateException 값이 잘못됨 */
    public static boolean isLiveAligned(Map<String, Object> strategyParams) {
        String v = violation(strategyParams);
        if (v != null) throw new IllegalStateException(v);
        return strategyParams != null && strategyParams.containsKey(KEY);
    }
}
