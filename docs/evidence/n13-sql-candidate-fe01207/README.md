# N13 HOLD 기준선 질의 SQL 후보 — 실제 PostgreSQL 동등성·로컬 생성 자료 측정 (계획 §5-9 69)

2026-11-04 22:27~23:30 KST. 야간 지시(`docs/OVERNIGHT_20261104.md` 2절)와 총괄 범위(`n13-review-1aa77eb`)에 따른 로컬 작업이다.
- 서버·운영 DB·외부 HTTP 에는 접속하지 않았다.
- 운영 DDL·ANALYZE·EXPLAIN ANALYZE·실제 SELECT 도 하지 않았다.
- 앱 후보는 main 에 합치지 않았다.

## 1. 고정 후보

| 항목 | 값 |
|---|---|
| 브랜치 | `candidate/n13-hold-sql-6995c52` (기점 운영 `6995c52259e3de4c7c922a32f9fb2aa9ddd49f00`, main 의 미배포 V95 등 없음) |
| 후보 SHA | `fe01207f3f60e97e308925157f84a1bb2da04388` (푸시 뒤 `git ls-remote` 원격 SHA 같음) |
| 바뀐 파일 | `StrategyLogRepository.java`(운영 코드 1) + 시험 3(`api/n13/HoldBaselinePgSupport`·`HoldBaselineSqlEquivalencePgTest`·`HoldBaselineLoopPgTest`) |
| 바꾸지 않은 것 | 서비스(`SignalQualityService`)·트랜잭션 경계·외부 호출·주기·예산(회차 200/배치 100)·마이그레이션(최고 V94)·인덱스·풀/누수 임계값·DB 설정 |
| jar | 6995c52 jar(#21 준비 때 만든 사본 `a8bdf107…`, 다시 빌드하지 않음) 대 후보 jar `73567cb3…` — §55 비교 코드 그대로 `변경 BOOT-INF/classes/com/cryptoautotrader/api/repository/StrategyLogRepository.class` 1줄, `(끝) 879 879`(`jar-sha.txt`·`jar-diff-p1.txt`) |

바뀐 SQL 은 두 질의 모두 NOT EXISTS 의 안쪽 하나뿐이다(4h 판, 24h 는 열 이름만 다르다).

```sql
-- 원문(6995c52)
  AND NOT EXISTS (
        SELECT 1 FROM strategy_log e
        WHERE e.signal = 'HOLD'
          AND e.coin_pair = l.coin_pair
          AND e.created_at >= date_trunc('hour', l.created_at)
          AND e.created_at <  date_trunc('hour', l.created_at) + interval '1 hour'
          AND e.price_after_4h IS NOT NULL)
-- 후보(C2, fe01207)
  AND NOT EXISTS (
        SELECT 1
        FROM (SELECT DISTINCT e.coin_pair, date_trunc('hour', e.created_at) AS hour_start
              FROM strategy_log e
              WHERE e.signal = 'HOLD'
                AND e.price_after_4h IS NOT NULL) d
        WHERE d.coin_pair = l.coin_pair
          AND d.hour_start = date_trunc('hour', l.created_at))
```

바깥 줄(선택 조건·`DISTINCT ON`·`ORDER BY`·`LIMIT`)은 순서까지 같다. 이것은 시험 `textualScope` 가 단언한다.

## 2. 비교한 두 SQL 형태와 고른 이유

| 판 | 형태 | 결과 |
|---|---|---|
| C1 | 상관 NOT EXISTS 에서 범위 두 조건을 `date_trunc('hour', e.created_at) = date_trunc('hour', l.created_at)` 하나로 바꿈 | limit 100 에서는 두 키 Merge Anti Join 이 나오기도 했다. 그러나 계획이 limit·자료에 따라 바뀌었다. **Nested Loop Anti 에 시간 조건 Join Filter** 가 되면 25만 S3 에서 13초, 250만 S1 limit 37·S3 limit 1·37 에서 **30초 초과**였다(r3·r4). |
| **C2(고정)** | 평가된 (coin_pair, 정시) 를 `DISTINCT` 하위 질의로 먼저 만든 뒤 등식 대조 | 측정한 모든 경우(6자료 × 4h/24h × limit 1·37·100 × custom/generic plan)에서 **두 키 Merge Anti Join**. 최대 1.44초(250만 S3·결과 0). |

C1 은 문장이 짧다. 그러나 운영처럼 남은 예산에 따라 limit 이 1~100 으로 바뀌면 30초 초과가 재현됐다. 그래서 C1 은 쓰지 않았다. 두 판의 실행 결과는 완료된 모든 경우에 구판과 같았다(4절).

## 3. 의미 동등성 — 실제 PostgreSQL 시험

- 기반: 로컬 격리 컨테이너 `n13-pg`(운영과 같은 `timescale/timescaledb` pg15 이미지 `b2618032…`, PostgreSQL 15.13), DB `n13_it`. 실제 Spring 컨텍스트를 썼고 Flyway V1~V94 를 적용했다. 설정은 `local-pg-settings.txt` 에 있다.
- 시험은 환경 변수 `N13_PG_URL` 이 있을 때만 돈다. 없으면 10건 모두 건너뛴다(`tests/run2-noenv`). 기본 H2 시험에는 영향이 없다.
- 실행: `./gradlew :web-api:test --offline --tests 'com.cryptoautotrader.api.n13.*' --tests …SignalQualityHoldBaselineTest --tests …SignalQualityDelistedMarketTest`. 23:15:35~23:16:32 KST, **16/16 통과**(첫 실행, `tests/run1-pg`). 이 시험들은 C2 반영 뒤 처음 돌렸다. C1 판으로는 돌리지 않았다.

### `HoldBaselineSqlEquivalencePgTest`(6건)

같은 연결의 REPEATABLE READ 읽기 전용 스냅숏에서, 고정 원문(시험 안 상수, 6995c52 와 바이트 같음 확인)과 후보(리포지토리 `@Query` 값)를 같은 cutoff·limit 으로 실행해 비교한다.

1. **손 기대값**: 경계 자료 30행을 쓴다(아래 목록). cutoff=H0+7h30m·limit 100 에서 4h `a0 a3 a7 b1 b2 e6 b5 (c0|c1)`, 24h `a0 e1 a3 e2 a5 a7 a8 b1 b2 b5 (c0|c1)` 가 원문·후보 모두 기대와 같다. UTC·Asia/Seoul 둘 다 확인했다.
   - 정각 정확히의 완료 행은 그 시간대를 제외한다.
   - 다음 정각 정확히의 완료 행은 앞 시간대를 제외하지 않는다.
   - 끝 직전 1µs 행은 같은 시간대에 속한다.
   - 가격 NULL 완료 행도 제외 근거다.
   - 다른 전략·세션의 완료 HOLD 도 제외 근거다.
   - BUY 완료 행은 제외 근거가 아니다.
   - 같은 시간대의 cutoff 뒤 완료 행도 제외 근거다.
   - cutoff 와 같은 시각의 행은 대상이 아니다.
   - 가격 NULL 행은 대표가 될 수 없다.
   - 4h·24h 는 서로 독립이다.
   - created_at 동률 두 행은 대표가 둘 중 하나다(비결정).
2. **limit 1·3·5 와 결과 0**.
3. **격자 대조**: cutoff 103개(모든 행 시각과 정각의 −1µs·정확값·+1µs) × limit {1,2,3,5,7,100} × 4h/24h × TimeZone {UTC, Asia/Seoul} = **2,472건**(결과 있는 경우 2,352건). 행 순서와 전체 열이 같다. 동률 시간대만 "동률 집합 안 1행" 으로 비교한다.
4. **리포지토리(Hibernate 엔티티)**: 반환 엔티티의 id·순서·코인·시각·가격·전략·세션이 원문 JDBC 결과와 같다.
5. **다음 배치**: 받은 대표 3행에만 price_after_4h 를 채운 뒤 다시 조회했다. 원문·후보 모두 같은 다음 대표 `b1 b2 e6` 를 냈다. 시간대의 둘째 행(a1·a3b)은 새 대표가 되지 않았다. 24h 는 그대로였다.
6. **문자 범위**: 원문에만 있는 6줄·후보에만 있는 7줄이 위 1절과 같다. 바깥 줄은 순서까지 같다.

### `HoldBaselineLoopPgTest`(4건, 실제 앱 경로)

정기 경로는 scheduler 스레드에서 프록시의 `evaluateSignalQuality` 를, catchup 은 `@Async` `catchupOnStartup` 을 불렀다. Upbit 는 목이다.
- **예산·배치**: 250 시간대 → 1회차 4h·24h 각 200, HOLD 질의 limit `100,100`. 2회차 50(limit `100,100`, 둘째는 0행). 3회차 0(limit `100` 한 번). 가격 조회는 400·100·0회. 시간대당 평가 행 1개, 평가 행은 각 시간대 첫 행이다.
- **가격 조회 실패**: 실패 코인 행은 미평가로 남고 다른 코인 3시간대는 평가된다. 회차는 진전이 없으면 접는다(limit `100,100`). 후속 회차는 실패 코인만 6건 평가했고 중복은 없었다.
- **질의 오류**: 4h 첫 HOLD 질의에 대역 SQLException 을 넣었다. 회차는 `DataAccessException` 으로 끝났고 24h 는 돌지 않았다. 연결은 모두 반환됐다(scheduler 스레드 보유 0, 풀 사용 0). 후속 회차는 4/4 평가했다.
- **catchup**: task 스레드에서 같은 예산·결과(limit `100,100` ×2)였다.
- 네 시험 모두 **가격 조회(목) 안에서 그 스레드의 보유 연결 0·트랜잭션 비활성** 을 단언했다. 이는 11-04 국소 재현(`n13-repro-6995c52`)의 이벤트 관찰을 후보에서 자동 단언으로 바꾼 것이다.
- 관련 기존 단위 시험 `SignalQualityHoldBaselineTest` 4·`SignalQualityDelistedMarketTest` 2 도 함께 통과했다(리포지토리 목 기반이라 SQL 과 무관하다).

의미 근거의 요약과 한계는 `semantics.md` 에 있다. 요점은 다음과 같다.
- 세션 TimeZone 의 정시 구간이 정확히 1시간인 동안(UTC·Asia/Seoul) 같은 행을 고른다.
- 서머타임 시간대는 시험하지 않았다.
- 운영 컨테이너 TZ 는 설정이 없는 temurin(기본 UTC)이다. 서버에서 다시 확인하지는 않았다.

## 4. 로컬 생성 자료 측정

- **생성**: `sql/gen.v3.sql`. 결정적이다(`setseed`, 같은 자료의 md5 가 r3·r4 에서 같음).
  - 코인 40개, 코인·시간대마다 HOLD(가격 있음) 1~29행과 그 밖의 행(BUY/SELL/가격 NULL HOLD) 0~29행을 넣었다.
  - 앱과 같게 시간대 첫 적격 HOLD 행에만 완료 값을 넣었다.
  - 기준 시각 T0=2026-11-04 12:00Z, cutoff 4h=08:00Z·24h=11-03 12:00Z, 기존 8개 인덱스(운영 `plan-out.txt` 의 정의와 문자 단위 같음).
- **분포**:
  - S1 완료 앞쪽 집중: cutoff 6시간 전까지 완료, 코인당 약 5개 시간대만 남김.
  - S2 미평가 다수: 완료 30%.
  - S3 결과 0: 전부 완료.
- **규모**: 약 25만(코인당 212시간)·약 250만(2,119시간). 250만은 운영의 오래된 추정치(252만 행, 완료 4h 약 8만, 미평가 약 118만)를 본뜬 부하 크기다. **운영 실측 행 수가 아니다.**
  - m250-S1 은 행 2,501,171·완료 4h 84,186·미평가 4h 1,186,833 이다.
- **측정**:
  - 세션마다 `statement_timeout 30s`, `EXPLAIN (ANALYZE, BUFFERS, TIMING OFF, FORMAT JSON)` 으로 직렬 실행했다.
  - 판마다 컨테이너를 재시작한 뒤 4h 3회 → 24h 3회 순서로 돌렸다. 최초 = 재시작 뒤 첫 실행이다. 공유 버퍼만 비우고 OS 캐시는 비우지 않았다. 24h 의 최초는 4h 뒤라 캐시가 데워진 상태다.
  - 초과하면 그 판의 반복을 멈추고 "30초 초과" 로 기록했다. 제한을 올리지 않았고 계획 강제 옵션도 쓰지 않았다.
- **결과 대조**: 같은 자료·cutoff·limit 으로 실제 SELECT 를 돌려 전체 열 출력의 sha256 을 비교했다.

### r3 — limit 100, 세 판(`runs/r3`, `runs/r3-summary.txt`, `runs/table-r3.md`)

| 자료 | 질의 | 구판 최초 / 후속(ms) | C1 최초 / 후속(ms) | C2 최초 / 후속(ms) | 반환 행 |
|---|---|---|---|---|---|
| 25만 S1 | 4h | 350 / 335~346 | 69 / 50~52 | 67 / 50~51 | 100 |
| 25만 S1 | 24h | 276 / 277~280 | 48 / 45~48 | 45 / 46~47 | 100 |
| 25만 S2 | 4h | 157 / 136~144 | 158 / 134~138 | 39 / 24~25 | 100 |
| 25만 S2 | 24h | 130 / 125~134 | 126 / 125~126 | 25 / 24~24 | 100 |
| 25만 S3 | 4h | 1,341 / 1,307~1,310 | 150 / 123~129 | 150 / 122~125 | 0 |
| 25만 S3 | 24h | 1,059 / 1,068~1,083 | 111 / 113~119 | 120 / 109~112 | 0 |
| 250만 S1 | 4h | **27,898 / 27,319~27,899** | 494 / 404~405 | **463 / 385~428** | 100 |
| 250만 S1 | 24h | **26,242 / 26,252~28,201** | 356 / 365~428 | **384 / 358~368** | 100 |
| 250만 S2 | 4h | 1,351 / 1,265~1,295 | 1,365 / 1,272~1,424 | 192 / 105~120 | 100 |
| 250만 S2 | 24h | 1,145 / 1,145~1,150 | 1,142 / 1,136~1,154 | 104 / 104~105 | 100 |
| 250만 S3 | 4h | **30초 초과** | 1,598 / 1,129~1,338 | **1,312 / 1,224~1,236** | 0 |
| 250만 S3 | 24h | **30초 초과** | 1,226 / 1,138~1,263 | **1,421 / 1,356~1,444** | 0 |

- **구판 계획**(250만 S1 4h): Merge Anti Join, Merge Cond 는 `coin_pair` 만이다. Join Filter 가 **280,443,933행** 을 버렸고, 안쪽 Materialize 가 2.8억 행을 되감았다. temp 쓰기는 약 17,000블록이다. 11-04 운영 계획(`n13-plan-6995c52`)과 같은 모양이다.
- **C2 계획**: Merge Anti Join, Merge Cond `(coin_pair, date_trunc('hour'))` 두 키. Join Filter 가 없고, 안쪽은 Seq Scan → Sort → Unique(84,186→18,932)다.
- **결과 해시**: 구판이 끝난 모든 경우(limit 1·100)에 세 판의 출력이 같았다. 250만 S3 는 구판이 30초를 넘어 대조하지 못했다. 그 경우 C2 는 0행이다.

### r4 — limit 1·37 과 준비문 generic plan, C1·C2 각 1회(`runs/r4`, `runs/r4-summary.txt`)

생성 직후 `VACUUM (ANALYZE)` 로 autovacuum 겹침을 없애고 재시작한 뒤 측정했다.
- **C2**: 모든 경우에 두 키 Merge Anti Join 이었다. 250만 S1 은 103~372ms, S2 는 101~107ms, S3 는 1,186~1,425ms 다. limit 1·37 결과는 구판이 끝난 경우 모두 해시가 같았다. 250만 S1 limit 37 과 S3 는 구판이 30초를 넘었다.
- **C1**: custom plan 의 limit 1·37 이 Nested Loop Anti + 시간 Join Filter 였다. 25만 S3 에서 10.9~13.5초, 250만 S1 limit 37·S3 limit 1·37 에서 **30초 초과**였다. generic plan 에서는 두 키 Merge 였다.

### 측정 이력과 조건(그대로 남김)

- **r1**(22:35~22:42): 생성 v1(`sql/gen.v1.sql`). 25만 세 세트는 완료했다. 250만 S1 의 완료 표시 UPDATE 가 5분 넘게 끝나지 않아 중단했다.
- **r2**(22:41~22:47): 생성 v2(임시 표 ANALYZE 추가, `sql/gen.v2.sql`). 25만 세 세트는 완료했다. 250만 S1 에서 같은 증상이 나왔다.
  - 원인: 대량 INSERT 직후 `strategy_log` 통계가 없어 UPDATE 가 Nested Loop(약 85K×84K)로 계획됐다. 같은 DB 에서 EXPLAIN 으로 확인했다.
  - 그 확인 실행이 측정 반복문과 겹쳤다. 반복문이 m250-S2 로 넘어가 구판 4h 2회(2.6초)를 찍었고, 그 수치는 **같은 표를 다른 세션이 비우던 중이라 무효** 로 둔다(`runs/r2/console.txt`, JSON 없음).
- **r3**(22:52~23:00): 생성 v3(INSERT 뒤 ANALYZE). 판정에 쓴 세트다. 250만 S1 은 생성 뒤 autovacuum 이 22:51:54 KST 까지 돌아 구판 4h 첫 실행(22:51:49 기록)과 일부 겹쳤다. 구판 세 번의 수치가 27.3~27.9초로 비슷해 판정을 바꾸지 않는다.
- **25만 r1·r2·r3**: 같은 자료(md5 같음)의 구판·후보 수치가 서로 비슷하다. 판정은 r3 만으로 한다.
- **로컬 기반 한계**:
  - Docker 16 CPU·16GB, shared_buffers 4.9GB 다. 250만 행 표(약 900MB)가 공유 버퍼에 다 들어간다.
  - 운영 서버의 하드웨어·설정·병렬 작업자·캐시 상태와 다르다. 운영 계획은 작업자 5개였고, 로컬 250만도 5개가 계획·실행됐다.
  - 로컬 수치를 운영 시간으로 옮기지 않는다.
  - 운영 141~545초가 이 질의 시간이라는 것도 여전히 확정하지 않는다. 로컬 구판 계획 모양과 시간 규모가 그 후보와 맞는다는 것까지만 말한다.

## 5. 인덱스 대안 — 실험하지 않음

C2 는 SQL 만으로 250만 행 모든 분포에서 1.5초 안이었다. 그래서 인덱스 대안은 실험하지 않았다(지시 6절의 "SQL 만으로 부족하면"에 해당하지 않음).
- 남은 비용은 안쪽 `strategy_log` 전체 Seq Scan(실행마다 표 전체)이다. 표가 커지면 선형으로 는다.
- 인덱스가 필요한지(예: 평가된 HOLD 만 담은 부분 인덱스)는 총괄 판단으로 남긴다. 그 경우 정의·크기·쓰기 부담을 로컬에서 따로 잰다.

## 6. 파일

| 파일 | 내용 |
|---|---|
| `sql/orig_*.sql`·`c1_*.sql`·`c2_*.sql` | 측정한 SQL 원문(`:cutoff`·`:limit` 자리표시자). `orig` 는 6995c52 리포지토리와, `c2` 는 fe01207 리포지토리 `@Query` 값과 바이트가 같음(확인함) |
| `sql/gen.v1~v3.sql` | 생성 SQL 판별 이력 |
| `tool/bench.py`·`bench2.py`·`summ.py` | 측정·요약 실행기(스크래치 작성, 근거 사본) |
| `runs/r1~r4/` | 원본 JSON(계획 JSON·버퍼·시각·결과 해시)과 콘솔. `runs/smoke` 는 30시간 자료 첫 확인 |
| `runs/r3-summary.txt`·`r4-summary.txt`·`table-r3.md` | 요약 |
| `tests/run1-pg/`·`run2-noenv/` | JUnit XML·Gradle 콘솔(UTF-8 변환)·소스 sha256·실행 조건 |
| `local-pg-settings.txt`·`semantics.md`·`jar-sha.txt`·`jar-diff-p1.txt` | 로컬 DB 설정, 의미 근거, jar 대조 |
| `.gitattributes`(`* -text`)·`SHA256SUMS` | 원본 바이트 보존 |

- jar 원본(각 약 90MB)은 Git 에 넣지 않았다. 이 PC 의 스크래치 `n13d/jars/` 와 `cat-n13/web-api/build/libs/` 에 있다.
- 로컬 DB 컨테이너 `n13-pg`(127.0.0.1:15433, 신뢰 인증·로컬 전용)는 남겨 두었다. 운영 자료는 들어 있지 않다.
- 이 근거를 담은 main 커밋의 SHA 는 여기에 적지 않는다(N06).
