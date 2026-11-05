# N15·N16 통합 초안과 개입 #23 배포·복구 초안 — 로컬 준비 (총괄 v1.109 §77 지시 3, 계획 §5-9 79)

2026-11-05 14:21~14:45 KST.

**총괄 검토 전 초안이다. 운영자는 실행하지 않는다.**
- 서버·DB에는 접속하지 않았고, 서버 재빌드·checkout·재기동·설치·DB 변경도 없었다.
- 블록을 서버에서 실행한 것으로 쓰지 않는다.
- 이 허용은 로컬 준비 범위이며, N15 최종 수용이나 운영 실행 승인이 아니다.

## 1. 통합 초안 (`integration/`)

- **통합 초안 `51dcee530721c0ab23f3f6f875a15b9416b751a3`**(브랜치 `candidate/n15-n16-integration-fe01207`, 원격 반영)
  - fe01207에서 `--no-ff`로 N16 브랜치(ac29ecf)를 먼저 병합(`5e45380`)하고, 그다음 N15 브랜치(d6f7e18)를 병합했다. 두 원본 브랜치와 실패 먼저 근거는 그대로 둔다.
  - 병합 충돌은 없었다.
- 변경 경로는 11개이고 모두 `.java`다(운영 코드 8, 시험 3). 마이그레이션(V95)·설정·의존성·리소스 변경은 없다. 운영 코드 누적 diff는 `diff-fe01207-51dcee5-main.txt`.
- **직접 대조**(`overlap-check.txt`):
  - 한쪽만 바꾼 9개 파일은 그 후보의 블롭과 바이트가 같다.
  - 두 후보가 모두 바꾼 `LiveTradingService`·`DynamicTradingService`는 통합본에서 다른 쪽 후보를 뺀 차이가 이쪽 후보의 변경과 줄 단위로 같다. N16은 8줄씩, N15는 23줄·26줄이다(`*.n15`·`*.n16`·`*.int-minus-*`).
  - 두 파일 모두 가격 검사(`isPrice` 2줄)와 승인 만료 전달(7줄·8줄)이 함께 남았다.

## 2. 관련 시험 (`tests/`)

겹치는 클래스는 한 번씩만 넣었다. N16 관련 10개와 N15 관련 16개를 합치되 `DynamicPaperLiveRuleParityTest`를 한 번만 넣어 25클래스다. 무관한 전체 리허설·뉴스·정기 점검 194건은 반복하지 않았다.

| 실행 | 결과 | 원문 |
|---|---|---|
| 통합 25클래스 1차 14:23:44~14:25:36 | 173 중 1 실패(`DailySma20LiveOrderPathTest` 통합 ①) | `fail-runs/integ-run1-25classes.log`·XML |
| `DailySma20LiveOrderPathTest` 단독 14:26:07~14:26:34 | 24/24 | `integ-dailysma20-alone/` |
| 통합 25클래스 2차 14:26:34~14:28:18 | 173 중 1 실패(같은 시험·같은 사유) — **나머지 24클래스 149/149, N15 12·N16 13 포함** | `integ-run2/`(XML 25·gradle 원문) |
| 통합 7클래스(BtcGate 2·CandleGapExit·CapitalPool 2·ConcurrentEntryCap·DailySma20) 14:29:52 | 49 중 1 실패(같은 사유) | `fail-runs/integ-7classes*` |
| **운영 fe01207 그대로** 같은 7클래스 3회 14:28:38·14:31:58·14:33:00 | 1회 49/49, **2회 49 중 1 실패(같은 사유)** | `baseline-fe01207/run1~3` |

- **실패 원문**: `접수 거절=회차 마감 뒤 도착(H1 …Z) — 이번 회차 후보 아님 · 승인 []`, `BUY 주문 1건` 기대에 0건이다.
- **원인**:
  - `DailySma20LiveOrderPathTest`는 회차 키를 첫 시험의 `now−61분`(초 단위)에서 시험마다 1초씩 내리며 쓴다.
  - 먼저 도는 `BtcGateUnknownEntryTest`의 승인 시험도 `now−61분`(초 단위)과 그 −5초를 키로 쓰고, `processDue`로 그 회차를 닫는다.
  - 두 클래스 사이가 약 17초라서, DailySma20의 k번째 시험 키가 앞 클래스가 닫은 키와 같아질 수 있다. 통합 7클래스 실패 때 충돌 키 04:29:27Z는 BtcGateUnknown 승인 시험이 돈 14:30:27 KST와 맞는다.
- **판정**: 운영 fe01207에서도 3회 중 2회 재현되는 **기존 시험 간 회차 키 충돌**이다. 두 시험 파일은 fe01207 그대로다. N15는 회차 닫힘 규칙(`closed` 기록·늦은 접수 거절)을 바꾸지 않았다(EntryPriorityRounds diff). 같은 클래스 단독은 24/24다.
- 그 시험 클래스는 이번 범위 밖이라 고치지 않았다. 고치려면 별도 승인이 필요하다(예: 클래스마다 다른 밀리초 오프셋). N15 시험은 같은 문제를 피하려고 371ms·613ms 오프셋을 쓴다.
- 통합에서 바뀐 두 경로(가격 검사·승인 만료 전달)와 예약·의도 정리는 `N16InvalidPriceReproTest` 13·`N15EntryExpiryTest` 6·`N15FinalApprovalBoundaryTest` 6과 관련 클래스에서 통과했다(2차 XML).

## 3. 실제 로컬 bootJar 대조 (`jars/`)

- `build.sh`: 같은 작업 사본에서 fe01207 → 51dcee5 순서로 detached checkout 했고(추적 파일 변경 없음 확인), 각각 `./gradlew :web-api:clean :web-api:bootJar -x test --offline`를 실행했다. 운영 Dockerfile의 `gradle :web-api:bootJar -x test`와 같은 작업이다. 빌드는 14:34:50~14:35:02였다.
- sha256(`jar-sha.txt`):
  - fe01207 `14b55d0f…`
  - 51dcee5 `68465166…`
  - 51dcee5 재빌드 `00914b07…`
  - jar 파일 해시는 빌드 시각 때문에 매번 다르므로 항목 비교만 쓴다.
- **비교 코드**: #22에서 수용된 `jarcmp.py`와 바이트가 같다(§55 비교 코드, R1 블록에 같은 내용). 클래스·리소스·자체 모듈 jar 안쪽(core-engine·strategy-lib·exchange-adapter)·외부 라이브러리·로더·중복 항목 이름을 모두 본다.
- **결과 `jar-diff.txt`**: 29줄. 변경 27·추가 2(`EntryClock.class`, `OrderExecutionEngine$IntentExpired.class`)·삭제 0, `(끝) 879 881`.
  - 변경은 모두 `BOOT-INF/classes/com/cryptoautotrader/api/` 아래 `dto/OrderRequest`와 `service/` 의 CapitalReservationService·DynamicTradingService·EntryPriorityRounds·LiveTradingService·MarkPriceBook·OrderExecutionEngine과 그 내부 클래스다.
  - 소스를 바꾸지 않은 내부 클래스(예: `DynamicTradingService$1`, `EntryPriorityRounds$RoundKey`)도 바깥 클래스와 함께 다시 컴파일되어 바이트가 달라졌다(줄 번호 표 등). 실제 jar에서 센 그대로이며 추측하지 않았다.
  - 외부 라이브러리·자체 모듈 jar·로더·리소스·중복 항목 차이는 없다.
- 재빌드 대조 `jar-diff-rebuild.txt`: 51dcee5 두 빌드의 항목 차이 0줄, `(끝) 881 881`. 항목 비교가 빌드마다 안정적이다.
- 11-04에 만든 fe01207 jar(`73567cb3…`)는 이 PC에 남아 있지 않다. `D:\tmp\jarcmp`의 jar 두 개는 10-31의 다른 판이라 대조에 쓰지 않았다.

## 4. 개입 #23 배포·복구 초안 (`blocks/`, 계획 §5-9 79)

- **입력**: 계획 §69의 P 블록 원문(782e7ac 고정판, PV=70 — #22에서 실제 실행되고 수용된 판). N14 설치 블록은 가져오지 않았다. 생성기 `make_r.py`, 값 `vals.json`, 차이 전체 `p-to-r.diff`(236줄).
- **고정값**

| 항목 | 값 |
|---|---|
| 지금 운영(복구 기준) | `fe01207f3f60e97e308925157f84a1bb2da04388`, 이미지 `sha256:9cb5d84f0bfde5208f24f591ad291dacabdfa691a0b2e1f057c4f8c3e1720fb7`(`~/maint-n13/image-new.txt`), V94 |
| 후보 | `51dcee530721c0ab23f3f6f875a15b9416b751a3`(통합 초안. 총괄이 최종 통합 SHA를 바꾸면 `NEW`·`DIFFN`·`RARX`·끝 줄을 다시 셈) |
| 변경 경로 | 11개(`DIFFN` = 실제 `git diff --name-only fe01207 51dcee5`) |
| jar 기대 | `RARX` 29줄, `(끝) 879 881` — 실제 두 jar에서 셈, #22 기대값은 옮기지 않음 |
| 기록 폴더 | `~/maint-n15n16`(새 폴더). `~/maint-n11`~`~/maint-n13`은 읽기만 |
| 이미지 보존 | R1이 지금 이미지를 새 태그 `autotrader-backend:fe01207`로 보존. 이전 복구 태그 `ea44900`·`b1d7664`·`6995c52`(→ `67294e80…`, #22 P1이 만든 것)는 R0이 셋 다 확인 |
| 판 표지 | `RV=79`. R 블록 8개 모두 확인한다. §69 P 블록과 섞이면 첫 줄에서 멈춘다 |
| 그대로 | 장부 8bfe8a9/`02a85850…`, 점검 c669ccd/`ff0a5d7d…`/SQL `653b22ba…`, cron `f2dce280…`, 공유 풀 등록값, Q·세션 `66 \| 63 \| 3 \| -`, BTC·경보·시간대 확인, 시작 창(KST 10:10~20:59·22:10~07:59), 순서(R0 → 30분 안 R1 → ⏸ 운영자 확인 1회 → R2 → 5~10분 뒤 R3 → 기동 30분 뒤 R4 → R 뒤 관측), backend만 재생성(`--no-deps`), 되돌림 조건 |

- **P → R에서 바꾼 것**:
  - 이름: P→R, `ok-P*`→`ok-R*`, P1-되돌림→R1-되돌림, PV=70→RV=79
  - 값: 위 표의 OLD/NEW/IMGOLD
  - 복구 태그: `IMGPREV3`와 R0의 `6995c52` 태그 확인 한 줄을 더했다. 보존 태그는 `fe01207`
  - `PARX`→`RARX`(29줄), 끝 줄 879 881
  - 문구: 되돌림·R1-되돌림 복귀 문구, diff 멈춤 문구의 "N15·N16 11개 파일"
- **심각도 표기**: `🔴 P0 —`·`🔴 P0:`(즉시 보고 등급)는 단계 이름이 아니라서 그대로 두었다.
- **새 관측 함수 `entryobs`**(필요한 새 관측만): 앱 로그를 읽기만 한다. 출력은 로그 머리 시각·엔진 종류·건수뿐이고, 코인·세션 id·가격·사유 본문은 내지 않는다.
  - N15:
    - `[진입 우선순위] …→ 승인 실행 만료(`: 콜백 전 만료
    - `의도 저장 직전 승인 실행 만료`
    - `매수 예약이 거절돼 롤백했다` 또는 `[Dynamic] 매수 예약 거절`의 `사유=ENTRY_EXPIRED`: 예약·의도 경계 거절
    - `만료 사유 기록 실패`(ERROR)
  - N16: `가격이 아닌 실시간 시세 무시`(LIVE와 `[Dynamic]` 구분)
  - 종료 1은 만료 사유 기록 실패뿐이고, 3은 인자 오류, 나머지는 0이다. 0건이면 `미관측`이라고 쓴다. **가격 이상·만료를 운영에서 유도하지 않는다.**
  - N16의 미래 관측 시각 보정(ac29ecf)은 로그 줄이 없어 이 함수로 보지 않는다. 운영 반영은 HEAD·이미지·jar로만 확인한다.
- **넣은 위치**:
  - R0: 배포 전 기준선(fe01207에는 이 줄이 없으므로 미관측이 기대, 기록만)
  - R3·R4: 종료 1이면 멈추고 보고. 그 ERROR 줄은 기존 ERROR 확인에서도 먼저 멈춘다.
  - R4: 미관측이면 ⚪ 안내 두 줄을 내고 계속한다.
  - R 뒤 관측: 기록만
- `holdobs`(N13)·`httpobs`·`newsobs`·`btcgate`·`kwin`은 바이트를 그대로 두었다. 연결 획득 시간 초과는 계속 즉시 P0 보고이고, 장기 대여 경고는 개선 미확인(자동 되돌림 없음)이다.
- 재기동은 다음 정기 보고에 계획 기동 🔴를 한 번 낸다. N14가 설치되어 있으니 Created 기준 로그 범위 ⚪도 예상된다. 이 배포로 설명되며 기록 대상이다.

## 5. 로컬 확인 (`local-check/`, 서버 명령 아님)

- **`bash -n`**: R 블록 8개를 꺼내(`blocks/`, 해시 `blocks.json`) 돌렸고 8/8 통과다(`bash-n.out`).
- **`check_r.sh` 17/17**(`check_r.out`). R 변수 블록을 임시 HOME에서 `source`했고, `python3`만 이 PC의 python으로 바꿨다.
  - 값 확인:
    - 판 표지·OLD/NEW·IMGOLD·이전 복구 태그 세 이미지
    - `DIFFN` = 실제 git diff, 변경 경로가 모두 `.java`
    - `RARX` = 실제 jar 비교 출력, `(끝) 879 881`
    - 장부·점검(c669ccd)·cron 기준값, 관측 함수 넷 정의
  - `entryobs` 7사례: 오늘 통합 시험(`tests/integ-run2/gradle-integ-run2.log`)에서 **실제로 찍힌 줄**에 docker 로그 머리를 붙여 썼다(`entryobs-cases/src-*.txt`).
    - 없음 → 미관측 0
    - N15 다섯 줄 → 범주·엔진별 건수
    - N16 두 줄 → LIVE·DYNAMIC 구분
    - 기록 실패 → 1
    - CRLF·연속 줄 섞임 → 같은 건수
    - 인자 없음 → 3
    - 출력에 코인·id·본문이 없음
- **R4의 N15·N16 구간**: 그 줄들만 `set -euo pipefail` 아래에서 돌렸고(`r4-entry-snippet.sh`), 세 경우 모두 기대대로다(`r4-*.out`).
  - 미관측: ⚪ 두 줄 뒤 계속
  - 관측: 계속
  - 기록 실패: 멈춤
- **하지 않은 것**:
  - R0~R4의 실제 실행이나 대역 실행
  - 서버 `docker`·`git` 명령, 운영 이미지 빌드
  - 운영 DB·22시 N03 재현 사용
  - 전체 리허설·뉴스·정기 점검 194건

## 6. 남은 것

- 총괄이 확인할 것: 최종 통합 SHA, 관련 시험 원문(2절의 기존 충돌 판정 포함), 실제 jar 대조, R 블록 초안.
- 실행하려면 그 뒤에 고정 출처 커밋과 운영자 확인이 필요하다. 총괄이 최종 SHA를 바꾸면 `NEW`·`DIFFN`·`RARX`·끝 줄을 실제 빌드로 다시 센다.
- 운영 fe01207/9cb5d84f…·V94·점검 c669ccd·F/F1/N06·최종 S·위험 한도·장부·cron·SID 203 `CREATED`·12-01 08:50은 그대로다. N15·N16은 운영 반영 확인 전 시작 차단 P0다.

## 7. 파일

| 파일 | 내용 |
|---|---|
| `integration/candidate-sha.txt`·`overlap-check.txt`·`diff-fe01207-51dcee5-main.txt`·`*.n15/.n16/.int-minus-*` | 통합 초안 SHA·병합 그래프·경로, 파일별 대조, 운영 코드 diff, 두 겹침 파일의 줄 단위 대조 입력 |
| `tests/summary.txt` | 실행별 클래스 결과·합계 |
| `tests/integ-run2/` | 통합 25클래스 2차 XML·gradle 원문 |
| `tests/integ-dailysma20-alone/` | DailySma20 단독 |
| `tests/fail-runs/` | 통합 1차·7클래스 실패 원문 |
| `tests/baseline-fe01207/run1~3/` | 운영 fe01207 같은 7클래스 3회 |
| `jars/` | 빌드 스크립트·로그, jar sha256, 비교 코드(#22와 같음), 차이·재빌드 대조 |
| `blocks/r-blocks.md` | 계획 §5-9 79에 넣은 R 블록 원문(생성본) |
| `blocks/p-blocks-input-section69.md`·`make_r.py`·`vals.json`·`entryobs.sh`·`p-to-r.diff` | 생성 입력·생성기·값·새 함수·차이 전체 |
| `local-check/` | 꺼낸 블록·`bash -n`·확인 스크립트·출력·사례 로그 |

이 근거를 담은 main 커밋의 SHA는 여기에 적지 않는다(N06).
