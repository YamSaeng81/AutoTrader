Tier 1 — 계산 정확성 버그 (즉시 수정 필요)

  1. Sharpe/Sortino/Calmar 연환산 오류 (MetricsCalculator.java, docs/20260415_sharpe_audit.md)
  - 그래프에서 MetricsCalculator → ANNUALIZATION_FACTOR 엣지가 EXTRACTED로 확인
  - 성과 지표 자체가 잘못 계산되어 있어 백테스트 결과 신뢰 불가
  - 수정 방향: 일별 equity curve 기반 재계산 (concept_daily_equity_curve 노드에 정의됨)

  2. Walk-Forward 윈도우 겹침 (WalkForwardTestRunner.java)
  - in-sample과 out-of-sample 구간이 겹쳐서 과적합 방지 기능이 무력화됨
  - concept_oos_independence 노드: OOS 독립성 원칙이 문서화돼 있지만 코드에 반영 미흡

  3. 백테스트 SL/TP 낙관적 체결 가정 (BacktestEngine.java, ExitRuleChecker.java)
  - SL/TP가 next-open이 아닌 exact-price에 체결되는 것으로 가정
  - 실전 결과와 백테스트 결과 사이의 체결가 Drift 문제로 이어짐
  - 그래프에서 ExecutionDriftTracker 커뮤니티가 별도 존재 → 이미 인식된 문제

  4. Partial Fill 이월 로직 모순 (BacktestEngine.java)
  - 부분 체결 발생 시 이월 처리 로직에 모순이 있음

  ---
  Tier 2 — 운영 안전성 (실전 매매 전 필수)

  5. LiveTradingService Race Condition
  - God Node 분석: LiveTradingService 48 edges — 너무 많은 책임을 단일 서비스가 가짐
  - executeSessionBuy(), executeSessionSell(), finalizeSellPosition() 등이 동시성 없이 호출됨
  - LiveTradingReliabilityTest가 존재하나 .riskCheck_ghostPositionsDoNotConsumeSlots() 같은 엣지 케이스만 커버

  6. WebSocket 단일 장애점 (UpbitWebSocketClient.java)
  - ExchangeHealthMonitor + WsFallbackTest가 커뮤니티로 분리됨 → 이미 인식되어 있으나
  - doConnect(), dispatchTrade(), addTradeListener() 체인이 WebSocket 하나에 집중
  - 장애 시 자동 재연결 + fallback REST 폴링 여부 확인 필요

  7. emergencyStopAll 연쇄 충격
  - useEmergencyStopAll() (프론트엔드)이 직접 백엔드 호출
  - 모든 세션을 동시 청산할 때 Upbit API rate limit (UpbitApiRateLimiter) 초과 가능성

  ---
  Tier 3 — 구조적 문제

  8. StochasticRsiStrategy 구조적 결함
  - 그래프에서 report_exec_stochrsi_flaw 노드: -70% 손실 기록된 문서 존재
  - strategy-lib 에 StochasticRsiStrategy.java 는 있으나 복합 전략에서 제외 권장

  9. 리스크 체크가 실제 자본이 아닌 추정 자본 사용 (RiskManagementService.java)
  - concept_risk_capital_error 노드: 리스크 계산 시 실시간 잔고가 아닌 세션 시작 시점 자본을 사용하는 버그

  10. Spring Security 미구현 (P0)
  - report_exec_spring_security_missing 노드 → odl_progress_spring_security 노드: 토큰 기반 인증만 있고 Spring Security 미적용
  - API가 외부 노출된 경우 보안 위험

  ---
  아키텍처 관찰 (개선 권장)

  God Node 경고:
  - of() (206 edges) — CoinPair.of() 가 전체 시스템의 단일 장애점. 시그니처 변경 시 전체 테스트 및 엔진이 깨짐
  - LiveTradingService (48 edges) — 서비스 분리 필요 (ExecutionService, SessionService, PositionService 등)
  - PUT() (110 edges) — 프론트엔드 proxy route가 전체 API 레이어의 병목

  AI 파이프라인의 위치:
  - LLM Task Router, AI Pipeline & News Feed, Morning Briefing 커뮤니티가 독립적으로 존재
  - 트레이딩 엔진과 AI 분석 레이어가 느슨하게 결합되어 있어 좋은 설계지만, 실전 트레이딩 의사결정에 LLM이 개입하는지 확인 필요
   (속도/신뢰성 위험)

  ---
  개선 우선순위 요약

  ┌──────┬─────────────────────────────────────────┬───────────┬────────┐
  │ 순위 │                  항목                   │  위험도   │ 난이도 │
  ├──────┼─────────────────────────────────────────┼───────────┼────────┤
  │ 1    │ Sharpe/Calmar 연환산 수식 수정          │ 높음      │ 낮음   │
  ├──────┼─────────────────────────────────────────┼───────────┼────────┤
  │ 2    │ BacktestEngine SL/TP 체결가 보수적 처리 │ 높음      │ 중간   │
  ├──────┼─────────────────────────────────────────┼───────────┼────────┤
  │ 3    │ LiveTradingService 동시성 보호          │ 매우 높음 │ 높음   │
  ├──────┼─────────────────────────────────────────┼───────────┼────────┤
  │ 4    │ WalkForward OOS 겹침 제거               │ 높음      │ 중간   │
  ├──────┼─────────────────────────────────────────┼───────────┼────────┤
  │ 5    │ Spring Security 적용                    │ 매우 높음 │ 중간   │
  ├──────┼─────────────────────────────────────────┼───────────┼────────┤
  │ 6    │ LiveTradingService 분리 리팩터          │ 중간      │ 높음   │
  ├──────┼─────────────────────────────────────────┼───────────┼────────┤
  │ 7    │ StochasticRsiStrategy 비활성화/수정     │ 중간      │ 낮음   │
  └──────┴─────────────────────────────────────────┴───────────┴────────┘

  ---