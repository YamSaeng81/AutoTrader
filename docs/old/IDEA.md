# CryptoAutoTrader

## 한 줄 요약
Upbit API 기반 가상화폐 자동매매 시스템 — 백테스팅으로 전략을 검증하고, 웹 대시보드로 관리하며, 보수적·안정적 투자를 추구하는 1인용 트레이딩 플랫폼.

## 해결하려는 문제
- 감정적 매매로 인한 손실 반복
- 24시간 시장 감시의 물리적 한계
- 전략의 유효성을 사전에 검증할 수단 부재
- 여러 코인·전략을 동시에 운용할 때의 복잡성

## 솔루션 개요
다양한 매매전략을 과거 데이터로 백테스팅하여 성과를 비교·검증한 뒤, 검증된 전략만 실전 자동매매에 투입하는 시스템. 웹 대시보드에서 전략 설정, 백테스팅 실행, 매매 기록 분석, 실시간 운영 제어를 모두 수행한다.

## 타겟 사용자
- 주요 사용자: 본인 (1인 전용)
- 페르소나: 보수적 투자 성향의 개인 투자자. 공격적 수익보다 안정적 자산 증식을 우선시. 충분한 데이터 검증 후에만 실전 투입을 결정.

## 핵심 기능

### 1. 백테스팅 엔진 (MVP Phase 1)
- Upbit 과거 데이터 수집 (최근 3년, 분/시간/일 단위)
- 데이터 파이프라인 분리: 백테스팅용 과거 데이터는 REST API, 실전용 실시간 데이터는 WebSocket
- 전략별 시뮬레이션 실행
- 수수료(Upbit 0.05%) 반영한 실제 수익률 계산
- 슬리피지(Slippage) 파라미터 지원 (기본 0.1~0.2%, 사용자 조정 가능) — 실전 수익률과의 괴리 최소화
- 미래 참조 오류(Look-Ahead Bias) 원천 차단: 신호 발생 캔들의 다음 캔들 시가(Open)에 매수/매도 실행 (현재 캔들 종가 기준 매매 금지)
- 시장 상태(Market Regime) 필터: ADX/변동성 지표로 추세장/횡보장 판별 → 장 상태에 맞는 전략만 활성화 (예: 횡보장엔 Grid, 추세장엔 EMA)
- Walk Forward Test (Overfitting 방지): 학습 구간(In-Sample)과 검증 구간(Out-of-Sample) 분리하여 전략 범용성 검증 — 과거만 잘 맞는 전략 원천 차단
- 성과 지표:
  - 누적 수익률, 승률, MDD(최대 낙폭), 샤프비율
  - Sortino Ratio (하방 변동성만 페널티 — 보수적 투자에 핵심)
  - Calmar Ratio (CAGR / MDD — 리스크 대비 수익 효율)
  - Win/Loss Ratio (평균 수익 / 평균 손실)
  - Recovery Factor (총 수익 / MDD — 낙폭 회복력)
  - 월별 수익률 히트맵 (계절성 분석)
- 전략 간 성과 비교 (A vs B vs C 비교 차트)

### 2. 웹 대시보드 (MVP Phase 2)
- 백테스팅 설정 및 실행 UI
- 전략별 성과 비교 차트/테이블
- 매매 기록 조회 및 분석
- 자동매매 설정 (코인별 전략 배정, 자본 배분)
- 실시간 운영 상태 모니터링

### 3. 매매전략 관리 (MVP Phase 3)
- 초기 집중 전략 4가지 (우선 완벽하게 다듬기):
  - VWAP (거래량 가중 역추세)
  - EMA 크로스 (추세 추종)
  - 볼린저밴드 Mean Reversion (역추세 횡보)
  - Grid Trading (횡보장 분할 매수/매도)
- 이후 추가 전략:
  - RSI+MACD (복합 모멘텀 개선)
  - ATR (변동성 돌파 개선)
  - DCA + 기술적 신호 보정 (정기 분할매수 개선)
  - Pairs Trading (코인 쌍 스프레드 수렴)
  - Ichimoku Cloud (구름대 기반 다중 확인)
  - Mean Reversion + Z-Score (통계적 이상치 진입)
- 다중 타임프레임 지원 (스캘핑~스윙)
- 시장 상태 필터 연동: 추세장/횡보장에 따라 전략 자동 스위칭
- 전략 파라미터 튜닝 UI

### 4. Paper Trading — 모의투자 (MVP Phase 3.5)
- 백테스팅 검증된 전략을 실시간 시세 데이터로 가상 매매 실행
- DB 스키마 분리: `paper_trading` 스키마로 실전 데이터와 완전 격리 (paper_trading.order, paper_trading.position 등)
- 필수 검증 항목:
  - 백테스트 vs Paper 수익률 차이 (5% 이내 목표)
  - 체결 지연(Latency) 실측: API 응답 시간, WebSocket 지연
  - 레이트 리밋 충돌 빈도 (429 에러 횟수)
  - 시스템 재시작 시 포지션 복구 정상 작동 여부
  - 급등락 상황 (BTC ±10% 이상) 대응 테스트
- 최소 운영 기간: 1주일 이상 (주중·주말 모두 포함)
- 충분한 모의투자 검증 후 실전 전환

### 5. 실전 자동매매 (MVP Phase 4)
- 이벤트 기반 아키텍처 (전략이 직접 주문하면 리스크 관리 우회·중복 주문·포지션 충돌 발생):
  ```
  Market Data Service → Event Bus (Redis) → Strategy Engine → Signal Engine → Risk Engine → Order Execution Engine → Exchange API
  ```
- 주문 실행 엔진(Order Execution Engine) 상태 전이:
  - PENDING → SUBMITTED (API 호출 직전)
  - SUBMITTED → PARTIAL_FILLED (체결량 > 0, 미체결 잔량 > 0)
  - SUBMITTED → FILLED (체결량 = 주문량)
  - SUBMITTED → CANCELLED (사용자 취소 or 타임아웃)
  - SUBMITTED → FAILED (API 오류, 잔고 부족 등)
  - PARTIAL_FILLED → FILLED (잔량 체결 완료)
  - PARTIAL_FILLED → CANCELLED (타임아웃 or 수동 취소)
  - 중복 주문 방지 로직 (시스템 꼬임 시 같은 코인 반복 매수 대참사 방지)
  - 각 상태별 로그 → 거래 로그, 주문 사유 → 전략 로그에 분리 기록
- 포지션 관리 시스템:
  - 추적 항목: coin, entry_price, size, avg_price, unrealized_pnl, realized_pnl
  - 부분 체결 시 평균단가 자동 재계산
  - 손절 라인 계산의 정확한 기준점 제공
- 미체결 주문 관리 (N분 후 미체결 시 취소 후 시장가 전환, 부분 체결 처리)
- 거래소 장애 대비 시스템 (Exchange Health Monitor):
  - API latency 모니터링
  - WebSocket 끊김 감지 + 자동 재연결
  - 주문 타임아웃 감지
  - 장애 감지 시 신규 주문 일시 중단 + 긴급 알림
- 거래량 상위 20개 코인 중 선택 운용
- 코인별 전략 배정 및 동시 운용
- 코인별 최대 투자금, 재투자 비율, 손절 라인 개별 설정
- Ubuntu 서버 24시간 상시 운영

### 6. 리스크 관리 (전 단계 공통)
- 코인별 최대 투자금 한도
- 일일/주간/월간 최대 손실 한도
- 포지션별 손절 라인 (개별 설정)
- 수익 재투자 비율 설정 (수익의 N%만 재투자)
- 전체 포트폴리오 리스크 한도
- 급등/급락 감지 시 자동 매매 중단
- 연속 손실 시 쿨다운 (일정 시간 매매 정지)
- 거래량 부족 시 진입 차단
- 수동 긴급 정지 및 코인별 개별 중단
- 포트폴리오 동조화 방지: 동시 진입 포지션 수 제한 (예: 최대 3개) — 암호화폐 시장은 BTC 연동성이 높아 분산 효과가 제한적

### 7. 알림 시스템
- 텔레그램 일일 리포트 (매일 06:30)
  - 지난 24시간 매매 내역 요약
  - 코인별 손익 현황
  - 전략별 성과
  - 포트폴리오 총 수익률
- 긴급 실시간 알림 (즉시 발송, 일일 리포트와 분리)
  - 연속 손실로 인한 쿨다운 발동
  - API 연동 에러 / 시스템 장애
  - 급락으로 인한 긴급 매매 정지
  - 리스크 한도 초과 경고
- 텔레그램 주간 리포트 (매주 월요일)
  - 주 단위 전체 수익률 정산
  - 전략별 주간 성과 비교
  - 주간 MDD 및 리스크 지표

### 8. 로그 시스템 (3단 분리)
- 시스템 로그: 서버 상태, 에러, 인프라 모니터링
- 전략 로그: "왜 이 시점에 매수/매도 신호를 발생시켰는가" — 전략 개선의 핵심 데이터
- 거래 로그: 주문 실행 내역, 체결 결과, 수수료, 슬리피지 실측값

### 9. 운영 제어
- 웹 대시보드에서 실시간 자동매매 정지/재개
- 운영 중 자금 배분 변경
- 전략 교체 (코인별)
- 특정 코인만 매매 중단/재개

## 차별화 포인트
- 백테스팅 우선 접근: 실전 투입 전 충분한 검증 프로세스
- 보수적 리스크 관리: 다층 안전장치로 원금 보존 최우선
- 전략 비교 시스템: 동일 조건에서 전략별 성과를 시각적으로 비교
- 기존 전략 자산 활용: crypto-trading/ 의 5가지 전략을 기반으로 확장
- 시장 상태 자동 감지: 추세장/횡보장에 따라 전략을 자동 스위칭
- 3단 로그 시스템: 시스템/전략/거래 로그 분리로 전략 디버깅 및 개선 가능
- 이벤트 기반 아키텍처: 전략→리스크→주문을 Event Bus로 연결하여 우회 불가능한 안전 구조
- 포지션 관리 시스템: 평균단가·미실현 손익 정확 추적으로 신뢰할 수 있는 손절/익절
- 거래소 장애 대비: Exchange Health Monitor로 Upbit 장애 시 자동 방어

## 시장 기회
- 국내 가상화폐 투자자 증가 추세
- Upbit 국내 1위 거래소, OpenAPI 제공
- 기존 자동매매 서비스는 대부분 유료이거나 커스터마이징 불가
- 개인이 직접 전략을 검증하고 운용할 수 있는 도구 수요 존재

## 기술 스택 제안
- Backend: Spring Boot 3.2 + Java 17 + Gradle
- Frontend: Next.js 14 + React 18 + TypeScript + Tailwind CSS (다크 모드 기본)
- Database: PostgreSQL 15 + TimescaleDB 확장 (시계열 데이터 성능/압축) + Flyway (마이그레이션)
- Realtime: Upbit WebSocket API (실전용 실시간 시세 수신)
- Event Bus: Redis Pub/Sub (이벤트 기반 아키텍처 — 전략→리스크→주문 간 메시지 전달)
- Cache: Redis (Upbit API Rate Limit 대응, 시세 데이터 캐싱, 데이터 파이프라인 중간 버퍼)
- Charting: Lightweight Charts 또는 ApexCharts (백테스팅 결과 시각화)
- Scheduler: Spring Scheduler (일일 리포트, 데이터 수집)
- Messaging: Telegram Bot API (일일 리포트 + 긴급 알림)
- Security: API Key AES-256 암호화 저장 (평문 저장 금지), 환경변수 분리
- Infra: Docker + Docker Compose, Ubuntu Server 24시간 운영
- 개발환경: Windows + VS Code

## MVP 범위 및 로드맵

### Phase 1 — 백테스팅 엔진
- Upbit 과거 데이터 수집기 (REST API → TimescaleDB 저장)
- 초기 집중 전략 4가지 우선 이식 (VWAP, EMA, 볼린저밴드, Grid)
- 백테스팅 실행기 (전략 + 데이터 → 시뮬레이션 결과)
- Look-Ahead Bias 차단: 다음 캔들 시가 기준 매매 실행
- 시장 상태(Market Regime) 필터 구현 (ADX 기반)
- 성과 지표 계산 (수익률, 승률, MDD, 샤프비율, Sortino, Calmar, Win/Loss, Recovery Factor, 수수료 + 슬리피지 반영)
- 월별 수익률 히트맵 (계절성 분석)
- 3단 로그 시스템 기반 설계 (시스템/전략/거래)

### Phase 2 — 웹 대시보드
- 백테스팅 설정/실행 UI (다크 모드 기본)
- 전략별 성과 비교 차트
- 매매 기록 테이블

### Phase 3 — 전략 개선 및 추가
- 초기 4개 전략 파라미터 튜닝 완료
- 나머지 전략 순차 추가 (RSI+MACD, ATR, DCA, Pairs, Ichimoku, Z-Score)
- 전략 파라미터 UI 편집
- 시장 상태 필터 연동 (추세장/횡보장 자동 스위칭)

### Phase 3.5 — Paper Trading (모의투자)
- paper_trading 스키마 분리 설계
- 실시간 시세 연동 + 가상 자금 매매 실행
- 필수 검증 항목 체크 (수익률 괴리 5% 이내, Latency, 429 에러, 포지션 복구, 급등락 대응)
- 최소 1주일 운영 검증

### Phase 4 — 실전 자동매매
- 이벤트 기반 아키텍처 구현 (Event Bus → Strategy → Signal → Risk → Order)
- 주문 실행 엔진 구현 (6단계 상태 머신 + 중복 주문 방지)
- 포지션 관리 시스템 구현 (평균단가, 미실현/실현 손익)
- Exchange Health Monitor 구현 (API latency, WebSocket 재연결, 장애 시 주문 중단)
- Upbit 주문 API 연동 + WebSocket 실시간 시세 + Redis 캐싱
- 미체결 주문 관리 로직
- 리스크 관리 엔진 가동 (포트폴리오 동조화 방지 포함)
- 텔레그램 일일 리포트 + 주간 리포트 + 긴급 알림
- API Key 암호화 (AES-256)
- 3단 로그 시스템 가동
- 24시간 서버 운영 세팅

### Phase 5 — 장기 비전 (MVP 완성 후)
- 타 거래소 연동 (바이낸스 등)
- AI 기반 파라미터 자동 최적화 (유전 알고리즘 등)

## 브레인스토밍 기록
- 거래량 상위 20개 코인 중 선택하여 동시 운용
- 전략별 다중 타임프레임 (분/시간/일) 지원
- 보수적 투자 성향 → 다층 리스크 관리 필수
- 실전 매매 전 백테스팅 충분히 수행 후 투입 (B→C→D→A 순서)
- 기존 crypto-trading/ 의 5가지 전략을 기반으로 개선 + 보수적 전략 5가지 추가
- 추가 전략 후보: Grid, DCA, Pairs, Ichimoku, Mean Reversion+Z-Score
- 텔레그램 알림은 매일 06:30 일일 요약 + 긴급 상황만 실시간 알림 (이중 구조)
- 웹에서 자동매매 정지/재개, 전략 변경, 자금 재배분 가능
- TimescaleDB로 수억 건 시계열 데이터 성능 확보
- Redis로 Upbit API Rate Limit 방어 (시세 캐싱)
- 다크 모드 기본 UI (트레이딩 대시보드 특성)
- 슬리피지 파라미터로 백테스팅 현실성 강화
- 미체결 주문 관리 로직 (타임아웃 후 시장가 전환)
- API Key AES-256 암호화 필수
- Phase 3.5 Paper Trading 단계 추가 (백테스팅 → 모의투자 → 실전의 3단계 검증)
- 주문 실행 엔진(Order Execution Engine) 도입 — 상태 머신으로 중복 주문 대참사 방지
- 데이터 파이프라인 분리: 백테스팅(REST API) vs 실전(WebSocket)
- Look-Ahead Bias 차단: 다음 캔들 시가 기준 매매 (백테스팅 신뢰성 핵심)
- Market Regime 필터: ADX로 추세장/횡보장 판별 → 전략 자동 스위칭
- 포트폴리오 동조화 방지: 동시 포지션 수 제한 (BTC 연동성 대응)
- 초기 전략 다이어트: 10개 한번에 X → 4개(VWAP, EMA, 볼린저, Grid) 우선 완성
- 3단 로그 분리: 시스템/전략/거래 — 전략 디버깅의 핵심
- 주간 리포트 추가 (일일 + 주간 이중 분석)
- Phase 5 장기 비전: 타 거래소 연동, AI 파라미터 자동 최적화 (MVP 이후)
- 백테스팅 성과 지표 확장: Sortino, Calmar, Win/Loss, Recovery Factor, 월별 히트맵
- Paper Trading DB 스키마 분리 (paper_trading 스키마) — 실전 데이터 오염 방지
- Paper Trading 필수 검증 항목 명시 (수익률 괴리 5% 이내, Latency, 429 에러, 포지션 복구, 급등락 대응)
- Paper Trading 최소 1주일 운영 검증 (주중·주말 포함)
- Order Execution Engine 상태 전이 상세화: PENDING→SUBMITTED→PARTIAL_FILLED→FILLED/CANCELLED/FAILED
- 이벤트 기반 아키텍처: Market Data → Event Bus → Strategy → Signal → Risk → Order → Exchange
- 포지션 관리 시스템: coin, entry_price, size, avg_price, unrealized_pnl, realized_pnl
- Exchange Health Monitor: API latency, WebSocket 재연결, 주문 타임아웃, 장애 시 신규 주문 중단

## 다음 단계
PLAN 에이전트에게 전달하여 상세 계획 수립

---
생성일: 2026-03-05
다음 에이전트: @PLAN
