package com.cryptoautotrader.strategy;

/**
 * 상태를 유지하는 전략 인터페이스.
 * Strategy는 기본적으로 stateless이지만, Grid 전략처럼
 * 진입 레벨 추적이 필요한 경우 이 인터페이스를 구현한다.
 */
public interface StatefulStrategy extends Strategy {

    /**
     * 전략 내부 상태를 초기화한다.
     * 그리드 범위 재설정 또는 세션 시작 시 호출한다.
     */
    void resetState();
}
