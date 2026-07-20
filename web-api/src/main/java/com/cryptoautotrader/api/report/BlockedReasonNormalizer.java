package com.cryptoautotrader.api.report;

/**
 * 차단 사유({@code blocked_reason}) 문자열을 신호품질 집계용 그룹핑 키로 정규화한다.
 *
 * <p>2026-07-20 발견: {@code BLACK_SWAN_GUARD}·"이미 포지션 보유 중" 등 일부 차단 사유는
 * 급락률·현재가·보유시간 같은 가변 수치를 메시지 본문에 그대로 포함한다. 기존 그룹핑
 * ({@code reason.split(":")[0]})은 콜론이 없는 이 메시지들을 전혀 나누지 못해, 같은 사유인데도
 * 매 건이 별도 그룹으로 쪼개져 신호품질 화면의 "차단 사유별" 표가 수십~수백 행으로 늘어지는
 * 원인이었다. 괄호 안 상세와 본문의 %/배 수치를 제거해 사유의 종류만으로 그룹핑한다.</p>
 */
public final class BlockedReasonNormalizer {

    private BlockedReasonNormalizer() {}

    /**
     * @param reason 원본 차단 사유 (null 아님을 호출부가 보장)
     * @return 그룹핑용 정규화 키
     */
    public static String normalize(String reason) {
        String base = reason.split(":")[0].trim();
        base = base.replaceAll("\\([^)]*\\)", " ");          // 괄호(및 내부 상세) 제거
        base = base.replaceAll("[-+]?\\d+(\\.\\d+)?%", " ");  // "-17.39%" 등 퍼센트 수치 제거
        base = base.replaceAll("[-+]?\\d+(\\.\\d+)?배", " "); // "26.1배" 등 배수 수치 제거
        base = base.replaceAll("\\s+", " ").trim();
        return base.isEmpty() ? reason.trim() : base;
    }
}
