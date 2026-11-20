# 12월 준비 28 — 읽기 게이트 v3 출처 표 (총괄 v1.204 §3-3·v1.205 §4-3·v1.206 §4-2) — 운영 실행 금지

2026-11-20 KST, 개발 세션 제출(총괄 판정 전). 읽기 게이트 v3의 실행 출처를 이 표 한 곳에 모은다. 아래 해시는 **검토된 작업 사본의 해시**다. 운영 실행 고정 출처나 실행 승인이 아니다. 이번 정리에서 PG·DB·운영 접속은 하지 않았다. 총괄 v1.206 §4-2에 따라 R3 PG 후속은 [v3 run1](../dec-read-gate-v3-20261120/README.md)으로 마쳤다.

## 1. 출처 표

| 항목 | 고정 내용 | 원문 근거 |
|---|---|---|
| SQL | `read.sql` v3 sha256 **9818db5452f66f058c97322ac9aaf73097036997777d10a0af9f6dde05bdae03**. META 첫 열 판 `v3` | [read.sql](../dec-read-gate-v3-20261120/read.sql), [SHA256SUMS](../dec-read-gate-v3-20261120/SHA256SUMS) |
| 파서 | `parse.py` v3 sha256 **0f6fe827c9da513445ce9c52300b8688f8f4f162a99026b90d05943c10341908** | [parse.py](../dec-read-gate-v3-20261120/parse.py) |
| 등록표 해시 | `-v reg_sha256=<등록표 파일 sha256>`. SQL은 META 8열에 그대로 되돌려 쓴다. 파서가 등록표 파일을 다시 해시해 대조한다. 예: A_V3의 `registry.json` sha256 aa42b1af…151c0 = META 8열 | [A_V3/vars.txt](../dec-read-gate-v3-20261120/run1/A_V3/vars.txt), [registry.json](../dec-read-gate-v3-20261120/run1/A_V3/registry.json), [stdout.tsv](../dec-read-gate-v3-20261120/run1/A_V3/stdout.tsv) 1행 |
| kind/ID 배열 | 해시 고정 등록표(`registry-3`)의 `sessions[]`를 kind별로 나눈다. `live_ids`=LIVE id, `dyn_ids`=DYNAMIC id, `{…}` 배열이다. kind+id를 같이 쓰며 같은 숫자도 kind가 다르면 다른 세션이다. 1단계 `ID_R`에서 REG_OK·REG_MISSING·REG_MODE를 확인한다. DYNAMIC은 `trading_mode='REAL'`이어야 한다. 등록표가 비면 STOP이다 | read.sql 머리말 R2, `ID_R` 절 |
| 스키마 | 대상 d81c3f2f(이행 V1~V96 중 95개). 운영 0e1d2a02와 관련 엔티티 5개의 블롭이 같다(총괄 v1.204 대조). META 6열에 flyway 최대 판을 남긴다(시험 96). 시험용 최소 flyway 표는 제품 validate 근거가 아니다 | [setup.txt](../dec-read-gate-v3-20261120/run1/setup.txt), [v1.204 checks](../review-43d729db/checks.json) |
| 읽기 범위 | 단일 `REPEATABLE READ READ ONLY` 트랜잭션이다. 1단계는 금액 없이 등록 대조·소유 분류·등록 밖 목록만 읽는다. 등록 밖 주문·포지션은 종결·CLOSED여도 STOP이다. 게이트 GO일 때만 2단계에서 등록 세션의 주문·포지션 원천 전체를 허용 열로 읽는다. 시간 필터는 없다. PAPER 522~587과 DYNAMIC PAPER 금액은 읽지 않는다 | read.sql v2→v3 머리말, [v1.206](../review-read-gate-v3-20261120/README.md) §1 |
| 시각 | META 3열 `now()`는 트랜잭션 시작 시각이고 4열 `clock_timestamp()`는 db 축 벽시계다. 둘 다 월말 T 시점을 보증하지 않는다. 스냅숏을 감싸는 수집 구간과 DB clock 표본은 **아직 정하지 않았다(미확정)**. 이 읽기의 성공을 T 시점 확정으로 바꾸지 않는다 | read.sql META 주석, v1.204 §3-3 |
| 종료값 | psql은 `-X -q -v ON_ERROR_STOP=1`로 부른다. rc≠0이면 파서 **20**이다. 파서 **0**은 2단계까지 읽음, **10**은 게이트 STOP(금액 열람 없음), **30**은 형식 결함이나 SQL 게이트와 파서 재계산의 불일치다. 파서 0은 계좌 일치나 월말 값 확정의 판정이 아니다 | parse.py 머리말 |
| 호출별 보존 | 호출마다 새 폴더를 쓰고, 폴더가 이미 있으면 멈춘다. 남기는 파일: `registry.json`·`vars.txt`·`stdout.tsv`·`stderr.txt`·`psql_rc.txt`·`parsed.json`·`parse_rc.txt`·`parse_stderr.txt`. 시험용에는 `fixture.sql`도 남긴다. 결과 원문은 파일에만 두고 대화에 전체 행을 출력하지 않는다 | [tests_pg.py](../dec-read-gate-v3-20261120/tests_pg.py), run1 사례 폴더 |

## 2. PG 결과와 보충 대조 (v1.206 §2 그대로)

보존된 [tests_pg.out.txt](../dec-read-gate-v3-20261120/run1/tests_pg.out.txt)는 **4건 중 PASS 3·FAIL 1**이다. A_V3의 FAIL은 고치지 않았고 4/4로 합산하지 않는다. A_V3가 정상 원천과 맞는다는 것은 따로 연결한 보충 근거로만 본다. 그 근거는 [a-v3-compare.json](../dec-read-gate-v3-20261120/run1/a-v3-compare.json)의 보존 원문 비교다: 11행 중 10행이 같고, 나머지 1행은 `D/1/11/FEE_DECREASE`의 `detected_at`만 다르다. 원인은 V82 `DEFAULT CURRENT_TIMESTAMP`와 시각 미지정 픽스처다. 수집 시각 열을 버리는 일반 규칙으로 넓히지 않는다. PG는 다시 돌리지 않았다.

## 3. SQL 주석 정정 메모 (검토된 바이트는 유지)

`read.sql` 82행 포지션 머리 주석 "등록 밖은 CLOSED 가 아니면 STOP"은 v2 설명이 남은 것이다. 실제 v3 조건은 **등록 밖 포지션은 CLOSED여도 STOP**이며 머리말 v2→v3와 v1.206 §1이 기준이다. 검토 해시 9818db54…를 지키려고 SQL 바이트는 고치지 않았다. 다음에 SQL을 새 판으로 고칠 때 이 주석과 A_V3 픽스처의 기본 시각 열을 함께 바로잡는다.

## 4. 보존 처리

`dec-read-gate-v3-20261120`에 `.gitattributes`(`* -text`)를 더했다. 기존 SHA256SUMS 44줄은 덮어쓰지 않았다. 속성 파일은 이 폴더의 [SHA256SUMS](SHA256SUMS)에 따로 적었다. 커밋 전 작업 사본 44개는 `sha256sum -c`로 모두 일치했다. 스테이징한(커밋될) 블롭을 8개 폴더의 모든 목록과 대조한 결과는 [blob-check.txt](blob-check.txt)에 있다. 검사 코드는 [blobcheck.py](blobcheck.py)다. 불일치는 0이고 권한 설정 파일은 제외했다. 두 파일은 자기 참조를 피하려고 이 폴더의 SHA256SUMS에 넣지 않았다. 커밋 뒤에는 같은 검사를 HEAD 기준으로 한 번 더 했다.
