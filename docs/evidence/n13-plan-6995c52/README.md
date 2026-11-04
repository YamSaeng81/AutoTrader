# N13 운영 실행 계획 조회 결과 — 계획 §5-9 67

2026-11-04 KST. 총괄이 승인한 고정 SQL `docs/evidence/n13-next-scope/n13-plan-readonly.sql` 을 **1회** 실행했다. N03 근거와는 따로 둔다.

## 1. 실행

- 실행 전 로컬 해시 `79d12e37…9c91` 이 총괄 값과 같음을 확인했다.
- 실행 경로는 기존 서버 내부 DB 경로다. `ssh cat-prod` → `docker compose -f docker-compose.prod.yml exec -T db psql -X -w -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader`.
- SQL 은 로컬 파일을 표준 입력으로 바이트 그대로 넘겼다. 지시의 PowerShell `Get-Content | ssh` 대신 Git Bash `ssh … < 파일` 을 썼다. 서버에 SQL 사본을 쓰지 않았다.
- 비밀번호는 대화로 주고받지 않았다.
- 시각은 21:26:31~21:26:32 KST 다. 종료값은 0이고 표준 오류는 0바이트다. 재시도는 없었다.
- 범위: `BEGIN TRANSACTION READ ONLY` … `ROLLBACK`. 통계 추정 행 수, 인덱스 정의·유효성, `EXPLAIN (FORMAT JSON, COSTS TRUE)` 두 개만 조회했다.
- 하지 않은 것: 실제 SELECT 실행, `EXPLAIN ANALYZE`, COUNT, ANALYZE/VACUUM, 인덱스 생성, 앱·영구 DB 설정 변경.

## 2. 읽은 값(추정치, 실측 아님)

- `strategy_log` 추정 행 2,521,187, 페이지 89,355.
- `last_analyze` 는 비어 있고, `last_autoanalyze` 는 2026-10-17 13:57:41Z 다. 통계는 조회 시점보다 18일 전 값이다.
- 인덱스 8개가 모두 `indisvalid=t`, `indisready=t` 다.
  - HOLD 기준선용 부분 인덱스는 `idx_hold_baseline_eval (coin_pair, created_at) WHERE signal='HOLD' AND signal_price IS NOT NULL` 이다.
  - `price_after_4h`/`price_after_24h` 를 조건이나 열로 가진 인덱스는 없다.
- 두 질의(4h·24h)의 계획 모양은 같다(`plan-summary.txt`). 병렬 작업자는 Gather 마다 5개로 계획됐다.
  - 바깥 `l`: `idx_hold_baseline_eval` Index Scan, `created_at < now()-4h/24h`, Filter `price_after_* IS NULL`. 추정 236,295행(병렬 작업자당), Gather Merge 뒤 1,181,477행이다. Incremental Sort 가 `coin_pair` 를 미리 정렬된 키로 쓴다.
  - 안쪽 `e`: **Seq Scan strategy_log**, Filter `price_after_* IS NOT NULL AND signal='HOLD'`. 추정 15,967행(작업자당)이고, Gather 뒤 79,833행을 `coin_pair` 로 Sort·Materialize 한다.
  - 결합: **Merge Anti Join**. Merge Cond 는 `l.coin_pair = e.coin_pair` 하나뿐이다. 시간대 조건(`e.created_at` 이 `l` 의 정시 1시간 안)은 **Join Filter** 로 남았다.
  - 위: Unique(DISTINCT ON), Limit 100. Unique 총비용 추정은 4h 419,020,892, 24h 413,778,855 다. Limit 총비용 추정은 155,790 / 155,090 이다.
- 이 계획의 의미(해석, 확정 아님):
  - 같은 코인 안에서는 `l` 행마다 `e` 행들을 Join Filter 로 거쳐야 짝이 없음을 안다. 그래서 결합 비용이 코인별 행 수의 곱에 가깝게 잡혔다.
  - LIMIT 100 의 비용 추정은 앞쪽에서 100행이 빨리 나온다는 가정이다. 이미 평가된 시간대가 앞쪽에 몰리면 실제로는 더 많은 행을 거쳐야 한다.
  - 안쪽 Seq Scan 은 실행마다 테이블 전체(추정 89,355페이지)를 읽는다.

## 3. 한계

- 모든 수치는 플래너 추정치다. 실제 실행 시간·실제 행 수·버퍼는 보지 않았다(`EXPLAIN ANALYZE` 제외).
- 통계가 10-17 값이라 현재 분포와 다를 수 있다. ANALYZE 는 범위 밖이라 하지 않았다.
- 운영의 추정 대여 141~545초와 이 계획을 직접 잇지 않는다. 계획 모양은 그 시간과 맞을 수 있는 후보일 뿐이다.
- 질의 개선 후보는 국소 재현 결과와 함께 총괄이 범위를 정한다. 인덱스 추가·질의 변경은 하지 않았다.

## 4. 파일

| 파일 | 내용 |
|---|---|
| `plan-out.txt` | psql 표준 출력 원본(바이트 그대로) |
| `plan-stderr.txt` | psql 표준 오류(0바이트) |
| `plan-summary.txt` | `plan-out.txt` 의 JSON 계획 두 개를 노드별 한 줄로 요약(스크래치 `plan.py`, sha256 `6856f3c4…0968`) |
| `.gitattributes`(`* -text`), `SHA256SUMS` | 원본 바이트 보존과 체크섬 |
