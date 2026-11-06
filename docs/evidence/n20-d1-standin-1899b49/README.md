# N20 다음 배포 초안 D1 — 전체 단계 로컬 대역 검증 (총괄 v1.127 §95 지시, 2026-11-06)

**개발 결과다. 총괄 판정 전이다.** D1은 그대로 **미승인·서버 실행 금지**다. 이 검증은 서버·DB·도커·원격 저장소·네트워크에 닿지 않았다. #25(342f461 §92 UB=92·ee509cf)에는 섞지 않았다.

- 실행 시각(실제): 2026-11-06 09:12~09:18 KST. 대역 안의 시각은 가짜 시계(2026-11-07 KST)다.
- 대상 블록: `/d/tmp/n20c/blocks-draft/` D1 여덟 블록 원문 그대로(n20-candidate-1899b49 `blocks-draft/`와 같은 파일). 앞에 #25 추출본 F·G 블록을 붙였다. 블록 원문은 고치지 않았다.
- 대역에서 넣은 값은 두 가지다.
  - `imgold.sh`: U변수 뒤에 `IMGOLD=<대역 고정값>` 한 줄을 넣었다. #25 이미지 자리이며 실제 값이 아니다. s2는 이 줄 없이 초안 그대로 돌렸다.
  - `extra/U1되돌림.sh`: 342f461 계획서의 U1 아래 "U1-되돌림" 한 줄 원문이다. D1 블록 묶음에는 따로 없다.

## 대역 장치 (harness/)

- `sim.sh <대역> <블록> [꼬리]`: F + G + D1 U변수 + (imgold.sh) + 블록을 이어 붙여 **로컬 `bash -c`** 로 돌린다. ssh는 쓰지 않는다.
  - `HOME`은 대역 폴더이고, `PATH`는 가짜 명령 폴더 + `/usr/bin:/bin`(Git Bash 기본 도구)뿐이다. Windows의 docker·git·python 경로는 넣지 않았다.
  - 출력 파일이 이미 있으면 덮어쓰지 않고 거부한다.
- 가짜 명령 `stubs/`: 모든 호출을 `calls.log`에 남긴다.
  - `docker`: 상태 파일로 태그·실행 이미지·로그·SQL 고정 응답을 흉내 낸다. `docker cp`는 **보존 실제 jar**(ee509cf `4f4f42aa…`, 1899b49 `74fd7c83…`, 7a94472)를 꺼내 준다. 그래서 U1 jar 대조는 실제 jar로 계산한다.
  - `git`: HEAD 파일만 바꾼다. 커밋 두 개(ee509cf, 1899b49)만 안다. `fetch`는 하지 않는다. `git show`로는 보존 maint_checks.sql·maint_verify.py만 낸다.
  - `python3`: 블록 안 heredoc과 보존 maint_verify.py만 실제 Python(`-I -X utf8`)으로 돌린다. 경보 조회(start_66.py)는 고정 출력이다.
  - `date`·`sleep`·`touch`: 가짜 시계를 쓴다. sleep은 기다리지 않고 시계만 민다.
  - `sha256sum`: 표준 입력 첫 줄이 `#STANDIN-SHA256=` 인 대역 설치본만 그 값을 낸다. 그 밖에는 실제 sha256sum이다.
  - `crontab -l`: 대역 파일을 낸다.
  - **금지 명령**(ssh·scp·curl·wget·nc·psql·sudo·systemctl·apt·pgrep·python·gradle·java 등)은 `forbidden.log`에 남기고 종료 99로 끝낸다.
- 실행 전 확인(`preflight/`):
  - 대역 PATH에서 docker·git·python3·ssh·curl·psql 등이 **모두 가짜 명령으로 풀린다**.
  - 블록과 F·G에 절대 경로 명령이 **0줄**이다.
  - 실행 뒤 **금지 명령 기록은 전체 0줄**이다(`runs/*/forbidden.log`, `calls-per-run.txt`).

## 결과 — 시나리오별 (runs/)

| 대역 | 경로 | 결과 |
|---|---|---|
| s1b-normal | **정상** U0→U1→(운영자 확인 자리: 표시만)→U2→U3 조기→U3→U4 조기→U4→U뒤관측 | U0·U1·U2·U3·U4·뒤관측 모두 ✅. U1은 실제 jar로 `변경 HeldMarkRefresher$Book.class`·`변경 HeldMarkRefresher.class`·`(끝) 881 881`. U3 조기(기동 1분 15초 뒤)는 "기동 5분 전", U4 조기(6분 15초 뒤)는 "아직 30분 전"으로 멈췄다. U4는 N15·N16·N17·N19·**N20 미관측 ⚪** 문구로 끝난다 |
| s2-imgold-unset | **IMGOLD 미정**(초안 그대로) | U0가 "IMGOLD 미정 — … 초안 D1 은 실행하지 않는다"로 멈췄다. 가짜 명령 호출 0회, maint-n20에 기록 파일 0개(시각 파일도 없음). 이어진 U1은 "U0 미통과" |
| s3-jar-mismatch | **jar 차이 → 재기동 전 멈춤** | 빌드가 7a94472 jar를 낸 경우: jar 차이 3줄(LiveTradingService 세 항목)이 RARX와 달라 U1이 "jar 차이가 N20 2줄이 아님 … (재기동 전). U1-되돌림"으로 멈췄다. 실행 backend는 #25 이미지 그대로이고, ok-U1이 없다. U2는 "U1 미통과". U1-되돌림 한 줄 뒤 HEAD는 ee509cf, latest는 image-before와 같고, 재기동은 없다 |
| s4a-window | 시간 표지(KST 09:30) | U0 "시작 시각·정기 보고 확인"에서 멈춤, 호출 0회 |
| s4b-noreport | 10:00 보고 파일 없음 | U0 같은 멈춤, 호출 0회 |
| s4c-u1late | U0 뒤 30분 1초에 U1 | "U0 가 30분보다 오래됨 — U0 부터(… U1-되돌림 하지 않는다)", HEAD·latest 그대로 |
| s5a-btc / s5b-btc20 | BTC H1 미충족 / 20일 봉 19/20 | 둘 다 U0 "BTC 입력 미충족 — 배포하지 않고 보고", ok-U0 없음 |
| s5c-session | 검증 세션 `66 \| 62 \| 4 \| 530:STOPPED` | U0 "검증 66세션 상태 다름" |
| s5d-sid203 | SID 203 RUNNING | U0 "SID 203·기준 행·EG·0 확인 중 다른 것 있음" |
| s5e-install / s5f-cron | 정기 점검 설치본 해시 / crontab 해시 다름 | U0 "정기 점검 설치본이 c669ccd 판이 아님" / "crontab 이 f2dce280… 과 다름" |
| s6-rollback | **명시 복구** — 배포 뒤 세션 변화 → U3 "되돌림 필요" → U되돌림 → U3(rollback) | U3가 "되돌림 필요: 검증 세션 상태 바뀜"으로 멈췄다. U되돌림 뒤 HEAD는 ee509cf, backend·latest는 #25 이미지 자리로 돌아왔다. 5분 45초 뒤 U3는 `t-rollback.utc`로 자동 전환해 "✅ U3(rollback) 통과 — ee509cf 복귀 확인. U4 는 하지 않고 출력을 보고"로 끝났다 |
| s7-p0 | 기동 뒤 **ERROR 수준** 연결 획득 시간 초과 줄 | U3가 P0 문구보다 앞선 일반 ERROR 검사("세 마켓 캔들 404·뉴스 출처 ERROR 밖의 ERROR — errors-deploy.txt 를 보고")에서 먼저 멈췄다. 아래 "발견"을 본다 |
| s7b-p0warn | 같은 줄이 **WARN 수준**일 때 | holdobs가 "연결 획득 시간 초과 줄 1건"을 세고 U3가 "🔴 P0 — … 즉시 보고"로 멈췄다 |
| s8-n20seen | **N20 줄이 자연 발생한** 로그 | U4 n20obs "항목 가격 아님 1·호가 보강 1". `N20: 미관측` 줄과 ⚪ N20 미관측 문구가 나오지 않았고, 멈추지 않고 ✅ |

## 발견 (블록은 고치지 않았다 — 최종 N20 절차 제출 때 판단을 받는다)

1. **P0 문구 순서**: U3·U4는 "그 밖의 ERROR" 검사가 holdobs P0 검사보다 앞에 있다. 연결 획득 시간 초과 줄이 ERROR 수준으로 찍히면, 일반 ERROR 멈춤으로 먼저 끝나고 "🔴 P0" 문구가 나오지 않는다. 멈추는 것은 같다. 다만 이때는 운영자·개발 에이전트가 `errors-deploy.txt` 원문에서 그 줄을 보고 **P0로 즉시 보고**해야 한다. 이 순서는 #25 U3·U4(342f461 §92)와 같다. D1에서 새로 생긴 차이가 아니다.
2. U되돌림 뒤 U4는 하지 않는 단계다. 대역 장치 결함 2(아래) 때문에 U4가 한 번 돌았고, "U3 미통과"로 멈췄다. ok-U3-deploy가 없으면 U4가 진행되지 않음을 덤으로 보인 것이다.
3. 대역은 실제 서버 응답·실제 로그·실제 이미지 ID·실제 설치 해시를 재현하지 않는다. SQL 응답·로그 문구·해시는 대역 고정값이다. 다음은 이 대역으로 주장하지 않는다.
   - 운영에서 이 블록이 통과한다는 것
   - #25 이미지의 실제 값
   - IMGOLD·기점의 확정

## 대역 장치 결함 이력 (보존)

1. `s1-normal`: python3 대역이 `-I`로 돌면서 PYTHONUTF8·PYTHONIOENCODING을 무시했다. 그래서 cp949 UnicodeEncodeError가 났고, U0가 "n17obs 인자 오류"로 멈췄다. 블록 판정과는 무관하다. `-X utf8`을 붙인 뒤 새 대역 `s1b-normal`로 다시 돌렸다. 원 출력은 `runs/s1-normal/`에 그대로 있다.
2. `s6-rollback`: scenarios.sh가 두 번째 U3에 꼬리를 붙이지 않았다. sim.sh가 "이미 있는 출력"으로 거부했고, 블록은 돌지 않았다. 이어 돈 U4는 "U3 미통과"로 멈췄다. 이후 꼬리 `rollback`을 붙여 U3를 다시 돌렸다(`scenarios-summary-2.txt`).

## 하지 않은 것

- 서버·DB·ssh·도커·원격 git·네트워크 접속은 없다.
- Gradle·Spring·jar 빌드를 하지 않았다. jar는 보존본을 그대로 썼다.
- 기존 114건 회귀 시험을 다시 돌리지 않았다.
- 블록·후보 코드·시험은 바꾸지 않았다.
- D1 IMGOLD를 확정하지 않았다. #25 결과 수용 뒤 실제 HEAD·전체 이미지·`~/maint-n19/image-new.txt`·보존 태그·F1 핀으로 다시 대조한다.
