# 11-23 10:36 v1.217 §2 상태 읽기 1회 — PRE0 통과·READ_OK·post 수집 불통과(옛 세션 이전 행)

2026-11-23 KST. 실행: [v1.217 §2-3~6](../review-1df256c7/README.md), [v1.223 §2-1](../review-8861a3da/README.md), [v1.220 §3-2](../review-9c338534/README.md). 예약 **934ceda9**가 10:36에 실행됐다. 사전 점검(PRE0) 1회, pm-read-2 read 1회, post 1회만 실행했고 재시도·대체 명령은 없다. 운영 쓰기·재기동·설정 변경·금액/성과/전략 파라미터·인증 정보 조회, paper_trading 522~587 조회는 없다.

## 1. 실행 조건 확인

| 조건 | 결과 |
|---|---|
| 창 10:34~10:54 KST | PC 01:36:05Z=10:36:05 KST 확인 뒤 시작, 마지막 원격 호출 끝 01:36:40Z |
| 10:00 보고/09:30 장부 회수 | 예약 8d0db7de 결과 [n03-1123-1000](../n03-1123-1000/README.md)는 v1.223에서 수용됨. 🟢 새 🔴 없음, 운영 0e1d2a02/2c5d8427, 장부 차이0. 기존 BLAST·CryptoPanic403 경보는 원문 그대로 |
| 사용률 | `~/.claude/usage-observed.json` 없음 → **미확인**(0%로 쓰지 않음) |
| 고정 원문 해시 | preflight-remote.sh 63b7819c…, pm-read-2.sh fa767e7d…, 6ca53da6의 approval/과 HEAD 차이 없음. 내부 5파일은 실행기가 직접 대조함(거절 없음) |
| 저장 폴더 | 두 폴더 모두 실행 전에 없음을 확인, 작업공간 docs/evidence 아래, 심볼릭 링크 아님 |

## 2. PRE0 — [pm-preflight-20261123-1036](../pm-preflight-20261123-1036/)

`ssh -o BatchMode=yes cat-prod 'bash -s' < preflight-remote.sh`를 1회 실행했다. PC 01:36:26.541Z~01:36:27.546Z, **rc0·stderr 0바이트·출력 `15s`/`2s` 두 줄 정확히 일치 → 통과**. 운영 compose의 `exec -T -e PGOPTIONS` 전달을 처음 실측했다. 기록 파일은 stdout/stderr/rc/run-time/source-sha256이고, 해시는 [SHA256SUMS](../pm-preflight-20261123-1036/SHA256SUMS)에 있다.

## 3. read — READ_OK(종료 0)

- 원격 호출은 4회다(PRE0 포함 5회). 각 호출 rc는 0이다([rc.txt](rc.txt)). 표준오류는 install_state·state·server-until·backend 모두 0바이트다.
- **핀:** 서버 UTC 01:36:38Z, HEAD 0e1d2a02, backend 이미지 2c5d8427, running, 재시작0, StartedAt 11-16 05:16:14Z, Flyway 96, 모두 일치([pins.txt](pins.txt)).
- **구간:** SINCE 01:00:00Z(10:00 KST) ~ UNTIL 01:36:39Z(서버 date -u). db 스냅숏은 01:36:39.3376~.4016Z이며 repeatable read다.
- **backend 원문**(162,069바이트)은 `local-only/`에만 있다(.gitignore, 커밋 안 함). 해시는 [local-only-SHA256SUMS](local-only-SHA256SUMS)로 남긴다(b95f6bee…).
- 해시 목록: [read-SHA256SUMS](read-SHA256SUMS) eda56843…, state.tsv d04fa0ad….

## 4. post — 최종 20(수집 불통과)

- **N03 전사:** [n03-values.json](../pm-post-input-20261123-1036/n03-values.json) 11196b60…. 원천은 [20261123-1000.txt](../n03-1123-1000/20261123-1000.txt) cacf8054…(서버 해시 일치 수용본)이다. 미분류 ERROR는 원문 '미분류 0'이고, 기존 검증 관례대로 `error_logs: []`로 적었다. 66은 원문 '원래 66 중 RUNNING 63·STOPPED 3/3'으로 `{66,63,3}`이다. 보고 구간(12시간), P0 로그 구간, db 스냅숏은 서로 다른 관측으로 명시했다.
- **P0:** `P0_ABSENT_IN_PROVIDED_LOG`(rc0). 이는 제공된 로그 01:00:00Z~01:36:39Z 안에서 DB 연결 획득 시간 초과 줄이 없었다는 뜻일 뿐이다. 구간 전체 감시나 운영 안전을 증명하지 않는다.
- **변환 pm-conv-4:** rc 20, `COLLECTION_NOT_PASSED`. problems 0, KIND_MODE_MISMATCH 0, U `0 0`. 수집 불통과 **7,225건은 전부 소유 세션 ID가 NULL인 LIVE 행**이다.
  - `ORDER_OWNER_NO_SESSION_ID` 7,026건: FAILED 6,705, FILLED 313, CANCELLED 8. 주문 ID 444~8398.
  - `POSITION_OWNER_NO_SESSION_ID` 199건: 전부 CLOSED. 포지션 ID 216~879.
- 결과 원본은 [post/conv/result.json](post/conv/result.json) 3ea69d7c…와 [post-status.txt](post-status.txt)다. W1/W2·R2·P7/F3·월 성적표는 실행하지 않았다. 실제 등록표 registry-3가 없고, 합성 등록표와 대조하지 않았다.

## 5. 식별 목록(금액 없음, 이 스냅숏 범위)

| 구분 | 이 읽기로 확인한 것 | 확인하지 못한 것 |
|---|---|---|
| 세션 141 | LIVE REAL 48: CREATED 1(**203**), STOPPED 11, EMERGENCY_STOPPED 20, DELETED 16. DYNAMIC REAL 51: STOPPED 1, EMERGENCY_STOPPED 1(92), DELETED 49. DYNAMIC PAPER 42: STOPPED 18, EMERGENCY_STOPPED 20, DELETED 4. **RUNNING 0** | 세션 금액·성과·배정 |
| 정상 소유 주문 337 | LIVE 163(FILLED 162·FAILED 1, ID 8399~12047), DYNAMIC 174(FILLED 90·FAILED 84, ID 8592~12082). **미종결 주문 0** | 정산 확정·비용·체결 금액 |
| 정상 소유 포지션 161 | LIVE 82·DYNAMIC 79, 전부 CLOSED. **OPEN 0** | 실현손익·원가 판정 가능 여부 |
| 소유 세션 없음(과거 종결 후보) | 주문 7,026·포지션 199. ID 구간이 정상 소유 행보다 **모두 앞선다**(주문 ≤8398 < 8399, 포지션 ≤879 < 891). 전부 종결 상태 | 생성 시각, 세션 체계 도입 전인지 여부. 지금은 미확인이며 시간 면제나 가짜 등록을 하지 않았다 |
| DYN_PAPER 제외 | 주문 945·포지션 473(종류=DYN_PAPER이고 세션 모드=PAPER로 일치하는 행만 제외) | — |
| SID 203 | CREATED, 주문/포지션 0행 | 계좌 잔액·장부 대조(이 SQL 범위 밖) |
| 모드 충돌·미등록 | KIND_MODE_MISMATCH 0, problems 0 | 실제 registry-3 대조(등록표 없음) |

수집 불통과는 그대로 유지한다. 원문 수정, 행 수정·삭제, ENTRY_REJECTED 사유 변경은 하지 않았다.

## 6. 다음 최소 추가 읽기안(제출만, 실행은 승인 전)

| 번호 | 목적 | 출처·허용 열 | 범위·기록 |
|---|---|---|---|
| A1 소유 없음 행 시각 | 7,225건이 세션 체계 도입 전 종결인지 확인. 과거 종결로 분류할지 소유 이상으로 남길지 결정하는 근거 | `"order"`: id·state·created_at(또는 updated_at), `position`: id·status·created_at·closed_at. 조건은 session_id IS NULL AND session_kind='LIVE'. **min/max/개수, 최근 30일 안 행 수만** 집계 | 금액/수량 열 없음. READ ONLY·ROLLBACK·15s/2s. 결과는 개수와 시각만 |
| A2 배포 전 비용 관문 | d81 전역 비용 관문의 배포 직후 거절 여부 | 고정 [predeploy_read.v3.sql](../dec-costbasis-followup-20261119/predeploy-v3/predeploy_read.v3.sql) **ca6e7c8d** R1(판정 불가 수)·R2(30일 손실 합)·R3(RUNNING REAL 세션 연속 손실 입력) | R2는 금액 합이므로 금액 열 사용 승인이 따로 필요하다. 이번 스냅숏은 RUNNING 0이라 R3는 0행이 **예상**된다(결과 아님). 개수/합계만 남기고 행 원문은 local-only |
| A3 고정1 계좌/장부 | SID203 20,200원과 거래소 계좌 KRW·보유의 일치 | 기존 일일 장부 L0~L5와 거래소 계좌 조회 1회. 형식은 P7 v5 수집 메타 `{c0,d,c1,mono_rtt_ms}` | 계좌 조회는 별도 승인. 상세 명령·예상 출력·멈춤 조건은 11-24 18:00 D81 실행·복구·계좌 대조 묶음에 넣는다 |

**보존 주의:** 첫 커밋 01a8cbbd에서 `post/p0/holdobs.stdout.txt`(작업 파일 CRLF, post-SHA256SUMS c7498541…)가 Git 줄바꿈 정규화로 LF 블롭(83298157…)이 됐다. 그래서 이 폴더에 `.gitattributes`(`* -text`)를 넣고 원래 바이트로 다시 커밋했다. 다른 파일은 첫 커밋부터 작업 파일과 블롭이 같다.

A1~A3는 모두 아직 실행하지 않았다. 새 운영 읽기·배포·자금 배정·세션 생성/시작 승인은 없다.
