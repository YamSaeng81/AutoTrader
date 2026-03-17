# 🔍 Crypto Auto Trader — 프로젝트 3차 분석 보고서

> **분석 일시**: 2026-03-15 (3차 분석)  
> **프로젝트 경로**: `d:\Claude Code\projects\crypto-auto-trader`  
> **이전 분석**: 2026-03-15 (1차), 2026-03-15 (2차)  
> **분석 목적**: 2차 분석 보고서 기반 수정 사항 검증 및 새로운 이슈 탐색

---

## 1. 2차 분석 이슈 수정 현황

> [!TIP]
> **2차 분석에서 지적된 5개 이슈 중 4개가 수정**되었습니다. 1개는 아직 미적용 상태입니다.

### ✅ 수정 완료 요약

| # | 심각도 | 이슈 | 수정 상태 |
|---|--------|------|-----------| 
| 3.1 | 🟡 MEDIUM | [closeSessionPositions()](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#542-566) @Async 리턴값 잔여 패턴 | ✅ 수정됨 |
| 3.2 | 🟡 MEDIUM | GlobalExceptionHandler 에러코드 하드코딩 | ✅ 수정됨 |
| 3.3 | 🟡 MEDIUM | StrategyController `Map<String, Object>` 응답 | ❌ 미적용 |
| 3.4 | 🟢 LOW | UpbitRestClient [throttle()](file:///d:/Claude%20Code/projects/crypto-auto-trader/exchange-adapter/src/main/java/com/cryptoautotrader/exchange/upbit/UpbitRestClient.java#80-89) Race Condition | ✅ 수정됨 |
| 3.5 | 🟢 LOW | Docker Compose healthcheck 부재 | ✅ 수정됨 |

---

### 1.1 ✅ [closeSessionPositions()](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#542-566) — @Async 리턴값 패턴 완전 제거

**파일**: [LiveTradingService.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#L542-L565)

```diff
 private void closeSessionPositions(LiveTradingSessionEntity session, String reason) {
     for (PositionEntity pos : openPositions) {
         OrderRequest sellOrder = new OrderRequest();
         sellOrder.setCoinPair(pos.getCoinPair());
         sellOrder.setSide("SELL");
         sellOrder.setOrderType("MARKET");
         sellOrder.setQuantity(pos.getSize());
         sellOrder.setReason(reason);
-        OrderEntity submitted = orderExecutionEngine.submitOrder(sellOrder);
-        if (submitted != null) {
-            submitted.setSessionId(session.getId());
-            submitted.setPositionId(pos.getId());
-            orderRepository.save(submitted);
-        }
+        sellOrder.setSessionId(session.getId());
+        sellOrder.setPositionId(pos.getId());
+        orderExecutionEngine.submitOrder(sellOrder);
     }
 }
```

**평가**: ✅ [executeSessionBuy()](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#442-486) / [executeSessionSell()](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#487-523) / [closeSessionPositions()](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#542-566) 모두 동일한 패턴으로 통일. @Async 리턴값 사용이 프로젝트 전체에서 완전히 제거됨. 비상정지 시에도 sessionId/positionId가 정상적으로 기록된다.

---

### 1.2 ✅ GlobalExceptionHandler — 에러코드 범용화 + IllegalStateException 핸들러 추가

**파일**: [GlobalExceptionHandler.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/controller/GlobalExceptionHandler.java)

````carousel
```diff
 // 이전: 모든 IllegalArgumentException에 BACKTEST_001 반환
-@ExceptionHandler(IllegalArgumentException.class)
-public ApiResponse<Void> handleBadRequest(IllegalArgumentException e) {
-    return ApiResponse.error("BACKTEST_001", e.getMessage());
-}

 // 수정: 범용 에러코드 사용
+@ExceptionHandler(IllegalArgumentException.class)
+@ResponseStatus(HttpStatus.BAD_REQUEST)
+public ApiResponse<Void> handleBadRequest(IllegalArgumentException e) {
+    log.warn("잘못된 요청: {}", e.getMessage());
+    return ApiResponse.error("BAD_REQUEST", e.getMessage());
+}
```
<!-- slide -->
```diff
 // 신규: IllegalStateException 핸들러 (409 CONFLICT)
+@ExceptionHandler(IllegalStateException.class)
+@ResponseStatus(HttpStatus.CONFLICT)
+public ApiResponse<Void> handleConflict(IllegalStateException e) {
+    log.warn("상태 충돌: {}", e.getMessage());
+    return ApiResponse.error("CONFLICT", e.getMessage());
+}

 // 개선: 범용 에러코드 + 로깅 추가
+@ExceptionHandler(Exception.class)
+@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
+public ApiResponse<Void> handleInternalError(Exception e) {
+    log.error("내부 오류: {}", e.getMessage(), e);
+    return ApiResponse.error("INTERNAL_ERROR", "서버 내부 오류가 발생했습니다.");
+}
```
````

**평가**: ✅ 3종의 예외 핸들러가 도메인 독립적 에러코드(`BAD_REQUEST`, `CONFLICT`, `INTERNAL_ERROR`)를 사용. `@ResponseStatus` 어노테이션으로 HTTP 상태 코드도 명시적으로 설정됨. 다만 이로 인한 **새로운 이슈**가 발생함 → [섹션 2.1](#21--medium--backtestcontrollerintegrationtest-에러코드-불일치) 참조.

---

### 1.3 ❌ StrategyController — 여전히 `Map<String, Object>` 사용

**파일**: [StrategyController.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/controller/StrategyController.java#L88-L103)

```java
// 여전히 Map<String, Object> 사용 — 변경 없음
@PostMapping
public ApiResponse<Map<String, Object>> createConfig(@RequestBody Map<String, Object> body) {
    entity.setName((String) body.getOrDefault("name", ""));
    entity.setMaxInvestment(new BigDecimal(body.get("maxInvestment").toString())); // NPE 가능
    // ...
}
```

**문제**:
- [createConfig()](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/controller/StrategyController.java#84-104), [updateConfig()](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/controller/StrategyController.java#105-126) 모두 `Map<String, Object>` 요청 → **Bean Validation 미적용**
- `body.get("maxInvestment").toString()` → `maxInvestment` 키가 없으면 **NullPointerException**
- 응답도 `Map<String, Object>` → **Swagger/OpenAPI 문서 자동 생성 불가**
- 1차 분석 **권장사항 #5, #6** 이 3차까지 미적용 상태

---

### 1.4 ✅ UpbitRestClient [throttle()](file:///d:/Claude%20Code/projects/crypto-auto-trader/exchange-adapter/src/main/java/com/cryptoautotrader/exchange/upbit/UpbitRestClient.java#80-89) — `synchronized` 추가

**파일**: [UpbitRestClient.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/exchange-adapter/src/main/java/com/cryptoautotrader/exchange/upbit/UpbitRestClient.java#L80-L88)

```diff
-private void throttle() throws InterruptedException {
+/** Upbit API Rate Limit 준수 — 연속 호출 시 최소 110ms 간격 보장 (원자적 처리) */
+private synchronized void throttle() throws InterruptedException {
     long now = System.currentTimeMillis();
     long elapsed = now - lastRequestTime.get();
     if (elapsed < MIN_INTERVAL_MS) {
         Thread.sleep(MIN_INTERVAL_MS - elapsed);
     }
     lastRequestTime.set(System.currentTimeMillis());
 }
```

**평가**: ✅ `synchronized` 키워드로 [get()](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/controller/TradingController.java#181-189)과 `set()` 사이의 원자성 보장. 다중 스레드에서 동시 호출 시에도 Rate Limit 준수가 보장됨. `AtomicLong` 은 이제 불필요하지만 해를 끼치지는 않음.

---

### 1.5 ✅ Docker Compose — healthcheck + condition 추가

**파일**: [docker-compose.prod.yml](file:///d:/Claude%20Code/projects/crypto-auto-trader/docker-compose.prod.yml)

````carousel
```diff
 services:
   db:
+    healthcheck:
+      test: ["CMD-SHELL", "pg_isready -U trader -d crypto_auto_trader"]
+      interval: 10s
+      timeout: 5s
+      retries: 5

   redis:
+    healthcheck:
+      test: ["CMD", "redis-cli", "ping"]
+      interval: 10s
+      timeout: 3s
+      retries: 3
```
<!-- slide -->
```diff
   backend:
     depends_on:
-      - db
-      - redis
+      db:
+        condition: service_healthy
+      redis:
+        condition: service_healthy
```
````

**평가**: ✅ DB와 Redis가 fully ready된 후에만 Spring Boot가 시작. `pg_isready`와 `redis-cli ping`으로 실제 가용성 확인. 배포 안정성이 크게 향상됨.

---

## 2. 🐛 새로 발견된 이슈

### 2.1 🔴 CRITICAL — BacktestControllerIntegrationTest 에러코드 불일치

**파일**: [BacktestControllerIntegrationTest.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/test/java/com/cryptoautotrader/api/controller/BacktestControllerIntegrationTest.java#L125-L131)

```java
@Test
@DisplayName("POST /api/v1/backtest/run — 필수 필드 모두 있지만 데이터 부족 시 400 (서비스 예외)")
void 백테스트_실행_데이터_부족_400() throws Exception {
    mockMvc.perform(post("/api/v1/backtest/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("BACKTEST_001")); // ← 실패!
}
```

**문제**: GlobalExceptionHandler가 `BACKTEST_001` → `BAD_REQUEST`로 변경되었지만, **통합 테스트는 여전히 `BACKTEST_001`을 기대**. 이 테스트는 반드시 실패한다.

**해결 방안**: 
```diff
-.andExpect(jsonPath("$.error.code").value("BACKTEST_001"));
+.andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
```

---

### 2.2 🟡 MEDIUM — TradingController의 이중 예외 처리 패턴

**파일**: [TradingController.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/controller/TradingController.java#L40-L50)

```java
@PostMapping("/sessions")
public ApiResponse<LiveTradingSessionEntity> createSession(
        @Valid @RequestBody LiveTradingStartRequest request) {
    try {
        return ApiResponse.ok(liveTradingService.createSession(request));
    } catch (IllegalStateException e) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()); // ← 패턴 A
    } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); // ← 패턴 A
    }
}
```

**문제**: [TradingController](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/controller/TradingController.java#24-239)는 `ResponseStatusException`(패턴 A)을 직접 throw하여 예외를 처리하는 반면, [GlobalExceptionHandler](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/controller/GlobalExceptionHandler.java#10-35)(패턴 B)는 `@RestControllerAdvice`로 예외를 일괄 처리. **두 패턴이 공존**하여:

1. `ResponseStatusException`은 Spring 기본 에러 형식(`{"status":409,"error":"Conflict","message":...}`)으로 반환 → [ApiResponse](file:///d:/Claude%20Code/projects/crypto-auto-trader/crypto-trader-frontend/src/lib/types.ts#1-6) 구조와 불일치
2. [GlobalExceptionHandler](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/controller/GlobalExceptionHandler.java#10-35)의 `IllegalStateException` 핸들러가 있지만, `ResponseStatusException`이 먼저 throw되므로 도달하지 않음
3. 프론트엔드가 두 가지 에러 응답 형식을 모두 처리해야 함

**해결 방안**: TradingController의 `try-catch` 블록을 제거하고, [GlobalExceptionHandler](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/controller/GlobalExceptionHandler.java#10-35)에 위임. 또는 커스텀 예외 클래스(`TradingException`, `NotFoundException` 등) 도입.

---

### 2.3 🟡 MEDIUM — MarketDataSyncService `new UpbitRestClient()` 매번 생성

**파일**: [MarketDataSyncService.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/MarketDataSyncService.java#L69-L74)

```java
private void syncPair(String coinPair, String timeframe) {
    UpbitRestClient restClient = new UpbitRestClient();  // ← 매번 새 인스턴스!
    UpbitCandleCollector collector = new UpbitCandleCollector(restClient);
    List<Candle> candles = collector.fetchCandles(coinPair, timeframe, from, to);
    // ...
}
```

**문제**: 
- [UpbitRestClient](file:///d:/Claude%20Code/projects/crypto-auto-trader/exchange-adapter/src/main/java/com/cryptoautotrader/exchange/upbit/UpbitRestClient.java#22-90)를 매 호출마다 `new`로 생성 → [EngineConfig](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/config/EngineConfig.java#15-47)에서 Bean으로 등록한 인스턴스를 사용하지 않음
- 새 인스턴스이므로 `lastRequestTime = 0`으로 초기화 → **Rate Limiting [throttle()](file:///d:/Claude%20Code/projects/crypto-auto-trader/exchange-adapter/src/main/java/com/cryptoautotrader/exchange/upbit/UpbitRestClient.java#80-89)이 무효화**
- [PaperTradingService](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/PaperTradingService.java#35-401)는 `@Autowired(required=false)` DI를 사용하도록 이미 수정되었지만, [MarketDataSyncService](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/MarketDataSyncService.java#27-110)는 미수정

**해결 방안**: 
```diff
+@Autowired(required = false)
+private UpbitRestClient upbitRestClient;

 private void syncPair(String coinPair, String timeframe) {
-    UpbitRestClient restClient = new UpbitRestClient();
+    if (upbitRestClient == null) {
+        log.warn("UpbitRestClient 미등록 — 시장 데이터 동기화 불가");
+        return;
+    }
-    UpbitCandleCollector collector = new UpbitCandleCollector(restClient);
+    UpbitCandleCollector collector = new UpbitCandleCollector(upbitRestClient);
```

---

### 2.4 🟡 MEDIUM — [Timeframe](file:///d:/Claude%20Code/projects/crypto-auto-trader/crypto-trader-frontend/src/lib/types.ts#9-10) 불완전 매핑 (M15, M30, H4 누락)

**파일들**: [TimeframeUtils.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/util/TimeframeUtils.java), [MarketDataSyncService.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/MarketDataSyncService.java#L100-L108), [types.ts](file:///d:/Claude%20Code/projects/crypto-auto-trader/crypto-trader-frontend/src/lib/types.ts#L9)

```java
// MarketDataSyncService.timeframeMinutes() — M15, M30, H4 누락
private long timeframeMinutes(String timeframe) {
    return switch (timeframe) {
        case "M1" -> 1;
        case "M5" -> 5;
        case "H1" -> 60;
        case "D1" -> 1440;
        default -> 60;  // M15 → 60분으로 잘못 계산!
    };
}
```

| 위치 | M1 | M5 | M15 | M30 | H1 | H4 | D1 |
|------|----|----|-----|-----|----|----|-----|
| `TimeframeUtils` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| [MarketDataSyncService](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/MarketDataSyncService.java#27-110) | ✅ | ✅ | ❌(60) | ❌(60) | ✅ | ❌(60) | ✅ |
| 프론트엔드 [Timeframe](file:///d:/Claude%20Code/projects/crypto-auto-trader/crypto-trader-frontend/src/lib/types.ts#9-10) | ✅ | ✅ | ❌ | ❌ | ✅ | ❌ | ✅ |

**문제**: [MarketDataSyncService](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/MarketDataSyncService.java#27-110)에 별도의 [timeframeMinutes()](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/MarketDataSyncService.java#100-109) 메서드가 존재하며, 이미 만들어진 `TimeframeUtils.toMinutes()`를 사용하지 않는다. `M15`로 세션을 시작하면 캔들 조회 범위가 **4배 넓어져** 불필요한 데이터 로드 발생. 프론트엔드 [Timeframe](file:///d:/Claude%20Code/projects/crypto-auto-trader/crypto-trader-frontend/src/lib/types.ts#9-10) 타입도 `M15`, `M30`, `H4`를 포함하지 않아 해당 타임프레임 선택이 불가능.

**해결 방안**: [MarketDataSyncService](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/MarketDataSyncService.java#27-110)에서 `TimeframeUtils.toMinutes()` 사용으로 교체. 프론트엔드 [Timeframe](file:///d:/Claude%20Code/projects/crypto-auto-trader/crypto-trader-frontend/src/lib/types.ts#9-10) 타입에 `M15 | M30 | H4` 추가.

---

### 2.5 🟢 LOW — EngineConfig [upbitOrderClient()](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/config/EngineConfig.java#34-46) null Bean 반환

**파일**: [EngineConfig.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/config/EngineConfig.java#L38-L45)

```java
@Bean
public UpbitOrderClient upbitOrderClient() {
    if (upbitAccessKey == null || upbitAccessKey.isBlank()
            || upbitSecretKey == null || upbitSecretKey.isBlank()) {
        return null; // ← null Bean 반환
    }
    return new UpbitOrderClient(upbitAccessKey.toCharArray(), upbitSecretKey.toCharArray());
}
```

**문제**: Spring의 `@Bean` 메서드가 `null`을 반환하면 Bean이 등록되지 않는 것이 아니라, **null 객체가 Bean으로 등록**될 수 있다 (Spring 버전에 따라 동작이 다름). `@Autowired(required=false)`로 주입받는 쪽에서 `null` 체크를 하므로 현재는 문제가 발생하지 않지만, Spring Boot 버전 업그레이드 시 예기치 않은 동작의 원인이 될 수 있다.

**해결 방안**: `@ConditionalOnProperty` 또는 `@ConditionalOnExpression` 사용:
```java
@Bean
@ConditionalOnProperty(name = {"upbit.access-key", "upbit.secret-key"})
public UpbitOrderClient upbitOrderClient() {
    return new UpbitOrderClient(upbitAccessKey.toCharArray(), upbitSecretKey.toCharArray());
}
```

---

### 2.6 🟢 LOW — PaperTradingService [closePosition()](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/PaperTradingService.java#302-346)의 totalKrw 계산 오류

**파일**: [PaperTradingService.java](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/PaperTradingService.java#L333-L335)

```java
private void closePosition(PaperPositionEntity pos, BigDecimal currentPrice,
                           VirtualBalanceEntity session, String reason) {
    // ...
    session.setAvailableKrw(session.getAvailableKrw().add(netProceeds));
    session.setTotalKrw(session.getAvailableKrw()); // ← 다른 오픈 포지션 가치 무시!
    balanceRepo.save(session);
}
```

**문제**: 매도 후 `totalKrw`를 `availableKrw`로만 설정. 만약 다중 포지션이 열려 있다면, **다른 오픈 포지션의 시가 합계가 totalKrw에서 누락**된다. [updateUnrealizedPnl()](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/PaperTradingService.java#347-359)에서 `totalKrw = availableKrw + posValue`로 재계산하지만, 해당 세션/코인의 포지션만 반영하므로 다중 포지션 시 여전히 부정확.

> [!NOTE]
> 현재 모의투자 세션은 1 세션 = 1 코인이므로 실질적 오류가 발생하지는 않음. 다만, 향후 다중 코인을 지원할 경우 반드시 수정 필요.

---

## 3. 추가 변경사항 확인

### 3.1 ✅ LiveTradingSessionEntity → `CREATED` 상태 추가

PROGRESS.md에 따르면 `@PrePersist` 기본 status가 `"STOPPED"` → `"CREATED"`로 변경됨. [createSession()](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#74-115)에서도 `.status("CREATED")`로 설정. 프론트엔드 [LiveSessionStatus](file:///d:/Claude%20Code/projects/crypto-auto-trader/crypto-trader-frontend/src/lib/types.ts#192-193) 타입에도 `'CREATED'` 포함 확인.

### 3.2 ✅ RiskManagementService `@Transactional(readOnly=true)` 수정

[getRiskConfig()](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/controller/TradingController.java#205-210)에서 readOnly 제거, `checkRisk()`에서 N+1 제거 (config 1회 로딩 후 파라미터 전달).

### 3.3 ✅ TradingController → OrderRepository 직접 의존 제거

`orderRepository.findAll()` → `orderExecutionEngine.getOrders()` 로 서비스 레이어 준수.

### 3.4 ✅ 데이터 격리 강화

| 영역 | 조치 |
|------|------|
| LiveTrading [getGlobalStatus()](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#325-351) | session 연결된 포지션/주문만 카운트 (`countBySessionIdIsNotNullAndStatus`) |
| LiveTrading [deleteSession()](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#252-286) | OPEN 포지션 있으면 삭제 거부 (orphan 방지 guard) |
| PaperTrading | `paper_trading` 스키마 전용 Entity/Repository로 완전 분리 |

---

## 4. 이전 개선 권장사항 적용 현황 (1차 → 2차 → 3차)

| # | 권장사항 | 1차 | 2차 | 3차 | 비고 |
|---|----------|-----|-----|-----|------|
| 1 | StrategyRegistry 동적 등록 | ❌ | ❌ | ❌ | 여전히 static 블록 |
| 2 | 이벤트 기반 아키텍처 | ❌ | ❌ | ⚠️ | ExchangeDownEvent 존재 |
| 3 | PortfolioManager 서비스 통합 | ❌ | ❌ | ❌ | 미적용 |
| 4 | Config 기반 Strategy 초기화 | ❌ | ❌ | ❌ | 미적용 |
| 5 | StrategyController DTO 사용 | ❌ | ❌ | ❌ | **3차까지 미적용** |
| 6 | StrategyController 입력 검증 | ⚠️ | ⚠️ | ⚠️ | OrderRequest에만 적용 |
| 7 | 예외 처리 일관성 | ✅ | ✅ | ⚠️ | GEH 개선됨, 다만 TradingController 이중 패턴 |
| 9 | Upbit Rate Limiter | ✅ | ✅ | ⚠️ | MarketDataSyncService에서 DI 미사용 |
| 13 | 서비스 레이어 단위 테스트 | ❌ | ❌ | ❌ | 미적용 |
| 14 | 통합 테스트 추가 | ✅ | ✅ | ⚠️ | 에러코드 테스트 불일치 |
| 16 | StrategyType 타입 동기화 | ✅ | ✅ | ✅ | 유지 |
| 19 | API 인증/인가 | ❌ | ❌ | ❌ | **여전히 미적용** |
| 23 | Docker healthcheck | ❌ | ❌ | ✅ | 3차에서 수정 확인 |

---

## 5. 업데이트된 아키텍처 평가

| 항목 | 1차 | 2차 | 3차 | 변화 | 설명 |
|------|-----|-----|-----|------|------|
| 모듈 분리 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | → | 변화 없음 — 여전히 우수 |
| 확장성 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | → | 변화 없음 |
| 테스트 | ⭐⭐⭐ | ⭐⭐⭐½ | ⭐⭐⭐½ | → | 에러코드 불일치로 소폭 저하 |
| 보안 | ⭐⭐⭐ | ⭐⭐⭐½ | ⭐⭐⭐½ | → | API 인증 여전히 미적용 |
| 코드 품질 | ⭐⭐⭐ | ⭐⭐⭐½ | ⭐⭐⭐⭐ | ↑ | @Async 완전 해결, GEH 개선, 데이터 격리 |
| 정확도 | - | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | → | 유지 |
| 안정성 | - | ⭐⭐⭐⭐ | ⭐⭐⭐⭐½ | ↑ | throttle 원자성, healthcheck, orphan 방지 |
| 운영성 | - | - | ⭐⭐⭐½ | 🆕 | Docker healthcheck, 텔레그램 즉시 알림 |

---

## 6. 새로운 우선순위별 작업 권장

### 🔴 즉시 수정

| # | 항목 | 영향도 |
|---|------|--------|
| 1 | BacktestControllerIntegrationTest `BACKTEST_001` → `BAD_REQUEST` 수정 | **테스트 빌드 실패** |
| 2 | API 인증/인가 추가 (Spring Security) | **실전매매 API 무방비** (1차부터 지속) |

### 🟡 단기 개선 (1~2주)

| # | 항목 | 영향도 |
|---|------|--------|
| 3 | TradingController 예외 처리 패턴 통일 | 프론트엔드 에러 응답 불일치 |
| 4 | MarketDataSyncService UpbitRestClient DI 주입 | Rate Limiting 무효화 |
| 5 | StrategyController DTO 전환 + Bean Validation | NPE, 타입 안전성 (1차부터 지속) |
| 6 | Timeframe 타입 통일 (M15/M30/H4 전 레이어 지원) | 타임프레임 선택 제한 |

### 🟢 중장기 개선 (1~3개월)

| # | 항목 | 영향도 |
|---|------|--------|
| 7 | 서비스 레이어 단위 테스트 (Mockito) | 코드 안정성 |
| 8 | PaperTradingService 다중 포지션 totalKrw 계산 | 다중 코인 지원 준비 |
| 9 | EngineConfig `@ConditionalOnProperty` 전환 | null Bean 방지 |
| 10 | 실시간 WebSocket 프론트 연동 | UX 향상 |
| 11 | 구조화 로깅 + Spring Actuator | 운영 가시성 |

---

## 7. 총평

### 개선 요약

2차 분석에서 지적한 **5개 이슈 중 4개를 수정**한 점은 훌륭한 대응이다:

- **@Async 리턴값 패턴**: 프로젝트 전체에서 완전 제거 — 비상정지 시 주문 추적 누락 가능성 해소
- **GlobalExceptionHandler**: 3종 핸들러 + 범용 에러코드 + `@ResponseStatus` 어노테이션으로 체계화
- **Rate Limiting 원자성**: `synchronized`로 다중 스레드 환경 안전 보장
- **Docker healthcheck**: 배포 안정성 대폭 향상
- 추가로 **데이터 격리** (orphan 방지, session 필터 카운트)가 강화됨

### 남은 핵심 과제

> [!CAUTION]
> **가장 시급한 2가지**: (1) 통합 테스트 에러코드 불일치 → 빌드 실패 우려, (2) API 인증/인가 — 1차 분석부터 **3차까지 지속적으로 미적용**. 실전매매 API가 외부 노출 시 자금 위험 존재.

새로 발견된 [MarketDataSyncService](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/service/MarketDataSyncService.java#27-110)의 `new UpbitRestClient()` 문제는 Rate Limiting을 우회하므로 단기 내 수정이 필요하다. [TradingController](file:///d:/Claude%20Code/projects/crypto-auto-trader/web-api/src/main/java/com/cryptoautotrader/api/controller/TradingController.java#24-239)의 이중 예외 처리 패턴은 프론트엔드 에러 핸들링 일관성에 영향을 주므로 함께 정리하는 것을 권장한다.

전체적으로 코드 품질과 안정성이 **1차 → 2차 → 3차**로 갈수록 꾸준히 향상되고 있으며, 위 권장사항 중 즉시 수정 2건만 적용하면 **실전 배포 최소 조건**을 충족할 수 있다.
