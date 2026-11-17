# 생성 도구 v4.2·SMA20 단건·봉/T0 검증·고정 생성 묶음 (총괄 v1.178 §2·§4, 2026-11-17)

**실제 생성 승인 전이다.** 운영 서버·운영 DB·실제 토큰에 연결하지 않았다. v4.1 제출(`ab-create-v41-20261116/`)과 그 원문·체크섬은 바꾸지 않았다. 시험 코드는 0e1d2a02 위 시험 전용 브랜치, 화면 후보는 0e1d2a02 위 별도 후보 브랜치다(둘 다 로컬 커밋, 푸시 안 함).

| 커밋 | 내용 |
|---|---|
| 시험 브랜치 `test/sma20-t0-path-20261117` **a11d414e** (0e1d2a02 → 4e4762cf → 67ce2ce6 → a11d414e) | `Sma20FirstNineT0PathTest`·`BundleMinCandlesTest`·`Sma20ChainPgTest` 시험 3개만(운영 코드 변경 0). 원문 사본 [javatests/](javatests/) |
| 화면 후보 `candidate/chart-title-dark-0e1d2a02` **7fbb8ab8** (부모 0e1d2a02) | 차트 카드 2줄 + 대비 측정 e2e. [chart/candidate-7fbb8ab8.patch](chart/candidate-7fbb8ab8.patch) |

## 1. R1 — 생성 POST 의 자동 리다이렉트 차단 (v4.2)

- `create/http_post.py`(새, `95213dbd…`): 어떤 3xx 도 따라가지 않는 처리기(`redirect_request → None`)와 빈 프록시 처리기로 만든 전송기. 3xx 는 본문을 읽지 않고 `(코드, None, "리다이렉트")`, 최종 URL 이 요청과 다르면 같은 불명. 재시도 없음.
- `create/ab_create.py`(`84d8aab9…`): Client 가 http_post 를 쓴다. `classify` 는 3xx·URL 다름을 **다른 판단보다 먼저 UNKNOWN(접수 여부 불명)** 으로 두고, 호출기는 남은 팔을 보내지 않는다(종료 3). 초안 형식은 v4.1 그대로(`tool_version=v4.1`)이고 생성기 판을 `creator_version=v4.2` 로 따로 적는다 — 완성기·arms·SQL·프로토콜은 v4.1 과 바이트가 같다.
- **보완 전 재현**([runs/test_redirect_r1.v41-before.txt](runs/test_redirect_r1.v41-before.txt), v4.1 원문 모듈): 같은 경로·전체 목록 경로·상대 경로·다른 호스트 × 301/302/303 = 12조합 모두 **POST → GET 두 번째 요청**(다른 호스트 포함). 307/308 은 두 번째 요청은 없지만 3xx 본문(대역 목록·가짜 성과 필드)을 JSON 으로 돌려받았고 v4.1 분류는 **HTTP_REJECTED(거절)** 였다([runs/v41-classify-307.txt](runs/v41-classify-307.txt)) — 접수 여부 불명이어야 할 응답을 "생성 안 됨"으로 읽는 공백이 하나 더 있었다. 20 실패.
- **보완 뒤**([runs/test_redirect_r1.v42-after.txt](runs/test_redirect_r1.v42-after.txt)): 20조합 모두 요청 = POST 1, 반환 본문 없음, UNKNOWN. 8팔 실행 중 3번째 POST 가 301/302/303/307/308 → 종료 3, 요청 3(GET 0), 결과 OK·OK·UNKNOWN, 초안 없음, 출력·기록 어디에도 토큰·`totalReturnPct` 없음, 같은 폴더 재실행 4·추가 POST 0.
- 영향 시험: 생성기 [32/32](runs/test_ab_create_v42.run1.txt)(v4.1 31건 이식 + 구조 시험 1: 기본 리다이렉트 처리기 없음·프록시 비움·소스에 `urlopen(` 없음·3xx 7종 UNKNOWN), 완성기·arms [26/26](runs/test_ab_v42.run1.txt).
- **8팔 실제 HTTP·PG 재연결**([runs/pgchain42/](runs/pgchain42/), [run_pgchain42.sh](run_pgchain42.sh)): 0e1d2a02 의 `AbChainV4PgTest`(바꾸지 않음) + v4.2 구동기(`chain_driver_v42.py`, 이름 래퍼 `javaharness/chain_driver_v4.py`). seed-other(`ab42a`) 1/1, main(`ab42b`, 다른 DB `ab42a`) **1/1**: 생성 8 OK(기록부 `creator_version=v4.2`) → 확인 0 → 음성 6개(1µs·다른 DB·지문 없음·복수·T0 전 거래·저장 비율) 모두 3 → 추출·집계 0. 작업 사본 HEAD 67ce2ce6(시험만) + 미커밋 `Sma20ChainPgTest`(같은 내용이 뒤에 a11d414e 로 커밋, 이 실행에서는 환경 변수 없어 꺼짐).
- 실제 생성 묶음의 기본 URL 은 RUNBOOK 의 backend 직접 경로 `http://localhost:8080` 로 고정했다(§5). 운영에 리다이렉트가 있었는지는 **미확인**이다.

## 2. SMA20 단건 도구 (D3)

`sma20/sma20_create.py`(`18983e03…`)·`sma20/sma20_complete.py`(`ef48ead7…`)·SQL 2개. 8팔 계약을 1팔로 맞추지 않고 v4.2 의 전송·분류·기록부·잠금·DB 메타 조회·정밀도 함수를 그대로 쓴다.

- 본문 [sma20/body.json](sma20/body.json) = 428차 묶음 파일과 바이트 같음(`c0725d44…`). DAILY_SMA20·KRW-BTC·H1·LIVE_ALIGNED·`forwardTag=SMA20-FWD-1`(D5)이 아니면 보내지 않는다.
- **창·T0 는 POST 전에 인자로 고정**(D4): T0 = 창 시작 뒤 첫 00:00 UTC(09:00 KST). 창은 01:00 UTC(10:00 KST) 이후 시작, T0−30분 전 끝. `--t0` 가 계산값과 다르면 보내지 않는다. 기록부에 T0 가 다른 줄이 있으면 4.
- 생성 반복 금지: 상태와 무관하게 `forwardTag=SMA20-FWD-1` 행이 있거나(새 `sma20_meta_preflight.sql`, 522~587 제외) 기록부에 전송 의도가 있으면 보내지 않는다. 상한 RUNNING+1 ≤ 120, DB 시각과 도구 시각 차이 ≤ 60초, POSIX 토큰 파일 권한 600/400(Windows 는 "확인 불가"로 기록 — 실제 실행은 서버).
- 완성기: 저장 설정 전체·`started_at` 나노초 정확 대조(다른 DB·1µs 거절)·`started_at < T0`·**확인 시각 db_now < T0**·T0 전 및 전체 주문/포지션 0.
- 시험 [14/14](runs/test_sma20.run2.txt)(2회차 저장. 1회차 14/14 는 화면 출력만, 파일 없음): 계획만, 정상 → 완성 → 같은 폴더 재실행 4·새 폴더 재실행 2(추가 POST 0), 완성 음성 11종, 창/T0 규칙 7종, 01:00 UTC 경계, 본문 규칙, 사전 중단(표지 STOPPED/RUNNING·상한·시각 차이·메타 실패, 522~587 표지 행은 읽지 않음), 응답 6종(401·500·success=false·생성 뒤 시간 초과·302·불일치) → 3·POST 1·재실행 4, 사후 메타 실패·다른 DB, 기록부 쓰기 실패·잠금·파손 → 4·POST 0, `--db-cmd` 비밀번호 거절.
- **실제 HTTP·PG 연결**: §2-1 (아래, 10:01 KST 이후 실행).

### 2-1. SMA20 실제 로컬 HTTP·격리 PG 연결 (예약 실행, 11-17 10:01:41 KST)

로컬 서버(127.0.0.1:2050, 시험 내장)·로컬 격리 PG 컨테이너만 사용. 운영 서버·운영 DB 접근 0. 원문 [runs/sma20chain/run1/](runs/sma20chain/run1/).

- 실행: 백그라운드 예약(`wait_then.sh 01:01:30` → [run_sma20_chain.sh](run_sma20_chain.sh) `4224bfa2…`). 실제 시작 `started_utc.txt` = **2026-11-17T01:01:41Z**, 끝 10:02:16 KST 무렵(gradle 로그), **gradle.rc = 0**. 작업 사본 HEAD **a11d414e**(시험만, 미커밋 변경 0 — `worktree_head.txt`), 구동기 `sma20/sma20_chain_driver.py` `1e11ef4d…`, 시험 `Sma20ChainPgTest` `0723b5e2…`. 기록부의 `code_sha` 인자는 0e1d2a02.
- 격리 DB `sm20a`(주)·`sm20o`(다른 DB) 새로 만듦(`createdb.txt`: 이전 같은 이름 없음). 창 01:01:12~01:07:12 UTC(01:00 UTC 뒤 시작), T0 **2026-11-18T00:00Z**(창 시작 뒤 첫 00:00 UTC).
- 결과 [TEST-…Sma20ChainPgTest.xml](runs/sma20chain/run1/TEST-com.cryptoautotrader.api.service.Sma20ChainPgTest.xml) tests 1 / failures 0 / errors 0. [transcript.txt](runs/sma20chain/run1/transcript.txt):

  | 단계 | 종료값 | 내용 |
  |---|---|---|
  | 생성(create) | 0 | 세션 id 2, DB 메타 대조 성공 |
  | 운영 스케줄러 평가 1회 뒤 | — | 주문 0 · 포지션 0 |
  | 확인(ok) | 0 | `ok.db_check.out`: db_now 01:02:13.058 < T0, RUNNING·`forwardTag=SMA20-FWD-1`·LIVE_ALIGNED·20000, 주문/포지션 전체·T0 전 모두 0 → 완성본 `ok.sma20_creation_log.json` |
  | 음성 1µs 다른 started_at | 3 | 나노초 대조 거절, 완성본 없음 |
  | 음성 다른 DB 같은 id | 3 | 같은 거절, 완성본 없음 |
  | 음성 T0 전 주문 1 | 3 | orders 거절, 완성본 없음 |
  | 음성 포지션 1 | 3 | positions 거절, 완성본 없음 |
  | 같은 폴더 재실행 | 4 | 기록부 전송 의도 있음 → 보내지 않음 |
  | 새 폴더 재실행 | 2 | forwardTag 세션 [2] 있음 → 보내지 않음 |
  | 끝 | — | DAILY_SMA20 세션 수 **1**(추가 POST 0) |

- 한계: 운영 서버 Linux·운영 DB 권한·POSIX 토큰 파일 권한은 이 실행으로 확인하지 않았다(Windows 로컬, 토큰 권한 "확인 불가" 기록). `local-test-token.txt` 는 구동기가 쓰는 고정 시험 문자열이다(비밀 아님). 실제 운영 생성·T0 는 승인 전이며 하지 않았다.

## 3. 봉 사전 확인 v2 (preflight_v2.sql `ccbc5529…`)

428차 묶음 SQL 의 BTC 질의는 개수·최소·최대뿐이었다. v2 는 운영 규칙을 옮겼다(SQL 머리말에 출처): 조회 창 500봉, 닫힌 봉, **B1**(floor(판단−2분)−주기, `CandleFreshnessGuard`), **결측 ≤ 2**(점검 공백 제외는 적용 안 함 — 보수적), **최소 이력**(`BundleMinCandlesTest` 운영 레지스트리 값 DAILY_SMA20 457·MTF_CONFIRMED 100·MEANREV_BB 20, 대체값 아님 확인), **B3**(최근 20일 23:00 UTC 봉 20개, daily_checks 식), 전략 활성(`StrategyEnablementGate`: 행 없음=활성, 행은 `is_active IS TRUE`만, 전략→전략·주기), RUNNING+9 ≤ 120, 중복·표지 후보(522~587 제외).

- 격리 PG(v152-pg, 템플릿 v41meta 사본 DB 12개, 판단 시각 고정) [runs-pg/check_cases.txt](preflight/runs-pg/check_cases.txt) **12/12**: 정상 / 결측 3칸 → missing / 결측 2칸 → normal(경계) / 23:00 봉 1개 없음 → B3 19·missing / ETH M15 90봉 → 이력 미달 missing / BTC M15 낡음 → stale / H1 05:01:59 normal·05:02:00 stale(2분 유예 경계) / ETH M15 없음 → none / 활성 행(전략 false·주기 false·주기 true) / RUNNING 111+9=120 통과·+10 초과 / 중복 후보 588·표지 STOPPED 9001(522~587 의 표지 행 530 은 제외).
- 실행기 1회차 결함(보존 [preflight/runs-pg-attempt1/](preflight/runs-pg-attempt1/)): 이력 부족 사례의 자료 변경 SQL 이 문법 오류로 실패했는데 실행기가 변경 전 DB 로 확인 SQL 을 돌렸다 — 그 사례 출력은 무효. 자료 변경 실패 사례는 실행하지 않게 고쳤다. 판정 파일은 1회차·정상 출력을 본 뒤 작성했다(사례 기대는 사례 설계 때 정함).
- 판정기 [bundle/judge_preflight.py](bundle/judge_preflight.py): 봉 3조 normal·활성 3·상한·9본문 서명/표지 중복 0 일 때만 0. 사례 출력에 대 본 결과 [bundle/judge-runs/](bundle/judge-runs/): 정상(템플릿의 588 이 P1 서명과 같음) 2, 중복 칸 비움 0, 결측·낡음·비활성·상한 2, 형식 이상 3.

## 4. 생성 → 첫 09:00 KST 경로 (D4, 고정 판단 시계)

`Sma20FirstNineT0PathTest`(4e4762cf) — 운영 등록 전략(23:00 UTC 마감), `EntryClock` 고정, 운영 평가 `runSessionStrategy`·회차 승인 `processDue`, 생성은 `PaperTradingService.start`(LIVE_ALIGNED·forwardTag). [run2 6/6](javatests/t0-path/run2/) · 표 [sma20-first-0900-t0.tsv](javatests/t0-path/run2/sma20-first-0900-t0.tsv)

| 사례 | 관측 |
|---|---|
| T1 회차 활성·14:00 KST 생성 | 14:00·15:00·00:00·(재기동 모사)00:10·08:00·08:59:30 평가 모두 HOLD "일봉 마감 봉 아님"·주문 0·포지션 0 → 09:00:30 BUY 접수(포지션 0) → 09:05 회차 승인 진입 |
| T2 회차·09:00 에 23:00 봉 늦음 | 09:00:30 평가는 22:00 봉 기준 HOLD(2분 유예 안이라 판정 불가 아님)·주문 0 → 봉 도착 뒤 09:01:30 접수 → 승인 진입 |
| T3 회차·09:02 KST 생성 | 첫 평가가 23:00 봉 마감을 보고 접수 → 09:05 진입 — **다음 날 T0 전 진입** |
| T4 회차·09:30 KST 생성 | BUY 지만 "회차 마감 뒤 도착" 차단·포지션 0 |
| T5 직접(회차 비활성)·14:00 생성 | 08:59:30 까지 주문 0 → 09:00:30 평가에서 진입 |
| T6 직접·09:30 생성 | 첫 평가에서 즉시 진입 — **다음 날 T0 전 진입** |

- 따라서 09:00~09:59 KST 생성 제외는 실행으로 확인됐다(회차 경로는 09:00~09:05 접수 가능, 직접 경로는 09시대 전체). 그 밖 시각(10:00~08:59 생성)은 이 6사례·이 자료(상승 추세, 결측 없음)에서 첫 09:00 전 주문 0 이었다. **모든 시각·모든 자료에 대해 안전하다고 닫지 않는다** — 확인한 것은 위 시각·재기동 모사·늦은 일봉 1건이다.
- 1회차 4/6([run1](javatests/t0-path/run1/)) — **시험 결함**: 포지션 `opened_at` 을 판단 시계로 잘못 가정했다(실제는 시스템 시계). T3·T6 실패, T1·T5 의 `opened_at ≥ T0` 통과도 실제 시각(11-16)이 고정 T0(11-03)보다 늦어 생긴 무의미한 통과였다. 판정을 "어느 고정 시각의 평가/승인 직후 처음 포지션·주문이 생기는가"로 바꿨고 `opened_at` 은 표에만 남긴다. 시계 범위: 판단은 고정 시계, `started_at`·`opened_at`·`filled_at` 은 시스템 시계(운영에서는 같음).

## 5. 고정 생성 묶음 ([bundle/](bundle/), 실행 금지 — 검토용)

- 해시 [bundle/SHA256SUMS.bundle](bundle/SHA256SUMS.bundle) 16개: 생성기·전송기·완성기·메타 SQL 2·확인 SQL·프로토콜·arms·SMA20 도구 2·본문·SMA20 SQL 2·preflight_v2·판정기·실행 틀.
- 본문 9개: 8팔은 v4.2 계획만 실행 [bundle/plan-ab/plan.json](bundle/plan-ab/plan.json) = 428차 묶음 `ab-plan/plan.json` 과 **바이트 같음**. SMA20 은 `body.json`(본문 해시 `809ac8b0…`, 계획 [bundle/plan-sma20/plan.json](bundle/plan-sma20/plan.json)).
- 실행 틀 [bundle/run_bundle.sh](bundle/run_bundle.sh)(`bash -n` 통과, 실행 안 함): STEP 0 출처(묶음 해시·서버 HEAD=0e1d2a02·python·토큰 파일 **권한만**)·1 사전 확인+판정·2 A/B 8·3 A/B 확인+완성·4 SMA20 1·5 SMA20 확인+완성. 단계마다 한 번 부르고 0 이 아니면 멈춘다. 자동 재시도·자동 다음 단계·자동 되돌림 없음. 부분 생성·불명은 도구가 남은 전송을 멈추고(3) 재실행은 기록부로 막힌다(4). 토큰은 `TOKEN_FILE` **경로만**(D2), DB 는 서버 안 `docker compose exec -T db psql`(비밀번호 인자 없음). 단계 끝마다 출력 폴더에 토큰 문자열이 없는지 개수로 확인.
- **기록 규칙 v4.2.1**(11-17 추가 지시, 실행 틀 `1c4bb697…` — v4.2.2 로 대체, 아래): ① 덮어쓰기 거절 — 그 단계가 쓸 파일·폴더(`N.rc`·출력 파일·`ab-create/`·`sma20-create/`)가 하나라도 있으면 아무것도 부르지 않고 **5**. ② 순서 — STEP k 는 `OUT/(k−1).rc` 가 정확히 0 일 때만, 아니면 **6**(부분 생성·실패 뒤 다음 단계 금지). ③ 기록 실패 — `steps.log` 추가·`tee` 기록·rc 파일 쓰기 실패는 도구가 0 이어도 **8**(성공으로 보이지 않음). ④ 종료값 가림 금지 — 파이프의 도구 rc 와 tee rc 를 따로 보고, 도구가 0 이 아니면 기록 실패가 겹쳐도 **도구 값 그대로**, 둘 다 로그에 남긴다. 최종값은 `OUT/STEP.rc`. `steps.log` 는 tee 없이 먼저 파일에 쓰고 화면에 보인다. 출력 폴더는 `OUT_DIR` 로 새로 줄 수 있다(덮어쓰기는 어느 쪽이든 거절).
- 시험 [bundle/test_run_bundle.sh](bundle/test_run_bundle.sh) **19/19**([runs/bundle-rules/test_run_bundle.run2.txt](runs/bundle-rules/test_run_bundle.run2.txt)): PATH 앞 대역(python3·docker·git·tee)으로 정상·재실행 거절(호출 0·기존 기록 바이트 불변)·출력 폴더만 있어도 거절·앞 단계 기록 없음/2 → 6·도구 3 → 3·tee 실패+도구 0 → 8·tee 실패+도구 3 → 3·steps.log 실패+도구 0/3 → 8/3·판정 전 로그 실패 → 8·판정 2·psql 실패 → 2(판정 호출 0)·토큰 노출 → 9·초안 없음 → 2(psql 0)·STEP3 정상·STEP5 완성 3 → 3·STEP4 창 값 없음·STEP 범위 밖. rc 파일만 못 쓰는 경우는 Windows 에서 모사하지 못해 시험 안 함(코드 확인만). **1회차 8/19 는 시험 결함**: 대역 경로를 `C:/…` 형으로 PATH 에 넣어 실제 python·docker 가 불렸다 — 실제 `ab_create --execute` 가 사전 DB 메타 조회 실패(compose 파일 없음)로 **POST 0**·rc2, 이 PC 에 8080 수신 없음, 운영 접근 없음. [runs/bundle-rules/attempt1-defect.md](runs/bundle-rules/attempt1-defect.md). 보완 후 시험은 대역이 앞에 잡히지 않으면 시작하지 않는다. (1회차 POST 0 은 당시 화면 관측에 대한 **제출자 설명**이다 — 그 화면 출력·임시 폴더는 남아 있지 않다.)
- **기록 규칙 v4.2.2**(11-17 총괄 보충 지시 [bundle-record-review-20261117](../bundle-record-review-20261117/README.md), 실행 틀 **`660e2332…`**): v4.2.1(`1c4bb697…`)은 `finish` 가 rc 파일에 0 을 쓴 **뒤** 마지막 로그를 써서, 그 로그가 실패하면 종료 8 인데 `2.rc=0` 이 남아 다음 단계가 허용됐다(총괄 재현). 고친 것(최소 변경): ① `finish` 는 마지막 로그를 **먼저** 쓰고, rc 파일은 맨 마지막에 `STEP.rc.tmp` → 이름 바꾸기로 확정한 뒤 다시 읽어 값이 같을 때만 그 값으로 끝난다 — **확정 뒤 필수 기록 없음**. 확정 실패면 반쯤 쓴 `STEP.rc` 를 지우고 `.tmp` 를 흔적으로 남기며 원래 값(≠0) 또는 8. ② 0 이 아닌 값으로 끝낼 때는 그 값을 먼저 `TOOL_RC` 로 잡아 로그 실패가 겹쳐도 원래 값으로 끝난다. ③ `prev_ok` 는 앞 단계 `.tmp` 흔적이 있으면 6, `fresh` 는 이 단계 `.tmp` 흔적도 덮어쓰기 대상으로 본다. ④ 로그 실패 화면 줄은 "이 줄 무효, 종료 N" 으로 실제 종료값을 함께 보인다.
  - 총괄과 같은 주입(함수만 호출, steps.log 를 폴더로) [runs/bundle-rules/probe_v422.result.json](runs/bundle-rules/probe_v422.result.json): `finish_exit=8 rc_file=없음`, 다음 단계 `next-step-allowed` 없음·종료 8.
  - 시험 **29/29**([runs/bundle-rules/test_run_bundle.v422.run2.txt](runs/bundle-rules/test_run_bundle.v422.run2.txt), 앞 19건 그대로 + 10건). 대역 `date`(N 번째 로그 줄에서 steps.log 를 폴더로 바꿈 → 마지막 로그만 실패)·`mv`(확정 실패 / 확정 직전 강제 종료 `kill -9`). 각 사례는 **그 상태 그대로 다음 단계를 직접 불러** 도구(python·psql 대역) 추가 호출 0 을 본다. 종료값·남은 파일·rc/.tmp 내용·steps.log·두 화면 출력은 [runs/bundle-rules/v422-cases.run2/](runs/bundle-rules/v422-cases.run2/) T20~T25 에 보존.

    | 사례 | 첫 호출 종료 | 남은 rc 표지 | 다음 단계 직접 호출 | 추가 도구 호출 |
    |---|---|---|---|---|
    | T20 도구 0 + 마지막 로그 실패 | 8 | `2.rc` 없음 | 8 (steps.log 가 막혀 거절 줄도 못 씀) | 0 |
    | T21 도구 0 + rc 확정 실패(**이름 바꾸기 `mv` 실패** 주입) | 8 | `2.rc` 없음, `2.rc.tmp`=0 | 6 | 0 |
    | T22 도구 3 + 마지막 로그 실패 | **3** | `2.rc` 없음 | 8 | 0 |
    | T23 도구 3 + tee 기록 실패 | **3** | `2.rc`=3 | 6 | 0 |
    | T24 확정 직전 중단(kill -9) | 137 | `2.rc` 없음, `.tmp` 흔적 | 6 | 0 |
    | T25 STEP1 판정 0 + 마지막 로그 실패 | 8 | `1.rc` 없음 | STEP2(생성기·POST 경로) 8 | 0 (`ab-create/` 없음) |

    추가로 T01b 정상 뒤 `.tmp` 없음, T20a 기준 정상, T26 기록 실패 상태 같은 단계 재실행 거절·호출 0, T26b `.tmp` 흔적만 남겨도 같은 단계 5.
  - **T21 범위**: T21 은 rc 파일 **이름 바꾸기 실패**만 주입했다. rc **내용 쓰기 실패**(`STEP.rc.tmp` 쓰기 실패)는 이 시험에서 따로 주입하지 않았고, 총괄 함수 검사([v422 점검](../bundle-record-review-20261117/v422/README.md), 7개 시나리오 — rc 내용 쓰기 실패·도구3+rc 내용 쓰기 실패 포함)의 결과에 연결한다.
  - **보존 경로 규칙(이후 회차)**: 실행 전에 회차별 고유 보존 경로(`…run<N>/`, 이미 있으면 시작하지 않음)를 정하고, 덮어쓰기·보존 실패가 있으면 그 회차를 성공 근거로 합치지 않는다. 이번 v4.2.2 시험의 성공 근거는 2회차(`test_run_bundle.v422.run2.txt`·`v422-cases.run2/`)뿐이다.
  - **v4.2.2 1회차 26/29 — 시험 기대값 결함**([runs/bundle-rules/test_run_bundle.v422.run1.txt](runs/bundle-rules/test_run_bundle.v422.run1.txt)): T20·T22·T25 에서 다음 단계가 6 이 아니라 8 로 거절했다(앞 단계가 막은 steps.log 에 거절 줄을 못 써서). 추가 도구 호출은 그때도 0. 기대를 "거절(6 또는 8)·호출 0" 으로 바꿨고 실행 틀 판정은 바꾸지 않았다(실행 틀은 그 사이 로그 실패 화면 문구만 고침). 1회차 사례 보존 파일은 폴더 이름 바꾸기가 실패(작업 위치 점유)해 **2회차가 같은 폴더에 덮어써 남아 있지 않다** — 1회차는 화면 출력 파일만 근거.
  - 시험하지 않은 것: 서버 Linux 에서의 실제 파일 시스템 쓰기 실패(디스크 가득 등)는 대역으로만 모사했다.
- 구체 창·T0 **제안**(총괄 결정 대상): A/B 는 도구 규칙대로 M15 경계 C 의 [C+6, C+12]분, T0 = C+15. SMA20 은 A/B 완성 뒤 같은 날 **15:00~15:10 KST**(06:00~06:10Z) 창, **T0 = 다음 날 09:00 KST**(00:00Z) — 계획만 실행이 T0 를 같게 계산했고, T0 를 하루 옮긴 입력은 거절([bundle/plan-sma20-t0-shift-neg.stdout.txt](bundle/plan-sma20-t0-shift-neg.stdout.txt)). 쌍 비교는 LIVE 203 실제 started_at 부터.
- 서버 전제(실행 때 STEP 0 에서 읽기로 확인): 저장소 `~/crypto_trader/AutoTrader`(N03 읽기와 같은 위치), python3 판(미확인), 토큰 파일은 운영자가 실제 호출 직전 준비.

## 6. 차트 제목 어두운 테마 대비 (독립 frontend 후보)

- 원인: `paper-trading/[sessionId]/page.tsx` 601~602행 카드만 `dark:` 배경·테두리 클래스가 없었다(같은 파일 다른 카드 7개는 있음 — 56·82·345·433·630·692·742행). 제목은 `dark:text-slate-100` 이라 흰 배경 위 밝은 글자.
- 후보 7fbb8ab8: 두 줄에 같은 파일 다른 카드와 같은 클래스(`dark:bg-slate-900`·`dark:border-slate-700`·`dark:border-slate-800`·`dark:bg-slate-800/50`)만 더했다.
- 측정 e2e `chart-title-contrast.spec.ts`: 대역 응답으로 차트를 그리고 제목 글자색·실제 배경(반투명 층 합성)을 캔버스 sRGB 로 바꿔 WCAG 대비. 기준 4.5:1.
  - 수정 전(0e1d2a02) [before-0e1d2a02/](chart/before-0e1d2a02/): 밝은 **14.23:1** 통과, 어두운 **1.07:1** 실패.
  - 수정 뒤 [after-candidate/](chart/after-candidate/): 밝은 14.23:1, 어두운 **14.76:1**, 2/2. 카드·전체 사진 각 2장.
  - 관측(범위 밖): 어두운 테마의 차트 격자선이 밝은 점선으로 보인다 — 차트 자체 색이며 이번 2줄과 무관.
- 실행 환경 편차(보존): 포트 3000 은 Windows 예약 범위(2954~3053)라 EACCES → 저장소 밖 설정 [chart/port3311.config.ts](chart/port3311.config.ts)(Git 무시 폴더에서 실행)로 3311. 그 과정의 실패 3회(작업 폴더 누락·node_modules 연결을 Turbopack 이 거부·개발 목 MSW 가 서비스 워커로 요청을 가져가 가로채기 실패)는 `before-0e1d2a02/playwright.*.txt` 로 남겼다. node_modules 는 주 작업 사본에서 로컬 복사(같은 package.json·lock).

## 7. 세션 공백 원인 (§4-5)

[session-gap/README.md](session-gap/README.md) · 발췌 [session-gap/usage-limit-extract.txt](session-gap/usage-limit-extract.txt). 11-16 17:36:00 KST 로컬 세션 기록에 **5시간 사용 한도 오류**(`rate_limit`, "resets 6pm")가 남았고, 18:00 재설정 뒤에도 자동 재개 없이 11-17 07:56:29 사용자 입력 "계속해줘"까지 레코드 0(이 프로젝트 다른 로컬 세션 11개도 0). 표기는 "11-16 17:36경~11-17 07:56 작업·감시 없음" 그대로. **야간 지시만으로 연속 작업이 보장됐다고 기록하지 않는다.**

## 8. 남은 것

- §2-1 SMA20 로컬 연결은 위 결과로 끝(10:01:41 KST, rc 0). 10:00 N03·09:30 장부 읽기는 [n03-1117-1000](../n03-1117-1000/README.md), 통신 오류 읽기 조사는 [netinv-20261117](../netinv-20261117/README.md).
- 운영 서버 Linux 에서의 실행 틀 v4.2.2 실제 디스크 쓰기 실패·SMA20 도구 실행(토큰 권한 포함)은 확인하지 않았다.
- 실제 9세션·SMA20 운영 생성과 T0 는 총괄 검토·승인 뒤. 이번 작업에서 하지 않았다.
