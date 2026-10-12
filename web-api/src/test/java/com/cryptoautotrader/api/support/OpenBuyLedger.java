package com.cryptoautotrader.api.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 계좌 여력 시험의 기준선을 고정한다 (2026-10-12).
 *
 * <p>계좌 여력은 <b>전역</b> 합계(열린 매수 약정)를 쓴다. 시험 기반의 H2 는 컨텍스트끼리 공유되고, 캐시된
 * 다른 컨텍스트의 폴러(5초)가 앞선 시험이 남긴 열린 주문을 시험 도중에 종결·철회한다 — 그러면 기준선이
 * 움직여 승인 경계가 흔들린다(전체 실행에서 실제로 한 번 그랬다). 앞선 시험이 끝난 뒤 남긴 열린 매수를
 * 시작 전에 <b>체결 없는 취소</b>로 닫아 약정 합계에서 뺀다.</p>
 *
 * <p>🔴 시험 전용이다. 체결 시각을 남기지 않으므로 "스냅숏 이후 종결" 에도 들어가지 않는다.</p>
 */
public final class OpenBuyLedger {

    private OpenBuyLedger() {
    }

    public static int quiesce(JdbcTemplate jdbc) {
        return jdbc.update("UPDATE \"order\" SET state = 'CANCELLED', filled_at = NULL "
                + "WHERE side = 'BUY' AND session_kind IN ('LIVE', 'DYNAMIC') "
                + "AND state IN ('PENDING', 'INTENT_READY', 'SUBMITTING', 'UNKNOWN', 'SUBMITTED', 'PARTIAL_FILLED')");
    }
}
