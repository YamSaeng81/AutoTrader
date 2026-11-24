# r1-diag-4 운영 진단 읽기 1회 — READ_OK (총괄 v1.240 §2)

2026-11-24 15:10 KST, Claude Code 단일 실행 세션. 승인 실행기 [r1-diag4-read-v1240.sh](../r1-diag4-20261124/approval/r1-diag4-read-v1240.sh) `e3db7236`를 원문 그대로 **1회** 실행했다. 결과는 **READ_OK, 종료 0**이다. 자동 재시도는 없다. 이 결과를 U0 실행으로 잇지 않았다. U0·U1·U2, 계좌 조회, 과거 DB 보정, 실주문은 하지 않았다.

## 실행 전 확인

| 항목 | 결과 |
|---|---|
| 해시 | 실행기 `e3db7236…` 일치. SQL `276a14fe…`·검사기 `27902178…`는 커밋 64a34eec 블롭과 같음. 원 사본과의 차이는 승인 값 3칸 + 결과 폴더 고정 1줄뿐 |
| 결과 폴더 | 실행 전 `r1-diag4-read-*` 없음 |
| 원격 도구 | `command -v ssh` = `/usr/bin/ssh`(실제), PATH 앞쪽에 가짜 경로 없음 |
| 창 | PC 06:09:49Z 확인 → 실행 06:10:05Z. 승인 창 05:46~09:00Z 안 |
| 다른 실행자·예약 | 이 세션 예약 0건. 다른 운영 명령 없음 |
| 사용률 | `~/.claude/usage-observed.json` 없음 → 미확인(0 아님) |

## 실행 결과

- 시각(PC): 06:10:05Z → 06:10:07Z. 서버 시각 06:10:06Z, PC와 차이 1초. SQL 직전 추정 서버 시각 06:10:07Z. db_now `2026-11-24T06:10:07.262161Z`(창 안)
- 원격 2호출: 설치 상태 rc0, SQL rc0. 두 표준오류 모두 0바이트
- 설치 핀: HEAD 0e1d2a02 일치 · 이미지 2c5d8427 일치 · running · 재시작 0 · Flyway 96 · META repeatable read·UTC·15s·2s
- 형태 검사: shape_check4 실제 종료 0, `SHAPE_OK`

| 항목 | 값 |
|---|---|
| R1 ORIG | **LIVE 0 · DYNAMIC 9** |
| SPLIT | DYNAMIC A 0 · P 9 / LIVE 0 · 0 |
| EXP DYNAMIC ALL = KNOWN | 9건. 첫 경계 `2026-11-25T08:45:40.122578Z` · 마지막 경계 **`2026-11-27T08:30:10.038383Z`** · 다음 분 **`2026-11-27T08:31:00Z`(11-27 17:31 KST)** |
| EXP DYNAMIC OTHER · LIVE 전부 | 0 (경계 `\N`) |
| NEW (06:10 기준, 직전 04:49:14.512077Z 뒤) | LIVE/DYNAMIC × A/P 모두 0 |
| REAL 세션 상태(RS) | LIVE: CREATED 1 · STOPPED 11 · EMERGENCY_STOPPED 20 · DELETED 16 · RUNNING/PAUSED/OTHER 0. DYNAMIC: STOPPED 1 · EMERGENCY_STOPPED 1 · DELETED 49 · CREATED/RUNNING/PAUSED/OTHER 0 |
| 세션 모드(RM) | LIVE REAL 48 · PAPER 0 · OTHER 0 / DYNAMIC REAL 51 · PAPER 42 · OTHER 0 |
| REAL 실행 상태(RX) | 열린/정리 중 포지션 · 미해결 제출 · 그 밖 활성 주문 · 정리 대기 — LIVE·DYNAMIC 10칸 모두 0 |
| REAL 밖 소유(OW) | PAPER·DYN_PAPER 포함 30칸 모두 0. 소유 불명 4종(NO_SESSION_ID·KIND_OTHER·NO_ROW·MODE_OTHER) 0 |
| TOT · UST | 모두 0 |
| 옛 고아(session_id NULL) | 주문 TERMINAL 7,026 · 포지션 CLOSED 199 (합 7,225, 11-23 pm-read의 소유 없는 7,225행과 같은 수) · ACTIVE·기타 상태 0. 정상으로 재분류하지 않는다 |

## 해석 한계 (v1.240 §1)

- **KNOWN은 진단 분류다.** 주문 0·exit_reason NULL을 요구하지 않는다. 그래서 이 9건이 직전 9건과 같은 행이라는 증거가 아니다. 무거래 확정, 과거값 보정, 배포 통과의 근거로도 쓰지 않는다.
- OTHER·NEW 0은 이 스냅숏에서 그 값 범위에 해당하는 행이 없다는 뜻이다. 그 사이 모든 삽입·수정·삭제가 없었다는 뜻은 아니다.
- 다음 분(11-27 08:31Z)은 **DB 시계 기준 재확인 계획**에만 쓴다. 그 시각이 됐다는 것만으로 R1=0을 확정하지 않는다. 실제 재확인 승인과 새 관측이 필요하다. 앱 시계와 DB 시계의 관계는 입증하지 않았다.
- 이 값은 06:10:07Z 한 시점의 관측이다. 그 뒤 상태는 관측하지 않았다.

## 파일

실행기가 쓴 원문 그대로다(read-SHA256SUMS는 실행기가 쓴 목록). r1.stderr·install_state.stderr는 0바이트다. 이 폴더 전체 체크섬은 [SHA256SUMS](SHA256SUMS)(자신 제외).
