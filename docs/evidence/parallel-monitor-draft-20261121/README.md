# 병행 감시 원천 준비 — 금액 없는 상태 SQL 초안·파일 변환기·설치 변경표·배포 승인 묶음 (총괄 v1.214 §3)

2026-11-21 KST, 개발 세션 제출(총괄 판정 전). 지시: [v1.214](../review-5a5b395b/README.md) §3. 기준 커밋 e9883a17.
**SQL 은 검토용 파일이며 어디에서도 실행하지 않았다.** 사례 입력은 모두 합성이다. 운영·SSH·psql·PG·DB·HTTP·거래소·Telegram 호출,
제품 Java·운영 SQL·설치본 c669ccdc·RUNBOOK·read.sh·cron·기존 evidence 수정은 없었다. 기존 W 묶음·R1/R2 사례·PG·제품 회귀·빌드는 반복하지 않았다.

## 1. 금액 없는 읽기 SQL 초안과 파일 변환기 (§3-1)

| 파일 | 내용 |
|---|---|
| [sql/state.sql](sql/state.sql) `pm-state-1` | 읽기 전용 REPEATABLE READ 한 트랜잭션. 세션(두 표 전체: 종류·ID·모드·상태·전략·주기·코인·생성 시각), 주문(ID·소유 종류/ID·side·코인·state·소유 분류), 포지션(ID·소유 종류/ID·코인·status·소유 분류), 정산 표지 개수 U, 행 수 CNT, META/END db 시각 |
| [candidate/convert_state.py](candidate/convert_state.py) `pm-conv-1` | SQL 해시·psql rc·stderr·TSV 형식·CNT·aux(P0/오류/66) 원천 해시를 검사한 뒤 성공일 때만 W1 `f1-obs-1`·W2 `parallel-in-1` 파일을 낸다 |
| [candidate/cases_pm.py](candidate/cases_pm.py) | 직접 영향 5사례 13변형. 변환 성공 때 고정 W1/W2 원본을 파일 입력으로 호출 |

**SQL 근거(d81c3f2f Git 원문).** `live_trading_session.session_type`(V46, REAL/PAPER), `dynamic_session.trading_mode`, `"order"`/`position`의 `session_kind`·`session_id`.
- 식별 단계는 read.sql 9818db54 의 소유 분류 CASE 를 따른다. 등록표 ID 는 받지 않으며, 등록 대조는 W1/W2 가 한다.
- **모드 식별을 생략하지 않았다.** read.sql 은 LIVE 모드를 상수 'REAL' 로 냈지만, 이 초안은 `session_type` 을 그대로 낸다. DYNAMIC 은 PAPER 행까지 `trading_mode` 를 낸다.
- 금액·수량·가격·잔액·수수료·성과 열, `strategy_params`·`invest_ratio` 같은 전략 파라미터, 인증 정보는 읽지 않는다.
- `paper_trading` 스키마(522~587)는 조회하지 않는다. 설치본의 66 확인은 `paper_trading.virtual_balance` 를 읽으므로, 그 결과는 따로 보존한 aux 입력(원천 파일 sha256·rc 확인)으로만 연결한다.
- DYNAMIC PAPER 소유 주문·포지션은 행을 내지 않고 개수로만 남긴다.

**변환기 규칙.**
- **INVALID(30):** rc≠0(행이 있어도 부분 출력), stderr 비어 있지 않음, 0바이트 출력, META/END 없음, 태그별 열 수 불일치(금액 열 혼입), 필드 형식 위반, CNT≠실제 행 수.
- **COLLECTION_NOT_PASSED(20):** 소유자 없는 주문·포지션, 알 수 없는 세션 상태·주문 상태·포지션 상태·side·모드, aux 미제공(null). 이런 행을 빼거나 빈 목록·0·정상으로 채우지 않는다.
- 상태 집합은 W1 `KNOWN_STATUS`·W2 `ORDER_STATES/POS_STATUS` 를 고정 로더(orig.py 3d7a1dcc)로 해시 확인한 뒤 그대로 쓴다.
- 오류 문구에는 줄·열·필드 이름만 쓰고 값은 쓰지 않는다. 시각은 db 축 구간만 적고, 계좌 시계와의 관계는 선언하지 않는다.

**결과 [pm-run2](runs/pm-run2/cases.out.txt) 13/13.** pm-run1 도 13/13 이었지만, 입력 파일 이름 `aux.json` 이 Windows 예약 장치 이름(AUX)이라 git 이 색인하지 못했다. 그래서 원문을 [runs/pm-run1.tar.gz](runs/pm-run1.tar.gz)로 보존하고, 사례 파일의 입력 이름만 `n03-aux.json` 으로 바꿔 한 번 다시 실행했다(변환기·SQL 무변경). 합성 입력이므로 실제 SQL 실행이나 P7/F3/F1 전체 통과의 증거로 세지 않는다.

| 사례 | 변환 | W1 / W2 |
|---|---|---|
| P1 정상 식별·상태 연결(LIVE203·DYN900 + 비활성 157/92 + DYN PAPER 901) | CONVERTED 0 | SET_MATCH 0 / NO_RISK_ITEMS 0 |
| P2a 개수 같고 ID 교체(900→902) | CONVERTED 0 | MISMATCH 20(누락 DYN900·초과 DYN902) / RISK 20(미등록 세션·주문·포지션) |
| P2b 등록 DYNAMIC 900 이 PAPER | CONVERTED 0 | MISMATCH 20(누락 DYN900) / NO_RISK 0 — W2 만으로는 못 잡고 W1 이 잡는다 |
| P3a 소유자 없는 주문(session_id NULL)·포지션(세션 행 없음) | COLLECTION_NOT_PASSED 20, 관측 파일 없음 | 호출 안 함 |
| P3b 알 수 없는 세션 상태·DYNAMIC 모드·주문 상태 | COLLECTION_NOT_PASSED 20 | 호출 안 함 |
| P4a 세션 행에 금액 열 추가 / P4b 상태 칸에 금액 | INVALID 30, 결과·출력에 금액 문자열 0건 | 호출 안 함 |
| P5a rc3+부분 행 / P5b 0바이트 / P5c END 없음 / P5d CNT 불일치 | INVALID 30 | 호출 안 함 |
| P5e **명시적 성공 빈 목록**(META·U·CNT 0·END) | CONVERTED 0 | MISMATCH 20(등록 2개 누락) / NO_RISK 0 — P5b 실패 빈 출력과 구분됨 |
| P5f aux P0 미제공(null) | COLLECTION_NOT_PASSED 20 | 호출 안 함 |

**검토 때 볼 점 — 판단은 하지 않고 남김.**
- W2 는 미등록 REAL 주문을 상태와 관계없이 위험으로 본다.
- 이 SQL 은 시간 필터 없이 원천 전체를 읽는다. 그래서 운영에 과거 LIVE 157…200·DYNAMIC 92 의 종결 주문이 남아 있으면, 실제 읽기마다 RISK_PRESENT 가 나온다. read.sql v3 의 R3(등록 밖 종결 행도 STOP)와 같은 성질이다.
- 기준 이전 행을 면제하는 목록과 그 근거를 둘지는 총괄과 사용자가 정할 일이다. 이 제출은 필터를 넣지 않았다.

## 2. 설치 변경표 (§3-2) — 설치본 c669ccdc·RUNBOOK·cron 은 바꾸지 않음

| 현재 설치본 항목(c669ccdc) | 병행 후보에서의 위치 | 남는 곳 |
|---|---|---|
| `real_open_positions`(SID203 밖 REAL OPEN·CLOSING 개수) | 포지션마다 종류+ID → W2 등록 LIVE / 등록 DYNAMIC REAL / 미등록 REAL. CLOSING 은 등록 여부와 따로 `POSITION_CLOSING_PENDING` | 설치본 값은 ID 없는 합계 — 병행 때 정상 DYNAMIC 보유도 🔴(dec-parallel-20261118 §2-1) |
| `real_unresolved_orders`(SID203 밖 REAL 미결 개수) | 주문마다 종류+ID·state → W2 분류. UNKNOWN 은 `ORDER_UNKNOWN`, 미등록은 `UNREGISTERED_REAL_ORDER` | 설치본은 `l.session_type='REAL' OR d.trading_mode='REAL'` 조인이라 **소유자 없는 주문은 세지 않음** → 후보는 수집 불통과 |
| `real_buys_window`(구간 안 SID203 밖 REAL 매수) | 미등록은 W2 `UNREGISTERED_REAL_ORDER`(시간 필터 없음). **등록 DYNAMIC 의 구간 매수 상한 검사는 후보에 없음** | 등록표 `rules`(max_open_positions 등) 대조는 미구현 — 별도 결정 |
| P0 | aux `p0_events`(원문 표지·출처 해시) → W2 `P0` | 설치본에는 P0 키가 따로 없다(앱 상태·재시작·공유 풀 🔴 줄). **어느 🔴 줄을 P0 로 옮길지 규칙 미정** — 미제공이면 변환 불통과 |
| 미분류 ERROR(뉴스 포함) | aux `error_logs`(원문 표지 그대로) → W2 `UNCLASSIFIED_ERROR` | 설치본 `OTHER` 분류·P_NEWS 그대로. 새 분류로 옮겨 정상 처리하지 않음 |
| 미정산 | SQL U(반영 대기·미해결 불일치 주문 수, DYN PAPER 제외) → W2 `SETTLEMENT_UNRESOLVED` | 설치본 `settlement_unresolved`·`sid_settle_*`·정리 대기·격리 대상은 설치본에 그대로 둠 |
| UNKNOWN 주문 / CLOSING | 위 W2 위험 코드 | SID203 은 설치본 `sid_unknown_orders` 그대로 |
| SID203 `sid_*`·장부(sid_ledger) | 후보에서 다루지 않음(W1 NOT_COVERED 목록) | **설치본에 그대로** — 단독 구간 원문 보존 |
| 66세션(522~587, paper_trading) | SQL 미조회. aux `paper_522_587` 개수만 → W2 표시 | **설치본 s66_* 경보 그대로**(지정 종료 576~578 규칙 포함) |
| 새 등록 LIVE / 등록 DYNAMIC / 미등록 REAL 세션 | SQL S → W1 집합·고정 조건, W2 세션 분류(활성 미등록 = `UNREGISTERED_REAL_SESSION_ACTIVE`) | F1 `EG/EGS/LIVE1` 원문은 바꾸지 않음. W1 SET_MATCH 는 F1 전체 통과가 아님 |

## 3. 배포 승인 묶음 (§3-2) — 연결만, 옛 수치로 빈칸을 확정하지 않음

| 항목 | 고정 값 | 근거 |
|---|---|---|
| 실행 출처 | **cd8bd76f**(D81D4R, `extract.py` 11/11·run.sh) | [d81d4r](../d81d4r-20261120/README.md) §4. 블록 9개 [blocks-d81.sha256](../d81d4r-20261120/d4/blocks-d81.sha256)(U변수 efb96108·U0 736351db) |
| 제품 | **d81c3f2f91c0239aacb0e1176dde9b898f4d7466** | [배포안](../dec-deploy-draft-d81-20261119/README.md) §2. 0e1d2a02 대비 14파일, migration 0 |
| JARM | **33a06f0e…**(898항목) | 배포안 §2 |
| 복구 관문 4판 | GS e97ca31b · GATESQL b24239f8 · GATESH 0b391e58 | 배포안 §2·§4(STOP_INCOMPATIBLE 3 은 멈춤) |
| 배포 전 읽기 | PFP predeploy v3 · PFSUM ca6e7c8d | 배포안 §5 |

**실행 전에 새 읽기로 확인할 것.**
- 현재 운영 SHA·이미지는 0e1d2a02 / backend 2c5d8427 / frontend 9d7f123a 이고, 근거는 마지막 N03 11-21 10:14 의 install_state 다. 실행 당일 다시 확인한다.
- 빈칸 IMGOLDM·IMGOLDC·FEMOLD·FECOLD·FEIMGOLD·JARN 과 보존 태그 `autotrader-backend:0e1d2a0` 의 상태(tagkeep 네 갈래)를 채운다.
- V96 기록, 배포 전 읽기 v3 결과, 관문 4판의 현재 판정을 확인한다.

**운영자 확인 지점.** U1 끝(U2 재기동 직전)에 1회 확인한다. U3 P0, 로그 미완료, holdobs 0·1·3 밖이면 멈춘다. 근거는 D81D4 README 와 V172D4 §2 다.

**각각 실행 전 따로 승인받을 항목:**
1. ① 이 SQL 초안 검토
2. ② 실제 읽기 1회(관리자 SSH psql). 결과 보존 경로는 실행 전에 정한다.
3. ③ 병행 감시 설치. 새 설치판이며 c669ccdc 를 바꾸지 않고, cron 변경을 포함한다.
4. ④ d81 배포(U0→U뒤관측).

이 묶음은 위 승인 어느 것도 대신하지 않는다.

## 4. 출처 고정과 다음 소비 경로 (§3-3)

[source-lock.json](source-lock.json)의 원천 해시는 v1.214 source-lock 값 그대로다. 이 제출의 SQL·변환기를 추가했다.
- 등록표는 **registry-3** 이다(`cash_base`·`holdings_base`·`capital_events`). 시험은 합성 fixture 14599ba6 를 썼다. 옛 등록표 v2 초안은 쓰지도 고치지도 않았다.
- 소비 경로는 SQL 출력 → `convert_state`(CONVERTED 일 때만) → W1 `registry_f1` / W2 `parallel_report` 이다.
- 통합 실행은 **R2** `run_offline_r2.py`(e203ea6c)로 하고, 월 성적표는 **R1** `build_monthly_r1.py`(bface5d5)로 만든다.
- 변환기 출력을 R2 통합에 연결해 시험하지는 않았다. 기존 통합 사례를 반복하지 않으려는 것이고, R2 는 W1/W2 를 같은 파일 형식으로 받는다.
- 아래 값은 **미정**으로 남긴다. 승인되지 않은 실제 값은 채우지 않았다.
  - 총원금, 월 손실 상한, 동적 전략·배정액, 실제 DYNAMIC ID
  - 실제 등록표 파일과 해시
  - P0 옮김 규칙, 과거 종결 행 면제 여부

## 5. 원문·체크섬

`.gitattributes`(`* -text`)를 처음에 두었다. 출력 폴더 이름은 `.gitignore` 의 `out/` 규칙을 피해 `conv/`·`w1/`·`w2/` 로 했다. 원본 W 후보 581/581·R1/R2 보완 721/721 체크섬은 실행 뒤에도 일치한다. 체크섬 목록은 [SHA256SUMS](SHA256SUMS)(이 폴더 전체, 자신 제외)다.
