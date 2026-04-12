# COMPOSITE_MOMENTUM 전략 분석

> MACD 모멘텀 + VWAP 가치 평가 + GRID 분할 매매로 구성된 **대형 코인 모멘텀 복합 전략**.
> BTC·ETH 등 거래량이 풍부한 대형 코인에 최적화.

---

## 1. 전략 구성

| 하위 전략 | 가중치 | 역할 |
|-----------|--------|------|
| **MACD** | **0.5** | 중기 모멘텀 방향 + 추세 전환 감지 |
| **VWAP** | **0.3** | 거래량 가중 평균 가격 대비 할인/프리미엄 판단 |
| **GRID** | **0.2** | 가격 격자 기반 분할 매수/매도 |
| **EMA 방향 필터** | — | 추세 역행 신호 억제 (활성화) |

### 설계 원칙

```
MACD = 핵심 트리거 (모멘텀 방향 + 크로스오버)
VWAP = 가치 평가 보조 (적정 가격 대비 할인/프리미엄)
GRID = 횡보 구간 분할 매매 보조
EMA 방향 필터 = 하락 추세 BUY / 상승 추세 SELL 억제
```

> **Stateful 전략**: GridStrategy가 그리드 레벨 상태를 보유하므로 세션마다 새 인스턴스를 생성합니다.

---

## 2. 가중 투표 엔진

각 하위 전략이 BUY / SELL / HOLD 신호와 **신호 강도(confidence, 0.0~1.0)**를 반환하면,
엔진이 가중치와 곱하여 점수를 합산합니다.

```
buyScore  = Sigma(weight_i x confidence_i)   <- BUY 신호 전략만 합산
sellScore = Sigma(weight_i x confidence_i)   <- SELL 신호 전략만 합산
```

### 최종 신호 판단 기준

| 조건 | 결과 |
|------|------|
| buyScore > 0.4 AND sellScore > 0.4 | **HOLD** (상충) |
| buyScore > **0.6** | **STRONG BUY** |
| sellScore > **0.6** | **STRONG SELL** |
| buyScore > **0.4** | **BUY** |
| sellScore > **0.4** | **SELL** |
| 그 외 | **HOLD** |

---

## 3. 하위 전략 상세

### 3-1. MACD (가중치 0.5 — 핵심 트리거)

MACD(12/26/9) 기반 모멘텀 추세 판단.

- **BUY**: MACD 라인이 시그널 라인을 상향 돌파 (골든크로스)
- **SELL**: MACD 라인이 시그널 라인을 하향 돌파 (데드크로스)
- 히스토그램 방향으로 모멘텀 강화/약화 판단

### 3-2. VWAP (가중치 0.3 — 가치 평가 보조)

거래량 가중 평균 가격 대비 현재가의 위치를 평가.

- **BUY**: 현재가 < VWAP (할인 구간 — 저평가)
- **SELL**: 현재가 > VWAP (프리미엄 구간 — 고평가)
- 거래량이 풍부한 대형 코인에서 VWAP 신뢰도가 높음

### 3-3. GRID (가중치 0.2 — 분할 매매 보조)

최근 100개 캔들의 고저 범위를 10등분한 격자에서 분할 매수/매도.

- **BUY**: 현재가 위치 <= 하위 30% + 그리드 레벨 근접
- **SELL**: 현재가 위치 >= 상위 30% + 그리드 레벨 근접
- 중복 진입 방지를 위해 레벨별 상태 관리

---

## 4. EMA 방향 필터

```
EMA(20) > EMA(50) -> 상승 추세 -> SELL 신호를 HOLD로 억제
EMA(20) < EMA(50) -> 하락 추세 -> BUY 신호를 HOLD로 억제
```

GRID·VWAP 등 역추세 성격의 하위 전략이 포함되어 있으므로,
강한 추세 구간에서 역방향 진입을 방지하는 안전장치 역할.

---

## 5. 백테스트 결과 (2023~2025년, KRW-BTC, H1)

| 항목 | 수치 |
|------|------|
| **총 수익률** | **+115.72%** |
| **승률** | 40.0% |
| **MDD (최대 낙폭)** | -25.62% |
| **테스트 완료** | 2026.04.10 14:48 |

### 수익률 분석

- 3년간 약 2.15배 수익 (+115.72%)
- 승률 40%이지만 손익비가 높아 전체 수익 양호
- MDD -25.62%는 암호화폐 시장 특성상 수용 가능한 수준이나, BTC 장기 횡보/급락 구간에서 낙폭 발생 가능

### 강점

- MACD 가중치가 0.5로 높아 **추세 전환 포착에 유리**
- VWAP 기반 가치 평가로 **과매수 구간 진입 자제**
- EMA 방향 필터로 **추세 역행 신호 억제**

### 약점

- 승률 40%로 연속 손실 구간(drawdown) 발생 가능
- GRID 전략은 횡보장에서 유리하나, 강한 추세장에서는 기여도 낮음
- VWAP은 거래량 부족 시 신뢰도 하락 (소형 알트 비권장)

---

## 6. Walk Forward 검증 (2023~2025년, KRW-BTC, H1)

| 항목 | 수치 |
|------|------|
| **수익률** | **7.4%** |
| **결과** | 통과 |
| **검증 완료** | 2026.04.10 15:28 |

### Walk Forward 분석

- Walk Forward 7.4%로 **통과** 판정
- 백테스트 대비 수익률 격차가 큼 (+115.72% vs 7.4%)
- 이는 과적합(overfitting) 가능성을 시사하나, Walk Forward에서 양수 수익으로 통과한 것은 전략이 미래 데이터에도 일정 수준 유효함을 의미
- 실전 배포 시 백테스트 수익률이 아닌 **Walk Forward 수준(7~15%)을 현실적 기대치**로 설정해야 함

---

## 7. 종합 평가

| 평가 항목 | 등급 | 비고 |
|----------|------|------|
| 수익성 | **B+** | 백테스트 우수, Walk Forward 양호 |
| 안정성 | **B** | MDD -25.62%, 승률 40% |
| 과적합 위험 | **주의** | 백테스트-WF 격차 큼 |
| 적합 코인 | BTC, ETH | 거래량 풍부한 대형 코인 |
| 적합 타임프레임 | H1 | 1시간봉 최적화 |

### 권장 사항

1. **실전 배포 시 리스크 관리** 필수 (포지션 사이징, 손절 설정)
2. **소형 알트에 적용 금지** — VWAP 신뢰도 문제
3. COMPOSITE_MOMENTUM_ICHIMOKU 대비 필터가 약하므로, 추세 전환 구간에서 오신호 위험 상대적으로 높음
4. 단독 운용보다 COMPOSITE_MOMENTUM_ICHIMOKU와 성과 비교 후 선택 권장

---

## 구현 파일 위치

| 항목 | 파일 |
|------|------|
| 전략 등록 | [CompositePresetRegistrar.java](../../web-api/src/main/java/com/cryptoautotrader/api/config/CompositePresetRegistrar.java) |
| 가중 투표 엔진 + EMA 필터 | [CompositeStrategy.java](../../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeStrategy.java) |
| MACD 전략 | [MacdStrategy.java](../../strategy-lib/src/main/java/com/cryptoautotrader/strategy/macd/MacdStrategy.java) |
| VWAP 전략 | [VwapStrategy.java](../../strategy-lib/src/main/java/com/cryptoautotrader/strategy/vwap/VwapStrategy.java) |
| GRID 전략 | [GridStrategy.java](../../strategy-lib/src/main/java/com/cryptoautotrader/strategy/grid/GridStrategy.java) |
| 복합 전략 가이드 | [COMPOSITE_STRATEGIES_GUIDE.md](../COMPOSITE_STRATEGIES_GUIDE.md) |
