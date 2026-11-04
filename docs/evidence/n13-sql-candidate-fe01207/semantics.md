## 의미 동등성 — 왜 두 식이 같은 행을 고르는가

바뀐 것은 NOT EXISTS 안의 "같은 시간대" 조건 하나다.

| | 조건 |
|---|---|
| 원문(6995c52) | `e.created_at >= date_trunc('hour', l.created_at) AND e.created_at < date_trunc('hour', l.created_at) + interval '1 hour'` |
| 후보(C1) | `date_trunc('hour', e.created_at) = date_trunc('hour', l.created_at)` |

- `T = date_trunc('hour', l.created_at)` 라 하자. 세션 TimeZone 의 정시 구간이 정확히 1시간이면, `date_trunc('hour', x) = T` 와 `T <= x < T + 1시간` 은 같은 뜻이다.
  - UTC·Asia/Seoul 은 오프셋 변화가 없어 이 조건을 늘 만족한다.
  - 운영 컨테이너는 TZ 설정이 없는 `eclipse-temurin:17-jre-alpine`(기본 UTC)이다. 서버에서 다시 확인하지는 않았다.
- **같지 않을 수 있는 경우**: 서머타임처럼 오프셋이 바뀌는 시간대에서 그 전환 시각 근처의 행. 이 앱 설정에는 해당하지 않는다. 시험은 UTC·Asia/Seoul 두 가지로만 했다.
- **NULL**:
  - `e.created_at` 이 NULL 이면 두 식 모두 참이 아니다.
  - `l.created_at` 이 NULL 인 행은 `l.created_at < :cutoff` 에서 이미 빠진다.
  - `coin_pair` 는 NOT NULL 이다.
- **그대로 둔 것**:
  - `l` 의 조건(HOLD·cutoff·signal_price·price_after_*)
  - `e` 의 조건: HOLD·같은 코인·price_after_* 만 본다. 가격·전략·세션·timeframe·cutoff 조건은 없다.
  - `DISTINCT ON` 의 키, `ORDER BY`, `LIMIT`, 반환 열(`l.*`)
  - 4h·24h 의 독립: 각 질의는 자기 열만 본다.
- **동률**: 같은 코인·시간대 안에서 created_at 이 같은 적격 행이 둘 이상이면, 원문의 대표 선택은 비결정적이다(ORDER BY 가 created_at 까지만 정한다). 후보도 같은 ORDER BY 를 쓰므로 같은 성질이다. 시험은 그 시간대의 대표가 동률 집합 안의 한 행인지만 보고, 나머지 시간대는 행과 전체 열을 정확히 비교한다. 동률을 깨는 새 정책(id 순 등)은 넣지 않았다.
