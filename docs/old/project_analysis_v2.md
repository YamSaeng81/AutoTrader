# 🔍 Crypto Auto Trader — 프로젝트 재분석 보고서

> **분석 일시**: 2026-03-15 (2차 분석)  
> **프로젝트 경로**: `d:\Claude Code\projects\crypto-auto-trader`  
> **이전 분석**: 2026-03-15 (1차)  
> **분석 목적**: 1차 분석 보고서 기반 수정 사항 검증 및 새로운 이슈 탐색

---

## 1. 이전 분석 버그 수정 현황

> [!TIP]
> **1차 분석에서 지적된 9개 버그가 모두 수정**되었습니다. 아래에 각 항목별 수정 내용을 상세히 기술합니다.

### ✅ 수정 완료 요약

| # | 심각도 | 버그 | 수정 상태 |
|---|--------|------|-----------|
| 1 | 🔴 CRITICAL | MetricsCalculator Calmar/Recovery Factor 동일 수식 | ✅ 수정됨 |
| 2 | 🔴 CRITICAL | BacktestEngine 매수 수수료 PnL 미반영 | ✅ 수정됨 |
| 3 | 🟡 MEDIUM | LiveTradingService 총자산 업데이트 경쟁 조건 | ✅ 수정됨 |
| 4 | 🟡 MEDIUM | OrderExecutionEngine @Async + 리턴값 문제 | ✅ 수정됨 |
| 5 | 🟡 MEDIUM | 프론트엔드 StrategyType 4개→10개 불일치 | ✅ 수정됨 |
| 6 | 🟡 MEDIUM | BacktestEngine Partial Fill continue 문제 | ✅ 수정됨 |
| 7 | 🟢 LOW | UpbitOrderClient JWT secret key String 누출 | ✅ 수정됨 |
| 8 | 🟢 LOW | UpbitWebSocketClient scheduler.shutdown() 재사용 불가 | ✅ 수정됨 |
| 9 | 🟢 LOW | UpbitRestClient Rate Limiting 미처리 | ✅ 수정됨 |

---

### 1.1 ✅ MetricsCalculator — Calmar/Recovery Factor 수식 분리

**파일**: [MetricsCalculator.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/core-engine/src/main/java/com/cryptoautotrader/core/metrics/MetricsCalculator.java#L87-L100)

```diff
-// 이전: 두 지표가 완전히 동일한 수식
-BigDecimal calmarRatio = totalReturnPct.divide(mddPct.abs(), ...);
-BigDecimal recoveryFactor = totalReturnPct.divide(mddPct.abs(), ...);

+// 수정: Calmar Ratio = 연환산 수익률 / |MDD|
+BigDecimal annualizedReturnPct = meanTradeReturn.multiply(BigDecimal.valueOf(365)).multiply(HUNDRED);
+BigDecimal calmarRatio = mddPct.compareTo(BigDecimal.ZERO) == 0
+        ? BigDecimal.ZERO
+        : annualizedReturnPct.divide(mddPct.abs(), SCALE, RoundingMode.HALF_UP);
+
+// Recovery Factor = 총 수익률 / |MDD| (기존과 동일하지만 Calmar와 구분)
+BigDecimal recoveryFactor = mddPct.compareTo(BigDecimal.ZERO) == 0
+        ? BigDecimal.ZERO
+        : totalReturnPct.divide(mddPct.abs(), SCALE, RoundingMode.HALF_UP);
```

**평가**: ✅ Calmar Ratio는 일평균 수익률을 365배 연환산한 값으로, Recovery Factor와 명확히 구분된다.

---

### 1.2 ✅ BacktestEngine — 매수 수수료 PnL 반영 + Partial Fill 개선

**파일**: [BacktestEngine.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/core-engine/src/main/java/com/cryptoautotrader/core/backtest/BacktestEngine.java)

````carousel
```diff
 // (1) entryFee 필드 추가
+BigDecimal entryFee = BigDecimal.ZERO;

 // BUY 시 수수료 기록
+entryFee = fee;

 // SELL 시 매수/매도 수수료 모두 반영
-BigDecimal pnl = executionPrice.subtract(entryPrice).multiply(position).subtract(fee);
+BigDecimal pnl = executionPrice.subtract(entryPrice).multiply(position).subtract(fee).subtract(entryFee);

 // SELL 후 entryFee 초기화
+entryFee = BigDecimal.ZERO;
```
<!-- slide -->
```diff
 // (2) Partial Fill 이월 시 continue 제거
 if (pendingQuantity.compareTo(BigDecimal.ZERO) > 0 && fillSimulator != null) {
     // ... Partial Fill 처리 ...
-    continue; // ← 전략 신호 평가를 건너뜀
+    // continue 제거: Partial Fill 처리 후에도 전략 신호 평가 진행
 }
```
````

**평가**: ✅ 매수 수수료가 PnL에 정확히 반영되어 수익률 과대 계산이 해결됨. Partial Fill 이월 중에도 전략 신호 평가가 진행되어 급변 시 매도 기회를 놓치지 않음.

---

### 1.3 ✅ LiveTradingService — 총자산 계산 로직 수정

**파일**: [LiveTradingService.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java)

````carousel
```diff
 // executeSessionSell() 수정
-session.setTotalAssetKrw(session.getAvailableKrw());
+// 총자산 = 기존 총자산 - 매도 수수료 (포지션→KRW 전환은 가치 중립)
+session.setTotalAssetKrw(session.getTotalAssetKrw().subtract(fee));
```
<!-- slide -->
```diff
 // updateSessionUnrealizedPnl() 수정 — 오픈 포지션 가치 반영
+BigDecimal posValue = pos.getSize().multiply(currentPrice);
+session.setTotalAssetKrw(session.getAvailableKrw().add(posValue));
```
````

**평가**: ✅ 매도 시 총자산에서 수수료만 차감하고, 미실현 손익 업데이트 시 가용 KRW + 오픈 포지션 시가 합계로 총자산을 계산. 다중 포지션 시 정확한 총자산 반영.

---

### 1.4 ✅ OrderExecutionEngine — @Async 리턴값 문제 해결

**파일**: [OrderExecutionEngine.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/OrderExecutionEngine.java#L83-L140), [OrderRequest.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/dto/OrderRequest.java)

```diff
 // OrderRequest에 sessionId, positionId 필드 추가
+/** 실전매매 세션 ID (@Async 리턴값 의존 회피용) */
+private Long sessionId;
+/** 연결 포지션 ID (@Async 리턴값 의존 회피용) */
+private Long positionId;

 // submitOrder() 내부에서 request에서 직접 추출
 OrderEntity order = OrderEntity.builder()
     ...
+    .sessionId(request.getSessionId())
+    .positionId(request.getPositionId())
     .build();
```

**평가**: ✅ @Async 리턴값에 의존하지 않고, 호출 전에 OrderRequest에 sessionId/positionId를 설정하여 주문 엔티티에 직접 주입. 비동기 실행 완료 전 리턴값 사용 문제 완전 해결.

> [!WARNING]
> 다만, [closeSessionPositions()](file:///D:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#535-563) 메서드(라인 535-561)에서는 여전히 `@Async` 메서드의 리턴값을 직접 사용하는 패턴이 남아 있습니다. 세부 내용은 [섹션 3.1](#31-closesessionpositions-의-async-리턴값-잔여-문제)을 참조하세요.

---

### 1.5 ✅ 프론트엔드 StrategyType — 10개 전략 동기화

**파일**: [types.ts](file:///d:/Claude%20Code/projects/crypto-auto-trader/crypto-trader-frontend/src/lib/types.ts#L7-L8)

```diff
-export type StrategyType = 'VWAP' | 'EMA_CROSS' | 'BOLLINGER' | 'GRID';
+export type StrategyType = 'VWAP' | 'EMA_CROSS' | 'BOLLINGER' | 'GRID'
+    | 'RSI' | 'MACD' | 'SUPERTREND' | 'ATR_BREAKOUT' | 'ORDERBOOK_IMBALANCE' | 'STOCHASTIC_RSI';
```

**평가**: ✅ 백엔드 10개 전략 모두 프론트엔드에 동기화됨.

---

### 1.6 ✅ UpbitOrderClient — JWT 시크릿 키 보안 강화

**파일**: [UpbitOrderClient.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/exchange-adapter/src/main/java/com/cryptoautotrader/exchange/upbit/UpbitOrderClient.java#L286-L297)

```diff
-// 이전: String으로 변환 → String Pool에 잔류 위험
-String secretKeyStr = new String(secretKey);
-SecretKeySpec keySpec = new SecretKeySpec(
-        secretKeyStr.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

+// 수정: CharBuffer/ByteBuffer 변환 → keyBytes 즉시 제로화
+private SecretKeySpec buildSecretKeySpec() {
+    ByteBuffer keyBuf = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secretKey));
+    byte[] keyBytes = new byte[keyBuf.remaining()];
+    keyBuf.get(keyBytes);
+    SecretKeySpec spec = new SecretKeySpec(keyBytes, "HmacSHA256");
+    Arrays.fill(keyBytes, (byte) 0);
+    return spec;
+}
```

**평가**: ✅ `new String()` 대신 `CharBuffer.wrap()` → `ByteBuffer` 변환으로 String Pool 노출 방지. `keyBytes`를 사용 후 즉시 0으로 채워 메모리 덤프 공격 위험 최소화.

---

### 1.7 ✅ UpbitWebSocketClient — disconnect/destroy 라이프사이클 분리

**파일**: [UpbitWebSocketClient.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/exchange-adapter/src/main/java/com/cryptoautotrader/exchange/upbit/UpbitWebSocketClient.java#L74-L93)

```diff
-// 이전: disconnect()에서 scheduler.shutdown() → 재사용 불가
-public synchronized void disconnect() {
-    shutdownRequested = true;
-    disconnectInternal();
-    scheduler.shutdown();
-}

+// 수정: disconnect()은 재연결 가능, destroy()에서만 scheduler 종료
+public synchronized void disconnect() {
+    shutdownRequested = true;
+    disconnectInternal();
+    log.info("Upbit WebSocket 연결 종료 (scheduler 유지 — 재연결 가능)");
+}
+
+public synchronized void destroy() {
+    shutdownRequested = true;
+    disconnectInternal();
+    scheduler.shutdown();
+    log.info("Upbit WebSocket 클라이언트 완전 종료");
+}
```

**평가**: ✅ [disconnect()](file:///D:/Claude%20Code/projects/crypto-auto-trader/exchange-adapter/src/main/java/com/cryptoautotrader/exchange/upbit/UpbitWebSocketClient.java#74-83) 후 [connect()](file:///D:/Claude%20Code/projects/crypto-auto-trader/exchange-adapter/src/main/java/com/cryptoautotrader/exchange/upbit/UpbitWebSocketClient.java#59-73) 재호출 시 Ping 스케줄러가 정상 동작. [destroy()](file:///D:/Claude%20Code/projects/crypto-auto-trader/exchange-adapter/src/main/java/com/cryptoautotrader/exchange/upbit/UpbitOrderClient.java#234-242)는 Bean 소멸 시(@PreDestroy)에만 호출.

---

### 1.8 ✅ UpbitRestClient — Rate Limiting 추가

**파일**: [UpbitRestClient.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/exchange-adapter/src/main/java/com/cryptoautotrader/exchange/upbit/UpbitRestClient.java#L29-L88)

```diff
+/** Upbit 캔들 API 제한: 초당 10회 → 110ms 간격으로 호출 (여유 10%) */
+private static final long MIN_INTERVAL_MS = 110;
+private final AtomicLong lastRequestTime = new AtomicLong(0);
+
+/** Upbit API Rate Limit 준수 — 연속 호출 시 최소 110ms 간격 보장 */
+private void throttle() throws InterruptedException {
+    long now = System.currentTimeMillis();
+    long elapsed = now - lastRequestTime.get();
+    if (elapsed < MIN_INTERVAL_MS) {
+        Thread.sleep(MIN_INTERVAL_MS - elapsed);
+    }
+    lastRequestTime.set(System.currentTimeMillis());
+}
```

**평가**: ✅ 초당 10회 제한 준수를 위한 110ms 간격 쓰로틀링 구현. `AtomicLong` 사용으로 스레드 안전.

---

## 2. 새로 추가된 기능/개선사항

### 2.1 ✅ GlobalExceptionHandler 추가

**파일**: [GlobalExceptionHandler.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/controller/GlobalExceptionHandler.java)

- `IllegalArgumentException` → `400 BAD_REQUEST` + `ApiResponse.error()`
- 기타 [Exception](file:///D:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/controller/GlobalExceptionHandler.java#10-28) → `500 INTERNAL_SERVER_ERROR` + `ApiResponse.error()`
- 이전 분석의 **권장사항 #7 (예외 처리 일관성)** 부분 반영

### 2.2 ✅ 통합 테스트 추가

**새로 추가된 테스트 파일** (이전 0개 → 4개):

| 파일 | 테스트 대상 |
|------|-------------|
| [IntegrationTestBase.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/test/java/com/cryptoautotrader/api/support/IntegrationTestBase.java) | 공통 베이스 (H2, MockMvc, Redis Mock) |
| [BacktestControllerIntegrationTest.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/test/java/com/cryptoautotrader/api/controller/BacktestControllerIntegrationTest.java) | 백테스트 API 12개 테스트 |
| [StrategyControllerIntegrationTest.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/test/java/com/cryptoautotrader/api/controller/StrategyControllerIntegrationTest.java) | 전략 API |
| [DataControllerIntegrationTest.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/test/java/com/cryptoautotrader/api/controller/DataControllerIntegrationTest.java) | 데이터 수집 API |
| [SystemControllerIntegrationTest.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/test/java/com/cryptoautotrader/api/controller/SystemControllerIntegrationTest.java) | 시스템 API |

- 이전 분석의 **권장사항 #14 (통합 테스트 추가)** 반영

### 2.3 ✅ OrderRequest에 Bean Validation 적용

**파일**: [OrderRequest.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/dto/OrderRequest.java)

```java
@NotBlank(message = "coinPair는 필수입니다")
private String coinPair;

@NotBlank(message = "side는 필수입니다")
private String side;

@NotNull(message = "quantity는 필수입니다")
private BigDecimal quantity;
```

- 이전 분석의 **권장사항 #6 (입력 검증)** 부분 반영

### 2.4 ✅ Flyway 마이그레이션 파일 확인

**경로**: `web-api/src/main/resources/db/migration/`

| 마이그레이션 | 내용 |
|--------------|------|
| V1 ~ V9 | 캔들, 백테스트, 전략, 포지션/주문, 리스크, 로그, 시그널, 모의매매 |
| V10 | 모의매매 다중 세션 |
| V11 | strategy_config에 manual_override 추가 |
| V12 | 실전 매매 세션 테이블 |
| V13 | 시장 데이터 캐시 |
| V14 | Strategy type enabled 테이블 |
| V15 | 모의매매 텔레그램 연동 |

총 **15개** 마이그레이션 파일이 존재하며, 이전 분석의 **권장사항 #15 (Flyway 마이그레이션 검증)** 완료.

---

## 3. 🐛 새로 발견된 이슈

### 3.1 🟡 MEDIUM — `closeSessionPositions()`의 @Async 리턴값 잔여 문제

**파일**: [LiveTradingService.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#L535-L561)

```java
private void closeSessionPositions(LiveTradingSessionEntity session, String reason) {
    for (PositionEntity pos : openPositions) {
        OrderRequest sellOrder = new OrderRequest();
        sellOrder.setCoinPair(pos.getCoinPair());
        sellOrder.setSide("SELL");
        sellOrder.setOrderType("MARKET");
        sellOrder.setQuantity(pos.getSize());
        sellOrder.setReason(reason);
        OrderEntity submitted = orderExecutionEngine.submitOrder(sellOrder); // @Async!

        if (submitted != null) {       // ← @Async이므로 null일 수 있음
            submitted.setSessionId(session.getId());
            submitted.setPositionId(pos.getId());
            orderRepository.save(submitted);
        }
    }
}
```

**문제**: `executeSessionBuy()`와 `executeSessionSell()`은 OrderRequest에 sessionId/positionId를 미리 설정하여 @Async 리턴값 의존을 해결했지만, `closeSessionPositions()`에서는 여전히 리턴값을 사용하는 이전 패턴이 남아 있다.

**해결 방안**: `sellOrder.setSessionId(session.getId())` / `sellOrder.setPositionId(pos.getId())`를 `submitOrder()` 호출 전에 설정하고, 리턴값 사용을 제거.

---

### 3.2 🟡 MEDIUM — GlobalExceptionHandler 에러코드 하드코딩

**파일**: [GlobalExceptionHandler.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/controller/GlobalExceptionHandler.java)

```java
@ExceptionHandler(IllegalArgumentException.class)
public ApiResponse<Void> handleBadRequest(IllegalArgumentException e) {
    return ApiResponse.error("BACKTEST_001", e.getMessage());  // ← 항상 "BACKTEST_001"
}

@ExceptionHandler(Exception.class)
public ApiResponse<Void> handleInternalError(Exception e) {
    return ApiResponse.error("BACKTEST_002", "서버 내부 오류가 발생했습니다.");
}
```

**문제**: 모든 `IllegalArgumentException`에 `BACKTEST_001` 코드를 사용한다. Trading, Strategy, Data 등 다른 도메인에서 발생하는 예외도 동일 코드를 반환하여 프론트엔드에서 에러 분류가 불가능.

**해결 방안**: 도메인별 에러 코드 분류 (예: `TRADING_001`, `STRATEGY_001`) 또는 `IllegalStateException` 등 별도 예외 핸들러 추가.

---

### 3.3 🟡 MEDIUM — StrategyController 여전히 `Map<String, Object>` 응답

**파일**: [StrategyController.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/controller/StrategyController.java#L89-L103)

```java
@PostMapping
public ApiResponse<Map<String, Object>> createConfig(@RequestBody Map<String, Object> body) {
    entity.setName((String) body.getOrDefault("name", ""));
    entity.setMaxInvestment(new BigDecimal(body.get("maxInvestment").toString()));
    // ...
}
```

**문제**: 이전 분석의 **권장사항 #5** 과 **#6**이 여전히 미적용:
- Request/Response에 `Map<String, Object>` 사용 → 타입 안전성 부재
- `createConfig()`의 `Map<String, Object> body` → Bean Validation 미적용
- `body.get("maxInvestment").toString()` → NullPointerException 가능

---

### 3.4 🟢 LOW — UpbitRestClient throttle()의 Race Condition

**파일**: [UpbitRestClient.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/exchange-adapter/src/main/java/com/cryptoautotrader/exchange/upbit/UpbitRestClient.java#L80-L88)

```java
private void throttle() throws InterruptedException {
    long now = System.currentTimeMillis();
    long elapsed = now - lastRequestTime.get();    // ← get과 set 사이에 다른 스레드 개입 가능
    if (elapsed < MIN_INTERVAL_MS) {
        Thread.sleep(MIN_INTERVAL_MS - elapsed);
    }
    lastRequestTime.set(System.currentTimeMillis());
}
```

**문제**: `AtomicLong`을 사용하지만 `get()`과 `set()` 사이에 원자성이 보장되지 않아, 다중 스레드에서 동시 호출 시 Rate Limit 위반 가능성이 있다.

**해결 방안**: `compareAndSet()` 루프 또는 `synchronized` 블록, 또는 `Semaphore` 기반 Rate Limiter 사용.

---

### 3.5 🟢 LOW — Docker Compose 서비스 healthcheck 부재

**파일**: [docker-compose.yml](file:///d:/Claude%20Code/projects/crypto-auto-trader/docker-compose.yml), [docker-compose.prod.yml](file:///d:/Claude%20Code/projects/crypto-auto-trader/docker-compose.prod.yml)

**문제**: `docker-compose.prod.yml`에서 backend가 `depends_on: [db, redis]`만 사용하고 healthcheck/condition이 없다. DB가 fully ready 되기 전에 Spring Boot가 시작 시도할 수 있다.

이전 분석의 **권장사항 #23**이 아직 미적용.

---

## 4. 이전 개선 권장사항 적용 현황

| # | 권장사항 | 적용 | 비고 |
|---|----------|------|------|
| 1 | StrategyRegistry 동적 등록 | ❌ | 여전히 static 블록 방식 |
| 2 | 이벤트 기반 아키텍처 | ❌ | 부분 적용 (ExchangeDownEvent 이벤트) |
| 3 | PortfolioManager 서비스 통합 | ❌ | 미적용 |
| 4 | Config 기반 Strategy 초기화 | ❌ | 미적용 |
| 5 | StrategyController DTO 사용 | ❌ | 여전히 Map 사용 |
| 6 | StrategyController 입력 검증 | ⚠️ | OrderRequest에만 Bean Validation 적용 |
| 7 | 예외 처리 일관성 | ✅ | GlobalExceptionHandler 추가 |
| 8 | BacktestRequest 필드명 불일치 | ❓ | 확인 필요 |
| 9 | Upbit Rate Limiter | ✅ | throttle() 구현 |
| 10 | BacktestEngine subList 성능 | ❌ | 미적용 |
| 11 | WebSocket binaryBuffer 정리 | ❌ | 미적용 |
| 12 | Database 복합 인덱스 | ❓ | 마이그레이션에서 확인 필요 |
| 13 | 서비스 레이어 단위 테스트 | ❌ | 미적용 (통합 테스트만 추가) |
| 14 | 통합 테스트 추가 | ✅ | 4개 컨트롤러 테스트 추가 |
| 15 | Flyway 마이그레이션 확인 | ✅ | V1~V15 존재 확인 |
| 16 | StrategyType 타입 동기화 | ✅ | 10개 완전 동기화 |
| 17 | API 에러 핸들링 강화 | ⚠️ | GlobalExceptionHandler는 있으나 에러코드 하드코딩 |
| 18 | 실시간 WebSocket 프론트 연동 | ❌ | 미적용 |
| 19 | API 인증/인가 | ❌ | 미적용 |
| 20 | 환경 변수 검증 | ❌ | 미적용 |
| 21 | 헬스체크 엔드포인트 | ❌ | 미적용 |
| 22 | 로깅 구조화 | ❌ | 미적용 |
| 23 | Docker healthcheck | ❌ | 미적용 |

---

## 5. 업데이트된 아키텍처 평가

| 항목 | 1차 | 2차 | 변화 | 설명 |
|------|-----|-----|------|------|
| 모듈 분리 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | → | 변화 없음 — 여전히 우수 |
| 확장성 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | → | 변화 없음 |
| 테스트 | ⭐⭐⭐ | ⭐⭐⭐½ | ↑ | 통합 테스트 4개 추가 |
| 보안 | ⭐⭐⭐ | ⭐⭐⭐½ | ↑ | JWT secret key 보안 강화 |
| 코드 품질 | ⭐⭐⭐ | ⭐⭐⭐½ | ↑ | 버그 수정, GlobalExceptionHandler, Bean Validation |
| 정확도 | - | ⭐⭐⭐⭐ | 🆕 | MetricsCalculator, BacktestEngine 수식 정확 |
| 안정성 | - | ⭐⭐⭐⭐ | 🆕 | Rate Limiter, WebSocket lifecycle, Partial Fill 개선 |

---

## 6. 새로운 우선순위별 작업 권장

### 🔴 즉시 수정

| # | 항목 | 영향도 |
|---|------|--------|
| 1 | `closeSessionPositions()` @Async 리턴값 패턴 수정 | 비상정지 시 sessionId/positionId 누락 |
| 2 | API 인증/인가 추가 (Spring Security) | **실전매매 API 무방비** |

### 🟡 단기 개선 (1~2주)

| # | 항목 | 영향도 |
|---|------|--------|
| 3 | GlobalExceptionHandler 에러코드 세분화 | 프론트엔드 에러 핸들링 |
| 4 | StrategyController DTO 전환 + Bean Validation | 타입 안전성, NPE 방지 |
| 5 | UpbitRestClient throttle() 원자성 보장 | 동시 호출 시 Rate Limit 위반 |
| 6 | Docker Compose healthcheck 추가 | 배포 안정성 |
| 7 | 환경 변수 검증 (시작 시 체크) | 런타임 에러 방지 |

### 🟢 중장기 개선 (1~3개월)

| # | 항목 | 영향도 |
|---|------|--------|
| 8 | 서비스 레이어 단위 테스트 (Mockito) | 코드 안정성 |
| 9 | StrategyRegistry 동적 등록 | 확장성 |
| 10 | PortfolioManager 실전매매 통합 | 다중 세션 안전성 |
| 11 | 실시간 WebSocket 프론트 연동 | UX 향상 |
| 12 | 구조화 로깅 + Spring Actuator | 운영 가시성 |

---

## 7. 총평

### 개선 요약

1차 분석에서 지적한 **9개 버그를 모두 수정**한 것은 훌륭한 대응이다. 특히:

- **MetricsCalculator**의 Calmar/Recovery Factor 수식이 금융 지표 정의에 맞게 올바르게 분리됨
- **BacktestEngine**의 `entryFee` 추적으로 수익률 계산 정확도가 크게 향상됨
- **@Async 리턴값 문제**가 근본적으로 해결됨 (OrderRequest에 미리 주입)
- **보안 개선** (JWT secret key CharBuffer 변환, WebSocket lifecycle 분리)이 프로덕션 수준으로 향상됨
- **Rate Limiter** 추가로 Upbit API 429 에러 방지

### 남은 과제

가장 시급한 과제는 여전히 **API 인증/인가**이다. 실전매매 API가 무방비 상태이므로, 외부 네트워크 노출 시 자금 위험이 존재한다. 또한 `closeSessionPositions()`의 @Async 잔여 패턴은 비상정지 시 주문 추적 누락을 야기할 수 있다.

전체적으로 **코드 품질과 안정성이 1차 대비 확실히 향상**되었으며, 위 권장사항을 순차적으로 적용하면 상용 수준의 자동매매 시스템으로 운영 가능한 수준이다.
