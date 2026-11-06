# N20 #26 최종 배포·복구안(§5-9 98, UB=98) 고정 — 총괄 v1.129 §97 지시 1~5 (2026-11-06)

**개발 결과다. 총괄 최종 출처 검토 전이다.** 이 판은 서버에서 실행하지 않았다. 서버·DB·도커·ssh 접속은 없었다. Gradle·jar 빌드, 기존 114건·173건 시험, 전체 대역도 다시 돌리지 않았다(제품 코드가 D2 때와 같다).

- 실제 시각: 2026-11-06 낮(13:17 KST 확인 시점 전후).
- 실행 출처: 이 근거를 담은 커밋의 40자리 SHA다. 커밋에는 자기 SHA 를 적지 않는다(N06). SHA 는 보고에 적는다.

## 고정 값

| 항목 | 값 |
|---|---|
| 기점(OLD) | `ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8` (운영 HEAD, v1.129 수용) |
| 후보(NEW) | `1899b49f633f8aefdc66b15c42debdaba8b3852c` = 원격 `candidate/n20-held-mark-ee509cf` |
| 변경 경로 | `HeldMarkRefresher.java`, `N20HeldMarkItemIsolationTest.java` 2개 (`check/candidate-git.txt`) |
| IMGOLD | `sha256:200f2f5727e90204574fd9965a5cc06dfc5d317e98c13b200de2ac7201378ae6` (#25 실제 이미지, `~/maint-n19/image-new.txt` 보존 사본과 같음) |
| 기록 폴더 | `~/maint-n20` (`~/maint-n19` 는 읽기만) |
| 보존 태그 | U1 이 `autotrader-backend:ee509cf` = IMGOLD 를 새로 만든다. `autotrader-backend:7a94472` = `e51b512a…` 는 U0 가 확인만 한다 |
| jar 기대 | 변경 2항목(`HeldMarkRefresher`, `HeldMarkRefresher$Book`)·`(끝) 881 881` — 재사용 |

## D2 대비 바뀐 것 (`check/D2-to-k26.diff`, 12줄)

- 입력 D2 여덟 블록은 커밋된 `n20-d2-standin-1899b49/blocks-d2` 와 바이트까지 같다(`check/blocks-d2-input.sha256`).
- 생성기 `generator/mkfinal.py` 가 바꾸는 곳마다 맞는 횟수를 강제한다.
  - 판 표지 `UB=N20D2` → `UB=98`: U 변수 1줄, U0 1줄, U1·U2·U3·U4·U 뒤 관측·U 되돌림 각 1줄.
  - `IMGOLD=미정` → 위 전체 ID 1줄. U0 의 `sha256:` 형식 검사는 그대로 두고 멈춤 문구만 고쳤다 1줄.
  - 설명 주석 2줄(RARX 출처, n20obs 머리).
- 그 밖의 로직은 D2 와 같다. U3·U4 의 holdobs(P0) 검사는 로그 저장 바로 다음 줄에서 한 번만 돈다. 가격·주문·호가·기한 규칙은 바꾸지 않았다.

## 확인

1. `bash -n` 8/8.
2. 판 표지·IMGOLD (`check/marker-check.txt`)
   - U1·U2·U3·U4·U 뒤 관측·U 되돌림: `UB=98` 통과. `N20D2`·`92`·빈 값은 멈춘다.
   - U0 IMGOLD 줄: 이 값은 통과하고 `미정` 은 멈춘다.
   - IMGOLD 는 64자리 형식이고 v1.129 수용 값과 같다.
3. 계획서 반영: `generator/k26-section.py` 가 §97 다음, "## 6." 앞에 §98 을 넣었다. `runner/extract.py` 로 작업 사본 문서에서 다시 꺼냈다(`check/worktree-extract-blocks.json`).
   - §98 여덟 블록이 생성본과 바이트까지 같다.
   - F(색인 12)는 총괄 v1.129 새 해시 `0975f7c0…` 와 같다.
   - G(색인 27)는 `4d7ff02a…` 그대로다.
   - `git diff --check` 를 통과했다.
4. jar: 보존 실제 jar 두 개를 다시 해시했다. ee509cf `4f4f42aa…`, 1899b49 `74fd7c83…` 로 v1.126 기록과 같다(`check/jar-sha-recheck-20261106.txt`). 그래서 기존 대조(2항목·881 881)를 재사용한다.
   - 서버 U1 은 #25 서버 jar(`89b4ca17…`)와 서버 새 빌드를 같은 방식으로 비교한다.
   - 로컬 기대가 서버 결과를 보장하지는 않는다. 다르면 U1 이 재기동 전에 멈춘다.

## 실행 틀 (총괄 지시 4) — `runner/`

- `run.sh <블록> <deploy|rollback> [시도 꼬리]`
  - 출력 이름은 `out-<블록>-<deploy|rollback>.txt` 다.
  - 다음 세 경우에는 블록을 돌리지 않고 종료 2 로 멈춘다.
    - 같은 이름의 출력이 이미 있을 때
    - 구분 값이 틀렸을 때
    - 블록 해시가 `blocks.json` 과 다를 때
  - ssh 종료값은 그대로 돌려준다.
- `extract.py <40자리 SHA|문서> <폴더>`: 승인된 출처에서 F·G·§98 여덟 블록을 꺼내고 기대 해시와 대조한다.
- 자체 시험(`runner-selftest.txt`)은 가짜 ssh 로만 했다. 서버 접속은 없었다.
  - 잘못된 구분·없는 블록·같은 출력·해시 다름: 모두 종료 2.
  - 가짜 ssh 의 종료 7 은 그대로 7 로 나왔다.
  - deploy 와 rollback 은 서로 다른 파일로 남았다.
- 이전 보존 원문(대역 결함 2·3 포함)은 고치지 않았다.

## 하지 않은 것

- 서버 빌드·배포·재기동은 하지 않았다. D1·D2·이 판 모두 총괄 판정 전에는 실행하지 않는다.
- 제품 코드·시험·F/G 블록·OPS 블록·한도·풀·장부·cron 은 바꾸지 않았다.
- `~/maint-n19` 기록은 건드리지 않았다.

## 유지

- SID 203 `CREATED`, 최종 S 미확정, 12-01 08:50 KST 목표.
- 연결 획득 시간 초과는 수준과 무관하게 P0 로 즉시 보고하고 다음 단계로 가지 않는다.
- 자동 되돌림은 추가하지 않았다.
