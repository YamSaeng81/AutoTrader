# CryptoAutoTrader - 개발 상태 검증 및 보강 항목 정리

## 문서 정보
- 검증일: 2026-03-08
- 검증 범위: Phase 1 ~ Phase 3.5 (Phase 4 제외)
- 기반 문서: IDEA.md, PLAN.md, DESIGN.md v1.1, CHECK_RESULT.md, 각 Phase별 구현 문서
- 목적: 현재 개발 상태 종합 검증 + 보강이 필요한 항목 식별

---

## 1. Phase별 개발 상태 종합

### 1.1 Phase 1 - 백테스팅 엔진 (완성도: 95%)

| 항목 | 상태 | 비고 |
|------|------|------|
| Gradle 멀티모듈 (core-engine, strategy-lib, exchange-adapter, web-api) | 완료 | 의존성 방향 설계대로 구현 |
| Docker Compose (TimescaleDB + Redis) | 완료 | 개발용/운영용 분리 |
| Flyway 마이그레이션 V1~V8 | 완료 | hypertable + 압축 정책 포함 |
| Upbit REST API 캔들 수집기 | 완료 | UpbitRestClient + UpbitCandleCollector |
| 초기 4전략 (VWAP, EMA Cross, Bollinger, Grid) | 완료 | strategy-lib 모듈 |
| BacktestEngine (Look-Ahead Bias 방지) | 완료 | 다음 캔들 시가 기준 체결 |
| WalkForwardTestRunner (Overfitting 방지) | 완료 | In-Sample/Out-of-Sample 분할 |
| FillSimulator (Market Impact + Partial Fill) | 완료 | 백테스트 현실성 강화 |
| MetricsCalculator (8종 성과 지표) | 완료 | Sortino, Calmar, Recovery Factor 등 |
| MarketRegimeDetector (ADX 기반) | 완료 | TREND/RANGE/VOLATILE |
| PortfolioManager | 완료 | 전략 충돌 방지, 자본 할당 |
| RiskEngine | 완료 | 손실 한도 체크 |
| 3단 로그 시스템 (Logback) | 완료 | system/strategy/trade 분리 |
| AsyncConfig 스레드 풀 3분리 | 완료 | 시세/주문/일반 |
| 단위 테스트 (15개) | 완료 | 전략, 엔진, 지표, 포트폴리오 |
| Phase 1 전략별 Config 클래스 | **누락** | StrategyConfig.getParams() Map으로 대체 |

### 1.2 Phase 2 - 웹 대시보드 (완성도: 95%)

| 항목 | 상태 | 비고 |
|------|------|------|
| Next.js 14 + TypeScript + Tailwind 초기 설정 | 완료 | App Router |
| Sidebar 네비게이션 | 완료 | Phase별 메뉴, 비활성 표시 |
| 대시보드 (/) 요약 카드 + 최근 백테스트 | 완료 | |
| 백테스트 이력 (/backtest) | 완료 | |
| 백테스트 신규 실행 (/backtest/new) | 완료 | 설계서 외 추가 분리 |
| 백테스트 결과 상세 (/backtest/[id]) | 완료 | 차트 + 지표 |
| 전략 비교 (/backtest/compare) | 완료 | 최대 6개 선택 비교 |
| 데이터 수집 관리 (/data) | 완료 | 수집 요청 + 현황 |
| 전략 로그 조회 (/logs) | 완료 | 페이지네이션 |
| 프론트-백 API 연동 | 완료 | 모든 경로 일치 확인 |
| MSW Mock 시스템 | 완료 | 독립 개발용 |
| 다크 모드 (ThemeProvider) | 완료 | ThemeProvider 구현, 기본 dark, 18개 파일 269개 dark: 클래스 적용 |
| Header 컴포넌트 분리 | 완료 | components/layout/Header.tsx — 다크모드 토글 버튼 포함 (2026-03-08) |
| 공통 UI 컴포넌트 (Button, Card, Badge, Spinner) | 완료 | components/ui/ — Button/Card/Badge/Spinner + index.ts (2026-03-08) |
| Walk Forward 프론트엔드 UI | 완료 | /backtest/walk-forward 별도 페이지로 구현 (전략/코인 선택, inSampleRatio, windowCount, 결과 차트) |

### 1.3 Phase 3 - 전략 개선 및 추가 (완성도: 95%)

| 항목 | 상태 | 비고 |
|------|------|------|
| 추가 5종 전략 스켈레톤 (RSI, MACD, Supertrend, ATR Breakout, Orderbook Imbalance) | 완료 | Phase 3 Config 클래스 포함 |
| StrategyController (CRUD) | 완료 | Registry(읽기) + DB(CRUD) 이중 구조 |
| StrategyConfigEntity DB 영속화 | 완료 | |
| 전략 관리 페이지 (/strategies) | 완료 | 9개 전략 카드 + 상태 배지 |
| StrategyConfigForm (동적 파라미터 폼) | 완료 | |
| 전략 설정 POST/PUT/PATCH API | 완료 | 백엔드 완료, MSW 핸들러 제거하여 실서버 직접 연결 (2026-03-08) |
| 시장 상태 필터 <-> 전략 자동 스위칭 연동 | **미완** | MarketRegimeDetector 존재하나 자동 스위칭 로직 미연동 |
| 추가 전략 로직 완성 (스켈레톤 -> 실제 로직) | **미완** | 5종 모두 스켈레톤 상태 |

### 1.4 Phase 3.5 - Paper Trading (완성도: 98%)

| 항목 | 상태 | 비고 |
|------|------|------|
| paper_trading 스키마 (V8 + V9 + V10) | 완료 | |
| Entity (VirtualBalance, PaperPosition, PaperOrder) | 완료 | |
| PaperTradingService (전략 실행 + 가상 체결) | 완료 | |
| PaperTradingController (7개 API) | 완료 | |
| 멀티세션 지원 (최대 5개) | 완료 | 설계 초과 품질 |
| 모의투자 페이지 (/paper-trading) | 완료 | 시작/중단/세션 관리 |
| 세션 상세 (/paper-trading/[sessionId]) | 완료 | 캔들 차트 + 매수/매도 마커 |
| 모의투자 이력 (/paper-trading/history) | 완료 | |
| 매도 시 매수단가/실현손익/수익률 제공 | 완료 | |

### 1.5 인프라 (완성도: 100%)

| 항목 | 상태 | 비고 |
|------|------|------|
| docker-compose.yml (개발용) | 완료 | DB + Redis |
| docker-compose.prod.yml (운영용) | 완료 | 4개 서비스, restart always |
| Dockerfile (Backend - multi-stage) | 완료 | eclipse-temurin:17 |
| Dockerfile (Frontend - multi-stage) | 완료 | node:20-alpine + standalone |
| .env.example | 완료 | |
| Flyway V1~V10 | 완료 | |
| SwaggerConfig (API 문서) | 완료 | 설계서 외 추가 |
| GlobalExceptionHandler | 완료 | 설계서 외 추가 |

---

## 2. 설계서(DESIGN.md) vs 구현 불일치 사항

| # | 항목 | 설계 | 구현 | 심각도 | 권장 조치 |
|---|------|------|------|--------|-----------|
| 1 | ~~GET /backtest/{id}/metrics~~ | ~~별도 엔드포인트~~ | ~~GET /{id}에 metrics 포함~~ | ~~해결됨~~ | ~~DESIGN.md v1.2에서 수정 완료~~ |
| 2 | ~~Paper Trading 스키마 위치~~ | ~~public 스키마 + paper_ 접두사~~ | ~~paper_trading 스키마~~ | ~~해결됨~~ | ~~DESIGN.md v1.1에서 이미 수정되어 있었음~~ |
| 3 | docker-compose.yml 서비스 | 4개 (db+redis+backend+frontend) | 2개 (db+redis만) | 낮음 | 개발 편의상 현행 유지 |
| 4 | strategies/{id} 경로변수 | id (숫자) | 읽기=name(문자열), CRUD=id(숫자) | 낮음 | 기능상 문제없음 |
| 5 | virtual_balance.strategy_name | VARCHAR(100) | VARCHAR(50) | 낮음 | 실질적 문제 없음 |

---

## 3. 보강 필요 항목 (Priority 순)

### 3.1 P0 - 즉시 조치 (설계 정합성)

| # | 항목 | 영역 | 작업 내용 | 예상 공수 |
|---|------|------|-----------|-----------|
| 1 | ~~DESIGN.md 불일치 수정~~ | ~~문서~~ | ~~완료: DESIGN.md v1.2로 업데이트 (metrics 엔드포인트 통합, Config 클래스 명세 정정)~~ | ~~완료~~ |

### 3.2 P1 - 품질 개선 (Phase 1~3.5 완성도 향상)

| # | 항목 | 영역 | 작업 내용 | 예상 공수 |
|---|------|------|-----------|-----------|
| 2 | ~~공통 UI 컴포넌트 분리~~ | ~~Frontend~~ | ~~완료: Button/Card/Badge/Spinner + index.ts 생성 (2026-03-08)~~ | ~~완료~~ |
| 3 | ~~Header 컴포넌트 분리~~ | ~~Frontend~~ | ~~완료: Header.tsx 생성, 다크모드 토글 포함 (2026-03-08)~~ | ~~완료~~ |
| 4 | ~~Phase 1 전략 Config 클래스~~ | ~~Backend~~ | ~~완료: 이미 존재, fromParams(Map) 팩토리 메서드 추가 (2026-03-08)~~ | ~~완료~~ |
| 5 | ~~전략 설정 POST/PUT/PATCH 실서버 연동~~ | ~~Frontend~~ | ~~완료: MSW handlers에서 전략 POST/PUT/PATCH 핸들러 제거, 실서버 직접 연결 (2026-03-08)~~ | ~~완료~~ |

### 3.3 P2 - 기능 보강 (Phase 3 미완성 항목)

| # | 항목 | 영역 | 작업 내용 | 예상 공수 |
|---|------|------|-----------|-----------|
| 8 | ~~추가 전략 로직 완성~~ | ~~Backend~~ | ~~완료: RSI(다이버전스), MACD(히스토그램), Supertrend(밴드 전환), ATR Breakout(변동성 돌파), Orderbook Imbalance(이중 모드) — 테스트 58개 통과 (2026-03-08)~~ | ~~완료~~ |
| 9 | ~~시장 상태 필터 <-> 전략 자동 스위칭~~ | ~~Backend~~ | ~~완료: MarketRegimeFilter + MarketRegimeAwareScheduler(1시간 주기), manualOverride 플래그, V11 마이그레이션 (2026-03-08)~~ | ~~완료~~ |
| 10 | ~~DESIGN.md 전략 6종 vs 구현 5종 차이~~ | ~~Backend~~ | ~~완료: StochasticRsiStrategy 스켈레톤 + Config 생성, StrategyRegistry 등록, MarketRegimeFilter 반영 (2026-03-08)~~ | ~~완료~~ |
| 11 | ~~RedisConfig 클래스~~ | ~~Backend~~ | ~~완료: JSON 직렬화 + 캐시별 TTL 설정 (2026-03-08)~~ | ~~완료~~ |
| 12 | ~~SchedulerConfig 클래스~~ | ~~Backend~~ | ~~완료: 전용 스레드풀 3개, Graceful shutdown, 에러 핸들러 (2026-03-08)~~ | ~~완료~~ |

### 3.4 P3 - 설계 개선 (장기)

| # | 항목 | 영역 | 작업 내용 | 예상 공수 |
|---|------|------|-----------|-----------|
| 13 | Custom Hooks 분리 | Frontend | useBacktest, useStrategies, usePaperTrading 훅 추출 (현재 api.ts 직접 호출) | 3h |
| 14 | Zustand UI Store | Frontend | sidebarOpen, selectedTheme 등 UI 상태 관리 (현재 미사용) | 1h |
| 15 | 통합 테스트 | Backend | web-api 모듈 API 통합 테스트 (Docker 환경 필요) | 5h |
| 16 | E2E 테스트 | Frontend | 핵심 워크플로우 Playwright 테스트 | 5h |

---

## 4. 서브에이전트 현황 점검

### 4.1 에이전트 파이프라인

```
SparkAI(01) -> PLAN(02) -> Design(03) -> Do-Backend(04a) + Do-Frontend(04b) -> Check(05) -> Report(06)
```

### 4.2 에이전트별 실행 상태

| 에이전트 | 입력 문서 | 출력 문서 | 상태 |
|----------|-----------|-----------|------|
| SparkAI (01) | 사용자 대화 | IDEA.md | 완료 |
| PLAN (02) | IDEA.md | PLAN.md | 완료 |
| Design (03) | PLAN.md | DESIGN.md v1.1 | 완료 (v1.1 업데이트 완료) |
| Do-Backend (04a) | DESIGN.md | PHASE1_BACKEND.md, PHASE3_BACKEND.md, PHASE3_5_BACKEND.md | 완료 (Phase 1~3.5) |
| Do-Frontend (04b) | DESIGN.md + API 스펙 | FRONTEND_GUIDE.md, FRONTEND_PHASE3_GUIDE.md, PHASE3_5_FRONTEND.md, FRONTEND_REALSERVER_GUIDE.md | 완료 (Phase 2~3.5) |
| Check (05) | DESIGN.md + 코드 | CHECK_RESULT.md | 완료 (v1.1 기준 재검증) |
| Report (06) | 전체 문서 | 미생성 | 미실행 |

### 4.3 에이전트 SKILL.md 점검

| 에이전트 | SKILL.md 상태 | 비고 |
|----------|---------------|------|
| 01-spark-ai | 정상 | 브레인스토밍 역할 |
| 02-plan | 정상 | 계획 수립 역할 |
| 03-design | 정상 | API/DB/UI 설계 역할 |
| 04-do (deprecated) | 비활성 | 04a/04b로 분리됨 |
| 04a-do-backend | 정상 | Phase별 구현 범위 명확, 모델 권장 포함 |
| 04b-do-frontend | 정상 | 대용량 데이터 주의사항, MSW 전략 포함 |
| 05-check | 정상 | 비교 매트릭스 + 피드백 루프 |
| 06-report | 정상 | 최종 보고서 생성 |

---

## 5. 문서 체계 점검

### 5.1 현재 docs/ 문서 목록

| 문서 | 역할 | 최종 수정 | 상태 |
|------|------|-----------|------|
| IDEA.md | 아이디어 정의 | 2026-03-05 | 최종 |
| PLAN.md | 개발 계획서 | 2026-03-05 | 최종 |
| DESIGN.md | 기술 설계서 v1.1 | 2026-03-07 | **수정 필요** (불일치 3건) |
| CHECK_RESULT.md | 검증 결과 | 2026-03-07 | 최종 |
| PHASE1_BACKEND.md | Phase 1 백엔드 구현 기록 | 2026-03-06 | 최종 |
| PHASE3_BACKEND.md | Phase 3 백엔드 구현 기록 | 2026-03-06 | 최종 |
| PHASE3_5_BACKEND.md | Phase 3.5 백엔드 구현 기록 | 2026-03-06 | 최종 |
| FRONTEND_GUIDE.md | Phase 2 프론트 가이드 | 2026-03-06 | 최종 |
| FRONTEND_PHASE3_GUIDE.md | Phase 3/3.5 프론트 가이드 | 2026-03-06 | 최종 |
| FRONTEND_REALSERVER_GUIDE.md | 실서버 전환 가이드 | 2026-03-06 | 최종 |
| PHASE3_5_FRONTEND.md | Phase 3.5 프론트 구현 기록 | 2026-03-06 | 최종 |

### 5.2 누락된 문서

| 문서 | 필요성 | 우선순위 |
|------|--------|----------|
| PHASE2_FRONTEND.md | Phase 2 프론트엔드 구현 완료 기록 (FRONTEND_GUIDE.md가 가이드 역할만 수행) | 낮음 |
| PHASE3_FRONTEND.md | Phase 3 프론트엔드 구현 완료 기록 (PHASE3_5_FRONTEND.md에 통합) | 불필요 |
| REPORT.md | 06-report 에이전트 최종 보고서 | 보류 (Phase 4 이후 또는 현 시점 생성 가능) |

---

## 6. 종합 평가

### 6.1 강점
- Phase 1~3.5 전체 **84% 완료** (CHECK_RESULT.md 기준 87항목 중 73항목)
- 프론트-백 API 연동 **100% 일치** (17개 엔드포인트 전부 매칭)
- 인프라 **100% 완료** (Docker, Flyway, CI/CD 준비)
- 설계서 초과 품질 항목 다수 (FillSimulator, MSW, SwaggerConfig, 멀티세션 등)

### 6.2 핵심 리스크
1. **추가 전략 5종이 스켈레톤 상태** - 백테스팅/Paper Trading에서 실질적 검증 불가
2. **시장 상태 필터 <-> 전략 자동 스위칭 미연동** - IDEA.md 핵심 차별화 포인트 미작동
3. **DESIGN.md에 명시된 전략 10종 중 실제 로직 완성은 4종** - Phase 3 목표 미달

### 6.3 권장 다음 단계

```
1단계: DESIGN.md 불일치 수정 (P0, 0.5h)
    |
2단계: 프론트엔드 품질 개선 (P1, 8h)
    |-- 공통 UI 컴포넌트 (4h)
    |-- Header 분리 (2h)
    |-- 전략 설정 실서버 연동 (2h)
    |
3단계: 전략 로직 완성 (P2, 14h)
    |-- 5종 스켈레톤 -> 실제 로직 (10h)
    |-- 시장 상태 필터 연동 (4h)
    |
4단계: 백엔드 Config 보강 (P2, 4h)
    |-- Phase 1 전략 Config 클래스 (2h)
    |-- RedisConfig + SchedulerConfig (2h)
    |
5단계: Report 에이전트 실행 또는 Phase 4 진입 판단
```

**총 예상 보강 공수: 약 26.5시간**

---

작성: 개발 상태 검증
기준: 2026-03-08
