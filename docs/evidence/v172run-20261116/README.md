# V172D4 운영 실행 기록 — 11-16 U0·U1 통과, U2 rc1(확인식 결함), 그 뒤 읽기

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

## 3. 상태

backend는 0e1d2a02 이미지 2c5d8427로 기동됐고 DB에는 V96이 적용됐다. frontend는 구판(d825a9f5)이다. ok-U2는 없다. U3 이후는 실행하지 않았다. 운영 반영 완료·F1·N06 핀 갱신은 후속 실행 결과의 판정이다.
