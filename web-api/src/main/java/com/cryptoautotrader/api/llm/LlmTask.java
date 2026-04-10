package com.cryptoautotrader.api.llm;

/**
 * LLM 작업 유형.
 * llm_task_config 테이블의 task_name과 1:1 매핑.
 *
 * <ul>
 *   <li>LOG_SUMMARY      — 12h 전략 로그 요약 (로컬 LLM 권장, 대용량)</li>
 *   <li>SIGNAL_ANALYSIS  — 신호 품질·패턴 분석 (Cloud LLM 권장, 정밀도 필요)</li>
 *   <li>NEWS_SUMMARY     — 뉴스 요약 (설정에 따라)</li>
 *   <li>REPORT_NARRATION — 보고서 서술 생성 (설정에 따라)</li>
 * </ul>
 */
public enum LlmTask {
    LOG_SUMMARY,
    SIGNAL_ANALYSIS,
    NEWS_SUMMARY,
    REPORT_NARRATION
}
