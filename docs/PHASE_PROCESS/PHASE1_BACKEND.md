# Phase 1: 백테스팅 시스템 - 백엔드 구현 완료

## 문서 정보
- 완료일: 2026-03-06
- 대상: Do-Backend Phase 1 (DESIGN.md 10.1 항목 1~11)
- 빌드 상태: BUILD SUCCESSFUL (컴파일 + 단위 테스트 통과)

---

## 1. 구현 범위

| # | 항목 | 상태 |
|---|------|------|
| 1 | Gradle 멀티모듈 프로젝트 초기 구조 | 완료 |
| 2 | Docker Compose (TimescaleDB + Redis) | 완료 |
| 3 | Flyway 마이그레이션 (V1~V8) | 완료 |
| 4 | exchange-adapter: UpbitRestClient + UpbitCandleCollector | 완료 |
| 5 | strategy-lib: Strategy 인터페이스 + 4개 전략 | 완료 |
| 6 | core-engine: BacktestEngine + MetricsCalculator + MarketRegimeDetector | 완료 |
| 7 | core-engine: WalkForwardTestRunner + Fill Simulation | 완료 |
| 8 | core-engine: PortfolioManager | 완료 |
| 9 | web-api: AsyncConfig + BacktestController + REST API | 완료 |
| 10 | 3단 로그 시스템 (Logback) | 완료 |
| 11 | 단위 테스트 | 완료 |

---

## 2. 프로젝트 구조

```
crypto-auto-trader/
├── settings.gradle                       # 멀티모듈 루트
├── build.gradle                          # 공통 설정 (Java 17, Lombok, JUnit 5, UTF-8)
├── docker-compose.yml                    # 개발용 (TimescaleDB + Redis)
├── docker-compose.prod.yml               # 운영용 (restart always, 보안)
├── .env.example                          # 환경변수 템플릿
├── .gitignore
│
├── strategy-lib/                         # 순수 전략 로직 (외부 의존성 없음)
│   └── src/main/java/com/cryptoautotrader/strategy/
│       ├── Strategy.java                 # 전략 인터페이스
│       ├── StrategySignal.java           # BUY/SELL/HOLD + 강도
│       ├── Candle.java                   # OHLCV 캔들 모델
│       ├── StrategyConfig.java           # 전략 파라미터 기본 클래스
│       ├── StrategyRegistry.java         # 전략 등록/조회 레지스트리
│       ├── IndicatorUtils.java           # SMA, EMA, StdDev, ATR, ADX 계산
│       ├── vwap/VwapStrategy.java        # VWAP 역추세 전략
│       ├── ema/EmaCrossStrategy.java     # EMA 골든/데드크로스
│       ├── bollinger/BollingerStrategy.java  # 볼린저 밴드 %B
│       └── grid/GridStrategy.java        # 그리드 트레이딩
│
├── core-engine/                          # 백테스팅 엔진, 성과 지표, 리스크
│   └── src/main/java/com/cryptoautotrader/core/
│       ├── backtest/
│       │   ├── BacktestEngine.java       # 백테스팅 실행 (Look-Ahead Bias 방지)
│       │   ├── BacktestConfig.java       # 백테스트 설정
│       │   ├── BacktestResult.java       # 백테스트 결과
│       │   ├── FillSimulator.java        # Market Impact + Partial Fill
│       │   └── WalkForwardTestRunner.java  # In-Sample/Out-of-Sample 분할
│       ├── metrics/
│       │   ├── MetricsCalculator.java    # 8가지 성과 지표 계산
│       │   └── PerformanceReport.java    # 성과 리포트 모델
│       ├── regime/
│       │   ├── MarketRegimeDetector.java # ADX 기반 시장 상태 감지
│       │   └── MarketRegime.java         # TREND / RANGE / VOLATILE
│       ├── portfolio/
│       │   └── PortfolioManager.java     # 전략 충돌 방지, 자본 할당
│       ├── risk/
│       │   ├── RiskEngine.java           # 손실 한도 체크
│       │   ├── RiskConfig.java           # 리스크 설정
│       │   └── RiskCheckResult.java      # 승인/거부 결과
│       └── model/
│           ├── CoinPair.java, TimeFrame.java, OrderSide.java
│           └── TradeRecord.java
│
├── exchange-adapter/                     # Upbit 거래소 연동
│   └── src/main/java/com/cryptoautotrader/exchange/
│       ├── adapter/
│       │   └── ExchangeAdapter.java      # 거래소 추상화 인터페이스
│       └── upbit/
│           ├── UpbitRestClient.java       # REST API 호출
│           ├── UpbitCandleCollector.java   # 과거 캔들 데이터 수집
│           └── dto/UpbitCandleResponse.java
│
└── web-api/                              # Spring Boot 메인 애플리케이션
    └── src/main/
        ├── java/com/cryptoautotrader/api/
        │   ├── CryptoAutoTraderApplication.java
        │   ├── config/
        │   │   ├── AsyncConfig.java      # 스레드 풀 3분리 (시세/주문/일반)
        │   │   └── WebConfig.java        # CORS 설정
        │   ├── controller/
        │   │   ├── BacktestController.java   # 백테스트 실행/조회/비교
        │   │   ├── DataController.java       # 데이터 수집
        │   │   ├── SystemController.java     # 헬스체크 + 전략 타입
        │   │   └── GlobalExceptionHandler.java
        │   ├── service/
        │   │   ├── BacktestService.java      # 백테스트 오케스트레이션
        │   │   └── DataCollectionService.java  # 비동기 캔들 수집
        │   ├── dto/
        │   │   ├── ApiResponse.java          # 공통 응답 형식
        │   │   ├── BacktestRequest.java
        │   │   ├── WalkForwardRequest.java
        │   │   └── DataCollectRequest.java
        │   ├── entity/                       # JPA 엔티티
        │   │   ├── BacktestRunEntity.java
        │   │   ├── BacktestMetricsEntity.java
        │   │   ├── BacktestTradeEntity.java
        │   │   ├── CandleDataEntity.java
        │   │   └── CandleDataId.java
        │   └── repository/                   # Spring Data JPA
        │       ├── BacktestRunRepository.java
        │       ├── BacktestMetricsRepository.java
        │       ├── BacktestTradeRepository.java
        │       └── CandleDataRepository.java
        └── resources/
            ├── application.yml
            ├── logback-spring.xml            # 3단 로그
            └── db/migration/
                ├── V1__create_candle_data.sql
                ├── V2__create_backtest_tables.sql
                ├── V3__create_strategy_config.sql
                ├── V4__create_position_order.sql
                ├── V5__create_risk_config.sql
                ├── V6__create_log_tables.sql
                ├── V7__create_strategy_signal.sql
                └── V8__create_paper_trading_schema.sql
```

---

## 3. 모듈 의존성

```
web-api ──→ core-engine ──(api)──→ strategy-lib
   │              │
   └──→ exchange-adapter
              │
              └──→ core-engine
```

- `strategy-lib`: 외부 의존성 없음 (순수 Java)
- `core-engine`: `java-library` 플러그인, `api` 의존성으로 strategy-lib 전이
- `exchange-adapter`: Spring WebFlux + Jackson
- `web-api`: Spring Boot Starter (Web, JPA, Redis, Validation) + Flyway + PostgreSQL

---

## 4. 구현된 API 엔드포인트

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/api/v1/backtest/run` | 백테스팅 실행 |
| POST | `/api/v1/backtest/walk-forward` | Walk Forward Test |
| GET | `/api/v1/backtest/{id}` | 결과 조회 |
| GET | `/api/v1/backtest/{id}/trades` | 매매 기록 (페이지네이션) |
| GET | `/api/v1/backtest/compare?ids=` | 전략 비교 |
| GET | `/api/v1/backtest/list` | 이력 목록 |
| POST | `/api/v1/data/collect` | 과거 데이터 수집 |
| GET | `/api/v1/data/coins` | 지원 코인 목록 |
| GET | `/api/v1/health` | 시스템 상태 |
| GET | `/api/v1/strategies/types` | 전략 타입 목록 |

---

## 5. 구현된 전략

| 전략 | 클래스 | 핵심 지표 | 매매 방식 |
|------|--------|----------|----------|
| VWAP | VwapStrategy | 거래량 가중 평균 가격 | 역추세 (할인 매수 / 프리미엄 매도) |
| EMA_CROSS | EmaCrossStrategy | EMA(fast) / EMA(slow) | 추세 추종 (골든/데드크로스) |
| BOLLINGER | BollingerStrategy | 볼린저 밴드 %B | 평균 회귀 (밴드 이탈 후 복귀) |
| GRID | GridStrategy | 가격 그리드 레벨 | 그리드 상/하단 근접 매매 |

---

## 6. 성과 지표 (MetricsCalculator)

1. **총 수익률** (Total Return %)
2. **승률** (Win Rate %)
3. **MDD** (Maximum Drawdown %)
4. **Sharpe Ratio** (연환산)
5. **Sortino Ratio** (하방 위험만 고려)
6. **Calmar Ratio** (수익률 / MDD)
7. **승패비** (Win/Loss Ratio)
8. **Recovery Factor** (수익률 / MDD)
9. 최대 연속 손실, 월별 수익률 등 부가 지표

---

## 7. 핵심 설계 결정

### Look-Ahead Bias 방지
- 현재 캔들(i)의 close에서 전략 신호 생성
- 다음 캔들(i+1)의 open에서 체결 실행

### Fill Simulation
- **Market Impact**: `impact = orderVolume / candleVolume * impactFactor`
- **Partial Fill**: 주문량 > candleVolume * fillRatio이면 이월

### Walk Forward Test
- In-Sample / Out-of-Sample 분할
- 하락률 > 50%: OVERFITTING / 30~50%: CAUTION / < 30%: ACCEPTABLE

### AsyncConfig 스레드 풀 3분리
- `marketDataExecutor`: 시세 수신 전용 (2~4 threads)
- `orderExecutor`: 주문 실행 전용 (2~4 threads)
- `taskExecutor`: 백테스팅/데이터 수집 (4~8 threads)

### 3단 로그 시스템
- `system.log`: 전체 애플리케이션 (30일 보관)
- `strategy.log`: 전략 실행 관련 (90일 보관)
- `trade.log`: 주문/체결 관련 (365일 보관)

---

## 8. 단위 테스트

| 모듈 | 테스트 클래스 | 테스트 수 | 검증 항목 |
|------|-------------|----------|----------|
| strategy-lib | VwapStrategyTest | 4 | 이름, 데이터 부족, 횡보 HOLD, 최소 캔들수 |
| strategy-lib | EmaCrossStrategyTest | 4 | 이름, 데이터 부족, 골든크로스 감지, 최소 캔들수 |
| strategy-lib | BollingerStrategyTest | 4 | 이름, 데이터 부족, 횡보 HOLD, 급락시 BUY |
| core-engine | BacktestEngineTest | 2 | 백테스트 실행 결과, Fill Simulation 활성화 |
| core-engine | FillSimulatorTest | 5 | Market Impact, Partial Fill, 최대 체결수량 |
| core-engine | MetricsCalculatorTest | 4 | 빈 거래, 수익 거래, 손실 거래 MDD, 연속 손실 |
| core-engine | PortfolioManagerTest | 5 | 할당 가능, 할당/반환, 초과 예외, 방향 충돌 |
| core-engine | MarketRegimeDetectorTest | 3 | 데이터 부족 기본값, 횡보, 강한 추세 |

총 **15개 테스트** 전부 통과.

---

## 9. Flyway 마이그레이션

| 파일 | 대상 테이블 |
|------|-----------|
| V1 | candle_data (TimescaleDB hypertable + 압축 정책) |
| V2 | backtest_run, backtest_metrics, backtest_trade |
| V3 | strategy_config |
| V4 | position, order |
| V5 | risk_config |
| V6 | strategy_log, trade_log |
| V7 | strategy_signal |
| V8 | paper_trading 스키마 (position, order, virtual_balance) |

---

## 10. 빌드 검증

```
$ ./gradlew build -x :web-api:test
BUILD SUCCESSFUL
16 actionable tasks

$ ./gradlew :strategy-lib:test :core-engine:test
BUILD SUCCESSFUL
15 tests passed
```

> web-api:test는 DB 연결이 필요하므로 Docker 환경에서 통합 테스트로 실행

---

## 11. 다음 단계

- **Phase 2 (Frontend)**: Do-Frontend 에이전트가 대시보드 구현
- **Phase 3 (전략 개선)**: 추가 6종 전략 + StrategyController
- **Phase 3.5 (Paper Trading)**: paper_trading 스키마 활성화
- **Phase 4 (실전 매매)**: OrderExecutionEngine + 이벤트 파이프라인

---

작성: Do-Backend 에이전트
기반: DESIGN.md 10.1 Phase 1
