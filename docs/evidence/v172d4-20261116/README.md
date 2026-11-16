# V172D4 준비 — R1~R3 보완 블록·관련 대역·실행 틀 (424차 제출, 총괄 판정 전)

총괄 v1.172(423차) §3~§4. 원본은 **V171D4(1644851b) 원문 10블록**이고 그 원문은 그대로 보존한다. 후보 **0e1d2a02**, GS 6385e1d5, DIFFN 133, jar 이름 차이 62줄·881→895, **JARM b6587953…**(Git LF), 이미지 고정 대응(backend 52e12428/ca440b84/0344757e, frontend 837db515/fa5c9ba8/80ce7550, 기록값 d825a9f5)과 imgchain.py(fddd5d1c)는 바꾸지 않았다. 판 표지 UB=V172D4, 기록 폴더 `~/maint-v172`. 제품·SQL·위험 한도·전략·세션·cron·daemon 변경 없음. 제품 전체 회귀·PG·jar 빌드는 입력이 바뀌지 않아 반복하지 않았다.

**운영 변경은 없다.** 서버에서 한 일은 12:58 KST에 Docker API로 태그 세 개를 GET으로 읽은 것뿐이다(§1). U0 이후 단계는 실행하지 않았다.

## 1. 보존 태그 조회 응답 형식 읽기 (11-16 12:58 KST, 읽기만)

원문: [state-1258/](state-1258/) (명령 원문 [tagq-probe.sh](state-1258/tagq-probe.sh), 출력 [tagq-probe-read.txt](state-1258/tagq-probe-read.txt))

| 조회 | 결과 |
|---|---|
| `GET /v1.56/images/autotrader-frontend:b5cd1d0/json` | HTTP 404, 본문 `{"message":"No such image: autotrader-frontend:b5cd1d0"}` |
| `… /autotrader-backend:b5cd1d0/json` | HTTP 200, `{"Id":"sha256:52e12428…","RepoTags":["autotrader-backend:b5cd1d0","autotrader-backend:latest"]…` |
| `… /autotrader-frontend:latest/json` | HTTP 200, `{"Id":"sha256:837db515…"` |
| 없는 소켓 | curl 종료 7, HTTP 000 |

R1의 "명시적 부재"는 이 형식(404 + 그 참조의 `No such image` 본문)만 인정한다. 그 밖의 응답은 모두 조회 불명으로 판정한다.

## 2. V172D4 블록 — §3 R1~R3 + 추가 검토 메모(postlog)

생성기 [d4/mkdraft11.py](d4/mkdraft11.py)는 V171D4 원문 10블록(1644851b 블롭 해시 확인)에서 지정한 부분만 바꾼다. 바꾸는 곳마다 출현 횟수를 확인한다. 끝에서 아래 항목을 확인하고, 하나라도 어긋나면 멈춘다.
- 옛 판 표기(maint-v171·UB=V171D4)와 옛 조회 형태 `if docker image inspect`가 남지 않았는가
- P0 1차 검사보다 앞에 뉴스 SQL·isimg·ps가 오지 않는가
- U되돌림에 latest를 무조건 덮어쓰는 줄이 남지 않았는가
- U1이 두 보존 태그를 모두 판정한 뒤 첫 태그를 쓰는가
- 두 되돌림이 두 이름을 판정한 뒤 첫 변경을 하는가
- 두 번째 로그 판정 실패 시 멈추는 줄이 있는가, P0를 출력만 하고 계속하는 줄이 없는가

블록은 [d4/blocks-d4/](d4/blocks-d4/), 해시는 [blocks-d4.sha256](d4/blocks-d4.sha256), V171D4 대비 차분은 [blocks-d4.v171-to-v172.diff](d4/blocks-d4.v171-to-v172.diff)다. 10블록 모두 `bash -n`을 통과했다.

| 보완 | 바뀐 블록 | 내용 |
|---|---|---|
| R1 | U변수 | `same3`은 0 같음 / 1 대응 다름 / 2 조회 불명을 돌려준다. `tagq`는 API 200+Id를 있음, 404+그 참조의 `No such image` 본문을 명시적 부재로 보고, 그 밖(curl 실패·500·형식 이상·다른 404 본문)은 불명으로 본다. `tagkeep`은 0 있음·같음 / 1 부재 / 2 다른 대상 / 3 불명을 돌려준다 |
| R1 | U0 | 두 보존 태그를 `tagkeep`으로 판정한다. 다른 대상이거나 불명이면 멈춘다. 모든 확인을 통과한 뒤 앞 시도의 이번 빌드 기록(image-new*, image-fe-new*)을 지운다 |
| R1 | U1 | 두 보존 태그를 **모두 판정하고 `tag-keep-u1.txt`에 기록한 뒤에만** 쓴다. 명시적 부재일 때만 만들고, 있으면서 같으면 다시 쓰지 않는다. 다른 대상이거나 불명이면 HEAD·latest·태그를 바꾸기 전에 멈춘다 |
| R2 | U변수 | `namecls`는 이름을 원판 / 새판 / 제3 / 불명으로 나눈다. 새판은 **U1이 빌드 직후 확인해 기록한 대응(image-new-chain·image-fe-new-chain)과 같을 때만** 인정한다. 기록 없이 바뀐 이름은 제3이다 |
| R2 | U1되돌림 | backend latest·frontend 이름을 판정한 뒤에만 바꾼다. 제3·불명이면 멈추고, 새판인데 단계 기록이 빌드 전이어도 멈춘다. 빌드시작 뒤 기록 없는 변경은 출처 미확인으로 멈춘다 |
| R2 | U되돌림 | 실행 판정(기존) 뒤 두 이름을 판정한다. backend latest는 **새판일 때만** `docker tag "$IMGOLD"`를 하고, 원판이면 그대로 두고 재생성한다. 제3·불명이면 관문·변경 전에 멈춘다. 상태 기반 복구 관문과 정상 복구 순서는 유지한다 |
| R3 | U3·U4·U뒤관측 | 단계 확인 뒤 **기동 구간 로그를 먼저 보존(`app-*-p0.log`)하고 holdobs로 P0를 검사**한 다음 ps·이미지 대응·뉴스 SQL을 한다. 로그 조회 실패·빈 로그·holdobs 종료가 0·1·3이 아니면 "P0 검사 미완료"로 멈춘다. 자동 되돌림은 없다 |
| 추가(postlog) | U뒤관측 | 두 번째 로그를 읽은 직후 검사한다. P0(종료 1)이면 즉시 멈추고, 0·3이 아니면 판정 실패로 멈춘다(종전에는 출력만 하고 rc0). 두 번째 로그 조회 실패·빈 로그도 멈춘다 |
| 추가(같은 종류) | U3·U4 | 두 번째 로그 holdobs 종료가 0·1·3 밖이면 판정 실패로 멈춘다. 종전에는 1만 멈춰서 종료 2 등이 통과했다 |

U2·UF2는 판 표지만 바뀌었다. JARM 계산기·RARX·JARN·관문/조회문 해시·imgchain.py·블록 순서는 그대로다. U0의 사전 관측(뉴스 SQL이 P0 기준선보다 앞)은 배포 전이라 어느 쪽이든 변경 없이 멈추므로 바꾸지 않았다.

**r1 뒤 보완 이력:** 추가 검토 메모가 r1 실행 중에 도착했다. r1 결과를 먼저 보존하고([r1/](standin/r1/), 당시 U3·U4·U뒤관측 원문 [d4/blocks-d4-r1/](d4/blocks-d4-r1/), 해시 [blocks-d4-r1.sha256](d4/blocks-d4-r1.sha256)) 생성기에 보완을 더했다. 바뀐 블록은 U3·U4·U뒤관측 셋뿐이다([blocks-d4.r1-to-final.diff](d4/blocks-d4.r1-to-final.diff)).

## 3. 대역 — §4-2

장치는 V171D4 대역 사본 `/d/tmp/v172d4s`다. 바뀐 파일은 [standin/harness/](standin/harness/)에 있고, 차분은 [harness-v171-to-v172.diff](standin/harness-v171-to-v172.diff)다. 정상 jar는 서버 0e1d2a02 빌드 실제 jar이고, 이미지 blob·컨테이너 값은 V171D4 대역과 같다.

장치에 더한 것:
- 태그 조회 API 흉내(§1 형식)와 고장 주입 `TAGQ_FAULT`(rc7·http500·badbody·nf404)
- 뉴스 SQL 실패 `NEWS_FAIL`, 로그 조회 실패·빈 로그 `LOGS_FAIL`·`LOGS_EMPTY`
- 옛 판 재현용 `INSPECT_FAIL`
- r2용 `logs-next`(첫 로그 조회 뒤 로그 교체), `logs-fail-next`, `PY_HOLD_FAIL`(지정 로그를 받은 holdobs만 종료 2)
- 함수 단위 시험 블록 [extra/UT.sh](standin/harness/extra/UT.sh): U변수 원문 정의에 rchain·tagq만 대역 함수로 바꿔 끼운다

r2용 장치 추가분은 해당 주입을 쓰지 않으면 동작이 같다. r1은 추가 전 가짜 명령으로 돌았다.

**r1(13:07~13:28, 3묶음 병렬, 당시 블록 = blocks-d4-r1)**: [r1/scenarios-v172.sh](standin/r1/scenarios-v172.sh), 결과는 a 56/4·b 108/0·c 83/0으로 **합계 성공 247 · 실패 4**다. 실패 4건은 모두 시나리오 쪽 실수였다.
- k01·k05·k08: 판정 줄을 4필드까지만 잘라 frontend 값이 빠졌다. 블록 기록은 기대와 같다.
- x08: `D2_BLOCKS`만 주어 U변수도 옛 판이 쓰였다(옛 판끼리 실행). r2에서 새 U변수를 명시해 재확인했다.

| 묶음(r1) | 내용 |
|---|---|
| UT | same3 0/1/2, tagkeep 0/1/2/3, namecls 원판/새판/제3/기록 없음/불명 **15/15** |
| x01 | 운영과 같은 시작 상태(backend 보존 태그 있음·같음, frontend 404) → U0~U뒤관측 정상. backend 보존 태그는 다시 쓰지 않고(tag 0회) frontend는 1회 만든다. JARM b6587953 |
| k01~k10 | 두 태그 404 → 둘 다 생성. U0 rc7/500 → U0 멈춤. U1 rc7·500·`*` 전부 rc7·200 형식 이상·다른 404 본문 → **두 태그 판정 뒤, 변경 명령(tag·build·up·checkout) 0**. frontend 보존 태그가 제3 대상 → 멈춤. 다른 대상 + 조회 rc7 → 멈추고 덮어쓰지 않음 |
| x02·x03·x04·f02·r01·r02·q08 | 정상 U되돌림(UF2 뒤·교체 전)·U1되돌림(빌드끝·JARM 멈춤·frontend 빌드 실패). backend latest가 이미 원판이면 latest에 tag 0회로 재생성·복귀 |
| f03·q01 | 빌드시작 뒤 이름이 기록 없이 바뀜(빌드 중 변경·제3 frontend) → U1되돌림이 바꾸기 전에 멈춤(변경 0, HEAD NEW 그대로) |
| q02·q04 | 제3 backend latest → U1되돌림·U되돌림 멈춤(변경 0, 관문 미실행) |
| q03·q05·q06·q07 | 이름별 save·API 조회 실패(backend 이번 빌드판·frontend 이번 빌드판·제3 latest) → 조회 불명으로 멈춤(변경 0) |
| p01~p10 | P0+이미지 조회 실패, P0+뉴스 SQL 실패(U3·U4·U뒤관측·U3 rollback) → **P0로 멈춤**(뉴스 기록 없음). 뉴스 SQL만 실패 → P0 1차 검사 뒤 뉴스 멈춤. 로그 조회 실패·빈 로그(U3·U4) → P0 검사 미완료 |
| i06·i06b·t01·i07·x07·x09·x10 | 해석 불가 다른 대상 → 조회 불명, 완전한 제3 대상 → 다른 이미지. latest 변경 → U2 멈춤. API 실패 → U1 변경 0. 판 혼합·기록 폴더 거부 |
| o02~o05 | **옛 V171D4 원문으로 총괄 지적을 재현**. 조회 실패 시 다른 대상 보존 태그를 덮어써 U1 rc0(R1). 제3 latest를 덮어써 U되돌림 rc0(R2b). 빌드시작 + 제3 frontend를 덮어써 U1되돌림 rc0(R2a). P0+뉴스 실패에서 "뉴스 설정 조회 실패"로 끝나 로그·holdobs 미도달(R3) |

**r2(13:29~13:46, 2묶음 병렬, 최종 블록)**: [scenarios-v172-r2.sh](standin/scenarios-v172-r2.sh), 결과는 [d1](standin/scen-r2-d1.txt) 23/0·[d2](standin/scen-r2-d2.txt) 77/0으로 **합계 성공 100 · 실패 0**이다. 묶음마다 금지 명령 합 0.

| 묶음(r2) | 내용 |
|---|---|
| x01 | 최종 블록으로 정상 전 구간(U3·U4 P0 1차 기록, U뒤관측 두 검사 기록) |
| k01·k05·k08·x08 | r1 시나리오 실수 재확인(판정 줄 5필드, 새 U변수 + 옛 블록 거부) |
| **p11** | U뒤관측: 첫 로그 정상 → 두 번째 로그 P0 → **"(두 번째 로그)" P0로 rc1**. 뒤 관측(newsobs) 미진행 |
| **p12·p16** | U뒤관측: 두 번째 holdobs 종료 2 → 판정 실패로 멈춤. 두 번째 로그 조회 실패 → 멈춤 |
| **p13·p14·p15** | U3·U4 두 번째 holdobs 종료 2 → 판정 실패로 멈춤(ok 파일 없음). U3 첫 로그 정상 → 두 번째 로그 P0 → 멈춤 |
| p03·p06~p10 | R3 사례를 바뀐 블록으로 다시 |

실행 원문은 r1·r2 사례 폴더 66개, 11,286파일이며 해시는 [runs-r1r2.sha256](standin/runs-r1r2.sha256)에 있다. 압축본 [standin-runs-r1r2.tar.gz](standin/standin-runs-r1r2.tar.gz)(9.1MB)는 jar 사본 94개, 사례별 고정 자료 사본(`fix/`), 감시 스크립트 사본(`scripts/prospective/`)을 뺀 9,212파일이다.

## 4. 실행 틀 — §4-3

[runner/run.sh](runner/run.sh)·[runner/extract.py](runner/extract.py)는 V171D4 실행 틀에서 판·경로(`/d/tmp/v172run`, `docs/evidence/v172d4-20261116/d4/`)와 **블록 12개 고정 해시**만 바꿨다. F·G 해시는 같다. 덮어쓰기 거절·ssh 종료값 즉시 기록도 그대로다.

가짜 ssh 시험 [runner-selftest.txt](runner/runner-selftest.txt)는 최종 해시로 **12/12**다. r1 해시로 돌린 결과는 [runner-selftest-r1.txt](runner/runner-selftest-r1.txt)에 보존했다. 시험 스크립트는 공백 없는 경로의 바이트 동일 사본(`/d/tmp/v172/runner/run.sh`)과 고정 F·G(`/d/tmp/v171/extract-check-1644851b`)로 돌렸다.

## 5. 남은 한계

- 이번 빌드판 판정은 U1의 대응 기록에 의존한다. 빌드는 성공했지만 직후 대응 조회(rchain)가 실패해 기록이 없으면, 바뀐 이름은 제3으로 판정되어 되돌림이 멈춘다. 보수적으로 멈추는 쪽이며, 수동 판단이 필요하다.
- `tagq`가 확인하고 `docker tag`를 하기까지의 사이에 다른 작업이 태그를 바꾸는 경우는 막지 못한다. 쓴 직후 `same3`으로 다시 대조한다.
- holdobs는 파이썬 예외에서도 종료 1이 되어 P0로 멈춘다(보수적).
- 서버의 `~/maint-v168`·`~/maint-v171`(없음)·backend:b5cd1d0 태그(→52e12428)는 그대로다. U0는 이 태그를 "있음·같음"으로 판정하고, U1은 다시 쓰지 않는다.

## 6. 판정 요청

V172D4 블록·대역·실행 틀을 새 고정 출처로 수용할지 판단을 요청한다. 수용되면 가장 이른 허용 창에 다음 순서로 진행한다: 직전 보고·전송·장부·실제 상태 → 새 U0 → U1(30분 안) → U2 직전 운영자 "재기동 진행" 확인 1회 → U2 → U3 → UF2 → U4 → 뒤 관측.
