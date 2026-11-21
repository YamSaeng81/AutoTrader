# 병행 감시 실제 읽기 1회 실행안 pm-read-2 (총괄 v1.216 §3-2) — 미실행

2026-11-21 KST, 개발 세션 제출. **이 문서와 [pm-read-2.sh](pm-read-2.sh)는 재검토용 실행안이다. 실행하지 않았다.** v1.216에서 실제 읽기는 보류됐다. 운영 SSH·DB·로그 접근, 감시 설치·cron, d81 배포, 생성·T0, 실자금 시작은 모두 별도 승인 전이다. pm-read-1([pm-read.sh](../../parallel-monitor-check-20261121/approval/pm-read.sh) 7f07c470)은 보존하며 쓰지 않는다.

## 1. 고정 파일

| 역할 | 파일 | sha256 |
|---|---|---|
| 실행안(read → post) | [pm-read-2.sh](pm-read-2.sh) | `fa767e7d2337d6fcbee0636d2ff270bafab9f0121c52f0b2089f482306ce1e1c` |
| 상태 SQL pm-state-3(격리 PG 2/2) | [sql/state-3.sql](../sql/state-3.sql) | `6d71df61a59d144afe9cfa1affc250c6e219c6195612aa97d2580fa8de1bec6b` |
| 변환기 pm-conv-4 | [candidate/convert_state_4.py](../candidate/convert_state_4.py) | `1416564e5df04cc7b59b5e4eaafce50a096c02a7ce333dd661ffb8d8be8edfcd` |
| aux 작성기 pm-aux-writer-1 | [candidate/make_aux2.py](../candidate/make_aux2.py) | `bde58ccb40af3a18d65d624b98edb5065486fee4f082d366291e16a2f5c50cbd` |
| P0 입력 pm-p0-1 | [p0_input.py](../../parallel-monitor-check-20261121/candidate/p0_input.py) | `3fdd580a…` |
| P0 기준(holdobs 원문) | [U변수.sh](../../d81d4r-20261120/d4/blocks-d81/U변수.sh) | `efb96108…` |
| 기록·종료값 규칙 출처 | [D81D4R run.sh](../../d81d4r-20261120/runner/run.sh) | `084cd60d…` |

시작할 때 5개 해시를 확인하고, 다르면 원격 호출 0회로 거절한다(종료 2).

## 2. pm-read-1 대비 보완 (v1.216 G2·G3)

- **핀 대조(G2):** 첫 원격 호출에서 서버 UTC와 설치 상태를 읽고 아래를 명시적으로 대조한다. 하나라도 다르거나 읽지 못하면 SQL·로그를 읽지 않고 `STOP_PIN_*`(종료 3)으로 끝낸다.
  - HEAD `0e1d2a02…`, backend 이미지 `sha256:2c5d8427…`, 상태 `running`, 재시작 0
  - Flyway는 SQL META에서 대조한다. 96이 아니면 로그 조회 전에 멈춘다.
- **실제 rc(G2):** 끝을 `exit 0`으로 고정하지 않는다.
  - 원격·SQL 실패는 그 rc로 끝낸다(SSH 255, psql 1 등).
  - SQL 표준 오류가 비어 있지 않으면 5, 로그 수집이 실패하면 6(`READ_LOG_FAILED`)이다.
  - post는 P0 상태와 변환 상태를 함께 내고 최종 종료값을 정한다: 0 정상, 10 P0 발생, 20 수집 불통과, 30 INVALID, 40 ERROR. **수집 불통과나 P0 발생을 성공 완료로 보고하지 않는다.**
- **필수 기록(G2):** D81D4R 실행 틀의 계약을 다시 쓴다.
  - 미완료 표지 `INCOMPLETE.txt`를 맨 처음 쓰고, 완료 줄 `read-status.txt`를 맨 마지막에 쓴다. 모든 기록이 성공한 뒤에만 표지를 지운다.
  - 기록이 실패하면 종료 4로 끝내고 표지를 남긴다. 원래 실패값이 0이 아니면 그 값을 유지해, 기록 실패가 덮지 않는다.
  - 자동 재실행은 없다. 표지가 남은 폴더와 소비할 수 없는 read 상태는 post도 거절한다(종료 2).
- **시각 축(G3):** SINCE와 UNTIL은 서버 UTC다. UNTIL은 서버 `date -u` 결과이며 로그 조회 `--until`에 그대로 쓴다. 구간 근거 `range.json`은 아래가 모두 성립할 때만 만든다. 성립하지 않으면 P0는 검사 미완료다.
  - backend StartedAt ≤ SINCE < UNTIL
  - 서버 시작 시각이 SINCE보다 뒤
  - PC 시각은 `run-time.txt`에만 남긴다.
- **원문 로그 보존:** backend 로그 원문과 표준 오류는 `local-only/`에만 둔다. 폴더 안 `.gitignore`가 이를 제외한다. 공개 제출에는 다음만 넣는다.
  - `local-only-SHA256SUMS`(원문 해시)
  - 구간, rc, 판정 요약

  이것은 의도한 로컬 전용 보존이다. 원문 공유는 따로 검토한다.

## 3. 원격 호출(read, 최대 4회, 서버 쓰기 0)

1. `date -u` + `git rev-parse HEAD` + `docker inspect`(backend 이미지·상태·재시작·StartedAt)
2. `docker compose … exec -T -e PGOPTIONS='-c statement_timeout=15s -c lock_timeout=2s' db psql -X -q -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader -f -` ← state-3.sql(표준 입력)
3. `date -u +%FT%TZ`(UNTIL)
4. `docker compose … logs --no-color --since <SINCE> --until <UNTIL> backend` → `local-only/backend.log`

## 4. 시험 — 가짜 SSH 5경계 8변형 [fake-ssh-run3](../runs/fake-ssh-run3/out.txt) 8/8

먼저 `command -v ssh`가 회차 폴더의 가짜 ssh를 가리키는지 확인했다. 확인되지 않으면 시험을 시작하지 않는다. 가짜 ssh에는 네트워크 연결이 없다. 상태 출력은 이 폴더 pg-run1 G1b의 실제 격리 PG psql 출력이고, 로그는 p0-run1 Q1의 합성 로그다.

| 변형 | read 종료 | 원격 호출 | 결과 | post |
|---|---|---|---|---|
| V1 정상 | 0 | 4 | READ_OK, `-f -`·PGOPTIONS 명령, SQL 표준 입력 해시 = state-3, `--until` = 서버 UTC | 0: P0 없음·CONVERTED·range server_utc |
| V2 HEAD 불일치 | 3 | 1 | STOP_PIN_MISMATCH, SQL·로그 0회 | 2 거절 |
| V3 이미지 불일치 | 3 | 1 | 같음 | 2 거절 |
| V4 Flyway 95 | 3 | 2 | SQL 뒤 멈춤, 로그 0회 | 2 거절 |
| V5 SQL 실패 | 1 | 2 | STOP_SQL_FAILED, psql rc 1 유지 | 2 거절 |
| V6 SSH 실패 | 255 | 1 | STOP_REMOTE_INSTALL_STATE, rc 255 유지 | 2 거절 |
| V7 로그 실패 | 6 | 4 | READ_LOG_FAILED | 20: P0_CHECK_INCOMPLETE → AUX_P0_CHECK_INCOMPLETE |
| V8 마지막 필수 기록 실패 | 4 | 4 | 완료 줄 쓰기 실패, 미완료 표지 남음, 끝 메시지 없음 | 2 거절 |

**변경 전 결과와 구분:** [fake-ssh-run1](../runs/fake-ssh-run1/out.txt)은 0/8이었고 원문과 당시 실행안·시험기 사본(`*.at-run1.sh`)을 보존했다. 원인은 둘이다.
- **실행안 결함 1건:** 로그 전 멈춤에서는 local-only가 비어 `sha256sum -- *`가 실패했다. 그래서 정상적인 멈춤 5건이 기록 실패 종료로 바뀌었다. 빈 목록을 정상 기록으로 쓰도록 고쳤다.
- **시험기 결함 2건:** Windows sha256sum의 `*` 표기를 기대하지 않았다. 또 V8의 실제 쓰기 오류 표준 오류를 실패로 셌다.

**2회차 뒤 추가 결함 1건:** [fake-ssh-run2](../runs/fake-ssh-run2/out.txt)(실행안 0b3536b7, 사본 `pm-read-2.at-run2.sh`)도 8/8이었다. 그러나 커밋할 때 post가 만든 `aux.rc`·`aux.stdout`을 git이 열지 못했다. `AUX`는 Windows 예약 장치 이름이고, 실제 읽기도 이 Windows 개발 PC에서 돌린다. 그래서 두 파일 이름만 `aux-writer.*`로 바꿨고, 3회차 8/8이 최종이다. 1·2회차의 해당 8개 파일은 로컬에만 보존했고 해시는 [windows-reserved-names.txt](../runs/windows-reserved-names.txt)에 있다. 폴더 `.gitignore`로 커밋에서 뺐다.

post의 `synthetic=false` 표지는 실행안이 실제 읽기용이기 때문이다. 이 시험 결과는 합성이다.

## 5. 승인자에게 내는 제안 값(제안일 뿐 운영 읽기 승인 아님)

1. **실행 창:** 다음 정기 보고 회수가 끝난 뒤 20~40분대. 예를 들어 22:14 회수라면 22:34~22:54 KST다. 보존 폴더 이름은 실제 시작 KST로 짓는다(`pm-read-YYYYMMDD-HHMM`).
2. **SINCE:** 그 보고의 명목 시각을 UTC로 바꾼 값. 10:00 KST는 `…T01:00:00Z`, 22:00 KST는 `…T13:00:00Z`다(전날 날짜 주의 없음, 같은 날짜).
3. **UNTIL:** 서버가 기록한 실제 끝 시각(3번 호출). 사람이 정하지 않는다.
4. **N03:** 해당 회수 원문과 해시에서 미분류 ERROR와 66 값만 `n03-values.json`으로 전사한다. 형식은 `{source_file, source_sha256, error_logs[{label,count}], paper_522_587{total,running,not_running}, observed_window}`이다.
   - 관측 구간 차이를 `observed_window`에 적는다. 보고 구간은 12시간, P0 로그는 SINCE~UNTIL, 상태는 db 스냅숏이다.
   - 전사가 없으면 null이 되고 수집 불통과로 남는다.
5. **SQL 보호값:** statement_timeout 15초, lock_timeout 2초. 격리 PG pg-run1에서 `docker exec -i -e "PGOPTIONS=…" … psql … -f -` 형식으로 SHOW 값 `15s`·`2s`를 확인했다.
   - **운영 형식 `docker compose exec -T -e`는 격리 검증에서 쓰지 않았다.** compose의 `-e` 전달은 승인 전에 따로 확인할 항목이다.
6. **backend 원문 로그:** 로컬 보존만 한다. 제출에는 해시, 구간, rc, 판정 요약만 넣는다.

## 6. 결과 해석(v1.215·v1.216 그대로)

- 과거 종결 행의 경보는 유지한다. 시간 필터나 예외 목록은 없다.
- DYNAMIC 종류 행이 PAPER 세션에 붙어 있으면 `KIND_MODE_MISMATCH` 수집 불통과로 남긴다. 숨기지 않는다.
- P0는 `Connection is not available, request timed out` 기준만 쓴다. 수집이 불완전해도 발생 줄이 있으면 P0_PRESENT다. 불완전 사유를 함께 남기며, 구간 전체 검사 완료로 쓰지 않는다.
- **W1/W2는 실행하지 않는다.** 실제 관측을 합성 등록표와 대조하지 않는다. 승인된 실제 registry-3 이후에 [pm-link-1](../candidate/link_r2.py)로 연결한다.
