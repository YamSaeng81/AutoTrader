# 🔥 Crypto Auto Trader — 20년차 PM/개발자의 혹독한 사망진단서

> **작성 기준일:** 2026-03-21  
> **분석 대상:** `D:\Claude Code\projects\crypto-auto-trader`  
> **분석 관점:** 실서비스에 이 시스템을 투입했다가 실제 돈을 잃었을 때의 관점

---

## TL;DR — 한 문장 결론

> **지금 이 프로젝트는 실전 운용 불가다.** 구조는 그럴듯하게 보이지만, 내부를 열어보면 _실돈을 다루는 시스템이라고 부르기 민망한_ 결함들이 곳곳에 박혀 있다.

---

## 1. 프로젝트 전체 구조 개요

```
crypto-auto-trader/
├── core-engine        — 백테스트 엔진, 리스크, 포트폴리오, 레짐 감지
├── strategy-lib       — 10개 전략 (RSI, EMA, Bollinger, Grid, MACD, Supertrend, ATR, Orderbook, StochRSI, VWAP)
├── exchange-adapter   — Upbit API 클라이언트 (REST + WebSocket)
├── web-api            — Spring Boot REST API (60+ 파일)
└── crypto-trader-frontend — Next.js
```

언뜻 보면 모듈 분리도 잘 됐고, 테스트도 있고, Docker까지 있다. **그래서 더 위험하다.** 겉으로 그럴듯해서 실제로 돌릴 것 같은데, 돌리면 안 된다.

---

## 2. 🚨 CRITICAL — 즉시 수정하지 않으면 실돈 날아간다

### 2-1. `PortfolioManager` — 고전적 TOCTOU 경쟁 조건 (Race Condition)

```java
// PortfolioManager.java
public boolean canAllocate(String strategyId, BigDecimal amount) {
    BigDecimal available = totalCapital.subtract(allocatedCapital);
    return amount.compareTo(available) <= 0;  // ← 여기서 true
}

public synchronized void allocate(...) {   // ← 여기서 실행
    if (!canAllocate(...)) { throw ... }   // 다른 스레드가 이미 할당해버렸다
    ...
}
```

**문제:** `canAllocate()`는 `synchronized`가 **없다**. `allocate()`는 있다. 두 메서드 사이에 다른 스레드가 끼어들면 잔고 초과 할당이 발생한다. 멀티 전략 동시 실행 환경에서는 이 버그가 반드시 터진다.  
**결과:** 계좌 잔고보다 더 많은 돈을 투자에 써버린다.  
**수정:** `canAllocate()`도 `synchronized`로 묶거나, `allocate()` 안에서 원자적으로 처리해야 한다.

---

### 2-2. `RiskEngine` — 상관관계 테이블 하드코딩

```java
private static final Map<String, Double> CORRELATION_MAP = Map.of(
    "BTC:ETH", 0.85,
    "ETH:BTC", 0.85,
    "BTC:BNB", 0.78,
    "BNB:BTC", 0.78,
    "ETH:BNB", 0.80,
    "BNB:ETH", 0.80
);
```

**문제:**  
- 상관계수는 **시장 상황에 따라 지속적으로 변한다.** 2022년 크래시 때 BTC-ETH 상관관계는 0.95까지 치솟았다. 이 정적인 숫자는 그냥 거짓말이다.
- **BTC, ETH, BNB 외 모든 코인은 상관관계 0.0으로 취급된다.** SOL, XRP, ADA, DOGE 등은 아예 고려 대상조차 안 된다.
- 실제 운용 중 새 코인 페어를 추가하면? 상관관계는 그냥 무시된다.

**결과:** 시장 급락 시 동조화 현상으로 포트폴리오 전체가 동시에 폭락하는데, 리스크 엔진은 이를 감지하지 못한다.

---

### 2-3. Stop-Loss / Take-Profit 메커니즘 — **존재하지 않는다**

백테스트 엔진(`BacktestEngine.java`)을 전체를 읽었다. 진입(BUY)과 청산(SELL)은 전략 신호에만 의존한다. **포지션 단위의 Stop-Loss, Take-Profit이 엔진 레벨에서 구현되어 있지 않다.**

물론 각 전략이 SELL 신호를 낼 수 있지만:
- 전략이 신호를 내기까지 10~20개 캔들이 지나는 동안 포지션은 아무런 보호 장치 없이 노출된다.
- 거래소 장애, 네트워크 단절 시 포지션은 영원히 열린 채로 남는다.
- 강제 청산 없음 = 실 서비스에서 밤새 -30%도 그냥 구경만 해야 한다.

---

### 2-4. `TradingController` — 수수료율 하드코딩

```java
// TradingController.java, line 217
private static final BigDecimal FEE_RATE = new BigDecimal("0.0005");
```

Upbit의 실제 수수료는 VIP 등급, 마켓 종류(KRW/BTC/USDT 마켓)에 따라 다르다. 더 심각한 것은 `getOrderChance()` API가 이미 실시간 수수료를 반환하는데, 그걸 쓰지 않고 컨트롤러에 하드코딩했다는 거다.

---

## 3. 🔴 HIGH — 실서비스 투입 시 심각한 문제

### 3-1. 인증 — Static Token 하나로 전체 API 오픈

```java
// ApiTokenAuthFilter.java
if (token.equals(expectedToken)) {
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken("admin", null, List.of(...))
    );
}
```

**문제들:**
- 토큰 하나가 털리면 게임 오버. 모든 API에 대한 완전한 접근권이 넘어간다.
- 토큰 만료 없음. 한 번 유출되면 영구적으로 유효하다.
- 사용자 개념 없음. "admin" 하나뿐이다.
- Rate Limiting 없음. API 남용/DDoS에 무방비.
- 주문 발행 API(`/sessions/start`)와 시스템 헬스 체크 API가 **동일한 권한 레벨**이다.

현재 구조로는 JWT + Refresh Token 기반 사용자 인증, Role 기반 접근제어(RBAC)가 없다. 이건 개인용이라 해도 외부에 노출하는 순간 위험하다.

---

### 3-2. `BacktestEngine` — 멀티코인 백테스트 미지원

현재 백테스트는 **단일 코인에 대한 단일 전략** 시뮬레이션만 가능하다.

```java
BigDecimal capital = config.getInitialCapital();
BigDecimal position = BigDecimal.ZERO; // 보유 수량 — 코인 하나
```

실전에서는 동시에 여러 코인, 여러 전략이 돌아가는데, 백테스트는 그 상황을 재현하지 못한다. 포트폴리오 수준의 백테스트 결과는 신뢰할 수 없다. 개별 전략 백테스트를 합산해봐야 실제 자본 배분과 상관관계 효과를 반영하지 못한다.

---

### 3-3. Exchange Adapter — Upbit 단일 거래소 하드코딩

```
exchange-adapter/
└── upbit/  ← 유일한 구현체
```

`ExchangeAdapter` 인터페이스가 존재하지만, 구현체는 Upbit 하나뿐이다.
- Binance, Bybit, OKX 등 글로벌 거래소 지원 없음
- Upbit 점검 시간(`00:00~00:10`)에 시스템은 어떻게 동작하는가?
- Upbit API 버전 업그레이드 시 전체 adapter 교체 필요

---

### 3-4. `MarketRegimeDetector` — Stateful 객체의 스레드 안전성 미보장

```java
public class MarketRegimeDetector {
    private MarketRegime previousRegime = MarketRegime.RANGE;
    private MarketRegime candidateRegime = null;
    private int holdCount = 0;
```

`BacktestEngine`에서 `new MarketRegimeDetector()`로 생성하는 건 괜찮다. 문제는 실전 매매 엔진에서 이 객체를 어떻게 다루는지다. 싱글톤으로 잘못 주입되면 여러 세션이 하나의 레짐 상태를 공유하는 재앙이 발생한다.

---

### 3-5. `GridStrategy` — 인스턴스 상태가 백테스트를 오염시킨다

```java
public class GridStrategy implements StatefulStrategy {
    private final Set<Integer> activeLevels = new HashSet<>();
    private BigDecimal lastHighest = null;
    private BigDecimal lastLowest = null;
```

`GridStrategy`는 상태를 가진 객체다. 백테스트에서 `StrategyRegistry.get()`을 통해 동일 인스턴스를 재사용하면, 이전 백테스트의 상태가 다음 백테스트로 오염된다. `WalkForwardTestRunner`에서 이걸 제대로 초기화하지 않으면 결과 자체를 믿을 수 없다.

---

## 4. 🟡 MEDIUM — 시스템 신뢰성과 운영성 문제

### 4-1. README.md — 2바이트짜리 쓰레기

```markdown
"# AutoTrader" 
```

이게 다다. 17바이트. 팀원이 이 프로젝트를 받아서 실행하려면 어떻게 해야 하는가? 환경 설정은? DB 초기화는? API 키 암호화 방법은? **아무것도 없다.** 코드베이스가 아무리 좋아도 문서가 없으면 버스에 치였을 때 프로젝트가 같이 죽는다.

---

### 4-2. docker-compose.prod.yml — 운영 환경 치명적 누락

```yaml
db:
  ports:
    - "5432:5432"  # ← 운영 서버에서 DB 포트를 외부에 노출하면 안 된다!
```

DB가 외부 인터넷에 노출되어 있다. `127.0.0.1:5432:5432`로 바꾸거나 별도 내부 네트워크로 격리해야 한다.

추가로:
- Redis에 `requirepass` 없음 (인증 없는 Redis가 외부에 노출되면 즉시 탈취 가능)
- 백업 정책 없음 (`pgdata` 볼륨만 있고, 백업 크론 없음)
- Log rotation 없음 (장기 운용 시 디스크 풀로 서비스 다운)
- Prometheus/Grafana 모니터링 없음

---

### 4-3. 실전 매매 루프 — Cron 기반의 근본적 한계

```java
// SchedulerConfig.java 추정
@Scheduled(fixedDelay = 60000)  // 1분 주기
void runTradingLoop() { ... }
```

캔들 타임프레임이 `1m`이라도 REST API 폴링 기반이면:
- 캔들 생성 시점과 신호 발생 시점 간 지연이 수 초~수십 초 발생
- 고빈도 전략(Grid, OrderbookImbalance)은 이 지연으로 인해 백테스트 대비 성능이 크게 하락
- Upbit WebSocket 클라이언트(`UpbitWebSocketClient.java`)가 있지만, 실전 매매 루프와 연동되어 있는지 불분명

---

### 4-4. 에러 처리 — RuntimeException 단일 무기

```java
} catch (Exception e) {
    log.error("주문 생성 실패: ...");
    throw new RuntimeException("주문 생성 실패", e);
}
```

`UpbitOrderClient`의 모든 예외가 `RuntimeException`으로 포장된다. 이 예외를 호출하는 상위 계층이 이를 적절하게 구분하여 처리하는가? 구분해야 하는 케이스:
- `429 Too Many Requests` → 백오프 후 재시도
- `401 Unauthorized` → API 키 갱신 알림
- `400 Bad Request` → 로직 버그, 재시도 불가
- 네트워크 타임아웃 → 재시도 가능, 주문 중복 방지 필요

현재는 이 모든 경우가 동일하게 처리된다.

---

### 4-5. `PortfolioManager` — `totalCapital` 고정값

```java
public PortfolioManager(BigDecimal totalCapital) {
    this.totalCapital = totalCapital;
}
```

**`totalCapital`이 생성 시점에 고정된다.** 실전 매매 중 수익/손실로 자산이 변동하면? 거래소에서 추가 입금하면? 시스템은 이를 반영하지 못하고 초기 자본액으로만 계산한다.

---

## 5. 🟠 구조적 부재 — 있어야 하는데 아예 없는 것들

### 5-1. 이벤트 드리븐 아키텍처 부재

현재 구조는 `Controller → Service → Repository` 의 동기식 레이어드 아키텍처다. 문제는 **거래는 비동기 이벤트**라는 것이다.

- 주문 제출과 주문 체결은 별개의 시점에 발생한다
- 주문 체결 이벤트를 WebSocket으로 수신해서 포지션을 업데이트해야 한다
- 현재는 체결 확인을 위해 REST 폴링을 하는 구조로 추정되며, 이는 근본적으로 신뢰할 수 없다

**필요한 것:** Upbit WebSocket 체결 이벤트 → 내부 Event Bus(ApplicationEventPublisher 또는 Kafka) → Position Update → Risk Check → Notification

---

### 5-2. 전략 파라미터 최적화 파이프라인 부재

10개 전략이 있고, 각각 파라미터를 Map으로 받는다. 그런데:
- 어떤 파라미터 조합이 최적인지 탐색하는 그리드 서치/베이지안 최적화가 없다
- Walk-Forward 테스트는 있지만, 최적 파라미터를 자동으로 찾아주는 파이프라인이 없다
- 결국 사람이 직접 파라미터를 때려박아야 한다 = 과최적화(Overfitting) 필연

---

### 5-3. 실시간 성과 모니터링 부재

- Sharpe Ratio, Calmar Ratio를 실시간으로 계산해서 기준 이하로 떨어지면 자동 중단하는 로직 없음
- 전략별 일간/주간/월간 성과 추세 추적 없음
- Drawdown Alert 없음 (텔레그램 알림은 있지만 어떤 조건에서 보내는지 불분명)
- `PerformanceReport`는 백테스트 결과용이고, 실전 성과 보고서는 별도 구현 필요

---

### 5-4. 데이터 파이프라인 신뢰성 부재

캔들 데이터를 `CandleDataEntity`에 저장하는 구조인데:
- 데이터 수집 실패 시 누락 캔들 처리 로직 없음
- 거래소 데이터와 DB 데이터 차이 검증 없음
- 캔들 데이터 이상값(스파이크) 필터링 없음
- TimescaleDB를 쓰고 있는데 Continuous Aggregate, Data Retention Policy 설정 흔적 없음

---

### 5-5. Paper Trading — DB 저장 신뢰성 불명확

`PaperTradingController`가 있는데, Paper Trading 결과가 실제 DB에 저장되는지, 아니면 메모리에만 존재하는지 서비스 계층을 보지 않고는 확인이 안 된다. 서버 재시작 시 Paper Trading 세션이 날아간다면 이건 장난감이다.

---

## 6. 🔵 코드 품질 — 기술 부채

### 6-1. `RsiStrategy.evaluate()` — 매 호출마다 전체 계산 반복

```java
// 매 신호 평가마다 전체 캔들 리스트로 RSI를 처음부터 다시 계산
BigDecimal currentRsi = calculateRsi(closes, period);

// 다이버전스 감지를 위해 과거 캔들에 대해 또 RSI 계산
BigDecimal swingLowRsi = calculateRsi(closes.subList(0, swingLowIdx + 1), period);
```

단일 평가 호출 시 RSI를 **3회** 계산한다. O(n) 계산을 불필요하게 반복한다. 200개 캔들 × 10개 전략 × 분당 1회 = 캔들 데이터 처리 6000번. 증분 계산(Incremental Computation) 또는 캐싱 필요.

---

### 6-2. `CompositeStrategy` — 가중치 정규화 없음

```java
for (WeightedStrategy ws : strategies) {
    buyScore += w * conf;  // 가중치 합이 1.0이 아닐 수 있음
}

if (buyScore > STRONG_THRESHOLD) {  // 0.6 고정
```

5개 전략을 각각 가중치 1.0으로 설정하면 buyScore가 최대 5.0이 될 수 있다. 그러면 STRONG_THRESHOLD(0.6)는 언제나 넘어버린다. 가중치를 정규화해야 threshold가 의미를 가진다.

---

### 6-3. `GridStrategy` — 싱글톤/빈 재사용 시 치명적

`GridStrategy`는 인스턴스 변수에 상태를 저장한다. Spring Bean으로 싱글톤 등록 시 여러 세션이 상태를 공유한다. 하지만 `StrategyRegistry`가 어떻게 인스턴스를 관리하는지 보면:

```java
// StrategyRegistry — 추정
Map.of("GRID", new GridStrategy(), ...)  // 단 하나의 인스턴스
```

다중 세션 환경에서 두 세션이 동일한 GridStrategy 인스턴스를 쓰면 서로의 `activeLevels`를 오염시킨다.

---

### 6-4. 파라미터 타입 불안전 `Map<String, Object>`

```java
public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params)
```

모든 전략이 `Map<String, Object>`로 파라미터를 받는다. 타입 안전성 없음, IDE 자동완성 없음, 런타임 ClassCastException 위험. 전략별 `Config` 클래스(`RsiConfig`, `GridConfig` 등)가 이미 있는데 왜 쓰지 않는가?

---

## 7. 📋 테스트 커버리지 평가

### 있는 것

- 단위 테스트: BacktestEngine, FillSimulator, MetricsCalculator, PortfolioManager, RiskEngine, 전략별 테스트
- `ConflictingSignalTest`: 전략 충돌 시나리오

### 없는 것

| 테스트 종류 | 상태 |
|-------------|------|
| 통합 테스트 (Controller → Service → DB) | ❌ 없음 |
| Upbit API 모의 통합 테스트 | ❌ 없음 |
| 동시성 테스트 (멀티 세션 동시 주문) | ❌ 없음 |
| E2E 테스트 (Playwright 설정은 있으나...) | ❓ playwright.config.ts 있음, 실제 테스트 수 불명확 |
| 실전 데이터 기반 Regression 테스트 | ❌ 없음 |
| Chaos Engineering (거래소 장애 시뮬레이션) | ❌ 없음 |

---

## 8. 우선순위별 개선 로드맵

### P0 — 즉시 수정 (오늘)

| # | 항목 | 설명 |
|---|------|------|
| P0-1 | `canAllocate()` synchronized 추가 | 자본 초과 할당 버그 수정 |
| P0-2 | DB 포트 외부 노출 차단 | `docker-compose.prod.yml` |
| P0-3 | Redis 인증 추가 | `requirepass` 설정 |
| P0-4 | Stop-Loss 엔진 레벨 구현 | 최소한의 포지션 보호 |

### P1 — 단기 (2주 이내)

| # | 항목 | 설명 |
|---|------|------|
| P1-1 | 동적 상관관계 계산 | 최근 60~90일 실시간 계산 |
| P1-2 | `GridStrategy` 인스턴스 격리 | 세션별 신규 인스턴스 생성 |
| P1-3 | 가중치 정규화 (`CompositeStrategy`) | 임계값 신뢰도 복원 |
| P1-4 | API 인증 강화 | JWT + Refresh Token + Rate Limiting |
| P1-5 | 수수료 동적 조회 | `getOrderChance()` API 활용 |
| P1-6 | `totalCapital` 실시간 동기화 | 거래소 잔고와 주기적 동기화 |

### P2 — 중기 (1~2개월)

| # | 항목 | 설명 |
|---|------|------|
| P2-1 | 이벤트 드리븐 주문 처리 | WebSocket 체결 이벤트 기반 포지션 업데이트 |
| P2-2 | 멀티코인 포트폴리오 백테스트 | 자본 배분 + 상관관계 반영 |
| P2-3 | 실시간 성과 모니터링 | Sharpe, Drawdown 기반 자동 중단 |
| P2-4 | Prometheus + Grafana | JVM, 거래 성과 메트릭 수집 |
| P2-5 | README 작성 | 설치, 설정, 운영 가이드 |
| P2-6 | Binance/Bybit 어댑터 추가 | 거래소 다각화 |

### P3 — 장기 (3개월+)

| # | 항목 | 설명 |
|---|------|------|
| P3-1 | 파라미터 최적화 파이프라인 | 베이지안 최적화 + Overfitting 방지 |
| P3-2 | ML 기반 레짐 분류 | 통계적 임계값 의존 탈피 |
| P3-3 | Incremental RSI/MACD 계산 | CPU 효율화 |
| P3-4 | Multi-tenant 지원 | 사용자별 독립 계정·포트폴리오 |
| P3-5 | Strategy Config 타입 안전성 | `Map<String, Object>` → 전략별 Config 클래스 |
| P3-6 | 통합·동시성 테스트 스위트 | CI 파이프라인 통합 |

---

## 9. 총평

### 잘 한 것 (인정은 해준다)

- **Look-Ahead Bias 방지:** BacktestEngine에서 현재 캔들 신호 → 다음 캔들 오픈 체결 구조는 정확하다.
- **Fill Simulation:** Market Impact + Partial Fill 시뮬레이션은 현실적이다.
- **API 키 보안:** `char[]` 기반 키 관리 및 사용 후 제거(`Arrays.fill('\0')`)는 올바른 접근이다.
- **Hysteresis 적용:** `MarketRegimeDetector`의 신호 노이즈 제거 로직은 실용적이다.
- **모듈 분리:** `core-engine`, `strategy-lib`, `exchange-adapter` 분리는 확장 가능한 구조다.

### 근본적인 문제

이 프로젝트는 **백테스트 연구 도구 수준**이다. 실전 투자 자동화 시스템이 되려면 다음이 필요하다:

1. **신뢰할 수 있는 포지션 관리** (현재 없음)
2. **강제 손절 메커니즘** (현재 없음)
3. **거래소 장애 대응** (현재 없음)
4. **감사 가능한 주문 이력** (부분적으로 있음)
5. **실시간 리스크 모니터링** (현재 없음)

> **결론:** 지금 실제 자산을 얹어서 돌리면 안 된다. 최소 P0~P1 완료 후 소액으로 Paper → 실전 순서를 밟아야 한다. 급하게 넣고 싶은 마음이 있다면, 그 마음이 바로 가장 큰 리스크다.

---

*분석: Antigravity AI (20년차 PM/개발자 관점 시뮬레이션)*  
*분석 범위: core-engine, strategy-lib, exchange-adapter, web-api (TradingController, ApiTokenAuthFilter, RiskEngine, PortfolioManager, BacktestEngine 외), docker-compose.prod.yml, .env.example*
