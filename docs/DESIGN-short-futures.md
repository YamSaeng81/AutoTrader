# DESIGN — 숏(선물) 도입 설계 스케치

> **상태**: 설계 스케치 (미구축). 실제 구현 전 검토·합의용.
> **작성**: 2026-07-27
> **전제**: 사용자 방침 — 업비트 현물은 롱 전용. 하락장 수익 구조를 위해 **별도 파생(숏) 거래소**를 검토.
> **결론 요약**: "전략 두뇌"는 재사용, "주문·포지션·리스크(손)"는 신규 + **주문 추상화 계층 신설** 필요. 규모 중~대.

---

## 0. 현 구조 실측 (스코프 판단 근거)

| 사실 | 근거 | 함의 |
|---|---|---|
| `ExchangeAdapter`는 **시세(캔들)만** 추상화 | [ExchangeAdapter.java](../exchange-adapter/src/main/java/com/cryptoautotrader/exchange/adapter/ExchangeAdapter.java) — `fetchCandles`·`getSupportedCoins`뿐 | 주문 경로엔 거래소 교체 지점이 **없음** |
| 주문은 업비트 하드코딩 | `OrderExecutionEngine` → `upbitOrderClient.createOrder(...)` 직접 호출 | **주문 추상화 신설**이 선행 과제 |
| 포지션 롱 전용 | `position.side` 컬럼은 있으나 매수진입→매도청산만 사용 | `exchange`·`market_type`·`leverage`·`liquidation_price` 개념 없음 |
| 세션에 거래소/시장유형 차원 없음 | LIVE/DYNAMIC/PAPER kind만 | 다거래소·선물 세션 모델 확장 필요 |

기존 `UpbitOrderClient` 노출 메서드(참고): `createOrder(market, side, volume, price, orderType)`, `getOrder(uuid)`, `cancelOrder(uuid)`, `getAccounts()`.

---

## 1. 거래소 선택

| | Binance Futures | **Bybit (추천)** | OKX |
|---|---|---|---|
| 유동성/API | 최상 | 상 (v5 통합 API 깔끔) | 상 |
| 한국 리테일 접근성 | 규제·제한 큼 | 상대적으로 접근 사례 많음 | 중 |
| 최소주문 | ~5 USDT | ~5 USDT | 낮음 |
| 테스트넷 | 있음 | **있음(우수)** | 있음 |

**추천: Bybit.** v5 통합 REST/WS가 알고 트레이딩에 깔끔하고 테스트넷이 좋아 **무자본 검증**이 쉽다.
⚠️ **한국 접근성(KYC·지역 제한)은 사용자가 직접 확인**해야 함 — 이게 Phase 0 선행 관문.

### ⚠️ 핵심 주의 — 견적 통화 불일치
업비트는 **KRW 페어**, Bybit 선물은 **USDT 마진**. 즉 숏 세션의 자본·손익은 **USDT 기준**이 된다.
→ 자본 회계, 손익 대시보드, 원화 환산(FX)에 별도 처리 필요. (현물 KRW 세션과 통합 뷰 설계 시 고려)

---

## 2. 아키텍처 계층 (재사용 vs 신규)

```
[전략 두뇌]  CompositeStrategy / RSI-MACD 등 → BUY/SELL 신호        ★재사용
      │        (SELL·하락모멘텀 = 숏 진입 시그널로 해석)
      ▼
[방향 매핑]  SignalDirectionMapper                                 ☆신규(소)
      │        롱: BUY→진입 / SELL→청산
      │        숏: SELL→진입(sell-to-open) / BUY→청산(buy-to-close, reduceOnly)
      ▼
[주문 게이트웨이]  interface OrderGateway  ← 신규 추상화 핵심        ☆신규(핵심)
      ├─ UpbitSpotGateway    (기존 UpbitOrderClient 래핑)
      └─ BybitFuturesGateway (신규: 서명·레버리지·포지션·reduceOnly)
      ▼
[주문 실행엔진]  OrderExecutionEngine — 게이트웨이 주입형 리팩터      ☆리팩터
      ▼
[포지션/리스크]  side=SHORT, 청산가 추적, 펀딩비, 역방향 SL/TP       ☆신규(중)
```

**재사용:** 전략 신호, 세션 라이프사이클, 레짐 감지, 텔레그램/디스코드 리포팅, 스케줄러, 손익 대시보드 골격.

---

## 3. 신규/변경 컴포넌트 (스코프)

| 항목 | 내용 | 규모 |
|---|---|---|
| ① OrderGateway 추상화 | 지금 없는 "주문" 인터페이스 신설. 기존 Upbit 경로를 뒤로 리팩터 | 중 (숏 없이도 이득) |
| ② Bybit 어댑터 | v5 REST(HMAC 서명)·WS(public+private)·심볼매핑·인증 | 대 |
| ③ 숏 포지션 시맨틱 | side=SHORT, 진입=매도개시/청산=매수마감, **PnL 부호 반전**, **SL 진입가 위·TP 아래** | 중 |
| ④ 레버리지·청산 | 레버리지 설정, **청산가 계산·근접 가드**, 격리마진 | 중~대 |
| ⑤ 펀딩비 | 주기적 accrual 반영·리포팅 | 소~중 |
| ⑥ 데이터 모델 | position/session에 `exchange`·`market_type`·`leverage`·`liquidation_price` 필드 | 소 |
| ⑦ 리스크 사이징 | 청산 인지 사이즈·최대 레버리지 캡(1~2배 권장) | 중 |
| ⑧ 시크릿/설정 | Bybit 키 `.env`, 테스트넷 토글 | 소 |
| ⑨ 백테스트 | 숏 방향 백테스트(펀딩·청산 근사) | 중 |

---

## 4. Phase 1 상세 설계

### 4.1 OrderGateway 인터페이스 초안

기존 Upbit 경로와 Bybit 선물을 모두 담는 **거래소·시장유형 무관 주문 계약**. 숏은 `intent` + `reduceOnly`로 표현.

```java
public interface OrderGateway {
    String exchangeName();          // "UPBIT" | "BYBIT"
    MarketType marketType();        // SPOT | FUTURES
    boolean supportsShort();        // Upbit=false, Bybit=true

    // ── 주문 ──
    OrderAck   submitOrder(GatewayOrder order);
    OrderState getOrder(String exchangeOrderId, String symbol);
    void       cancelOrder(String exchangeOrderId, String symbol);

    // ── 잔고/포지션 ──
    List<Balance>              getBalances();
    Optional<ExchangePosition> getPosition(String symbol); // 현물은 Optional.empty()

    // ── 선물 전용 (현물 구현체는 no-op) ──
    default void setLeverage(String symbol, int leverage) { /* spot: no-op */ }
}

// 방향·의도를 명시적으로 — 숏 진입/청산을 부호가 아니라 타입으로 안전하게 표현
enum PositionIntent { OPEN_LONG, CLOSE_LONG, OPEN_SHORT, CLOSE_SHORT }
enum OrderSide      { BUY, SELL }
enum OrderType      { MARKET, LIMIT }
enum MarketType     { SPOT, FUTURES }

record GatewayOrder(
    String symbol,             // 거래소 심볼 (KRW-BTC / BTCUSDT)
    OrderSide side,            // BUY | SELL (거래소로 나가는 실제 방향)
    PositionIntent intent,     // OPEN_SHORT 등 — 매핑·검증·로깅용
    OrderType type,            // MARKET | LIMIT
    BigDecimal qty,            // 수량 (선물·현물 매도)
    BigDecimal quoteAmount,    // 현물 시장가 매수용 총액(Upbit price-type). 그 외 null
    BigDecimal price,          // LIMIT 가격
    boolean reduceOnly,        // 청산 주문 여부(선물). CLOSE_* → true
    Integer leverage           // 선물 레버리지(진입 시)
) {}

record OrderAck(String exchangeOrderId, String rawStatus) {}
record OrderState(String exchangeOrderId, String status,   // FILLED/PARTIAL/CANCELED...
                  BigDecimal filledQty, BigDecimal avgPrice, BigDecimal fee) {}
record Balance(String asset, BigDecimal free, BigDecimal locked) {}
record ExchangePosition(String symbol, String side,        // LONG/SHORT/NONE
                        BigDecimal size, BigDecimal avgPrice,
                        BigDecimal liquidationPrice, BigDecimal unrealizedPnl) {}
```

**의도↔실주문 매핑 (SignalDirectionMapper):**

| intent | side | reduceOnly | 비고 |
|---|---|---|---|
| OPEN_LONG | BUY | false | 현물/선물 롱 진입 |
| CLOSE_LONG | SELL | true | 롱 청산 |
| OPEN_SHORT | SELL | false | **숏 진입(sell-to-open)** |
| CLOSE_SHORT | BUY | true | **숏 청산(buy-to-close)** |

`UpbitSpotGateway`는 OPEN_SHORT/CLOSE_SHORT 요청 시 `UnsupportedOperationException`(현물 숏 불가) — 방어.

### 4.2 Bybit v5 API 매핑

| Gateway 메서드 | Bybit v5 엔드포인트 | 핵심 파라미터 |
|---|---|---|
| submitOrder | `POST /v5/order/create` | category=linear, symbol, side(Buy/Sell), orderType(Market/Limit), qty, price, reduceOnly |
| getOrder | `GET /v5/order/realtime` (+history 폴백) | category=linear, orderId |
| cancelOrder | `POST /v5/order/cancel` | category=linear, symbol, orderId |
| getBalances | `GET /v5/account/wallet-balance` | accountType=UNIFIED |
| getPosition | `GET /v5/position/list` | category=linear, symbol → size, side, avgPrice, **liqPrice**, unrealisedPnl |
| setLeverage | `POST /v5/position/set-leverage` | symbol, buyLeverage, sellLeverage |
| fetchCandles (ExchangeAdapter) | `GET /v5/market/kline` | category=linear, symbol, interval |
| WS 시세(public) | `wss://stream.bybit.com/v5/public/linear` | topic: tickers.{symbol} |
| WS 체결/포지션(private) | `wss://stream.bybit.com/v5/private` | 인증 후 order/position/execution |

**인증(서명):** HMAC-SHA256.
`sign = HMAC(secret, timestamp + apiKey + recvWindow + (queryString | jsonBody))`
헤더: `X-BAPI-API-KEY`, `X-BAPI-TIMESTAMP`(ms), `X-BAPI-SIGN`, `X-BAPI-RECV-WINDOW`(예 5000).
(업비트의 JWT 서명과 완전히 다름 — 신규 서명 유틸 필요.)

**테스트넷:** `https://api-testnet.bybit.com` / `wss://stream-testnet.bybit.com`.

### 4.3 데이터 모델 확장 (향후 마이그레이션 Vxx)

```sql
-- position
ALTER TABLE position ADD COLUMN exchange          VARCHAR(20)  NOT NULL DEFAULT 'UPBIT';
ALTER TABLE position ADD COLUMN market_type        VARCHAR(10)  NOT NULL DEFAULT 'SPOT';   -- SPOT | FUTURES
ALTER TABLE position ADD COLUMN leverage           INTEGER      NOT NULL DEFAULT 1;
ALTER TABLE position ADD COLUMN liquidation_price   NUMERIC;                                -- 선물 숏/롱 청산가
-- position.side 는 이미 존재 → LONG/SHORT 실제 사용 시작

-- live_trading_session (또는 세션 엔티티)
ALTER TABLE ... ADD COLUMN exchange     VARCHAR(20) NOT NULL DEFAULT 'UPBIT';
ALTER TABLE ... ADD COLUMN market_type   VARCHAR(10) NOT NULL DEFAULT 'SPOT';
ALTER TABLE ... ADD COLUMN direction     VARCHAR(12) NOT NULL DEFAULT 'LONG_ONLY';          -- LONG_ONLY | SHORT_ONLY
ALTER TABLE ... ADD COLUMN leverage      INTEGER     NOT NULL DEFAULT 1;
```

기본값 덕에 **기존 업비트 현물 데이터는 무영향**(exchange=UPBIT, SPOT, LONG, lev 1).

### 4.4 숏 SL/TP·청산 역산 (⑬ 안전 핵심)

| | 롱 | **숏** |
|---|---|---|
| 진입 | 매수 | 매도 |
| SL(손절) | 진입가×(1−x), 가격 ≤ SL 발동 | **진입가×(1+x), 가격 ≥ SL 발동** |
| TP(익절) | 진입가×(1+y), 가격 ≥ TP | **진입가×(1−y), 가격 ≤ TP** |
| 청산 | (현물 없음) | **진입가 위쪽** — 근접 시 가드/강제 축소 |
| PnL | (현재−진입)×수량 | **(진입−현재)×수량** |

`OrderExecutionEngine`·SL 감시(§9)·PnL 계산에 side 분기 필요.

### 4.5 Phase 1 실행 순서 (무자본 우선)

1. **Phase 0 관문** — Bybit 계정·API·**한국 접근성 확인**. (여기서 막히면 전체 보류)
2. `exchange-adapter`에 `com.cryptoautotrader.exchange.bybit` 패키지 신설.
3. **BybitRestClient(시세)** — `ExchangeAdapter` 구현(`/v5/market/kline`). 서명 유틸 포함. → **읽기 전용, 무위험.**
4. **BybitFuturesGateway(테스트넷)** — submitOrder/getOrder/cancel/getPosition/setLeverage를 **테스트넷**에 대해 구현.
5. **설정** — `bybit.api-key/secret/testnet` (application.yml) + `.env`(`BYBIT_API_KEY` 등). 실매매 엔진엔 아직 미배선.
6. **독립 통합 테스트(테스트넷)** — 소액 숏 진입→포지션 조회(liqPrice 확인)→reduceOnly 청산 왕복 검증. **메인 시스템 무영향.**
7. **심볼 매핑 유틸** — KRW-BTC ↔ BTCUSDT (+ 견적통화 불일치 주석).

> ①(OrderGateway 추상화 + UpbitSpotGateway 래핑)은 숏과 무관하게 **지금 해도 이득인 리팩터**라, Phase 1과 병행하거나 선행하면 Upbit 결합을 안전하게 풀 수 있다. 기존 `OrderExecutionEngineTest` 통과로 회귀 검증.

---

## 5. 규모·리스크 요약

- **규모:** 중~대. "기능 추가"가 아니라 **주문 추상화 + 신규 거래소 + 파생 리스크 3워크스트림.**
- **핵심 리스크:** ①청산(소액도 증거금 전액 손실 가능) ②한국 규제/접근성 ③펀딩비 드래그 ④API rate limit ⑤견적통화(USDT) 회계.
- **де-리스킹:** OrderGateway 추상화부터(숏 무관 이득) → Bybit **테스트넷**에서 무자본 검증 → 레버리지 1~2배 소액 → 메인넷.

---

## 6. 다음 단계 후보

- [ ] Phase 0 — Bybit 접근성 확인 (사용자)
- [ ] ① OrderGateway 추상화 + UpbitSpotGateway 래핑 (리팩터, 회귀테스트)
- [ ] ③ BybitRestClient 시세 어댑터 (읽기 전용)
- [ ] ④ BybitFuturesGateway 테스트넷 + 통합테스트
- [ ] 숏 SL/TP·청산 로직 + 데이터 모델 마이그레이션
- [ ] SELL 신호 → 숏 진입 매핑 + 숏 백테스트
