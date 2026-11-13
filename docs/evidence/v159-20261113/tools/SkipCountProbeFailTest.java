package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.Test;

/** v1.159 진단(제품 트리에 넣지 않음) — Gradle 은 실패가 있을 때만 "N tests completed, F failed, S skipped" 를 찍는다. 집계 줄을 얻으려고 일부러 실패한다. */
class SkipCountProbeFailTest {
    @Test
    void failOnPurpose() {
        throw new AssertionError("의도한 실패 — 콘솔 집계 줄을 얻기 위한 진단");
    }
}
