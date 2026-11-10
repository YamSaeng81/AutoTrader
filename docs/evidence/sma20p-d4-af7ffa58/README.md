# SMA20 PAPER 후보 `af7ffa58` · 실제 로컬 jar · 배포·복구 초안 D4 (야간 H)

2026-11-10 · 개발 제출 · 총괄 v1.144 H

**D4는 미승인이며 서버 실행 금지 초안이다.**
- 서버 빌드, 배포, 재기동, 세션 생성을 하지 않았다.
- main에 제품 코드를 합치지 않았다.
- D3 원문, 과거 근거, 체크섬은 고치지 않았다.
- 운영 이미지 ID를 만들거나 추정하지 않았다. 아래 이미지 값은 수용된 기록에서 옮긴 것이다.

## 1. 후보 (`candidate/`)

- **고정 SHA `af7ffa58960fa1392dee00397924827b4e4e6cd5`**. 브랜치 `candidate/sma20-paper-fwd-b5cd1d0`, 원격 HEAD와 같다(`remote-head.txt`).
- 기점은 운영 `b5cd1d0d`다. 사슬은 `2aa2a498 → 192d4459(A) → 0913daf3(B) → 581312ff(C) → 3f6afb90(D 시험) → 20008751(E 시험) → af7ffa58(F 시험)`(`commits.txt`)이다. main의 V95와 슬리피지 열은 섞지 않았다.
- 바뀐 경로는 **12개**다: 제품 Java 5, 시험 Java 7(`diffn-af7ffa58.txt`).
  - 제품 Java: `PaperTradingStartRequest`, `EntryPriorityRounds`, `PaperExecutionMode`(새), `PaperMaxInvestment`(새), `PaperTradingService`
  - `*.sql`, `*.yml`, `*.properties`, gradle, Docker, Python 감시 스크립트의 변경은 0이다.
  - `start_66.py`와 시험 파일의 블롭은 운영과 같다: `6305e512…`, `15166c0e…`.
- REAL에 닿는 동작 변경은 C 근거에 적은 두 가지뿐이다. PAPER 후보가 없거나 LIVE_ALIGNED 세션이 없으면 종전과 같다.
  - 회차 승인 순서의 첫 키
  - WS 구독 출처 `PAPER_ALIGNED`

## 2. 전체 시험 — 같은 SHA로 두 번 (`fulltest/`)

| 회차 | 시각(KST) | 결과 |
|---|---|---|
| 1 | 17:50:38~18:18:08 | 175클래스 1,180건, 건너뜀 23, **실패 1**: `DynamicStopRaceTest` ① "의도 커밋 → 정지 → claim"에서 기대 FAILED, 실제 INTENT_READY |
| 단독 | 22:12 이후 | 같은 클래스만 실행 6/6 |
| 2 | 22:15:38~22:43:09 | 175클래스 1,180건, 건너뜀 23, **실패 0** |

- 그 시험이 보는 경로(DYNAMIC 정지·claim, 주문 실행기)를 후보는 바꾸지 않았다.
- 같은 시험은 이전 전체 실행 4회(1899b49·1ec9104 계열 근거)에서 모두 통과했다.
- 시험의 대기 함수 `awaitDrained`는 실행기의 근사 계수(`getTaskCount`)로 비었는지 본다. 그래서 부하 중의 시점 경합일 가능성이 있다. **그러나 원인은 확인하지 않았다. 불안정 시험이라고 단정하지 않는다.** 1회차 실패 원문은 `run1-DynamicStopRaceTest.xml`에 있다.
- 건너뛴 23건에는 PG가 필요한 `N15PgLockWaitExpiryTest` 4건이 들어 있다. 로컬 PostgreSQL 실증은 하지 않았다.

## 3. 실제 bootJar와 운영 jar 대조

- 깨끗한 후보 작업 사본에서 `./gradlew :web-api:clean :web-api:bootJar -x test --offline`을 **한 번** 실행했다. 22:43:24~22:43:37, 종료 0. 결과 jar의 sha256은 `ff0e7f00…2e77`이다.
- 비교기는 jar 비교기 v2 원문(`jarcmp_v2.py` 56f5d3ba…, 고치지 않음)이다. 비교 대상은 보존 운영 `b5cd1d0.jar`(a7ef759f…, D3 근거와 같은 해시)다.
- 결과: 종료 0, 중복·형식 거절 0, **(끝) 881 → 883**, 7줄(`jar-diff-b5cd1d0-af7ffa58.txt`).
  - 변경 `PaperTradingStartRequest`, `EntryPriorityRounds`
  - 추가 `PaperExecutionMode`, `PaperMaxInvestment`
  - 변경 `PaperTradingService`, `PaperTradingService$1`, `PaperTradingService$RiskMetrics`
- 모두 바뀐 제품 소스 5개에 딸린 클래스다. 리소스, 설정, 라이브러리 차이는 0이다.
- 서버에서 빌드한 jar의 해시는 이 로컬 해시와 다를 수 있다. 판정은 서버 old→new **항목 비교**로 한다(D4 U1).

## 4. 배포·복구 초안 D4 (`d4/`)

`mkdraft4.py`가 수용된 D3 블록 원문에서 D4를 만든다.
- 각 치환은 지정한 횟수만큼 정확히 맞아야 한다.
- 남은 D3 표기가 있으면 멈춘다.
- 9블록 모두 `bash -n`을 통과했다(`d4-syntax.txt`). 해시는 `blocks-d4.sha256`, D3 대비 차이는 `D3-to-D4.diff`에 있다.

| 바뀐 곳 | 내용 |
|---|---|
| 판 표지·기록 폴더 | `UB=SMA20PD4`, `M=~/maint-sma20p`. 이전 폴더는 읽기만 한다 |
| OLD/NEW | `OLD=b5cd1d0…`(지금 운영), `NEW=af7ffa58…` |
| 복구 기준 | `IMGOLD=sha256:52e12428…9055`(#27, `~/maint-o13n21/image-new.txt`, v1.140 수용 값). U0가 실행 이미지와 기록 파일을 함께 대조한다. 이전 복구 태그 사슬에 `autotrader-backend:1899b49 = 426a09a2…`(#26, D3 U1이 만듦)를 더했다. U1은 현재 이미지를 `autotrader-backend:b5cd1d0`로 보존한 뒤 체크아웃한다 |
| diff·jar 기대 | `DIFFN` 12경로, `RARX` 7줄, `JARN="(끝) 881 883"` — 위 실제 대조 값 |
| 감시 스크립트 | D4는 `start_66.py`와 시험 파일을 바꾸지 않는다. U0·U1·되돌림은 전후 같은 블롭·같은 sha256(`68d441ce…`)이고 시험 파일이 있는지 확인한다 |
| 뺀 것 | **U5(N21 뒤 확인)와 `n21mark` 호출.** 감시 스크립트가 바뀌지 않아 대상이 없다. `fresh_idle`과 :14~:24 창은 보수적으로 남겼다 |
| 그대로 | jar 비교기 v2 내장 본문, P0 선행 검사, 관측 함수, 단계 표지, 출력 덮어쓰기 방지, U2~U4, U뒤관측 본문 |

순서: `F 변수 → G 변수 → D4 U변수 → U0 → (30분 안, :14~:24 밖) U1 → ⏸ 운영자 확인 → U2 → 5~10분 U3 → 30분 U4 → U뒤관측`.
- U1에서 멈추면 U1되돌림을 쓴다(재기동 없음). U2 뒤에 멈추면 U되돌림을 쓴다(이미지 #27·b5cd1d0으로 복귀).
- **v1.143 지시 반영**: U2 전에 U3·U4·뒤 관측의 실행 권한과 담당을 미리 준비한다. 한 단계가 막혀도 다른 안전 확인을 함께 멈추지 않는다.

## 5. 대역 — 바뀐 분기와 복구 경로만 (`standin/`)

- 장치: D3 대역 장치의 사본이다(`standin/harness/`, D3 원본은 그대로).
  - D4에 맞춰 바꾼 곳은 셋이다: `conf.sh`의 값, `init.sh`(#27 기록 경로·1899b49 태그·운영 판에도 시험 파일), `stubs/git`(두 커밋의 `start_66.py`·시험 파일 블롭이 같음 — 실제 `git rev-parse` 값).
  - 고정 시계는 2026-11-11 10:30 KST다.
- **r1은 실패했다. 장치 설정 결함이다.** 앞선 명령 사슬이 실패한 `sed`에서 멈춰 `conf.sh`가 만들어지지 않았다. 그래서 블록이 하나도 돌지 않았다. 원문은 `scenarios-d4-r1.txt`에 있다. 블록과 기대값은 바꾸지 않았다.
- **r2(판정): 단언 52/52 통과, 13사례 금지 명령 기록 0줄**(`scenarios-d4-r2.txt`). 사례별 출력·호출·금지 기록은 `runs/r2-*`에 있다.

| 사례 | 확인 |
|---|---|
| e01 정상 | U0→U1(checkout 1, HEAD af7ffa58, start_66.py 68d441ce… 그대로, 시험 파일 있음, 보존 태그 b5cd1d0 = #27, 1899b49 태그 손대지 않음, jar `(끝) 881 883`)→U2→U3→U4→U뒤관측 |
| e02~e08 U0·U1 경계 | #27 기록 파일 다름, 1899b49 태그 다름, 13번째 경로(V95), start_66.py 변경, 시험 파일 없음, D3 변수 블록(U0·U1 둘 다 거절), U1 :14~:24 → 모두 종료 1, checkout 0 |
| e09~e11 U1되돌림 | 운영 jar로 빌드된 상황에서 U1이 "jar 차이가 기대 7줄이 아님"으로 멈춤 → U1되돌림 뒤 HEAD·latest·backend가 원래대로, 감시 스크립트·시험 파일 그대로, 이후 U2 거절. 예상 밖 HEAD → 거절. U2 뒤 → 거절 |
| e12~e13 U되돌림 | 정상 복귀(HEAD b5cd1d0, backend #27, U3 rollback "b5cd1d0 복귀 확인"). 실행 이미지 불일치 → 바꾸지 않고 멈춤 |

대역으로 주장하지 않는 것: 실제 docker 재기동·헬스·서버 빌드, 실제 pgrep/syslog 동작, 경쟁 상태, 운영 적용 성공.

## 6. 세션 생성 초안 — 실행 금지, 자리표시자 명시

D4가 적용되고 U0~U4·뒤 관측 결과를 총괄이 수용한 **뒤**, 별도 승인이 있을 때만 쓴다.

1. 읽기 전용 확인(생성 직전)
   - 두 A/B 전략과 `DAILY_SMA20`에 비활성 행 0
   - PAPER RUNNING 수 + 9 ≤ 120
   - 66세션 상태(63 RUNNING / 3 STOPPED)
   - BTC 입력
   - `EntryPriorityRounds` 활성(STAGE_1 등록)
   - 기록부(`exit-ab-protocol-20261110/VERSION_LOG_TEMPLATE.md`)의 생성 전 칸
2. 생성 본문
   - A/B 8개: `exit-ab-protocol-20261110/REQUEST_DRAFT.json`. 두 팔의 실행 모드는 같게 한다. 모드는 결정 항목이다.
   - SMA20 1개: `{"strategyType":"DAILY_SMA20","coinPair":"KRW-BTC","timeframe":"H1","initialCapital":20200,"investRatio":0.995,"stopLossPct":5,"maxHoldHours":0,"strategyParams":{"paperMaxInvestment":20000,"paperExecutionMode":"LIVE_ALIGNED"}}`. 숫자는 SID 203과 같다. `maxHoldHours` 0은 203 결정 (나)와 같다.
3. **【미정 — 결정 필요】** 운영에서 생성 요청을 내는 경로와 인증.
   - 앱 API를 쓰는지, 기존 생성 도구를 쓰는지 정하지 않았다.
   - 이 초안은 호출 명령을 쓰지 않는다. 서버 인증값을 다루는 블록을 추측해서 만들지 않는다.
4. 생성 뒤 읽기: 저장값 대조(쌍 안에서 하한만 다름, SMA20은 상한·모드 키), 지문, 첫 공통 평가 틱(T0), WS 구독에 KRW-BTC.

## 7. F1·N06 핀 변경 예정표 — 지금은 바꾸지 않는다

D4 실행이 승인되고 결과가 수용된 **뒤에만** 갱신한다. 실제 핀과 최종 S는 그대로다.

| 위치 | 지금 | 갱신 예정 |
|---|---|---|
| F 변수 `NEW`(`LIMITED_LIVE_TEST_PLAN.md` 920행) | `b5cd1d0d…`(v1.141 반영) | `af7ffa58960fa1392dee00397924827b4e4e6cd5`, 주석에 이전 값 b5cd1d0 |
| F1 `IMGF1`(972행)·기록 경로 | `52e12428…`·`~/maint-o13n21/image-new.txt` | **D4 U1이 남긴 `~/maint-sma20p/image-new.txt`의 실제 전체 값.** 지금은 모르며 추정하지 않는다 |
| 정기 점검 머리 | "운영 b5cd1d0" | "운영 af7ffa58"(설치판 c669ccd는 그대로) |
| 복구 태그 | 1899b49(#26) 등 | D4 U1이 `autotrader-backend:b5cd1d0` → 52e12428…(#27)를 만든다 |

바뀌지 않는 것: 설치 c669ccd·V94·장부 8bfe8a9·crontab f2dce280·한도/풀·SID 203 CREATED/20200·12-01 08:50 목표·66세션 계약.

## 8. 하지 않은 것

- 서버 접속을 통한 빌드·배포·재기동·세션 생성, cron·DB·설정 변경, 실제 주문·알림
- D3·비교기·과거 근거 수정
- jar 재빌드 반복(한 번에 기대와 맞음), 대역 r2의 반복
- 로컬 PostgreSQL 실증
- `DynamicStopRaceTest` 원인 조사(실패 1/2만 기록)
