# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 작업이 끝날 때마다 `## 다음 할 일`과 `## 최근 변경사항`을 반드시 업데이트한다.
> **마지막 갱신**: 2026-03-21 (Upbit API 테스트 페이지 보강)

---

## 프로젝트 개요

- **서비스**: 업비트 기반 가상화폐 자동매매 시스템
- **운영 환경**: Ubuntu 서버, Docker Compose (`docker-compose.prod.yml`)
- **기술 스택**: Spring Boot 3.2 (Java 17) + Next.js 16.1.6 / React 19.2.3 (TypeScript) + TimescaleDB + Redis

### 모듈 구조

```
crypto-auto-trader/
├── web-api/          # Spring Boot 백엔드 (Gradle 멀티모듈)
│   ├── core-engine/      # 백테스팅 엔진, 리스크, 포트폴리오
│   ├── strategy-lib/     # 전략 10종
│   ├── exchange-adapter/ # Upbit REST/WebSocket
│   └── web-api/          # REST API, 스케줄러, 서비스
├── crypto-trader-frontend/  # Next.js 16.1.6 / React 19.2.3 프론트엔드
├── docs/                    # 설계 문서 및 진행 기록
└── docker-compose.prod.yml  # 운영용 (backend + frontend + db + redis)
```

---

## 개발 완료 현황

| Phase | 내용 | 완성도 |
|-------|------|--------|
| Phase 1 | 백테스팅 엔진 (BacktestEngine, WalkForward, FillSimulator, MetricsCalculator) | **100%** |
| Phase 2 | 웹 대시보드 (Next.js, 백테스트/전략/로그/데이터 UI) | **100%** |
| Phase 3 | 전략 10종 + MarketRegimeFilter + 자동 스위칭 | **100%** |
| Phase 3.5 | 모의투자 (PaperTrading) 멀티세션 | **100%** |
| Phase S1~S5 | 전략 고도화 (버그수정 → Regime/Risk → CompositeStrategy → 개별최적화 → MTF 백테스트) | **100%** |
| 인프라 | Docker, Flyway V1~V21, SchedulerConfig, RedisConfig, Spring Security | **100%** |
| Phase 4 | **실전매매** (LiveTrading) | **~98%** — 소액(1만원) 실거래 검증 완료 (2026-03-19~), ORDERBOOK WebSocket 미확인 |

### 구현된 전략 10종

VWAP / EMA Cross / Bollinger Band / Grid / RSI(다이버전스) / MACD(히스토그램) / Supertrend / ATR Breakout / Orderbook Imbalance / Stochastic RSI

### 2025 H1 백테스트 결과 요약 (KRW-BTC / KRW-ETH)

| 전략 | BTC | ETH | 비고 |
|------|-----|-----|------|
| GRID | +8.4% | +1.4% | 양코인 안정 |
| ORDERBOOK_IMBALANCE | +0.8% | +30.6% | ETH 강세 |
| ATR_BREAKOUT | -29.8% | +39.0% | ETH 전용 |
| BOLLINGER | +3.2% | -37.0% | BTC 전용 |
| EMA_CROSS | -51.2% | +23.7% | BTC/ETH 역전 |
| STOCHASTIC_RSI | -70.4% | -67.6% | 구조적 결함 |
| MACD | -58.8% | -57.6% | 추가 개선 필요 |

> 전체 결과: `docs/BACKTEST_RESULTS.md`

---

## 다음 할 일

### 즉시 (실전매매 안정화)

- [ ] `🔴 HIGH` **ORDERBOOK_IMBALANCE WebSocket 의존성 확인** — `StrategyController` 주석에 "Phase 4 WebSocket 연동 필요" 기재됨. 현재 REST 폴링으로 동작 중인지, 실전에서 신호가 정상 생성되는지 로그로 확인 필요
- [ ] `🟡 MEDIUM` **실전매매 금액 증액 계획** — 소액 1만원 → 5만원 → 10만원 단계적 증액. 1~2주 결과 확인 후 판단

### 전략 고도화 후속 (백테스트 결과 기반)

- [ ] `🔴 HIGH` **STOCHASTIC_RSI 구조 재설계 또는 제거** — ADX 필터 후에도 BTC -70.4%, ETH -67.6%. 파라미터 문제가 아닌 구조적 결함
- [ ] `🔴 HIGH` **MACD 히스토그램 기울기 필터 추가** — ADX > 25 필터로도 BTC -58.8%, ETH -57.6%
- [ ] `🟡 MEDIUM` **VWAP 임계값 재조정** — BTC 승률 0% (거래 없음). thresholdPct 2.5% → 1.5% 재테스트
- [ ] `🟡 MEDIUM` **코인별 전략 선택 최적화** — BTC: GRID+BOLLINGER / ETH: ATR_BREAKOUT+EMA_CROSS+ORDERBOOK
- [ ] `🟢 LOW` 2023~2025 전체 기간 백테스트 (현재 2025년만 결과)

---

## 최근 변경사항

### 2026-03-21 — Upbit API 테스트 페이지 보강

| 항목 | 내용 |
|------|------|
| `GET /api/v1/settings/upbit/ticker` | 현재가 조회 엔드포인트 추가 (공개 API, 인증 불필요) |
| `settingsApi.upbitTicker()` | 프론트엔드 API 함수 추가 |
| `upbit-status/page.tsx` 전면 재작성 | slate- 계열로 디자인 통일, 상태 카드 4개(API키·잔고·WebSocket·캔들), 잔고 상세(보유코인 테이블), 현재가 조회 섹션 신규 추가 |

### 2026-03-21 — 손익 대시보드 및 성과 통계 구현

| 항목 | 내용 |
|------|------|
| `PerformanceSummaryResponse` DTO | 전체 집계 + 세션별 성과 내역 (세션 수 / 승률 / 수익률 / 수수료 등) |
| `LiveTradingService.getPerformanceSummary()` | 실전매매 전 세션 PositionEntity 기반 집계 |
| `PaperTradingService.getOverallPerformance()` | 모의투자 전 세션 VirtualBalanceEntity 기반 집계 |
| `GET /api/v1/trading/performance` | 실전매매 성과 API |
| `GET /api/v1/paper-trading/performance` | 모의투자 성과 API |
| `/performance` 페이지 | 실전/모의 탭 전환, 요약 카드 7개, 세션별 테이블 |
| 사이드바 | 실전매매 그룹에 "손익 대시보드" 항목 추가 (PieChart 아이콘) |

### 2026-03-20 — 코드 보완 전체 완료

| 항목 | 내용 |
|------|------|
| null 안전 | `evaluateAndExecuteSession()` — `stopLossPct` null 시 기본값 5.0 (NPE 방어) |
| 실전매매 다중전략 | `MultiStrategyLiveTradingRequest` DTO + `POST /api/v1/trading/sessions/multi` + 프론트 체크박스 UI (2개 이상 선택 시 일괄 생성) |
| position_fee | V20 migration + `PaperPositionEntity.positionFee` + `executeBuy()`·`closePosition()` 누적 |
| version 낙관적 락 | V21 migration + `VirtualBalanceEntity.@Version` JPA 낙관적 락 |

### 2026-03-20 — 실전매매 세션별 투자비율(investRatio) 설정

- `live_trading_session.invest_ratio` 컬럼 추가 (V19, 기본 0.8000)
- `executeSessionBuy()` — 하드코딩 80% → `session.getInvestRatio()` + `maxInvestment` 절대 상한
- 세션 생성 폼에 투자비율 입력 추가 (1~100%, 기본 80%, TEST_TIMED 숨김)

**동작**: `availableKrw × investRatio`가 매수금액. `maxInvestment` 설정 시 그 값이 절대 상한.

### 2026-03-20 — 모의투자 실현손익·누적수수료 추적

- `virtual_balance`에 `realized_pnl`, `total_fee` 컬럼 추가 (V18)
- 매수/매도 체결 시마다 누적 저장 → 세션 종료 후에도 조회 가능
- 프론트엔드 SessionCard + 잔고 카드에 표시

### 2026-03-20 — 프론트엔드 로그인 인증

- Next.js middleware → `auth_session` 쿠키 미존재 시 `/login` 리다이렉트
- `AUTH_PASSWORD` / `AUTH_SECRET` 환경변수로 비밀번호 관리
- **운영서버 배포 시**: `docker-compose.prod.yml` frontend 서비스에 두 환경변수 추가 필요

### 2026-03-19~20 — 실전매매 소액 테스트 검증 완료

- TEST_TIMED / ORDERBOOK_IMBALANCE / COMPOSITE 각 1만원 매수·매도 사이클 ✅ 확인
- 2026-03-19 07:00 ~ 지속 운영 중
- 리스크 한도(`riskManagementService.checkRisk()`) BUY 진입 전 연동 완료

---

## 2026-03-17~18 주요 작업 요약

> 세부 내역은 git log 참조

| 날짜 | 작업 |
|------|------|
| 03-18 | **매수→매도 전 사이클 수정**: `@Async` 반환 타입 `void`로 변경, JSONB 타입 수정, 포지션 size=0 초기화, `syncOrderState()` CANCELLED+executed_volume>0 → FILLED 처리 |
| 03-18 | **invalid_volume_ask 근본 해결**: `resolveAskVolume()` — Upbit 잔고 조회 후 `min(포지션수량, 잔고)` 사용 |
| 03-18 | **평균단가 오계산 수정**: price타입 매수 avgFillPrice = `quantity ÷ executed_volume`, 매도 체결단가 역산 |
| 03-18 | **세션 단위 중복 주문 체크**: 기존 전역 체크 → sessionId 기준으로 변경, 세션간 포지션 데이터 혼재 수정 |
| 03-18 | **서버 로그 뷰어** / **DB 초기화 페이지** / **Upbit API 테스트 기능** 추가 (설정 메뉴) |
| 03-18 | **다중 전략 모의투자·백테스트 일괄 등록** + 프론트 체크박스 UI |
| 03-17 | **캔들 데이터 버그**: `MarketDataSyncService` 실전매매 세션 누락, `LiveTradingService` 잘못된 테이블 읽기 수정 |
| 03-17 | **Spring Security API 인증** (`Authorization: Bearer`), **Upbit 계좌 현황 페이지**, **전략 로그 아코디언 UI** |
| 03-17 | **TEST_TIMED 테스트 전략** 추가, **텔레그램 이력 DB 저장 버그** 수정, **사이드바 카테고리 구조** 개편 |
