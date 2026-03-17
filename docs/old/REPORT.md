# CryptoAutoTrader - 최종 보고서

## 문서 정보
- 작성일: 2026-03-08
- 버전: 1.0
- 기반 문서: IDEA.md, PLAN.md, DESIGN.md v1.2, CHECK_RESULT.md, DEV_STATUS_REVIEW_v2.md
- 작성: Report 에이전트

---

## 1. 경영진 요약 (Executive Summary)

### 한눈에 보기

| 항목 | 내용 |
|------|------|
| 프로젝트명 | CryptoAutoTrader |
| 목적 | Upbit 기반 가상화폐 자동매매 시스템 -- 백테스팅으로 전략을 검증하고, 검증된 전략만 실전에 투입하는 보수적 투자 플랫폼 |
| 개발 기간 | 2026-03-05 ~ 2026-03-08 (Phase 1~3.5) |
| 현재 완성도 | Phase 1~3.5: **100%** |
| Phase 4 진입 권장 | **GO** |

### 해결하는 문제
- 감정적 매매로 인한 반복적 손실
- 24시간 시장 감시의 물리적 한계
- 전략 유효성을 사전에 검증할 수단 부재
- 여러 코인/전략 동시 운용 시 복잡성

### 핵심 성과
- 10종 매매전략 완전 구현 및 백테스팅 검증 체계 확립
- Look-Ahead Bias 차단, Walk-Forward Test, FillSimulator 등 백테스팅 신뢰성 확보
- 웹 대시보드를 통한 전략 설정/비교/분석 원스톱 환경 구축
- Paper Trading(모의투자) 멀티세션으로 실전 전 최종 검증 가능
- 시장 상태(추세/횡보/고변동성) 자동 감지 및 전략 자동 스위칭
- Docker 기반 운영 환경 완비

### Phase별 진행률

```
전체 진행률: ██████████ 100% (Phase 1~3.5)

Phase 1 (백테스팅 엔진):    ██████████ 100%
Phase 2 (웹 대시보드):      ██████████ 100%
Phase 3 (전략 개선/추가):   ██████████ 100%
Phase 3.5 (Paper Trading):  ██████████ 100%
인프라:                      ██████████ 100%
Phase 4 (실전매매):          ░░░░░░░░░░   0%  (미착수, 예정)
```

---

## 2. 프로젝트 개요

### 2.1 목적

Upbit API를 활용한 가상화폐 자동매매 시스템. 과거 데이터로 전략을 백테스팅하여 성과를 비교/검증한 뒤, 모의투자를 거쳐, 검증된 전략만 실전 자동매매에 투입하는 보수적/안정적 1인용 트레이딩 플랫폼이다.

### 2.2 기술 스택

| 영역 | 기술 | 선정 사유 |
|------|------|-----------|
| Backend | Spring Boot 3.2 + Java 17 | 안정성, 풍부한 생태계, 강타입 언어로 금융 로직 안전성 |
| Frontend | Next.js 14 + React 18 + TypeScript | SSR/SEO, App Router, 타입 안전성 |
| Styling | Tailwind CSS (다크 모드 기본) | 트레이딩 대시보드 특성에 적합한 다크 테마 |
| Database | PostgreSQL 15 + TimescaleDB | 시계열 데이터 성능/압축, 확장성 |
| Cache | Redis 7 | API Rate Limit 방어, 시세 캐싱, 캐시별 TTL 분리 |
| Migration | Flyway 9 | 버전 관리된 스키마 마이그레이션 (V1~V11) |
| Build | Gradle 8 (멀티모듈) | core-engine / strategy-lib / exchange-adapter / web-api 분리 |
| Deploy | Docker + Docker Compose | 개발/운영 환경 분리, 이식성 |

### 2.3 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Client (Web Browser)                             │
└───────────────────────────┬─────────────────────────────────────────┘
                            │ HTTPS
                            v
┌─────────────────────────────────────────────────────────────────────┐
│              Next.js Frontend (crypto-trader-frontend)               │
│          React 18 + TypeScript + Tailwind CSS (Dark Mode)            │
│                         Port: 3000                                   │
└───────────────────────────┬─────────────────────────────────────────┘
                            │ REST API
                            v
┌─────────────────────────────────────────────────────────────────────┐
│                  Spring Boot Backend (web-api)                       │
│                      Java 17 + JPA                                   │
│                        Port: 8080                                    │
├─────────────────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌───────────────┐  ┌──────────────────────────┐  │
│  │ core-engine  │  │ strategy-lib  │  │   exchange-adapter       │  │
│  │              │  │               │  │                          │  │
│  │ BacktestEng. │  │ 10 Strategies │  │ Upbit REST Client        │  │
│  │ WalkForward  │  │ Registry      │  │ (Phase 4: WebSocket)     │  │
│  │ Metrics      │  │ Config        │  │ (Phase 4: OrderEngine)   │  │
│  │ RegimeDetect │  │ Indicators    │  │                          │  │
│  │ RiskEngine   │  │               │  │                          │  │
│  └──────────────┘  └───────────────┘  └──────────────────────────┘  │
├─────────────────────────────────────────────────────────────────────┤
│  ┌──────────────────┐   ┌─────────────────────────────────────────┐ │
│  │ TimescaleDB      │   │  Redis                                  │ │
│  │ (PostgreSQL 15)  │   │  Cache + (Phase 4: Event Bus)           │ │
│  │ Port: 5432       │   │  Port: 6379                             │ │
│  └──────────────────┘   └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.4 Gradle 멀티모듈 구조

```
crypto-auto-trader/
├── core-engine/          # 백테스팅 엔진, 성과 지표, 시장 상태 필터, 리스크 엔진
├── strategy-lib/         # 전략 인터페이스 + 10개 전략 구현체 + Config 클래스
├── exchange-adapter/     # Upbit REST/WebSocket 연동, 주문 실행 엔진
├── web-api/              # REST API 컨트롤러, Spring Boot 메인, Flyway, Config
└── crypto-trader-frontend/  # Next.js 프론트엔드
```

- `strategy-lib`는 `core-engine`에만 의존, 거래소 구현과 무관
- `exchange-adapter` 분리로 향후 타 거래소 연동 시 교체만 하면 됨

---

## 3. Phase별 개발 결과

### 3.1 Phase 1 -- 백테스팅 엔진 (100%)

백테스팅의 신뢰성을 최우선으로 설계하여, 실전 투입 전 전략 검증의 핵심 인프라를 구축했다.

**구현 완료 항목:**
- Upbit REST API 캔들 데이터 수집기 (분봉/시봉/일봉)
- TimescaleDB hypertable + 압축 정책으로 대용량 시계열 데이터 처리
- BacktestEngine: Look-Ahead Bias 차단 (다음 캔들 시가 기준 체결)
- WalkForwardTestRunner: Overfitting 방지 (In-Sample/Out-of-Sample 분할)
- FillSimulator: Market Impact + Partial Fill로 백테스트 현실성 강화
- MetricsCalculator: 8종 성과 지표 (수익률, 승률, MDD, Sharpe, Sortino, Calmar, Win/Loss Ratio, Recovery Factor)
- MarketRegimeDetector: ADX 기반 시장 상태 감지 (TREND/RANGE/VOLATILE)
- 초기 4전략: VWAP, EMA Cross, Bollinger Band, Grid Trading
- 3단 로그 시스템: System/Strategy/Trade 분리 (Logback)
- AsyncConfig: 3개 스레드 풀 분리 (시세/주문/일반)

**특장점:**
- Look-Ahead Bias 원천 차단으로 백테스트 결과의 과도한 낙관 방지
- Walk-Forward Test로 과적합(Overfitting) 전략 사전 필터링
- FillSimulator로 실전과의 괴리 최소화 (슬리피지, 부분 체결 시뮬레이션)

### 3.2 Phase 2 -- 웹 대시보드 (100%)

백테스팅 설정/실행/분석을 웹 UI로 제공하여 사용 편의성을 확보했다.

**구현 완료 항목:**
- 대시보드 메인 (/) -- 요약 카드 + 최근 백테스트 이력
- 백테스트 설정/실행 (/backtest/new) -- 전략/코인/기간/파라미터 선택
- 백테스트 결과 상세 (/backtest/[id]) -- 누적 수익률 차트 + 성과 지표
- 전략 비교 (/backtest/compare) -- 최대 6개 백테스트 병렬 비교
- Walk-Forward UI (/backtest/walk-forward) -- Overfitting 검증
- 데이터 수집 관리 (/data)
- 전략 로그 조회 (/logs) -- 페이지네이션 지원
- Sidebar 네비게이션 (Phase별 메뉴, Phase 4 비활성 표시)
- 다크 모드 (ThemeProvider, 기본 dark, localStorage 유지)
- Header 컴포넌트 (다크모드 토글)
- 공통 UI 컴포넌트 (Button/Card/Badge/Spinner)

### 3.3 Phase 3 -- 전략 개선 및 추가 (100%)

초기 4전략에 6종을 추가하여 총 10종 전략 라이브러리를 완성했다.

**추가 전략 6종:**
- RSI (다이버전스 감지)
- MACD (히스토그램 분석)
- Supertrend (밴드 전환 기반)
- ATR Breakout (변동성 돌파)
- Orderbook Imbalance (호가창 불균형)
- Stochastic RSI (크로스오버 신호)

**시장 상태 연동:**
- MarketRegimeFilter: 시장 상태별 적합/비적합 전략 매핑 테이블
- MarketRegimeAwareScheduler: 1시간 주기 자동 스위칭 (manualOverride=false 전략만 대상)
- 수동 오버라이드 기능으로 운영자 개입 가능

### 3.4 Phase 3.5 -- Paper Trading (100%)

실전 투입 전 최종 검증 단계로, 실시간 시세 기반 가상 매매를 지원한다.

**구현 완료 항목:**
- paper_trading 스키마 분리 (실전 데이터 오염 방지)
- 멀티세션 지원 (최대 5개 동시 실행) -- 설계 초과 품질
- PaperTradingService: 전략 실행 + 가상 체결
- 세션 상세 페이지: 캔들 차트 + 매수/매도 마커
- 모의투자 이력 관리
- 매도 시 매수단가/실현손익/수익률 자동 제공
- 삭제 기능: 백테스트 이력 + 모의투자 이력 (단건/다건, @Modifying @Query 직접 DELETE)

---

## 4. 기술적 성과

### 4.1 전략 10종 구현

| # | 전략 | 유형 | 핵심 지표 | 적합 시장 |
|---|------|------|-----------|-----------|
| 1 | VWAP | 역추세 | 거래량 가중 평균 가격 대비 편차 | RANGE |
| 2 | EMA Cross | 추세 추종 | EMA(9)/EMA(21) 골든/데드크로스 | TREND |
| 3 | Bollinger Band | 평균 회귀 | %B (밴드 이탈 후 복귀) | RANGE |
| 4 | Grid Trading | 분할 매수/매도 | 가격 구간별 그리드 주문 | RANGE |
| 5 | RSI | 모멘텀 | RSI 과매수/과매도 + 다이버전스 | RANGE/TREND |
| 6 | MACD | 모멘텀 | MACD/Signal 크로스 + 히스토그램 | TREND |
| 7 | Supertrend | 추세 추종 | ATR 기반 밴드 전환 | TREND |
| 8 | ATR Breakout | 변동성 돌파 | ATR 배수 돌파 진입 | VOLATILE |
| 9 | Orderbook Imbalance | 호가 분석 | 매수/매도 호가 비율 불균형 | VOLATILE |
| 10 | Stochastic RSI | 크로스오버 | %K/%D 크로스 (과매수/과매도 구간) | RANGE/TREND |

### 4.2 백테스팅 엔진 신뢰성

| 기능 | 설명 | 효과 |
|------|------|------|
| Look-Ahead Bias 차단 | 신호 발생 캔들의 다음 캔들 시가에 체결 | 미래 정보 참조 오류 원천 방지 |
| Walk-Forward Test | In-Sample/Out-of-Sample 구간 분할 | Overfitting 전략 사전 필터링 |
| FillSimulator | Market Impact + Partial Fill 시뮬레이션 | 실전 괴리 최소화 |
| 슬리피지 파라미터 | 사용자 조정 가능 (기본 0.1~0.2%) | 보수적 수익률 산출 |
| 수수료 반영 | Upbit 0.05% 매매 수수료 자동 반영 | 현실적 수익률 계산 |

### 4.3 시장 상태 자동 스위칭

```
시장 데이터 → MarketRegimeDetector (ADX 분석)
    │
    ├── TREND (추세장)    → EMA Cross, MACD, Supertrend 활성화
    ├── RANGE (횡보장)    → VWAP, Bollinger, Grid, RSI 활성화
    └── VOLATILE (고변동) → ATR Breakout, Orderbook Imbalance 활성화
    │
    └── MarketRegimeAwareScheduler: 1시간 주기 자동 전환
        (manualOverride=true 전략은 스위칭 대상 제외)
```

### 4.4 테스트 커버리지

| 구분 | 수량 | 프레임워크 | 범위 |
|------|------|-----------|------|
| 단위 테스트 | 58개 | JUnit 5 | 전략 5종 신규 + 기존 16개 + MarketRegimeFilter 13개 |
| 통합 테스트 | 34개 | Spring Boot Test + H2 + NoOpCache | Backtest 9, Strategy 14, Data 9, System 2 |
| E2E 테스트 | 31개 | Playwright + MSW | Navigation 9, Theme 6, Backtest 9, Strategies 7 |
| **합계** | **123개** | | |

### 4.5 코드 규모

| 영역 | 파일 수 | 코드 라인 수 |
|------|---------|-------------|
| Backend (Java) | 113 | ~8,980 |
| Frontend (TypeScript/TSX) | 48 | ~4,665 |
| DB Migration (SQL) | 11 | ~460 |
| **합계** | **172** | **~14,105** |

---

## 5. 품질 지표

### 5.1 설계-구현 일치도

CHECK_RESULT.md 검증 및 DEV_STATUS_REVIEW_v2.md 최종 확인 기준:

| 구분 | 현황 |
|------|------|
| 설계-구현 불일치 항목 | 모두 해결 또는 허용 처리 |
| 프론트-백 API 경로 일치율 | 100% (21개 엔드포인트 전수 확인) |
| 공통 응답 형식 (ApiResponse) | 일치 (success, data, error 구조) |
| DB Entity-테이블 매핑 | 완료 (Phase 1~3.5 범위 내 전수) |

### 5.2 코드 구조 품질

- Gradle 멀티모듈로 관심사 분리 (core-engine / strategy-lib / exchange-adapter / web-api)
- 전략 인터페이스 + Registry 패턴으로 전략 추가/제거 유연성 확보
- Custom Hooks 20개 분리 (useBacktest 6, useStrategies 5, usePaperTrading 7, useDataCollection 3)
- Zustand UI Store (사이드바 collapse + persist)
- GlobalExceptionHandler로 전역 예외 처리
- SwaggerConfig로 API 문서 자동 생성
- 삭제 기능: 백테스트 이력 + 모의투자 이력 (단건/다건, @Modifying @Query 직접 DELETE)
- RedisConfig: JSON 직렬화 + 캐시별 TTL 분리 (ticker 1초, candle 60초, strategyConfig 10분, backtestResult 30분)
- SchedulerConfig: 전용 스레드풀 3개 + Graceful shutdown 30초

### 5.3 인프라 품질

- Multi-stage Dockerfile (Backend: eclipse-temurin:17, Frontend: node:20-alpine + standalone)
- 개발용 docker-compose.yml (DB + Redis) / 운영용 docker-compose.prod.yml (4개 서비스)
- .env.example로 환경변수 문서화
- Flyway V1~V11로 버전 관리된 스키마 마이그레이션

---

## 6. 설계 vs 구현 차이점

CHECK_RESULT.md에서 식별된 불일치 항목의 최종 처리 현황:

| # | 항목 | 설계 | 구현 | 최종 상태 |
|---|------|------|------|-----------|
| 1 | GET /backtest/{id}/metrics | 별도 엔드포인트 | GET /{id}에 metrics 포함 | **해결**: DESIGN.md v1.2에서 통합 방식으로 설계서 수정 |
| 2 | Paper Trading 스키마 위치 | public 스키마 + paper_ 접두사 | paper_trading 스키마 | **해결**: DESIGN.md v1.1에서 수정 완료 |
| 3 | docker-compose.yml 서비스 수 | 4개 (backend/frontend 포함) | 2개 (DB + Redis만) | **허용**: 개발 편의상 유지, 운영용 prod에는 4개 포함 |
| 4 | strategies/{id} 경로변수 타입 | id (숫자) | 읽기=name, CRUD=id | **허용**: 기능상 문제 없음 |
| 5 | virtual_balance.strategy_name 길이 | VARCHAR(100) | VARCHAR(50) | **허용**: 실질적 문제 없음 |

모든 불일치 항목이 설계서 수정 또는 허용 판정으로 해소되었다.

---

## 7. Phase 4 (실전매매) 진입 판단

### 7.1 진입 체크리스트

| 항목 | 상태 | 비고 |
|------|------|------|
| 백테스팅 엔진 정상 동작 | PASS | Look-Ahead Bias 차단, Walk-Forward, FillSimulator 모두 동작 |
| 전략 10종 로직 완성 | PASS | VWAP/EMA/Bollinger/Grid/RSI/MACD/Supertrend/ATR Breakout/Orderbook Imbalance/Stochastic RSI |
| Paper Trading으로 전략 검증 가능 | PASS | 멀티세션, 가상 체결, 손익 추적 |
| 시장 상태 필터 자동 스위칭 | PASS | MarketRegimeFilter + MarketRegimeAwareScheduler |
| 리스크 관리 엔진 (RiskEngine) | PASS | 손실 한도 체크, 포트폴리오 관리 |
| 운영 Docker 환경 | PASS | docker-compose.prod.yml, multi-stage Dockerfile |
| 테스트 커버리지 | PASS | 단위 58 + 통합 34 + E2E 31 = 123개 |
| 설계-구현 정합성 | PASS | 모든 불일치 해결/허용 |

### 7.2 Phase 4 진입 권장 여부: GO

Phase 1~3.5의 모든 기능이 100% 완성되었고, 설계-구현 정합성이 확보되었으며, 테스트 커버리지가 충분하다. Phase 4 실전매매 개발을 시작할 수 있는 상태이다.

### 7.3 Phase 4에서 구현할 내용

#### 이벤트 기반 아키텍처
```
Market Data Service → Event Bus (Redis Pub/Sub) → Strategy Engine → Signal Engine → Risk Engine → Order Execution Engine → Upbit API
```

#### 핵심 구현 항목

| 항목 | 설명 |
|------|------|
| Upbit WebSocket 실시간 시세 | WebSocket 클라이언트로 실시간 시세 수신, Redis 캐싱 |
| 주문 실행 엔진 (OrderExecutionEngine) | 6단계 상태 머신 (PENDING -> SUBMITTED -> PARTIAL_FILLED -> FILLED/CANCELLED/FAILED) + 중복 주문 방지 |
| 실시간 포지션/손익 관리 | 평균단가 자동 재계산, 미실현/실현 PnL 추적 |
| Exchange Health Monitor | API latency 모니터링, WebSocket 자동 재연결, 장애 시 주문 중단 |
| 리스크 관리 강화 | 일일 손실 한도, 연속 손실 쿨다운, 포트폴리오 동조화 방지, 긴급 정지 |
| 텔레그램 알림 | 일일 리포트 (06:30), 주간 리포트 (월요일), 긴급 알림 (즉시) |
| API Key 암호화 | AES-256으로 Upbit API Key 암호화 저장 |
| 운영 제어 UI | 실시간 매매 정지/재개, 전략 교체, 자금 재배분 |

### 7.4 진입 전 권장 사항

1. **Paper Trading 최소 1주일 운영 검증**: 주중/주말 모두 포함하여 전략별 실시간 시세 기반 성과 확인
2. **백테스트 vs Paper Trading 수익률 괴리 확인**: 5% 이내 목표
3. **체결 지연(Latency) 실측**: API 응답 시간, WebSocket 지연 측정
4. **Upbit API Rate Limit 충돌 테스트**: 429 에러 빈도 확인
5. **시스템 재시작 시 포지션 복구 테스트**
6. **급등락 상황 (BTC +-10% 이상) 대응 시나리오 수립**

---

## 8. 리스크 및 주의사항

### 8.1 실전매매 관련 리스크

| 리스크 | 영향도 | 발생확률 | 대응 방안 |
|--------|--------|----------|-----------|
| 시장 리스크 (예측 불가 급락) | 매우 높음 | 중간 | 코인별 손절 라인, 일일 최대 손실 한도, 긴급 정지 |
| Upbit API 장애/지연 | 높음 | 중간 | Exchange Health Monitor, WebSocket 자동 재연결, 장애 시 주문 중단 |
| 슬리피지 (체결가 괴리) | 중간 | 높음 | 슬리피지 파라미터 보수적 설정, 거래량 부족 코인 진입 차단 |
| 중복 주문 | 매우 높음 | 낮음 | 상태 머신 + 이벤트 기반 아키텍처로 원천 차단 |
| 백테스트 vs 실전 괴리 | 높음 | 높음 | Paper Trading 1주 검증, Walk-Forward Test, FillSimulator |
| Overfitting (과거만 맞는 전략) | 매우 높음 | 높음 | Walk-Forward Test 의무화, 검증 구간 성과 하락 시 경고 |
| Redis 단일 장애점 | 중간 | 낮음 | Redis Sentinel 또는 DB 직접 조회 fallback |
| 포트폴리오 동조화 | 높음 | 높음 | 동시 진입 포지션 수 제한 (BTC 연동성 대응) |

### 8.2 권장 안전장치

**필수 (Phase 4 구현 시 반드시 포함):**
1. 코인별 최대 투자금 한도
2. 일일/주간/월간 최대 손실 한도
3. 포지션별 손절 라인
4. 연속 손실 시 쿨다운 (일정 시간 매매 정지)
5. 급등/급락 감지 시 자동 매매 중단
6. 수동 긴급 정지 기능
7. 중복 주문 방지 (상태 머신)

**권장 (운영 안정성 향상):**
1. 수익 재투자 비율 설정 (수익의 N%만 재투자)
2. 거래량 부족 시 진입 차단
3. 동시 진입 포지션 수 제한 (최대 3개)
4. 미체결 주문 자동 취소 (N분 후 시장가 전환)
5. 텔레그램 긴급 알림 (연속 손실, API 에러, 급락 정지)

---

## 9. 결론

CryptoAutoTrader 프로젝트는 Phase 1(백테스팅 엔진)부터 Phase 3.5(Paper Trading)까지 모든 기능을 100% 완성했다. 10종의 매매전략, 신뢰성 높은 백테스팅 엔진(Look-Ahead Bias 차단, Walk-Forward Test, FillSimulator), 시장 상태 자동 스위칭, 웹 대시보드, 모의투자 멀티세션이 모두 동작하며, 123개 테스트(단위 58 + 통합 34 + E2E 31)로 품질을 검증했다.

설계서(DESIGN.md v1.2)와 구현 간의 모든 불일치가 해결 또는 허용 처리되었고, 프론트-백 API 연동이 21개 엔드포인트에서 100% 일치한다.

**Phase 4(실전매매) 진입을 권장한다(GO).** 다만, 실전 투입 전 Paper Trading으로 최소 1주간 검증하고, 백테스트 대비 수익률 괴리가 5% 이내인지 확인한 후 실전 전환할 것을 강력히 권고한다. Phase 4에서는 이벤트 기반 아키텍처, 주문 실행 엔진(상태 머신), Exchange Health Monitor, 다층 리스크 관리를 구현하여 안전한 24시간 자동매매 운영 환경을 구축한다.

---

## 부록

### A. 에이전트 실행 이력

| 에이전트 | 출력 문서 | 상태 |
|----------|-----------|------|
| SparkAI (01) | IDEA.md | 완료 |
| PLAN (02) | PLAN.md | 완료 |
| Design (03) | DESIGN.md v1.2 | 완료 |
| Do-Backend (04a) | PHASE1_BACKEND.md, PHASE3_BACKEND.md, PHASE3_5_BACKEND.md | 완료 |
| Do-Frontend (04b) | FRONTEND_GUIDE.md, FRONTEND_PHASE3_GUIDE.md, PHASE3_5_FRONTEND.md, FRONTEND_REALSERVER_GUIDE.md | 완료 |
| Check (05) | CHECK_RESULT.md | 완료 |
| Report (06) | REPORT.md (본 문서) | 완료 |

### B. 참고 문서

- [IDEA.md](./IDEA.md) -- 아이디어 문서 (프로젝트 배경, 핵심 기능, 차별화)
- [PLAN.md](./PLAN.md) -- 프로젝트 계획서 (범위, 마일스톤, 리스크)
- [DESIGN.md](./DESIGN.md) -- 기술 설계서 v1.2 (아키텍처, API, DB, UI)
- [CHECK_RESULT.md](./CHECK_RESULT.md) -- 검증 결과 보고서
- [DEV_STATUS_REVIEW_v2.md](./DEV_STATUS_REVIEW_v2.md) -- 개발 상태 검증 v2.0

### C. 프로젝트 디렉토리 구조

```
crypto-auto-trader/
├── build.gradle                    # Root Gradle 설정
├── settings.gradle                 # 멀티모듈 설정
├── docker-compose.yml              # 개발용 (DB + Redis)
├── docker-compose.prod.yml         # 운영용 (4개 서비스)
├── core-engine/                    # 백테스팅, 성과 지표, 시장 상태 필터
├── strategy-lib/                   # 10종 전략 구현체
├── exchange-adapter/               # Upbit REST/WebSocket 연동
├── web-api/                        # REST API + Flyway + Config
│   └── src/main/resources/db/migration/  # V1~V11 마이그레이션
├── crypto-trader-frontend/         # Next.js 프론트엔드
│   ├── app/                        # App Router 페이지
│   ├── components/                 # UI/Layout/기능 컴포넌트
│   ├── hooks/                      # Custom Hooks (20개)
│   ├── stores/                     # Zustand Store
│   └── lib/                        # API 클라이언트, 유틸리티
└── docs/                           # 프로젝트 문서
    ├── IDEA.md
    ├── PLAN.md
    ├── DESIGN.md
    ├── CHECK_RESULT.md
    ├── DEV_STATUS_REVIEW_v2.md
    └── REPORT.md (본 문서)
```

---
작성일: 2026-03-08
작성: Report 에이전트
