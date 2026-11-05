# N15·N16 통합 시험 fixture 격리·최종 후보 1103f48·개입 #23 R 블록(RV=81)

2026-11-05, 계획 v1.113 §5-9 81 / PROGRESS 301차. 총괄 v1.112 §80 지시 1·2를 실행한 로컬 결과다. **#23 서버 실행 승인 전이다.** 서버·DB 접속, 서버 빌드·checkout·재기동·설치·DB 변경은 없었다.

실제 시간(KST): 14:54 지시 확인 → 14:56~14:57 수정 전 재현 → 14:58~14:59 수정 후 확인 → 14:59:27~15:00:58 25클래스 → 15:01:30~15:01:37 bootJar → 15:04:11 통합 브랜치 푸시 → 문서 기록 커밋(종료 시각은 계획 §81에 적는다).

## 1. 최종 후보

| 항목 | 값 |
|---|---|
| 후보 | `1103f48505a1457029f6555547a87aa986535738` (`candidate/n15-n16-integration-fe01207`, origin 푸시 51dcee5..1103f48 빨리감기) |
| 부모 | `51dcee530721c0ab23f3f6f875a15b9416b751a3` (N16 ac29ecf + N15 d6f7e18 통합 초안) |
| 51dcee5 → 1103f48 | 시험 파일 1개(`DailySma20LiveOrderPathTest.java`, +8줄)뿐. `web-api/src/main` 변경 0개 → **운영 8개 파일은 51dcee5와 바이트 같음** (`integration/main-diff-51dcee5-1103f48.txt` 빈 파일, `diff-51dcee5-1103f48.patch`) |
| fe01207 → 1103f48 | 12개 경로, 모두 `.java`(운영 8·시험 4). V95·설정·의존성·리소스 변경 없음 (`integration/diffn-fe01207-1103f48.txt`, 전체 `diff-fe01207-1103f48-full.patch`) |

시험 격리 외 제품 변경은 없다.

## 2. 시험 fixture 격리

**원인(총괄 v1.112 판정 그대로)**: `DailySma20LiveOrderPathTest`·`BtcGateUnknownEntryTest`는 같은 `@MockBean`/`@SpyBean` 묶음이라 같은 캐시 Spring 컨텍스트, 즉 같은 `EntryPriorityRounds` 빈을 쓴다. 앞 클래스가 정상 처리로 닫은 초 단위 H1 회차 키가 그 빈의 `closed`에 남고, DailySma20의 같은 키 접수가 `회차 마감 뒤 도착`으로 거절됐다.

**수정**: DailySma20 클래스에 다음 두 줄과 설명 주석을 더했다(+8줄).
- `@TestPropertySource(properties = "test.fixture.entry-rounds-context=DailySma20LiveOrderPathTest")` — 컨텍스트 캐시 키가 달라져 이 클래스만 자기 컨텍스트(자기 회차 빈)를 쓴다.
- `@DirtiesContext(classMode = AFTER_CLASS)` — 끝나면 그 컨텍스트를 닫아 뒤 클래스에 남기지 않는다.

같은 방식(`@TestPropertySource`, `@DirtiesContext`)은 이미 저장소 시험(KillCriteria*, SchedulerProfileCleanupTest 등)에서 쓰인다. 시험 설정은 스케줄러를 끈다(`application-test.yml` `scheduling.enabled: false`), 그래서 두 컨텍스트가 공유 H2를 따로 건드리지 않는다.

**바꾸지 않은 것**:
- 운영 코드, 닫힌 회차 보존, 늦은 접수 거절, 만료 규칙, 운영용 reset API
- DailySma20의 시험 메서드·단언·회차 키 규칙(`LAST_ROUND`)
- `BtcGateUnknownEntryTest`(필요 없어 손대지 않음)

제외·`@Disabled`·sleep·순서 고정·재시도는 쓰지 않았다.

**격리 원리 증명(고정 재현, `isolation/`)**: 탐침 두 클래스(`isolation/probe-src/`)는 로컬 근거 전용이며 후보에 커밋하지 않았다. 실행 뒤 작업 사본에서 지웠다.
- `AaaEntryRoundsPoisonProbeTest`는 DailySma20(수정 전)과 같은 mock 묶음이라 같은 공유 컨텍스트를 쓴다. 그 빈에서 지금−64분30초 ~ 지금−50분의 초 단위 H1 키 871개를 정상 경로(접수 → `processDue`)로 닫는다. 앞 클래스가 같은 키를 닫은 상황을 시각·순서 우연 없이 만든다.
- `ZzzEntryRoundsSharedContextProbeTest`는 뒤에 돌며, 같은 공유 빈이 닫힌 키를 여전히 거절하는지 확인한다.
- 클래스 순서는 실행 명령의 JUnit 설정(`-Djunit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer$ClassName`)으로 고정했다. 이 설정은 재현 실행에만 썼고 25클래스 실행에는 쓰지 않았다.

| 실행 | 후보 | 결과 |
|---|---|---|
| 수정 전 `before-51dcee5/` | 51dcee5 + 탐침(작업 사본에만) | 탐침 1/1, **DailySma20 24건 중 9건 실패**. 실패 9건은 모두 BUY 0건이다. 8건의 메시지는 `접수 거절=회차 마감 뒤 도착(H1 …04:55:xxZ)`이다. 1건(이익 잔액 25,000)은 trace 없이 `Expected size: 2 but was: 0`이고 같은 원인이다. 키 04:55:10~04:55:31Z는 탐침이 닫은 04:52:03~05:06:33Z 안에 있다. |
| 수정 후 `after-1103f48/` | 1103f48 + 탐침 | 탐침 1/1 → **DailySma20 24/24** → 뒤 탐침 1/1. 뒤 탐침은 같은 공유 빈(`rounds@12347cd`, 앞 탐침과 같은 ID)이 키 04:56:00Z를 `회차 마감 뒤 도착`으로 거절함을 확인했다. DailySma20의 키(실행 시각 05:58:55Z − 61분 = 04:57:55Z부터 1초씩 앞)는 닫힌 범위 04:54:22~05:08:52Z 안인데도 접수·승인됐다. 즉 DailySma20은 그 공유 빈을 쓰지 않았다. |

새 격리 사례는 25클래스 집계에 더하지 않았다(탐침은 후보 밖).

## 3. 같은 25클래스 1회 (`tests/integ25-1103f48/`)

- 명령: `./gradlew :web-api:cleanTest :web-api:test --tests *.<25클래스> --offline -i`, 클래스 순서 설정 없음
- HEAD: `1103f48…`(`head.txt`), 시간 14:59:27~15:00:58, 종료 0(`rc.txt`), `BUILD SUCCESSFUL`
- 결과: **25클래스 tests=173 skipped=0 failures=0 errors=0** (`summary.txt`, XML 25개)
  - DailySma20 24/24, 나머지 24클래스 149/149(N15 12건·N16 13건 포함)
  - 기존 173건만이고, 새 사례는 없다
- 로그에 `회차 마감 뒤 도착` 줄 0개

운영 원본 7클래스 3회·뉴스·194건 점검·전체 리허설은 반복하지 않았다(지시).

## 4. 실제 로컬 bootJar 대조 (`jars/`)

- 1103f48을 `:web-api:clean :web-api:bootJar -x test --offline`으로 한 번 빌드했다(15:01:30~15:01:37, 종료 0, `build-1103f48.log`).
- fe01207·51dcee5 jar는 다시 빌드하지 않았다. 보존 파일 해시가 k23-prep-51dcee5 `jars/jar-sha.txt`의 기록과 같다(`jar-sha.txt`).
- 비교기는 #22·k23-prep과 같은 `jarcmp.py`(k23-prep-51dcee5/jars)다.

| 대조 | 결과 |
|---|---|
| fe01207 → 1103f48 (`diff-fe01207-1103f48.txt`) | 30줄 = 항목 29줄 + `(끝) 879 881`. 변경 27·추가 2(`EntryClock`, `OrderExecutionEngine$IntentExpired`)·삭제 0. **k23-prep의 fe01207→51dcee5 출력과 바이트 같다**(`cmp` 일치). |
| 51dcee5 → 1103f48 (`diff-51dcee5-1103f48.txt`) | 항목 차이 0, `(끝) 881 881` |

jar 파일 해시(`f7ee2ef4…`)는 빌드 시각 때문에 51dcee5 jar와 다르다. 비교 대상인 항목 내용은 같다. 기대값은 위 실제 출력에서 옮겼다.

## 5. R 블록(RV=81) (`blocks/`)

- **생성기·입력**:
  - 생성기: `make_r2.py`. k23-prep의 `make_r.py`에서 세 군데만 바꿨다(`make_r-to-r2.diff`).
    - ① 판 표지 줄 주석에 후보·출처와 "옛 §5-9 79 초안(RV=79)은 실행하지 않는다"를 넣었다.
    - ② IMGPREV3 주석의 실제 단계명 `#22 P1`이 P→R 일괄 바꾸기에 걸려 `#22 R1`로 바뀌던 것을 막았다(총괄 지적).
    - ③ 남은 옛 값 검사에 51dcee5를 더했다.
  - 입력은 같은 §69 P 블록 원문(782e7ac 고정판 계획 문서), 값은 `vals.json`(NEW 1103f48, RV/SEC 81, VER 1.113, DIFF 12개, RARX·끝 줄은 실제 jar 출력), 새 함수는 `entryobs.sh`(k23-prep과 바이트 같음)다.
- **§79(RV=79) → §81(RV=81) 차이**(`r79-to-r81.diff`):
  - 판 표지 확인 6곳과 변수 순서 확인 1곳
  - NEW 40자리 SHA
  - DIFFN에 `DailySma20LiveOrderPathTest.java` 1줄 추가, R0 멈춤 문구 "11개 → 12개"
  - R0 "후보 1103f48 없음"
  - RARX 주석(v1.113, fe01207·1103f48 jar)
  - IMGPREV3 주석 `#22 P1`
  - 그 밖의 줄은 바이트가 같다. RARX 29줄과 `(끝) 879 881` 기대도 그대로다(실제 출력이 같으므로).
- **그대로 둔 것**:
  - 복구 기준 fe01207/`9cb5d84f…`
  - 보존 태그 fe01207과 이전 복구 태그 3개 확인
  - backend만 교체
  - 세션·BTC·V94·점검 c669·장부·cron 확인
  - holdobs·httpobs·newsobs·entryobs, 획득 시간 초과 P0, 되돌림 조건
- `r-blocks.md` sha256 `3989bc6c…`. 계획 §81에 같은 본문을 넣었다.

## 6. 로컬 확인 (`local-check/`, 서버 명령 아님)

- `split_blocks.py`로 블록 8개를 꺼냈다(`blocks/`, 해시 `blocks.json`).
- `bash -n` 8/8 통과(`bash-n.out`).
- `check_r.sh`: 1차 19/1 → 2차 **20/20**(`check_r.out`). HOME을 임시 폴더로 바꿔 R 변수 블록을 source한다. 확인 항목:
  - RV=81, NEW 1103f48
  - IMGOLD, 이전 태그 3개
  - DIFFN = 실제 `git diff --name-only fe01207 1103f48`(12개, 모두 .java)
  - RARX = 실제 jar 출력(29줄), `(끝) 879 881`
  - 장부·점검·cron 기준값
  - 51dcee5→1103f48이 시험 파일 1개뿐
  - IMGPREV3 `#22 P1`, 블록에 옛 후보·판 표지 없음
  - 관측 함수 4개
  - entryobs 사례 7개(k23-prep과 같은 실제 로그 줄)
- 🔴 **1차 실패 원문 보존**(`check_r-run1-checkdesign-fail.out`): "옛 판 표지 없음" 확인이 entryobs 함수 머리 주석 `# N15·N16 줄 관측(v1.111)`까지 잡았다. 이 주석은 함수 도입 판을 적은 것이고 본문은 무변경이다. 확인 설계 오류이며, 그 한 줄만 빼도록 확인을 고쳤다(블록은 바꾸지 않음).
- 🔴 **생성기 수정 과정의 실패**: 첫 수정 스크립트는 문자열 대조 단언에서 멈춰 `make_r2.py`가 원본 그대로 남았다. 그 상태로 한 번 생성된 출력은 버리고 다시 생성했다. 두 번째 시도는 내가 정한 출처 문구 안의 "51dcee5"가 새 옛 값 검사에 걸려 생성기가 멈췄다. 출처 문구를 바꿨다. 최종 `r-blocks.md`는 마지막 생성기 실행 출력이다.

## 7. 한계·다음

- 탐침 재현은 H2와 Spring 시험 컨텍스트에서의 증명이다. 운영 PostgreSQL이나 운영 동작에 대한 주장은 아니다.
- 운영 실행은 하지 않았다. 총괄이 최종 후보·시험·전체 diff·jar·R 블록 출처를 확인한 뒤 R0→R1, R2 직전 운영자 확인 1회, R3/R4/뒤 관측 순서로 간다. 옛 §79(RV=79, 51dcee5) 초안은 실행하지 않는다.
- 운영 fe01207/9cb5d84f…, V94, c669ccd, 장부·cron·한도, F/F1/N06·최종 S, SID 203 `CREATED`, 12-01 08:50 KST는 그대로다. N15/N16은 운영 반영 확인 전 시작 차단 P0다.

## 파일

| 경로 | 내용 |
|---|---|
| `integration/` | 후보 SHA, 51dcee5→1103f48 patch·운영 diff(빈), fe01207→1103f48 경로·전체 patch |
| `isolation/` | 탐침 소스, 수정 전/후 XML·gradle 로그·요약 |
| `tests/` | 25클래스 XML 25개·로그·요약·시각·종료 코드, 요약기 `summ.py` |
| `jars/` | jar 해시 3개, 비교 출력 2개, 빌드 로그 |
| `blocks/` | `make_r2.py`·`make_r-to-r2.diff`·`vals.json`·`entryobs.sh`·`r-blocks.md`·`r79-to-r81.diff` |
| `local-check/` | 꺼낸 블록·`bash -n`·확인 스크립트·출력(1차 실패 포함)·entryobs 사례 |
