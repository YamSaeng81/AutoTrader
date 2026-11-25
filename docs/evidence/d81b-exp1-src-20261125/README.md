# D81B-EXP1 고정 실행 출처 — B 포함판 1b719645 단일 배포(총괄 v1.247 §2, 제출)

2026-11-25 KST 로컬 작업. **출처 커밋 `452ac38430dff30300048026e9478e555e015b8c`**(블록·추출기·실행 틀·생성기). 이 폴더의 README·로그·SHA256SUMS는 뒤 커밋에 있다. 실행 승인이 아니다. 서버·ssh·운영 읽기 0회, 빌드·회귀·PG 0회.

## 1. 고정값

| 항목 | 값 |
|---|---|
| 원판 출처 | 794480ea(D81A2-EXP1). 그 폴더·체크섬은 그대로 보존, 이 출처에 섞지 않음 |
| OLD / NEW | 0e1d2a02…(불변) / **1b719645ef907cb1aa45ffa01438fcfda7566758**(origin/candidate/live-one-entry-d81c3f2f에 있음) |
| DIFFN | `git diff --name-only OLD NEW` **48경로**(제품 Java 16·시험 32, 리소스·마이그레이션·프런트·build.gradle 0) |
| RARX | **42줄 = 변경 37·추가 5·삭제 0** — [jar-diff 원문](jarcmp/jar-diff-0e1d2a02-vs-1b719645.txt) |
| JARN / JARM | **`(끝) 895 900`** / **e8f425079d0fb77a9f224ba027ad16a18415e74238dac41edcbe2f9a2018a546** |
| 서버 기록 / 로컬 실행 / 실행 근거 | `~/maint-d81b-20261127-exp1` / `/d/tmp/d81brun-20261127-exp1` / `docs/evidence/d81b-expiry-run-20261127-1735/`(초안 값, 서버 존재 조회 안 함) |
| 판 표지 | run.sh `판=D81BX1`. `UB=D81D4`는 전 블록 공통 검사라 바꾸지 않음 |

**블록 해시(blocks-d81.sha256)** — 바뀐 3개: U변수 `12776871f93dd37c…`, U0 `8ed75a9a4a23ef01…`, U1 `beec0138758be9d7…`. 그대로인 8개: U1되돌림 a272ab58 · U2 caac178a · U3 cc414408 · U4 979c5d85 · U되돌림 65df00d5 · U뒤관측 c748a791 · F 26d39269 · G 4d7ff02a(794480ea와 바이트 같음). 추출기 `runner/extract.py`, 실행 틀 `runner/run.sh`의 해시는 [SHA256SUMS](SHA256SUMS)에 있다.

## 2. jar 차이 목록 — 직접 비교(합산 아님)

- 비교기: 794480ea U1.sh의 heredoc 원문을 기계 추출한 [jarcmp_v2_u1.py](jarcmp/jarcmp_v2_u1.py) = 기존 대조에 쓴 jarcmp_v2.py와 같은 sha256 56f5d3ba…. JARM 계산기 [jarm_u1.py](jarcmp/jarm_u1.py)도 같은 U1 원문에서 꺼냄.
- 옛 jar: 운영 0e1d2a02 서버 빌드 보존본 `/d/tmp/v171/jars/srv-e25c2a85-app-new.jar` sha256 5c53c9e3…(d81d4-20261120/jarcmp-real 기록과 같음). U1 계산 JARM **b6587953…·895줄** = 운영 기록값.
- 새 jar: `/d/tmp/final-lf-1b719645/…/web-api-0.1.0.jar` sha256 **c631cf4d…**(v1.246 저장본, 재빌드 없음). JARM e8f42507·900줄, 목록 = 저장 `jar-manifest-1b719645.lf.txt`와 바이트 같음.
- 결과 rc0: 42줄 + `(끝) 895 900`. 대조만: d81 목록 38줄은 전부 남고 종류도 같음. 새로 생긴 4줄은 변경 `CapitalReservationService$Decision`·`CapitalReservationService`, 추가 `LiveEntryLimit$State`·`LiveEntryLimit`.
- 인코딩: `python -I`는 PYTHONIOENCODING을 무시해서 첫 출력이 CP949였다. `-X utf8`로 다시 만들었다. 원문은 UTF-8·LF·CR 0이다.

## 3. 허용 변경표

| 파일 | 바뀐 것(정확히 1회 치환, [make_d81b.py](make_d81b.py)) | diff |
|---|---|---|
| U변수 | 1행 기록 경로와 v1.247 설명 꼬리, 2행 NEW, DIFFN 구간, RARX 구간, JARN 줄, JARM 줄, 감시 스크립트 설명 2곳의 후보명(블롭 값 그대로) | [diff](diff/U변수-vs-794480ea.diff) |
| U0 | 4행 핀 4개(경로·NEW·JARN·JARM), `후보 … 없음` 문구, diff 경로 수 문구 | [diff](diff/U0-vs-794480ea.diff) |
| U1 | 오류 문구 3개(42줄 변경37·추가5 / 895 → 900 / 1b719645 JARM e8f42507). 검사식 불변 | [diff](diff/U1-vs-794480ea.diff) |
| extract.py | 1행 설명, D 경로, 주석 경로, U변수·U0·U1 고정 해시 | [diff](diff/extract.py-vs-794480ea.diff) |
| run.sh | 2행 설명, 기본 실행 폴더, U변수·U0·U1 고정 해시, 판 표지 | [diff](diff/run.sh-vs-794480ea.diff) |

**DIFFN을 바꾼 이유:** U0 16행 `git diff --name-only $OLD $NEW = $DIFFN`이 NEW에서 파생되는 고정 검사다. 옛 43경로로 두면 U0가 반드시 멈춘다. 같은 고정 Git에서 기계적으로 만들었다.
**자체 점검:** 같은 서식 함수에 옛 d81 입력(git diff OLD d81c3f2f, 저장 d81 차이 목록)을 넣으면 794480ea 원문 DIFFN·RARX와 바이트가 같다. 같지 않으면 생성기가 종료 1로 멈춘다.
**그대로 둔 것:** OLD·이미지 대응값·보존 태그 0e1d2a0·V96 체크섬·배포 전 SQL v3/pf3ok·R1=0 기준·복구 관문 4판·F·G·frontend·DDL·감시 설치본·cron은 바꾸지 않았다. 유지보수 예외나 KNOWN 통과는 넣지 않았다. 시험 세션 종료 게이트 v4는 이 출처와 무관하다.
**NEW 의존 U0 조건 사전 확인(로컬 Git):** OLD는 NEW의 조상이다. start_66.py 블롭 6305e512와 sha 68d441ce, 시험 파일 블롭 15166c0e는 운영판과 같다. V95 이후 마이그레이션은 V96 하나뿐이고 블롭 5b92eef0다.

## 4. 최소 파일 검증(1묶음, [verify.sh](verify/verify.sh))

| 검증 | 결과 | 로그 |
|---|---|---|
| `bash -n` 9블록·run.sh, Python 구문 extract.py·make_d81b.py | 전부 통과 | — |
| 새 변수 + U0 3~7행(핀·형식·경로 거절·mkdir) | rc0, 새 기록 경로만 생성 | [pins.log](verify/pins.log) |
| 옛 NEW / JARM / JARN / 기록 경로 하나씩 혼입, 794480ea U변수 통째 | 5/5 rc1, mkdir 없음 | 같은 곳 |
| 보존 jar 두 개 → 새 U1의 비교기·RARX·JARN·JARM 검사 줄 | rc0. jar-diff·manifest가 저장본과 바이트 같음 | [jar-r2.log](verify/jar-r2.log) |
| 옛 d81 jar(eae7e1d2)를 새 jar 자리에 | rc1 `기대 42줄 … 아님` | 같은 곳 |
| 452ac384에서 extract.py로 11블록 추출 | 11/11 같음 | [hash.log](verify/hash.log), [blocks-extracted.json](verify/blocks-extracted.json) |
| run.sh 해시 검사 heredoc(ssh 전 단계만 잘라 실행): 원본 / U1 본문 변조 / U변수 변조 + blocks.json 덧쓰기 | 통과 / 거절 / 거절 | 같은 곳 |
| 변조 U0를 넣은 임시 커밋 2bcb3314(브랜치·push 없음) → extract.py | 종료 1, U0 `다름 ✗` | 같은 곳 |

**검증 r1 실패 기록:** 첫 jar 단계([jar-r1-scriptbug.log](verify/jar-r1-scriptbug.log))는 검증 스크립트 결함으로 rc1이었다. `M`을 U변수 eval보다 먼저 넣어서 기본 경로로 덮였다. 그 경로가 없어 아무 파일도 쓰지 않았고, 실제 홈에 `maint-d81b-20261127-exp1`이 없음을 확인했다. 순서만 고쳐 r2를 1회 돌렸다. 제품과 출처 파일은 바꾸지 않았다.

이 검증은 핀 검사 줄과 파일 해시만 확인한다. U0 전체 실행, 서버 Git·Docker·DB 동작, 서버 빌드 jar의 동일성은 확인하지 않았다. 서버 U1은 1회 재빌드한 뒤 JARM·JARN·RARX로 내용을 확인한다. 전체 jar SHA 동일성은 기동 조건으로 추가하지 않았다.

## 5. 다음 판단(총괄)

- 452ac384의 블록·추출기·실행 틀 해시를 수용하면 단일 U0 → U1 실행 출처로 확정한다. 옛 794480ea 단독 실행은 그때까지 대기한다.
- 11-27 17:35~19:30 창, U0 실제 R1=0, U1 → 사용자 '재기동 진행' 확인 → U2 순서는 그대로다. 실주문은 배포·인수 뒤 진행한다.
