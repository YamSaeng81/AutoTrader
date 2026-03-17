# CryptoAutoTrader - 개발 계획서

## 문서 정보
- 버전: 1.0
- 작성일: 2026-03-05
- 기반 문서: IDEA.md
- 다음 단계: Design 에이전트

---

## 1. 프로젝트 개요

### 1.1 목적
Upbit API를 활용한 가상화폐 자동매매 시스템을 구축한다. 백테스팅으로 전략을 사전 검증하고, 모의투자를 거쳐, 검증된 전략만 실전에 투입하는 보수적·안정적 투자 플랫폼.

### 1.2 배경
- 감정적 매매로 인한 반복 손실
- 24시간 시장을 사람이 감시하는 것은 불가능
- 전략의 유효성을 검증할 수단이 없어 감으로 투자
- 기존 자동매매 서비스는 유료이거나 커스터마이징 불가

### 1.3 성공 기준
- [ ] 백테스팅 엔진이 3년치 분봉 데이터로 4가지 전략을 시뮬레이션하고 성과 지표를 산출할 수 있다
- [ ] 웹 대시보드에서 백테스팅 실행/비교/분석이 가능하다
- [ ] Paper Trading에서 백테스팅 대비 수익률 괴리가 5% 이내이다
- [ ] 실전 자동매매가 24시간 무중단으로 안정 운영된다
- [ ] 리스크 관리 안전장치가 모든 단계에서 정상 작동한다
- [ ] Walk Forward Test에서 학습/검증 구간 성과 괴리가 허용 범위 이내이다 (Overfitting 아님을 확인)

---

## 2. 범위 정의

### 2.1 기능 우선순위 매트릭스

| 구분 | 기능 | 복잡도 | 우선순위 | Phase |
|------|------|--------|----------|-------|
| 필수 | Upbit 과거 데이터 수집기 (REST API → TimescaleDB) | Medium | P0 | 1 |
| 필수 | 백테스팅 실행 엔진 (수수료·슬리피지·Look-Ahead Bias 차단·Walk Forward Test) | High | P0 | 1 |
| 필수 | 초기 4전략 이식 (VWAP, EMA, 볼린저밴드, Grid) | Medium | P0 | 1 |
| 필수 | 성과 지표 계산 (수익률, 승률, MDD, 샤프, Sortino 등 8종) | Medium | P0 | 1 |
| 필수 | Market Regime 필터 (ADX 기반 추세/횡보 판별) | Medium | P0 | 1 |
| 필수 | Walk Forward Test (Overfitting 방지, 학습/검증 구간 분리) | High | P0 | 1 |
| 필수 | Gradle 멀티 모듈 구조 (core-engine, strategy-lib, exchange-adapter, web-api) | Medium | P0 | 1 |
| 필수 | 3단 로그 시스템 설계 (시스템/전략/거래) | Medium | P0 | 1 |
| 필수 | 웹 대시보드 — 백테스팅 설정/실행 UI (다크 모드) | High | P0 | 2 |
| 필수 | 전략별 성과 비교 차트 | Medium | P0 | 2 |
| 필수 | 매매 기록 조회/분석 테이블 | Low | P0 | 2 |
| 중요 | 추가 전략 6종 (RSI+MACD, ATR, DCA, Pairs, Ichimoku, Z-Score) | High | P1 | 3 |
| 중요 | 전략 파라미터 튜닝 UI | Medium | P1 | 3 |
| 중요 | 시장 상태 필터 ↔ 전략 자동 스위칭 | Medium | P1 | 3 |
| 중요 | Paper Trading 엔진 + paper_trading 스키마 분리 | High | P1 | 3.5 |
| 중요 | Paper Trading 검증 항목 체크 (괴리율, Latency, 429, 복구, 급등락) | Medium | P1 | 3.5 |
| 중요 | 이벤트 기반 아키텍처 (Event Bus → Strategy → Risk → Order) | Very High | P1 | 4 |
| 중요 | 주문 실행 엔진 (6단계 상태 머신 + 중복 주문 방지) | Very High | P1 | 4 |
| 중요 | 포지션 관리 시스템 (평균단가, 미실현/실현 PnL) | High | P1 | 4 |
| 중요 | Exchange Health Monitor (Upbit 장애 대비) | Medium | P1 | 4 |
| 중요 | 리스크 관리 엔진 (다층 안전장치, 동조화 방지) | High | P1 | 4 |
| 중요 | 텔레그램 알림 (일일 + 주간 + 긴급) | Medium | P1 | 4 |
| 선택 | 타 거래소 연동 (바이낸스 등) | High | P2 | 5 |
| 선택 | AI 파라미터 자동 최적화 (유전 알고리즘) | Very High | P3 | 5 |

### 2.2 제외 범위 (Out of Scope)
- 멀티 유저 지원: 1인 전용 시스템, 인증/권한 시스템 불필요
- 모바일 앱: 웹 대시보드로 충분 (반응형 UI로 모바일 브라우저 대응)
- 선물/마진 거래: 현물 매매만 지원 (보수적 투자 원칙)
- 타 거래소 연동: Phase 5 장기 과제로 보류

### 2.3 가정 사항
- Upbit OpenAPI가 안정적으로 제공된다 (v1 기준)
- Upbit API Rate Limit: 초당 약 10회 (Redis 캐싱으로 대응 가능)
- 3년치 분봉 데이터 수집 가능 (Upbit 캔들 API 제공 확인 필요)
- 개발자 1인이 전 과정을 진행한다

### 2.4 제약 조건
- 기술: Spring Boot 3.2 + Java 17 / Next.js 14 + TypeScript / PostgreSQL 15 + TimescaleDB
- 환경: Windows (개발) / Ubuntu Server (운영)
- 인프라: Docker + Docker Compose
- 보안: API Key AES-256 암호화 필수

---

## 3. 기술 스택 확정

### 3.1 백엔드 (Gradle 멀티 모듈)
- Framework: Spring Boot 3.2.x
- Language: Java 17
- Build: Gradle 8.x (멀티 모듈)
- ORM: Spring Data JPA
- Scheduler: Spring Scheduler
- WebSocket Client: Spring WebSocket (Upbit 실시간 시세)

**모듈 구조:**
```
crypto-auto-trader/
├── core-engine/          # 백테스팅 엔진, 성과 지표, 시장 상태 필터
├── strategy-lib/         # 전략 인터페이스 + 10개 전략 구현체
├── exchange-adapter/     # Upbit REST/WebSocket 연동, 주문 실행 엔진
├── web-api/              # REST API 컨트롤러, Spring Boot 메인
└── web-dashboard/        # Next.js 프론트엔드 (별도 프로젝트)
```
- `strategy-lib`는 `core-engine`에만 의존, 거래소 구현과 무관
- `exchange-adapter`를 분리하면 향후 Phase 5 타 거래소 연동 시 교체만 하면 됨
- `core-engine`이 중심이고, 나머지 모듈은 플러그인처럼 연결

### 3.2 프론트엔드
- Framework: Next.js 14.x
- Language: TypeScript 5.x
- UI Library: React 18.x
- Styling: Tailwind CSS 3.x (다크 모드 기본)
- Charting: Lightweight Charts 또는 ApexCharts
- State: Zustand (경량 상태 관리)

### 3.3 데이터베이스
- RDBMS: PostgreSQL 15.x + TimescaleDB 확장
- Migration: Flyway 9.x
- 스키마 분리: `public` (실전) / `paper_trading` (모의투자)

### 3.4 미들웨어
- Cache + Event Bus: Redis 7.x (Pub/Sub + 캐싱 + Rate Limit 방어)

### 3.5 외부 연동
- 거래소: Upbit OpenAPI (REST + WebSocket)
- 알림: Telegram Bot API

### 3.6 인프라
- 개발환경: Windows + VS Code
- 운영환경: Ubuntu Server 22.04+
- 컨테이너: Docker 24.x + Docker Compose 2.x
- 보안: AES-256 (API Key 암호화), 환경변수 분리

---

## 4. 마일스톤 & 일정

### 4.1 전체 타임라인

```
Phase 1          Phase 2          Phase 3        Phase 3.5      Phase 4
[백테스팅 엔진] → [웹 대시보드] → [전략 추가] → [Paper Trading] → [실전 자동매매]
    5주              4주             3주            2주              5주
```

총 예상 기간: **약 19주 (5개월)** (버퍼 1.5배 적용)

### 4.2 상세 마일스톤

#### Phase 1 — 백테스팅 엔진 (Week 1~5)

##### M1.1: 환경 구축 & 데이터 수집 (Week 1~2)
- [ ] 프로젝트 초기 구조 생성 (Spring Boot + Gradle)
- [ ] Docker Compose 설정 (PostgreSQL + TimescaleDB + Redis)
- [ ] Flyway 마이그레이션 기반 DB 스키마 생성
- [ ] Upbit REST API 연동 — 캔들 데이터 수집기 구현
- [ ] 3년치 과거 데이터 수집 스케줄러 (분봉/시봉/일봉)
- [ ] TimescaleDB hypertable 설정 및 압축 정책
- [ ] 3단 로그 시스템 기반 설계 (Logback 설정 분리)
- **산출물**: 데이터 수집기, DB 스키마, Docker 환경

##### M1.2: 백테스팅 엔진 코어 (Week 3~4)
- [ ] Gradle 멀티 모듈 구조 설정 (core-engine, strategy-lib, exchange-adapter, web-api)
- [ ] 백테스팅 실행기 프레임워크 (전략 인터페이스 정의 — strategy-lib 모듈)
- [ ] Look-Ahead Bias 차단 로직 (다음 캔들 시가 기준 매매)
- [ ] 수수료(0.05%) + 슬리피지(조정 가능) 반영 엔진
- [ ] 초기 4전략 이식: VWAP, EMA 크로스, 볼린저밴드, Grid Trading (strategy-lib)
- [ ] Market Regime 필터 구현 (ADX 기반 추세/횡보 판별 — core-engine)
- [ ] Walk Forward Test 프레임워크 구현 (Overfitting 방지):
  - 학습 구간(In-Sample)과 검증 구간(Out-of-Sample) 자동 분할
  - 예: 2019~2022 학습 → 2023 검증, 윈도우 슬라이딩 방식
  - 학습 구간 성과 vs 검증 구간 성과 비교 리포트
  - 검증 구간 성과가 학습 구간 대비 크게 하락하면 Overfitting 경고
- **산출물**: 멀티 모듈 프로젝트 구조, 백테스팅 엔진 코어, Walk Forward Test, 4개 전략 서비스

##### M1.3: 성과 지표 & API (Week 5)
- [ ] 성과 지표 계산기 (8종: 수익률, 승률, MDD, 샤프, Sortino, Calmar, Win/Loss, Recovery Factor)
- [ ] 월별 수익률 히트맵 데이터 생성
- [ ] 전략 간 비교 API
- [ ] 백테스팅 실행 REST API (전략 선택 → 실행 → 결과 반환)
- [ ] 단위 테스트 (전략 로직, 지표 계산 정확성)
- **산출물**: 성과 지표 API, 백테스팅 REST API

#### Phase 2 — 웹 대시보드 (Week 6~9)

##### M2.1: 프론트엔드 기반 & 백테스팅 UI (Week 6~7)
- [ ] Next.js 프로젝트 초기 설정 (TypeScript + Tailwind + 다크 모드)
- [ ] 레이아웃/네비게이션 구조
- [ ] 백테스팅 설정 페이지 (전략 선택, 코인, 기간, 파라미터)
- [ ] 백테스팅 실행 + 로딩/진행 상태 표시
- [ ] 백엔드 API 연동
- **산출물**: 백테스팅 설정/실행 UI

##### M2.2: 결과 시각화 & 분석 (Week 8~9)
- [ ] 성과 지표 대시보드 (카드형 지표 표시)
- [ ] 누적 수익률 차트 (Lightweight Charts / ApexCharts)
- [ ] 전략 비교 차트 (A vs B vs C 동일 기간 비교)
- [ ] 월별 수익률 히트맵
- [ ] 매매 기록 테이블 (필터/정렬/페이지네이션)
- [ ] 전략 로그 조회 (왜 이 시점에 매수/매도했는지)
- **산출물**: 결과 시각화 페이지, 매매 기록 페이지

#### Phase 3 — 전략 개선 및 추가 (Week 10~12)

##### M3.1: 초기 4전략 튜닝 (Week 10)
- [ ] VWAP 파라미터 최적화 (편차율, 기간)
- [ ] EMA 크로스 최적화 (빠른/느린 EMA 기간)
- [ ] 볼린저밴드 최적화 (기간, 표준편차 배수)
- [ ] Grid Trading 최적화 (그리드 간격, 주문 수량)
- [ ] 웹 UI에서 파라미터 편집 기능
- **산출물**: 최적화된 4개 전략, 파라미터 편집 UI

##### M3.2: 추가 전략 구현 (Week 11~12)
- [ ] RSI+MACD 복합 모멘텀 (기존 코드 개선)
- [ ] ATR 변동성 돌파 (기존 코드 개선)
- [ ] DCA + 기술적 신호 보정
- [ ] Pairs Trading (코인 쌍 스프레드)
- [ ] Ichimoku Cloud
- [ ] Mean Reversion + Z-Score
- [ ] 시장 상태 필터 ↔ 전략 자동 스위칭 연동
- **산출물**: 총 10개 전략 완성, 자동 스위칭 로직

#### Phase 3.5 — Paper Trading (Week 13~14)

##### M3.5: 모의투자 엔진 (Week 13~14)
- [ ] paper_trading 스키마 생성 (order, position, trade 테이블)
- [ ] Upbit WebSocket 실시간 시세 수신 클라이언트
- [ ] 가상 자금 매매 실행 엔진
- [ ] 포지션 관리 시스템 (평균단가, 미실현/실현 PnL)
- [ ] 백테스팅 vs Paper Trading 괴리율 자동 계산
- [ ] 검증 항목 대시보드 (Latency, 429 에러, 포지션 복구 테스트)
- [ ] 최소 1주일 연속 운영 검증 (주중·주말 포함)
- **산출물**: Paper Trading 엔진, 검증 보고서

#### Phase 4 — 실전 자동매매 (Week 15~19)

##### M4.1: 이벤트 기반 아키텍처 (Week 15~16)
- [ ] Event Bus 구현 (Redis Pub/Sub)
- [ ] Market Data Service (WebSocket → Redis 캐싱 → 이벤트 발행)
- [ ] Strategy Engine (이벤트 수신 → 전략 실행 → 신호 발행)
- [ ] Signal Engine (신호 집계 → 매매 결정)
- [ ] Risk Engine (리스크 체크 → 승인/거부)
- [ ] 전체 파이프라인 통합 테스트
- **산출물**: 이벤트 기반 파이프라인

##### M4.2: 주문 실행 & 포지션 관리 (Week 17)
- [ ] Order Execution Engine (6단계 상태 머신)
- [ ] 중복 주문 방지 로직
- [ ] 미체결 주문 관리 (타임아웃 → 시장가 전환)
- [ ] 포지션 관리 시스템 (실전 스키마)
- [ ] Upbit 주문 API 연동
- [ ] API Key AES-256 암호화 저장
- **산출물**: 주문 실행 엔진, 포지션 관리 시스템

##### M4.3: 리스크 관리 & 모니터링 (Week 18)
- [ ] 리스크 관리 엔진 (코인별 한도, 일일 손실, 쿨다운, 동조화 방지)
- [ ] Exchange Health Monitor (API latency, WebSocket 재연결, 장애 시 주문 중단)
- [ ] 운영 제어 API (정지/재개, 자금 변경, 전략 교체)
- [ ] 운영 제어 웹 UI
- **산출물**: 리스크 엔진, Health Monitor, 운영 제어 UI

##### M4.4: 알림 & 배포 (Week 19)
- [ ] 텔레그램 Bot 연동
- [ ] 일일 리포트 (06:30), 주간 리포트 (월요일), 긴급 알림 (즉시)
- [ ] Docker 이미지 빌드 (백엔드 + 프론트엔드 + Redis + PostgreSQL)
- [ ] Ubuntu 서버 배포 (docker-compose.prod.yml)
- [ ] 24시간 운영 모니터링 설정
- [ ] 실전 투입 전 최종 체크리스트 검증
- **산출물**: 알림 시스템, 운영 환경, 배포 가이드

---

## 5. 리소스 계획

### 5.1 필요 도구
- IDE: VS Code + Java Extension Pack + ESLint + Prettier
- DB Client: DBeaver (PostgreSQL + TimescaleDB 관리)
- API 테스트: Postman 또는 Thunder Client
- 버전관리: Git + GitHub
- 컨테이너: Docker Desktop (Windows 개발), Docker Engine (Ubuntu 운영)

### 5.2 외부 계정
- Upbit 개발자 API Key (OpenAPI 접근)
- Telegram Bot Token (BotFather로 생성)
- GitHub Repository

### 5.3 학습 필요 영역
- TimescaleDB hypertable/compression 설정 및 쿼리 최적화
- Upbit OpenAPI (REST + WebSocket) 사양 숙지
- Lightweight Charts 또는 ApexCharts 사용법
- Redis Pub/Sub를 활용한 이벤트 기반 설계 패턴

---

## 6. 리스크 관리

| 리스크 | 영향도 | 발생확률 | 대응방안 |
|--------|--------|----------|----------|
| Upbit API 변경/중단 | 높음 | 낮음 | API 버전 고정, 추상화 레이어로 교체 용이하게 설계 |
| 3년치 분봉 데이터 수집 제한 | 높음 | 중간 | Upbit API 캔들 조회 한도 확인, 필요 시 시봉/일봉으로 축소 |
| TimescaleDB 성능 병목 (수억 건) | 중간 | 중간 | 압축 정책 적극 활용, 파티션 전략, 오래된 데이터 다운샘플링 |
| 백테스팅 vs 실전 수익률 괴리 | 높음 | 높음 | 슬리피지 파라미터 보수적 설정, Paper Trading 단계로 검증 |
| 전략 Overfitting (과거만 잘 맞는 전략) | 매우 높음 | 높음 | Walk Forward Test로 학습/검증 구간 분리, 검증 구간 성과 하락 시 경고 |
| Upbit 서버 장애 (주문 지연/WebSocket 끊김) | 높음 | 중간 | Exchange Health Monitor + 자동 재연결 + 장애 시 주문 중단 |
| 중복 주문 대참사 | 매우 높음 | 낮음 | 상태 머신 + 이벤트 기반 아키텍처로 원천 차단 |
| 일정 지연 (1인 개발) | 중간 | 높음 | 1.5배 버퍼 적용, Phase별 독립 배포 가능 구조 |
| Redis 단일 장애점 | 중간 | 낮음 | Redis Sentinel 또는 장애 시 DB 직접 조회 fallback |

---

## 7. 버전 계획

### v0.1 (MVP) — Phase 1 완료
- 백테스팅 엔진 + 4가지 전략 + 성과 지표 (CLI/API 수준)
- 이 단계에서 전략의 실제 효용 확인

### v0.5 — Phase 2 완료
- 웹 대시보드로 백테스팅 실행 및 결과 시각화
- 여기서부터 실용적인 분석 도구로 활용 가능

### v1.0 — Phase 3 + 3.5 완료
- 10개 전략 + Paper Trading 검증 완료
- 실전 투입 직전 상태

### v2.0 — Phase 4 완료
- 실전 자동매매 + 이벤트 아키텍처 + 리스크 관리 + 알림
- 24시간 운영 가능한 완전한 시스템

### v3.0 — Phase 5 (장기)
- 타 거래소 연동, AI 파라미터 최적화

---

## 8. 다음 단계

### Design 에이전트 전달 사항
- 이 문서를 기반으로 상세 설계 진행
- 필요 산출물:
  - DB 스키마 설계 (TimescaleDB hypertable 포함)
  - REST API 명세 (백테스팅, 전략 관리, 운영 제어)
  - 이벤트 기반 아키텍처 상세 설계 (Event Bus 메시지 포맷)
  - 주문 실행 엔진 상태 다이어그램
  - 웹 대시보드 UI 와이어프레임
  - 리스크 관리 규칙 상세 정의
  - Gradle 멀티 모듈 의존성 구조도
  - Walk Forward Test 윈도우 설계 (학습/검증 구간 비율, 슬라이딩 방식)

---

## 부록

### A. 용어 정의
| 용어 | 정의 |
|------|------|
| MDD | Maximum Drawdown, 최대 낙폭 — 고점 대비 최대 하락률 |
| Sortino | 하방 변동성만 반영한 위험 조정 수익률 |
| Calmar | CAGR / MDD — 리스크 대비 수익 효율 |
| Look-Ahead Bias | 미래 정보를 백테스팅에 사용하는 오류 |
| Market Regime | 추세장(Trend) / 횡보장(Range) 등 시장 상태 구분 |
| ADX | Average Directional Index — 추세 강도 지표 |
| Slippage | 주문 시 원하는 가격과 실제 체결 가격의 차이 |
| Paper Trading | 실제 자금 없이 실시간 시세로 가상 매매 |
| Event Bus | 시스템 컴포넌트 간 비동기 메시지 전달 채널 |
| Overfitting | 과거 데이터에만 과도하게 맞춰진 전략, 실전에서 실패하는 주요 원인 |
| Walk Forward Test | 학습 구간(In-Sample)과 검증 구간(Out-of-Sample)을 분리하여 전략의 범용성을 검증하는 방법 |

### B. 참고 자료
- IDEA.md: 아이디어 문서
- crypto-trading/: 기존 5가지 전략 Java 소스코드
- Upbit OpenAPI 문서: https://docs.upbit.com
- TimescaleDB 문서: https://docs.timescale.com

---
작성: PLAN 에이전트
다음: @Design
