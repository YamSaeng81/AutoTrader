# V172D4 운영 실행 기록 — 11-16 U0·U1 통과, U2 rc1(확인식 결함) → U2확인·U3·UF2·U4·뒤 관측 rc0

2026-11-16. 출처 ce6df2fa(V172D4, 총괄 v1.173 수용). 실행 틀은 [v172d4-20261116/runner/run.sh](../v172d4-20261116/runner/run.sh)이고, 블록은 extract.py로 꺼낸 12블록이다([blocks/blocks.json](blocks/blocks.json), 추출 출력 [diag/extract-run.txt](diag/extract-run.txt), 이 파일은 콘솔 코드 페이지 때문에 한글이 깨진 원문 그대로다). 이 폴더의 파일은 실제 출력 원문이다. 고치거나 추정해 채운 값은 없다.

## 1. 단계와 종료값([out/exit-codes.txt](out/exit-codes.txt))

| 단계 | 로컬 시각(KST) | 종료 | 원문 |
|---|---|---|---|
| 사전 읽기 | 14:00 | — | [pre/n03-reread-1400/](pre/n03-reread-1400/) (10:00 보고·전송·장부 그대로, 새 안전 이상 없음) |
| U0 | 14:00:23~14:01:10 | 0 | [out-U0-deploy.txt](out/out-U0-deploy.txt) — Q3~Q9 기대값, BTC H1 true 20/20, 보존 태그 backend 있음·같음, frontend 404 |
| U1 | 14:01:20~14:04:13 | 0 | [out-U1-deploy.txt](out/out-U1-deploy.txt) — HEAD 0e1d2a02, 새 backend 2c5d8427, JARM b6587953 일치, 새 frontend 9d7f123a, 보존 태그 frontend:b5cd1d0 생성 |
| 운영자 확인 | — | — | "재기동 진행" 1회 |
| U2 | 14:15:58~14:16:35 | **1** | [out-U2-deploy.txt](out/out-U2-deploy.txt) — 14:16:12 배포 기록, backend 재생성·새 이미지 대응·공유 풀·Flyway 96 통과 뒤 V95 이후 이력 문자열 비교에서 멈춤 |

U2 멈춤 줄은 `= "96:t:$V96SUM"` 비교다. 실제 연결 결과는 `96:true:-424836241`이다([diag/u2-diag-2.txt](diag/u2-diag-2.txt) 마지막 줄). 개별 열로 읽으면 V96 한 줄, 성공 `t`, 체크섬 -424836241이다. 첫 진단 SQL은 `"|"` 따옴표 오류로 실패했고, 그 출력도 [diag/u2-diag-1.txt](diag/u2-diag-1.txt)에 남겼다. 원래 U2 원문과 rc1은 바꾸지 않는다. 판정은 [총괄 v1.174](../review-v172-u2/README.md)를 따른다. 보정은 [v172u2c-20261116](../v172u2c-20261116/README.md)에 있다.

## 2. U2 뒤 읽기(읽기만, 실제로 읽은 시각만)

| 시각 | 원문 | 요지 |
|---|---|---|
| 14:17:17 | [diag/post-u2-read.txt](diag/post-u2-read.txt) | 로그 222줄, 연결 획득 실패 0·누수 0, 공유 풀·위험 한도 확인 줄 정상, 🔴 한도 0, ERROR 3(캔들 404 3), 다섯 서비스 재시작 0, HEAD 0e1d2a02 변경 0, ok-U2 없음 |
| 14:22:38 | [diag/post-u2-read-5min.txt](diag/post-u2-read-5min.txt) | 414줄, 연결 획득 실패 0·누수 0·시작 Catchup 완료 1, 🔴 한도 0, ERROR 3(404 3), backend 재시작 0 |
| 14:40:32 | [diag/read-u2c-prep-1.txt](diag/read-u2c-prep-1.txt) | 923줄, 연결 획득 실패 0·누수 0·Catchup 완료 1, 🔴 한도 0, ERROR 3(404 3), 다섯 서비스 재시작 0(frontend·db·redis·db-backup 그대로), HEAD 변경 0, ~/maint-v172 에 ok-U0·ok-U1·t-deploy 있음, ok-U2·svc-deploy·git-deploy 없음 |

backend StartedAt은 2026-11-16T05:16:14.129686133Z(14:16:14 KST)이고, U2 배포 기록은 14:16:12 KST다. 두 시각은 구분해 기록한다. 이 표의 시각 밖은 세션이 감시하지 않았다.

## 3. U2확인부터 뒤 관측까지(총괄 v1.175 승인, 427차)

출처 c584166b. 실행 틀은 [v172u2c runner/run.sh](../v172u2c-20261116/runner/run.sh)(공백 없는 사본 /d/tmp/v172u2c/run.sh, 커밋본과 바이트 같음)이다. 블록은 `extract.py /d/tmp/v172run/blocks-u2c c584166b…`로 꺼냈고 rc0, 13해시가 일치한다([blocks-u2c/blocks.json](blocks-u2c/blocks.json), [diag/extract-u2c-run.txt](diag/extract-u2c-run.txt), 이 파일도 콘솔 코드 페이지 때문에 한글이 깨진 원문이다). 운영자 확인을 다시 받지 않았다(v1.175 §2).

| 단계 | 로컬 시각(KST) | 종료 | 원문 · 요지 |
|---|---|---|---|
| U2확인 | 16:13:59~16:14:30 | 0 | [out-U2확인-deploy.txt](out/out-U2확인-deploy.txt) — P0 0(장기 대여 0·획득 실패 0), backend 2c5d8427 StartedAt 05:16:14.129686133Z·재시작 0·running·배포 기록 뒤 2초, V95 이후 이력 `1\|96:true:-424836241`, 다섯 서비스 기록, git NEW·변경 없음 → svc-deploy·git-deploy 꼬리 완성 → ok-U2 |
| U3 | 16:14:37~16:14:47 | 0 | [out-U3-deploy.txt](out/out-U3-deploy.txt) — P0 0, Q3~Q9 기대값, 66세션 63/3, BTC H1 true 20/20, 판정 불가 대상 0, 60분 출처 5개 첫 회차 완료, cryptopanic HTTP_403 미완료 3(고정 한 줄·짝 3), N15·N16·N17·N19·N20 미관측 |
| UF2 | 16:14:58~16:15:30 | 0 | [out-UF2-deploy.txt](out/out-UF2-deploy.txt) — t-fe 16:15:15, frontend 9d7f123a StartedAt 07:15:16.447837224Z, frontend 외 4서비스 그대로, `/` 307, frontend 로그 오류 0 |
| 화면 확인 | 16:16:55 · 16:20:38 · 16:20:52(서버 시계) | — | [screen/](screen/) — 아래 |
| U4 | 16:20:58~16:21:03 | 0 | [out-U4-deploy.txt](out/out-U4-deploy.txt) — P0 0, Q3~Q9 기대값, 이미지 다섯 서비스(backend 2c5d8427·frontend 9d7f123a), BTC H1 그대로, 뉴스 60분 출처 완료 2, cryptopanic 403 ×4, 🔴 KRW-BLAST 적재 뒤처짐 3회(배포 전 1일 24회와 같은 종목·같은 :19 회차), N15~N20 미관측 |
| U뒤관측 | 16:21:08~16:21:16 | 0 | [out-U뒤관측-deploy.txt](out/out-U뒤관측-deploy.txt) — 두 번째 로그 P0 0, 출처 ERROR 0, 403 ×4 고정 한 줄, N15~N20 미관측 |

**U3 시간 창 편차.** 기준 창 14:21:14~14:26:14 KST(backend 기동 14:16:14 기준 5~10분)에 대해 실제 U3는 16:14:37에 시작해 약 1시간 48분 늦었다. 원래 배포 기록 14:16:12와 StartedAt 14:16:14는 바꾸지 않았다. U2확인 통과는 당시 창 준수의 근거가 아니다. U4의 30분 기준(14:46:12)도 지난 뒤 실행했다.

**cryptopanic HTTP 403.** U3에서 미완료 3, U4·뒤 관측에서 미완료 4이고 모두 고정 한 줄과 상위 FAILED 짝이 맞아 블록 허용 범위다(멈춤 아님). 같은 출처 403은 11-04 [k20-result-b1d7664](../k20-result-b1d7664/)에도 있다. 새 이상인지는 총괄 판단에 맡긴다.

### 화면 확인(브라우저 시각 확인이 아니라 서버 안 HTTP·프록시 경로 확인)

- [fe-screen-noauth-1.txt](screen/fe-screen-noauth-1.txt) 16:16:55: 로그인 없이 `/` 307→/login, `/login` 200(오류 문자열 0, 스크립트 묶음 14개, 하나를 받아 200), 목록 세 경로 307→/login.
- AUTH_SECRET을 컨테이너 환경에서 꺼내는 첫 시도는 세션 권한 검사에서 거절됐고 실행되지 않았다. 운영자가 로그인 비밀번호를 주어 정상 로그인(POST /api/auth/login, 비밀번호는 표준 입력으로만 보내고 파일·출력에 남기지 않음, 쿠키는 서버 임시 파일로 끝나면 삭제) 뒤 GET만 보냈다.
- [fe-screen-1.txt](screen/fe-screen-1.txt) 16:20:38: 로그인 200, 페이지 6개 모두 200·오류 문자열 0. API 요약은 서버 python 판의 f-string 제약으로 SyntaxError — 스크립트 [fe-screen-1.sh](screen/fe-screen-1.sh) 그대로 보존.
- [fe-screen-2.txt](screen/fe-screen-2.txt) 16:20:52(요약식만 고친 [fe-screen-2.sh](screen/fe-screen-2.sh)):

| 화면 | 페이지 | 같은 경로 프록시 API |
|---|---|---|
| 모의 목록 /paper-trading | 200 | 200 · 420건(RUNNING 76·STOPPED 344) |
| 모의 상세 /paper-trading/587 | 200 | 200 · RUNNING KRW-INJ 1,000,000 |
| 실거래 목록 /trading | 200 | 200 · 48건(CREATED 1·DELETED 16·EMERGENCY_STOPPED 20·STOPPED 11) |
| 실거래 상세 /trading/203 | 200 | 200 · SID 203 CREATED KRW-BTC DAILY_SMA20 20,200 |
| DYNAMIC 목록 /trading/dynamic | 200 | 200 · 40건(EMERGENCY_STOPPED 21·STOPPED 19) |
| DYNAMIC 상세 /trading/dynamic/93 | 200 | 200 · STOPPED 10,146.06 |

화면이 실제로 그려진 모양(표·숫자 배치)은 이 확인 범위 밖이다.

### 서버 기록 사본

[server/maint-v172.sha256](server/maint-v172.sha256)은 16:22에 서버 `~/maint-v172` 127파일을 서버에서 sha256sum 한 원문이다. [server/maint-v172-nojar.tar.gz](server/maint-v172-nojar.tar.gz)는 app-new.jar·app-old.jar(각 87MB)·backend-before.log(33MB)를 뺀 124파일이며, 풀어서 위 목록과 124/124 일치했다. ok-U0·U1·U2·U3-deploy·UF2·U4가 있다. svc-deploy.txt·git-deploy.txt는 svc-u2c·git-u2c-20261116-161400과 해시가 같다(a0df6b65…·d1a8c10a…).

## 4. 상태(16:21 기준)

backend 0e1d2a02 / 이미지 2c5d8427(StartedAt 14:16:14 KST·재시작 0), DB V96, frontend 9d7f123a(16:15:16 KST)다. U0~U4·뒤 관측 기록은 있고 U2는 원래 rc1 + U2확인 rc0이다. 운영 반영 완료·F1·N06 핀 갱신·새 모의 세션·T0는 총괄 판정 뒤다. 이 기록의 시각 밖은 세션이 감시하지 않았다.
