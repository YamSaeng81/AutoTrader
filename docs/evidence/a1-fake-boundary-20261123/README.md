# A1 가짜 시험 실행 경계 보완과 로컬 실패 경로 시험 (v1.225 §3·§4-1)

2026-11-23 KST, Claude Code(개발 세션) 제출. **총괄 수용 전이며 A1 운영 실행 승인과 무관하다.** 이 세션은 운영에 접속하지 않았다(개발 보고이며 독립 서버 감사는 아님). 전역 SSH·방화벽·권한 설정은 바꾸지 않았다.

## 1. 결론

- 시험 회차 **r1: 27/27 통과, 감지용 대체 파일 호출 0회.** 가드 거절 13건은 모두 종료2·가짜 호출0·보존폴더 미생성이며, 거절 문구가 의도한 사유와 일치한다. 합성 응답 13건은 종료값·상태 이름·호출 수·미완료 표지가 기대와 일치한다. 구 초안 결함 재현 1건 포함.
- **생산용 초안 v2**([prod/approval/a1-read.sh](prod/approval/a1-read.sh) `b1e9e1f0`): 15e3e412에서 **설치 상태 표준오류 정지(STOP_INSTALL_STDERR 5) 1줄**과 주석 2줄만 추가했다. 기록 실패(RECORD_FAILED_INSTALL_STATE→종료4·표지 남김)는 원래부터 SQL 전에 멈췄고 이번에 직접 확인했다. SQL은 b84a89e7 그대로다.
- 이 결과는 **시험 사본**의 검증이다. 생산용 원문의 원격 호출 줄(실제 원격 도구 호출)은 시험되지 않았다. 실행기 최종 검토·운영 허가는 후속 제출에서 별도로 판단한다.

## 2. 실행 구조 — 실제 원격 도구로 갈 경로를 없앤 방법

| 층 | 내용 | 근거 |
|---|---|---|
| 시험 사본 생성 | [make-test-copy.sh](tools/make-test-copy.sh)가 생산용 초안에서 기계적으로 만든다. 바뀌는 곳은 ①표지 1줄 ②가드 삽입 ③원격 호출 1줄→`"$FAKE" "$TEST_HOST" …`뿐. 주석 아닌 줄에 원격 도구 이름(ssh·scp·sftp·curl·wget·nc·telnet)·운영 별칭이 남으면 생성 실패. `docker`는 가짜에 넘기는 원격 명령 문자열 2줄에만 있음을 확인 | [r1 차이](runs/r1/diff/prod-v2-vs-test-copy.diff) |
| 가짜 고정 | [test-guard.inc](tools/test-guard.inc): 가짜는 **키트의 fake/fake-remote.sh 절대 경로 하나**만 허용, 해시를 사본에 박아 대조. 누락·미설정·상대 경로·다른 경로(같은 해시라도)·해시 불일치·연결 파일이면 시작 전 거절. 기본값/대체 실행 없음 | r1 g01~g05 |
| PATH·셸 | 가드 앞부분은 셸 내장 명령만 쓴다. 상속 함수·BASH_ENV/ENV·Windows 형식(역슬래시·세미콜론·드라이브 문자)·빈 항목·상대·비정규 경로 PATH 거절. 구동기는 `env -i`+`bash --noprofile --norc`로 실행 | r1 g06~g08·g12·g13 |
| 대상 이름 | `A1_TEST_HOST=a1-test.invalid`만 허용. 운영 별칭이 환경값·시험 사본·가짜 어디에든 있으면 거절 | r1 g09~g11 |
| 가짜 본체 | [fake-remote.sh](tools/fake-remote.sh) `6b175026`: 고정 응답 파일을 읽어 내고 호출 기록만 덧붙인다. 원격/컨테이너/HTTP 도구 이름 0건(구동기가 시작 전 정적 검사). 시험 대상이 아닌 host면 98 | [정적 검사](runs/r1/fake-static-check.txt) |
| 사전 확인 | 구동기가 시작 전 PATH 선택(`command -v`)이 감지용 대체 파일로 가는지, 가짜 고유 식별 응답·경로·해시를 보존. 하나라도 다르면 시험 시작 안 함 | [preflight](runs/r1/preflight.txt) · [식별](runs/r1/fake-identity.txt) |
| 감지용 대체 파일 | PATH 맨 앞의 ssh·scp·sftp·docker·psql·curl·wget 이름 파일은 호출되면 기록만 하고 97로 끝난다(실제 도구를 부르지 않음). r1 호출 0회 | [summary](runs/r1/summary.txt) |

합성 응답([fixtures](tools/fixtures/))은 2000-01-01 시각·주문6/포지션3의 만든 값이며 운영 출력에서 가져온 값이 없다.

## 3. r1 결과 — [results.tsv](runs/r1/results.tsv)

| 사례 | 기대·실제 종료값 / 상태 | 가짜 호출 | 비고 |
|---|---|---|---|
| g01~g13 가드 거절 13건 | 2 / — | 0 | 보존폴더 미생성·감지0 |
| s01 ok | 0 READ_OK | 2(install,sql) | 받은 SQL 해시 b84a89e7 |
| s02 핀 불일치 | 3 STOP_PIN_MISMATCH | 1 | SQL 호출 차단 |
| s03 Flyway 불일치 | 3 STOP_PIN_MISMATCH | 2 | |
| s04 보호값 거절(A1_GUARD 모사) | 3 STOP_SQL_FAILED | 2 | |
| s05 SQL 표준오류 | 5 STOP_SQL_STDERR | 2 | |
| s06 빈 출력 / s07 합계 불일치 | 7 STOP_SQL_SHAPE | 2 / 2 | |
| s08 SSH 실패(255 모사) | 255 STOP_REMOTE_INSTALL_STATE | 1 | SQL 호출 차단 |
| **s09 설치 상태 표준오류(신규 정지)** | 5 STOP_INSTALL_STDERR | **1** | SQL 호출 차단 |
| **s10 설치 상태 기록 실패** | 4 / 상태 줄 없음 | **1** | INCOMPLETE.txt 남음 |
| s11 재실행(1회 범위) | 첫 0 READ_OK → 둘째 2 | 2 → 2 | 둘째 호출 0 |
| s12 승인 창 지남 | 2 | 0 | |
| o01 **구 초안 15e3e412** 설치 표준오류 | 0 READ_OK | 2 | 결함 재현: 표준오류가 있어도 SQL로 넘어감 |

s10의 `rc.txt`는 가짜가 기록 실패를 일으키려 만든 빈 폴더라 Git에 남지 않는다. 회차 해시는 [runs/r1/SHA256SUMS](runs/r1/SHA256SUMS)(파일만).

## 4. 생산용 초안과 시험 사본의 차이

| 위치 | 생산용 v2 `b1e9e1f0` | 시험 사본 `ca9888bd`(r1 kit-new) |
|---|---|---|
| 2행 | — | 시험 사본 표지 |
| `set -u` 뒤 | — | 가드 35줄(가짜 해시 6b175026 고정) |
| remote() 원격 호출 | `ssh -o BatchMode=yes cat-prod "$4" …` | `"$FAKE" "$TEST_HOST" "$4" …` |
| 그 밖 | 같음(기록·종료값·핀·SQL 형태 검사·1회 범위·승인 창) | 같음 |

따라서 r1은 **기록 순서·종료값·호출 차단 논리**의 검증이며, 실제 원격 도구의 종료값·표준오류 동작, 서버 쪽 명령 문자열, 운영 PG의 보호값 처리는 검증하지 않았다. 구 초안과 v2 차이는 [old15e3-vs-prod-v2.diff](runs/r1/diff/old15e3-vs-prod-v2.diff).

## 5. 한계와 남은 것

- **네트워크 차단 환경은 확보하지 않았다.** Windows 호스트의 Git Bash에서 실행했다. 대신 위 절대 경로 강제·도구 이름 정적 검사·감지용 대체 파일을 갖췄다. 감지용 대체 파일은 PATH를 거치는 호출만 잡는다(절대 경로 직접 호출은 정적 검사로만 배제).
- BASH_ENV는 bash가 가드보다 먼저 읽으므로 가드는 탐지·거절만 한다. 1차 차단은 구동기의 `env -i`다.
- 실제 원격 도구가 불리는지 확인하려고 실제 원격 도구를 실행하지 않았다.
- 사건 당시 **로컬PG SQL 검증의 구동 명령·출력·종료값은 미보존**이다(임시 폴더 `%TEMP%\claude\a1test`에 6회 실행 기록 사본·가짜·시험 키트만 있고 PG 출력 없음). 채우기 위한 PG 반복은 하지 않았다. 그 임시 사본은 보존본과 같은 실행 기록이며 삭제하지 않았다.
- a1-pg 정리: 생성 04:06:07Z(13:06:07 KST)·포트 연결 없음·익명 볼륨·로컬 Docker Desktop 문맥(`npipe:////./pipe/dockerDesktopLinuxEngine`)을 확인한 뒤 `docker rm -f -v` rc0([cleanup](cleanup/)).
- 사건 원문·초안 15e3e412·SQL b84a89e7·이전 체크섬은 바꾸지 않았다.

## 6. 다시 실행하는 방법

`bash tools/run-cases.sh r2` — 새 회차 이름만 받는다(있으면 거절). 성공 수를 이전 회차와 합치지 않는다.
