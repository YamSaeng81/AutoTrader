# 11-24 묶음 r4 — 캐시 추적 제외·A3 축 변환·검토용 읽기 원문·월손실 원천 정정·승인표 (총괄 v1.228 §3-1~§3-4)

2026-11-23 KST, Claude Code(개발 세션)가 제출했다. **총괄 판정 전이며 어떤 실행 승인도 아니다.** 근거는 Git 고정 객체(운영 0e1d2a02·후보 d81c3f2f)·기존 수용 문서·합성 파일 2건뿐이다. 운영 SSH·DB·계좌·Docker·PG·빌드·예약 조회는 하지 않았다. 읽기 원문은 `bash -n` 문법 확인만 했고 실행·가짜 SSH 시험은 하지 않았다. [v1.228 지시](../review-821158a3/README.md)의 순서를 따랐다. [r3 묶음](../bundle-1124-r3-20261123/README.md)과 그 a3calc2.py·사례는 바꾸지 않는다.

## 1. 혼입 캐시 추적 제외와 채택 목록 (§3-1)

- 보정 커밋 **7b2e1f26**: `docs/evidence/d81a2-src-20261123/test/%SystemDrive%/ProgramData/Microsoft/Windows/Caches/` 아래 DB **4경로만** `git rm --cached`. 로컬 파일 4개는 그대로 있다(이제 미추적). 내용은 열거나 해석하지 않았다.
- 원래 목록 [d81a2-src SHA256SUMS](../d81a2-src-20261123/SHA256SUMS)(77항목, 파일 sha256 **aea09274…**)는 수정하지 않았다. 원 제출은 **821158a3**에 고정한다.
- 채택 근거 목록 [cache/ADOPTED-SHA256SUMS](cache/ADOPTED-SHA256SUMS) **73항목** = 원래 목록에서 4줄만 뺀 바이트 그대로. 73항목 모두 821158a3 블롭과 작업 파일 해시가 같다(73/73).
- 제외 사유와 821158a3 블롭 ID: [cache/excluded.tsv](cache/excluded.tsv). 루트 `%SystemDrive%/`·`.gitignore`·권한 설정·다른 파일은 건드리지 않았다. 과거 원격 커밋의 사본은 남아 있으며 이력 재작성·강제 푸시는 하지 않았다. D81A2 65a384e5와 11블록 고정값은 그대로다.

## 2. A3 — DB 축 변화 근거를 서버 축으로 확실히 덮는 구간으로만 쓴다 (§3-2 앞부분)

[a3/a3calc3.py](a3/a3calc3.py)는 a3calc2.py(f9552229) 사본이며 바꾼 곳은 변화 근거 구간 비교와 소수 초 해석뿐이다.

| 변화 근거 축 | v2 | v3 |
|---|---|---|
| server | 구간을 그대로 비교 | 같음 |
| db | DB 끝점을 서버 호출 구간과 **직접 비교** | ① DB–서버 관계에 **적용 범위**(`valid_from`·`valid_to`, 서버 축)가 없으면 보류 ② 그 범위가 변화 근거 구간 ±최대 차이를 덮지 않으면 보류 ③ 확실히 덮는 서버 구간 = **[from + k, to − k]**. 이 구간이 계좌·장부 두 호출 전체를 포함할 때만 변화 근거를 쓴다 |

축 표지만 server로 바꾸거나 오차 구간의 합집합 [from − k, to + k]로 통과시키지 않는다. 새 파일 확인은 두 건만 했다 — [a3v3-r1.tsv](a3/a3v3-r1.tsv) **2/2**:

| 사례 | 입력 | 판정 |
|---|---|---|
| [p1](a3/cases/p1-db-cover-hold.json) 총괄 반례 | n3에서 `stability` 00~05초, DB–서버 ±2초(적용 범위 23:29:40~23:30:20Z) | **보류** — 확실히 덮는 구간 02~03초 ⊉ 호출 00~05초 |
| [p2](a3/cases/p2-db-cover-margin-ok.json) 충분한 여유 | n3 원래 23:29:50~23:30:10Z, 같은 적용 범위 | **충분** — 확실히 덮는 구간 23:29:52~23:30:08Z ⊇ 00~05초, 범위 [79,800.50, 79,801.50) |

p1은 총괄 탐침(n3에서 두 값만 바꿈)에 적용 범위를 더한 것이다. 적용 범위가 없는 원래 탐침은 v3에서 ①로 먼저 보류된다(설계상 결과, 별도 실행 안 함). 기존 3/8건·2020/2027 탐침은 다시 돌리지 않았다.

## 3. A3 사전 조회 검토용 실행 원문 (§3-2 뒷부분) — 미실행

| 항목 | 내용 |
|---|---|
| 파일·고정 해시 | [read/a3-preread.sh](read/a3-preread.sh)(PC 쪽) · [read/a3-remote.sh](read/a3-remote.sh) **94db4ca9…** · [read/a3-pre.sql](read/a3-pre.sql) **d505d08f…**. PC 쪽은 두 해시가 다르면 원격 호출 0회로 거절한다. 전체 해시는 §6 |
| 목적 | 배포 전 1회, a3calc3.py 입력 칸(서버 시계 구간·앱/DB 시계 표본·KRW·S 대상 후보·진행/정리 개수)과 F1 전제를 **같은 ssh 1회** 안에서 얻는다 |
| 시작 전 거절(종료 2, 원격 0회) | 승인 날짜(오늘 KST와 같아야 함)·승인 창(지금이 창 안)·등록 ID·**배정 원천 파일**(운영자 자금 이벤트 기록, 로컬 파일 존재·비어 있지 않음, 해시만 기록)·인증 파일 **경로**·등록 SID·with-orders·새 보존 폴더 이름 중 하나라도 비거나 틀림 |
| 인증 | 운영자가 서버에 둔 헤더 파일(`Authorization: Bearer …` 한 줄, 권한 600)의 경로만 받는다. 원격은 존재·권한만 보고 `curl -H @경로`로 넘긴다. 토큰을 만들거나 읽거나 옮기지 않는다. 서버 curl의 `-H @파일` 지원은 **미확인**(실패하면 종료 6) |
| 원격 호출(재시도 없음) | ① 장부 SQL 1회(`docker compose exec -T db psql`) ② 앱 시계 1회(`exec -T backend date`) ③ 계좌 요약 `GET localhost:8080/api/v1/account/summary` 1회 — 백엔드가 업비트 계좌 1회 + 보유 코인 있으면 공개 시세 1회 ④ 앱 시계 1회. 각 호출을 서버 `date -u +%6N`로 전후 감싼다 |
| 보호값 | PGOPTIONS `statement_timeout=15s`·`lock_timeout=2s`(기존 pm-read-2·PRE0와 같음), `REPEATABLE READ READ ONLY` + `ROLLBACK`, `ON_ERROR_STOP`, curl `--max-time 20`, ssh `BatchMode` |
| 실패 시 중단 | 서버 출력 폴더 있음/인증 파일·SQL 해시 불일치 → 2 · SQL rc≠0 → 3 · SQL 표준오류 있음 → 5 · 계좌 http≠200/curl 실패 → 6 · 기록 실패 → 4(미완료 표지 남김). 어느 경우도 재실행 전 보고 |
| 고유 출력 경로 | 서버 `~/a3-pre/<TAG>/`(a3-pre.sql·ledger.tsv·account.json·stderr.txt·SERVER-SHA256SUMS, 이미 있으면 거절) · PC `a3pre-YYYYMMDD-HHMM/`(args·run·remote.out·token-check·PC-SHA256SUMS) |
| 콘솔/PC 허용 열 | 시계 표본·호출 전후 구간 · `ACCT`: apiKeyConfigured·error 키 유무·KRW 세 정수·fetchedAt·BTC balance/locked/currentPrice·다른 보유 **개수** · SQL 표지 줄(META·SESS·OBUY·CNT·POS·MAXTS·END, 월중이면 MORD·MSES) · 서버 해시 · 표준오류 **바이트 수**. 계좌 JSON·SQL 결과·오류/로그 원문은 반출하지 않는다 |

**a3calc3.py 입력 칸과 이 읽기의 연결 — 채우지 못하는 칸은 보류로 둔다**

| 입력 칸 | 이 읽기에서 | 상태 |
|---|---|---|
| `calls.account/ledger` t0·t1 | `ACCOUNT_CALL`·`LEDGER_CALL` 서버 시각(마이크로초) | 얻음 |
| `clock_relation`(앱–서버) | `APP_CLOCK_1/2`: 서버 구간 안의 앱 시각 2표본 → 표본 시점의 차이 상한 | 표본 시점만. 두 표본 **사이 구간 전체**에 적용하는 것은 총괄 결정 칸(`valid_from/to`) |
| `db_clock_relation`·`db_now` | SQL 안 `clock_timestamp()` 시작·끝이 `LEDGER_CALL` 서버 구간 안 → 그 구간의 차이 상한 | 적용 범위 = 장부 호출 구간뿐 |
| `stability`(변화 없음 근거) | **없음.** REPEATABLE READ는 DB 값 한 시점만 묶고 계좌 호출 시점과는 다르다. `MAXTS`는 정산 정정·환급·배정 변경을 반영하지 않아 근거가 아니다. 전후 잔고 같음도 근거로 쓰지 않는다 | **현재 증명 불가 → 보류**. 근거 원천 승인 칸 ____ |
| `active_orders`·`cleanup_pending` | `CNT` 개수(F1과 같은 정의) | 얻음 |
| `events_in_gap` | 두 호출 사이 이벤트 원천 없음 | **보류**(stability와 같은 이유) |
| `allocation_scope` | `SESS`는 이 DB 실자금 세션 **전 상태**지만 배정 전부 포함의 증명은 아니다. 실제 자금 기록 = `--alloc-src`(운영자 자금 이벤트 기록) | 기록 담당 **미기입** → 원문이 시작 전 거절 |
| `acct` | `ACCT` KRW 세 정수·fetchedAt(앱 시계, 응답 생성 직전) | 얻음(원 단위 반올림 → 범위 ±0.5 규칙 유지) |
| `sessions` S | `SESS` available_krw + `OBUY` reserved_krw(세션 잔액은 예약이 빠진 값이라 계좌 쪽 열린 매수와 이중 차감 주의) | 값은 얻음. 포함 범위는 위 allocation_scope |
| 소유 없는 열린 주문 | `CNT` 넷째 칸 개수만(행 미열람, 예외 없음) | 0이 아니면 보류 |

결론: 현재 자료로는 이 읽기를 승인해 실행해도 **A3는 '보류'까지**다. 변화 근거와 배정 전부 포함 근거를 얻는 원천이 정해지기 전에는 '충분'이 나오지 않는다. 이를 메우려고 이력 저장 기능·계좌 반복 호출·세션 강제 정지를 추가하지 않았고, 고정1 단독 시작에 병행 P7 완성을 조건으로 붙이지 않는다.

## 4. 월손실 원천 정정과 최소 읽기 부록 (§3-3)

### 4-1. r3 §2-3 표의 'F3-2 장부 L2/L3/L4' 정정

고정 설치 장부 [sid_ledger.sql](../n18-f1-install-check/fixtures/sid_ledger.sql)(02a85850, 바꾸지 않음)을 열 단위로 대조했다. 이 장부는 `BEGIN TRANSACTION READ ONLY`(READ COMMITTED)라 **L0~L5가 한 스냅숏이 아니다.**

| 성적표 v5·변환기 v4 입력 | 장부에서 | 분류 |
|---|---|---|
| 주문 체결량·반영 대금·반영 수수료 | L2 `filled`·`funds_applied`·`fee_applied` | **기존 출력에서 직접** |
| 정산 대기 | L2 `pend`(fill_accounting_pending) | **직접** |
| `settlement_final`·`settlement_version` | 없음 | **별도 읽기** (MORD) |
| 월 귀속 시각 BUY `filled_at`·SELL `sell_applied_at` | L2 `fill_kst`는 `coalesce(filled_at, created_at)`의 KST 초 단위 표시이고 SELL도 filled_at | **별도 읽기** (MORD, UTC 마이크로초) |
| 미해결 정산 불일치 | L3 안 집계에만 쓰임 | **별도 읽기** (MORD `open_discrepancy`) |
| 현금 `available_krw` | L4 `available_krw` | 값은 **직접**, 다른 항목과 같은 포착은 아님 → 묶어 쓸 때 **별도 읽기** (MSES) |
| 미체결 잔여 매수 예약 `pending_buys`(order_id·reserved_krw·filled_qty) | L2에 상태·체결량은 있으나 `reserved_krw` 없음, L4 열린 수량 합계는 예약이 아님 | **별도 읽기** (MORD/OBUY) |
| 포지션 행 `positions`(id·status·size·cost_krw) | L3는 왕복 집계, L4는 `open_size` 합계 | **별도 읽기** (POS) |
| 보유 평가 가격 | L4 `last_mark`는 H1 봉 종가(DB 캐시)이고 거래소 평가와 같은 관측이 아님 | **별도 읽기**(ACCT `btc_currentPrice`) 후에도 DB 포착과 동시가 아님 → **현재 증명 불가**(보유가 있으면 그 시점 평가 미확정) |
| 포착 시각·`snapshot_id` | L0 `queried_kst`(now() 표시) | **별도 읽기** (META `db_txn_now` + REPEATABLE READ 한 스냅숏) |
| 외부 순유입·배정(`registry_events`) | 장부에 없음 | **현재 증명 불가** — 운영자 기록 담당 미기입 |
| 월말 T(01-01 00:00) 값 | 포착 경로 없음 | **현재 증명 불가 — 종결된 한계**. 값별 불변 근거나 T 포착이 없으면 달력월 미확정 |
| 최근 24시간 기준 | 같은 부록을 정기 회차마다 남겨야 함 | **별도 읽기**(전 회차 부록 포착), 없으면 미확정 |

누락은 0·정산 확정·같은 시각으로 채우지 않는다.

### 4-2. 최소 읽기 부록 — A3 원문과 같은 파일, `--with-orders true`

월중 회차는 [read/a3-pre.sql](read/a3-pre.sql)의 M절을 켜서 **같은 트랜잭션** 안에서 등록 SID의 LIVE 주문(MORD)·세션(MSES)을 더한다. 계좌 요약·시계 표본은 A3와 같은 ssh 1회에서 얻으므로 **중복 호출을 합칠 수 있다**(회차당 ssh 1·SQL 1·계좌 요약 1·앱 시계 2). 기존 F3-2 장부 읽기와 RUNBOOK은 바꾸지 않고, 부록은 그 회차 직후 별도 승인으로 1회 돈다.

| 열 → 계약 | d81(d81c3f2f) 규칙 연결 |
|---|---|
| MORD `reserved_krw`·`filled_quantity`·`state` → `pending_buys` | 열린 상태 6종 BUY. 세션 `available_krw`는 예약이 이미 빠진 값이라 체결 0이면 `reserved_krw`를 더한다(v5). **일부 체결된 예약은 그 시점 평가 미확정**(v5 그대로). `reserved_krw` NULL(V84+)은 차감된 적 없음 → 더하지 않고 미확정 |
| MORD `applied_*`·`settlement_final`·`fill_accounting_pending` → 주문 값 | 확정 = settlement_final=true 이고 정산 대기 아님(v4 C4). `reported_observed_at`은 정정 때 갱신되지 않아 근거로 쓰지 않는다(v4 S2) — 그래서 이 부록도 읽지 않는다 |
| MORD `settlement_version` → 정정 관측 | 회차 사이 버전·값 차이는 정정 기록으로만(v4 C5). 관측 구간이 월을 걸치면 귀속 미확정 |
| MORD `filled_at`·`sell_applied_at` → 월 귀속 | v4 C3. 앱이 쓴 시각(DB 저장)이며 거래소 체결 시각을 추정하지 않는다 |
| POS `size`·`buy_cost_krw`·`buy_cost_complete` → `positions` | CLOSING은 미확정(v5). 원가 미완이면 미실현 미확인 |
| ACCT `btc_currentPrice`·`fetchedAt`(앱 축) ↔ META `db_txn_now`(DB 축) | **포착 시각 구간**: DB 값은 `LEDGER_CALL` 서버 구간 안의 한 스냅숏, 가격은 `ACCOUNT_CALL` 구간 안(서버 축). 두 구간은 겹치지 않으며 동시 포착이 아니다. 시세 실패 시 0원은 가격이 아니다 |

범위 밖으로 둔 것: 등록 SID 밖 과거 주문, PAPER 522~587, 소유 없는 7,225행(개수 칸 외 미열람·예외 없음). 병행(DYNAMIC) 입력은 별도 미완료다. 변환기 v4·성적표 v5·W4 R1·제품은 고치지 않았고, PG·회귀·빌드는 돌리지 않았다. **이 부록은 운영 읽기 승인이 아니다.**

## 5. 운영자 승인표 (§3-4) — r3 §3을 대신한다

각 행은 별도 승인이다. 출처 수용은 실행 승인이 아니다.

| # | 요청 | 원문·해시 | 운영 호출 | 반출 범위 | 상태 |
|---|---|---|---|---|---|
| 0 | D81A2 출처 | 65a384e5(U0 91ea9342), 근거 8edb7d22 + 보정 7b2e1f26·채택 73항목 | 0 | — | **수용(v1.228)** |
| 1 | D81 배포 1회 | 65a384e5 추출물·run.sh. U0(**A2 = U0 안 1회**, 별도 선행 조회 없음) → U1 → **U2 직전 운영자 확인 1회** → U3 → U4 → U뒤관측 | 블록 원문 그대로 | A2 성공=R1/R2/R3 요약 줄, 실패=종료값·사유·경로·해시. `predeploy-read.txt`는 서버에만 | 승인 전 |
| 2 | A3 사전 조회(배포 전 1회) | §3 a3-preread-1, `--with-orders false` | ssh 1(SQL 1·계좌 요약 1·앱 시계 2) | §3 허용 열 | **원문 제출·승인 전**. 배정 원천 미기입이면 원문이 거절. 실행해도 변화 근거 없어 '보류'까지 |
| 3 | 당일 F1(12-01 08:30) | RUNBOOK F1 원문 | F1 그대로 | F1 출력 줄 | 시작 승인 안 |
| 4 | 월중 원천 | §4-2 부록 `--with-orders true`, F3-2 회차 직후 | 회차당 #2와 같은 1회 | §3 허용 열 + MORD·MSES | 승인 전. 자금 이벤트 담당 미기입 |
| 5 | 월말 원천 | 같은 부록, 12-31 23:30~23:50 경계 전 1회·01-01 10:00 경계 뒤 1회 | 각 1회 | #4와 같음 | 승인 전. T 포착 아님 → **달력월 미확정(종결된 한계)** |
| 6 | (선택) A3 정밀 KRW 2열 | — | — | — | 제안·미작성 |

**유지하는 경계:** ENTRY_REJECTED로 구판 복구가 거절되면 d81을 유지한다. 기존 정지(§6-2)에는 미종결 매수 취소와 보유분 시장가 청산이 포함된다. 정지 완료 = 미결 주문 0·정리 대기 0·실보유 = 내부 기록 확인. DB 행 변조 금지. 예산 계산을 실제 원금·배정·미배정 현금으로 채우지 않았다.

**고정1·동적1 남은 조건(읽기 근거 부족과 배포 코드 준비를 분리)**

| | 배포 코드·실행 출처 | 읽기 근거 | 운영자 |
|---|---|---|---|
| 고정1(SID203) | 후보 d81c3f2f 로컬 수용, D81A2 출처 수용. 배포 승인 전 | A3 원문 제출(실행 전·'충분' 근거 원천 미정), 월중/월말 부록 승인 전, 달력월 확정 불가(종결 한계) | 아래 칸 |
| 동적1 | 같은 d81 후보 안 | 고정1의 A3/월손실 근거와 별개로 R1/R2 설정 읽기([동적1 §9](../dec-dyn1-final-20261123/README.md)) 승인 전, 병행 입력 미완료 | 아래 칸 |

**운영자 회신(비워 둔다 — 하나라도 비면 감시·대응 관문 미완료):** 배포 실행자·창 ____ · U2 직전 확인자 ____ · 휴대폰 실제 수신 확인 시각 ____ · 대응 가능 시간대 ____ · 대응 불가 시간의 대체 경로 ____ · 활성 주문/정리 대기/실보유 확인 담당 ____ · 자금 이벤트 기록 담당·파일 경로 ____ · A3 인증 헤더 파일 준비자·서버 경로 ____ · A3 변화 근거 원천 결정 ____ · 관리자 읽기·보안 회신 ____.

## 6. 체크섬

[SHA256SUMS](SHA256SUMS)(이 폴더 전체, 자신 제외). 실행·가짜 SSH·PG·빌드 0회. 예약 4bc9e12a(22:14)는 조회·변경하지 않았다.
