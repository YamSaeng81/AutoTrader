# V172D4 U2확인 보정 출처 — 재기동 없이 U2 남은 확인을 마치는 블록(제출, 총괄 판정 전)

2026-11-16. 지시는 [총괄 v1.174](../review-v172-u2/README.md)와 [후속 메모 tail-record](../review-v172-u2/tail-record/README.md)다. 실행 기록(U0~U2 원문, 그 뒤 읽기)은 [v172run-20261116](../v172run-20261116/README.md)에 있다. 이 폴더는 블록·생성 대조기·실행 틀·로컬 PG 대조·대역을 담는다. 운영 변경은 없다(읽기만 했다).

## 1. 결과 요약

| 항목 | 값 |
|---|---|
| U2확인 블록 | [u2c/U2확인.sh](u2c/U2확인.sh) **1c9eab3582d8574371501ebb1cc693408bd822f66f752261ad0c8997ddbb7cc2** |
| U2 교정 사본(참조, 실행 안 함) | [u2c/U2-교정.sh](u2c/U2-교정.sh) a8478e3a… — ce6df2fa U2(2feef9e1)에서 `96:t:` → `96:true:` 한 줄 |
| 생성·대조기 | [u2c/mku2c.py](u2c/mku2c.py), 결과 [u2c/blocks-u2c.sha256](u2c/blocks-u2c.sha256) |
| 실행 틀 | [runner/run.sh](runner/run.sh)·[runner/extract.py](runner/extract.py) 13해시(원래 12 + U2확인), 가짜 ssh [runner-selftest.txt](runner/runner-selftest.txt) **18/18** |
| 로컬 PG 15.13 | [pg/pg-v96-r3-out.txt](pg/pg-v96-r3-out.txt) — 새 블록은 12입력 중 정상 1개만 통과 |
| 대역 r3(최종) | [standin/r3/scen-u2c-run3.txt](standin/r3/scen-u2c-run3.txt) **187/0** |
| 대역 r2(844e5c0c 블록) | [standin/r2/scen-u2c-run2.txt](standin/r2/scen-u2c-run2.txt) 158/4(실패 4는 시나리오 쪽, §5) |
| 운영 읽기 | 14:40:32·15:19:00 — P0 0·누수 0·🔴 한도 0·ERROR 404 3줄뿐·다섯 서비스 재시작 0([v172run diag](../v172run-20261116/diag/)) |

## 2. U2확인 블록

전제를 먼저 확인한다. 기록 폴더·판 표지·NEW, ok-U1 있음, ok-U2 없음, t-deploy 있음을 본다. 원래 U2 꼬리 기록(svc-deploy·git-deploy)이 없어야 한다. 복구 시작 기록 8종과 frontend 교체 시작 기록 4종도 없어야 한다. frontend 단계는 빌드끝이고 U1 대응 기록이 있어야 한다. 모두 파일만 확인하고 조회는 하지 않는다.

그다음 순서는 아래와 같다. 하나라도 어긋나면 멈추고, ok-U2는 쓰지 않는다.

1. **P0 먼저.** t-deploy.utc 뒤 backend 로그를 `app-u2c-<시각>.log`에 보존한다. 로그 조회 실패나 빈 로그는 검사 미완료로 멈춘다. holdobs 1이면 P0, 0·3이 아니면 판정 실패로 멈춘다. 3은 기록만 한다.
2. HEAD = NEW이고 추적 변경이 없어야 한다.
3. backend latest와 실행 backend가 U1 새 이미지 대응이어야 한다. backend 재시작 0·running이어야 하고, StartedAt은 배포 기록 뒤 0~120초여야 한다.
4. 실행 frontend가 U0 구판 대응이어야 한다. frontend 이미지 이름은 U1 새 frontend여야 하고, 두 보존 태그는 U0 대응이어야 한다.
5. SID203·66세션·공유 풀 등록값을 확인하고, Flyway 최고 버전이 96이어야 한다.
6. **V95 이후 이력은 한 SELECT로 행수와 내용을 함께 받는다.** 통과는 `1|96:true:-424836241` 하나뿐이다(체크섬 NULL은 `NULL` 글자로 나온다). 원문은 `v96-u2c-<시각>.txt`에 남긴다.
7. `svc-u2c-<시각>.txt`에 서비스 5줄을 쓴다. backend StartedAt이 3과 같아야 하고, backend 밖 서비스는 svc-before와 같아야 한다.
8. `git-u2c-<시각>.txt`에 NEW와 `(끝)`만 있어야 한다.
9. **U2 꼬리 완성.** ok-U2·svc-deploy·git-deploy가 없음을 다시 확인한다. 7·8의 기록을 원래 이름 svc-deploy.txt·git-deploy.txt로 같은 바이트로 복사하고 cmp로 대조한다. U4 원문이 svc-deploy.txt의 backend 행으로 재시작을 대조하기 때문이다(§5 r1). 원래 U2 시각의 기록으로 소급하지 않는다. ok-U2 본문에 "U2확인 <시각> · 원래 U2 rc1(14:16:35) · 배포 기록 · backend 기동"을 적는다.
10. **마지막 줄에서만** ok-U2를 쓴다.

재빌드·재기동·tag·checkout·DDL·DB 쓰기·sleep·touch·rm는 없다. mku2c.py가 금지 패턴과 줄 위치를 검사한다. 확인 내용은 다음과 같다.

- ok-U2 쓰기는 마지막 실행 줄 한 곳이다.
- 꼬리 복사는 그 바로 앞 한 곳이다.
- P0 검사가 git·이미지·DB 조회보다 앞이다.
- SQL의 표·조건·내용식은 U2 원문과 같다.
- 실행 줄에 `96:t:`가 없다.

U3·UF2·U4·U되돌림·U뒤관측은 ce6df2fa 원문과 해시를 그대로 쓴다. 판 표지 UB=V172D4와 ~/maint-v172도 유지한다.

## 3. 로컬 PostgreSQL 대조(실제 SQL 출력 근거)

시험용 컨테이너 v152-pg(PostgreSQL **15.13**, 운영과 같은 판)를 썼고 운영 DB는 쓰지 않았다. 같은 세션의 임시 표 flyway_schema_history(운영과 같은 열 형식)에 입력을 넣었다. 블록 파일에서 꺼낸 SQL 원문을 그대로 실행했다.

- [pg-v96-expr-out.txt](pg/pg-v96-expr-out.txt)는 첫 대조다. 정상 행은 `96:true:-424836241`이다. 개별 boolean 열은 `t`로 나오고 `true || ':'`는 `true:`다. 운영 진단 [u2-diag-2.txt](../v172run-20261116/diag/u2-diag-2.txt)와 같다.
- [pg-v96-null-before-out.txt](pg/pg-v96-null-before-out.txt)는 후속 메모 §2 확인이다. 정상 V96에 체크섬 NULL인 V95·V96·V97 행을 더하거나, 실패·NULL V95 행을 더해도 옛 SELECT 결과는 그대로 `96:true:-424836241`이었다. **재현됐다**(string_agg는 NULL 입력을 뺀다).
- [pg-v96-r3-out.txt](pg/pg-v96-r3-out.txt)는 12입력을 블록마다 그 블록의 SQL과 기대값으로 판정한 결과다.
  - 844e5c0c(r2 블록)는 정상과 NULL 혼합 4종, 모두 5개를 통과시킨다.
  - **1c9eab35(최종)는 정상 1개만 통과**시키고 11개에서 멈춘다. NULL 혼합 입력은 `2|95:true:NULL,96:true:-424836241` 같은 형태로 나온다.
  - 이 판정 스크립트의 첫 실행은 Windows python 출력의 `\r` 때문에 정상도 "멈춤"으로 표시했다. 그 출력은 [pg-v96-r3-out-판정줄결함.txt](pg/pg-v96-r3-out-판정줄결함.txt)에 보존했다. SQL 결과 열은 두 실행이 같다.

## 4. 대역(제어 흐름 근거)

장치는 v172d4s/sim(ce6df2fa 커밋본과 같은 가짜 명령, [docker-stub-ce6df2fa.sh](standin/docker-stub-ce6df2fa.sh))에 아래를 더한 것이다.

- **가짜 DB의 V95 이후 이력 응답을 실제 형식으로 고쳤다.** 종전 `96:t:`는 틀린 응답이었다. r2는 `96:true:`를 쓰고, r3는 새 SELECT에 `1|96:true:` 또는 `0|-`로 답한다.
- 응답 주입(V96H_OUT·V96C_OUT·V96H_FAIL)을 더했다.
- docker inspect의 `{{.RestartCount}}`·`{{.State.Status}}`(RESTARTS·CSTATUS)를 지원한다.
- 차분: [r2](standin/r2/docker-stub.ce6df2fa-to-r2.diff)·[r3](standin/r3/docker-stub.ce6df2fa-to-r3.diff)

모든 사례는 실제와 같은 상태에서 시작한다. U0 → U1 → 원래 U2(ce6df2fa)를 돌리면, 고친 가짜 DB에서 U2가 실제와 같은 줄인 "V95 이후 이력이 V96 한 줄…"에서 rc1로 멈춘다. 이것이 11-16 14:16의 재현이다.

r3([본문](standin/r3/scen-u2c-r3-body.sh), [r2→r3 차분](standin/r3/scen-body.r2-to-r3.diff), [원문 압축](standin/r3/standin-runs-r3.tar.gz) 5,471파일, jar 제외)는 **187/0**이다.

| 사례 | 확인 |
|---|---|
| c01 정상 | U2확인 rc0 → 같은 블록 다시 실행은 "U2 를 이미 통과" → U3 → UF2 → U4 → U뒤관측 통과. 변경 명령(tag·build·up·checkout)이 늘지 않고 t-deploy 해시가 그대로다. 출력 순서는 P0 → 이력 → ✅이다. 새 기록 6종이 각 1개이고 svc-deploy·git-deploy는 새 기록과 같은 바이트다. |
| c02~c13n V96 이력 | false·체크섬 다름·0행·복수 행·V96 두 번·조회 실패·옛 t 표기·NULL 혼합 2종·단독 NULL·행수 없는 옛 형식에서 모두 멈춘다. |
| c11~c14 P0 | 로그 P0·로그 조회 실패·빈 로그·holdobs 판정 실패에서 멈춘다. 멈춘 뒤 git·이미지·DB 조회는 0이다. |
| c21~c27 상태 | 다른 서비스 변경·backend 구판·frontend 제3 이미지·latest 구판·재시작 1·배포 10분 뒤 재기동·HEAD 다름에서 멈춘다. |
| c31~c34 DB | SID203·66세션·공유 풀·Flyway 95에서 멈춘다. |
| c41~c45 전제 | U2 재기동 전·복구 시작·frontend 교체 시작·원래 꼬리 기록 있음·단계 기록 다름에서 멈춘다. |

모든 실패 사례에서 ok-U2·svc-deploy·git-deploy는 생기지 않았다(c44는 미리 둔 svc-deploy). 변경 명령은 늘지 않았고, 배포 기록 해시는 그대로이며, 금지 명령 기록은 0이다.

## 5. 앞 실행(원문 보존, 합산하지 않음)

- **r1(혼합 진단).** [standin/r1-mixed/](standin/r1-mixed/)에 보존했다. 블록 d56d03eb로 시작했다.
  - c01 U4가 "되돌림 필요: backend 재시작 있음"으로 멈췄다. U4 원문이 원래 U2 꼬리인 svc-deploy.txt를 읽기 때문이다. 그래서 §2-9의 꼬리 완성을 더했다(총괄이 허용).
  - 실행 중에 대역 블록 사본과 시나리오 파일을 바꿨다. 중단 요청은 바깥 셸만 멈췄고, 시나리오는 15:12:58까지 계속 돌다가 재조립된 파일 끝에서 문법 오류로 끝났다. 결과는 쓰지 않는다.
- **r2.** 블록 844e5c0c, 장치·사례 고정, [standin/r2/](standin/r2/)에 보존했다. 결과는 158/4다.
  - 실패 4는 c31·c32다. 주입값(`EG_OUT=1|0|…`, `SC_OUT=66 | 62 …`)을 따옴표 없이 env.sh에 써서 source할 때 `|`가 파이프로 해석됐다. 그래서 주입이 되지 않았고 블록은 정상값을 받아 통과했다(시나리오 쪽 실수).
  - r3에서 따옴표를 넣어 두 사례가 멈추는 것을 확인했다.
  - 실행 틀 당시판은 [runner/r2-844e5c0c/](runner/r2-844e5c0c/)에 있다.
- **844e5c0c → 1c9eab35.** 후속 메모 §2의 NULL 혼합 입력을 막으려고 V95 이후 이력을 행수·내용 한 SELECT로 바꿨다. 바뀐 것은 블록 3줄과 주석 1줄이다(보존 사본 [u2c/r2-844e5c0c/](u2c/r2-844e5c0c/)).

## 6. 실행 방법과 시간 편차

1. 보정 출처 수용 뒤 `python -I runner/extract.py /d/tmp/v172run/blocks-u2c <이 커밋 40자>`로 13블록을 꺼낸다. 10블록은 ce6df2fa와 이 커밋에서 바이트가 같은지 대조하고, U2확인은 blocks-u2c.sha256과 대조한다.
2. `run.sh U2확인 deploy`를 실행하고, 통과하면 즉시 `run.sh U3 deploy` → UF2 → U4(30분 이후) → U뒤관측 순서로 진행한다. 재기동 확인은 다시 요청하지 않는다(v1.174 §3).

기동 5~10분 확인 창은 StartedAt 기준 14:21:14~14:26:14 KST였고 이미 지났다. U3는 늦게 실행한 편차로 보고한다. 기준 시각(t-deploy 14:16:12)과 기대 창은 바꾸지 않는다. U3는 기동 이후 전체 로그를 본다.
