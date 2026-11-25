# 1회 제한 B 최종 확인 — 전체 회귀 1회 + LF jar 1회 (총괄 v1.246 §2, 제출)

2026-11-25 KST, 로컬 작업. 운영·거래소·SSH 호출 0. PG·r3·실제 LIVE 경로·합성 29건은 다시 돌리지 않았다. 회귀는 10:59에 시작된 1회를 이어받았고(중지·재실행 없음), jar는 회귀 rc0·실패0 확인 뒤 1회만 빌드했다.

| 항목 | 값 |
|---|---|
| 시험 SHA | **1b719645**(제품 = c43e1f1a, 차이는 시험 1파일 `LiveOneEntrySmokePathPgTest.java`). 실행 전·후 HEAD 동일, 작업 트리 변경 0 |
| 실행기 | [full.sh](full.sh)(회귀) · [jar.sh](jar.sh)(jar). 실행 전에 작성해 둔 원문 그대로 실행 |

## 1. 전체 회귀 1회 [full-1b719645/](full-1b719645/)

`./gradlew cleanTest test --continue`, 10:59:29 → 11:22:05, **rc=0**([time.txt](full-1b719645/time.txt)). PG 변수 전부 비움, 진단 감시 끔.

| 모듈 | 시험 | 실패 | 오류 | 건너뜀 | 통과 |
|---|---|---|---|---|---|
| core-engine | 298 | 0 | 0 | 7 | 291 |
| exchange-adapter | 11 | 0 | 0 | 0 | 11 |
| strategy-lib | 113 | 0 | 0 | 0 | 113 |
| web-api | 1459 | 0 | 0 | 98 | 1361 |

- **실패 0·오류 0.** 건너뜀 105는 성공이 아니다. d81c3f2f 회귀의 94에 이번 새 PG 클래스 11건(maxEntries 9·실제 LIVE 경로 2)을 더한 값이다. 모두 PG 변수가 없어 꺼진 PG 클래스와 수동 실행기다([summary.txt](full-1b719645/summary.txt)).
- 필수 PG 근거는 따로 수용된 결과에 연결한다: 실제 LIVE 경로 2/2·합성 29/29는 [r2](../one-entry-pg2-20261125/R2.md), 게이트 v4는 [r3](../one-entry-pg2-20261125/R3.md).
- 망 차단(NET-GUARD): 검사 클래스 195, **외부 연결 시도 차단 0**, 허용한 것은 DNS 확인 3·기계 안 자기 연결 75.
- XML 보존 [xml-copy-check.txt](full-1b719645/xml-copy-check.txt): 47/2/17/226, 해시 불일치 0. `gradle.log`는 `*.log` 무시 규칙에 걸려 명시 경로로 강제 추가했다.

## 2. LF jar 1회 [jar-1b719645/](jar-1b719645/)

작업 사본 `/d/tmp/final-lf-1b719645`(HEAD 1b719645, 변경 0). 시스템 git 설정은 `core.autocrlf=true`지만 체크아웃된 리소스 97개는 **CRLF 0·블롭 불일치 0**([head.txt](jar-1b719645/head.txt)). `./gradlew :web-api:clean :web-api:bootJar -x test --offline` **rc=0**(11:22:27 → 11:22:36, [bootjar.log](jar-1b719645/bootjar.log) 강제 추가).

| 항목 | 값 |
|---|---|
| jar sha256 | **c631cf4dc5b1b3e733bb775e9964134f871708d96ea2c26dd681f9bfc8ddd952**([jar-sha.txt](jar-1b719645/jar-sha.txt)) |
| JARM | **e8f425079d0fb77a9f224ba027ad16a18415e74238dac41edcbe2f9a2018a546**, JARN **900**([jarm.txt](jar-1b719645/jarm.txt), 계산 코드는 d81 때와 같은 `jarm_u1.py`) |
| 저장 d81c3f2f jar | eae7e1d2…(재빌드 없음), 목록 sha256 33a06f0e… = 기대값 |
| d81c3f2f 대비 | 898 → 900 · 같음 891 · **변경 7 · 추가 2 · 삭제 0**([jar-diff](jar-1b719645/jar-diff-vs-d81c3f2f.txt)) |
| 제품 원천 차이 | 4파일, +110줄 삭제 0([product-paths](jar-1b719645/product-paths-d81c3f2f-1b719645.txt)): `OrderRepository`·`CapitalReservationService`·`LiveTradingService`·새 `LiveEntryLimit` |

변경·추가 9항목은 모두 `BOOT-INF/classes` 클래스이고, 위 4파일과 일대일로 대응한다(OrderRepository 1, CapitalReservationService 2, LiveTradingService 4(내부 클래스 포함), LiveEntryLimit 추가 2). 라이브러리·리소스·설정 차이는 0이다. 예상 밖 차이 없음. jar 원본은 `/d/tmp/oe-final/jar-1b719645/web-api-0.1.0.jar`에만 있다(저장소에 넣지 않음).

## 3. 함께 보존한 출처

- r3: 원래 요약 `ok 2 / 10`과 재대조 `ok 10 / 10`을 둘 다 그대로 둔다. 체크섬 [SHA256SUMS-r3](../one-entry-pg2-20261125/SHA256SUMS-r3)(이번에 만듦, 자신 제외).
- 게이트 v4: [README](../one-entry-gate-v4-20261125/README.md)·`SHA256SUMS`(검증 통과). 파일 2개는 a5f08222 블롭과 같다.
- 시험 초안: [live-smoke-draft](../live-smoke-draft-20261125/README.md)의 v3→v4 참조 변경(README·launch.sh 각 1줄). 변경 전 체크섬 `SHA256SUMS-v3ref`, 변경 후 `SHA256SUMS`(검증 통과). `bash -n`만 했고 실행하지 않았다.

체크섬: [SHA256SUMS](SHA256SUMS)(이 폴더 전체, 자신 제외). 커밋 블롭 대조 결과는 커밋 뒤 관리 문서와 제출 보고에 적는다.

## 4. 판단에 넘길 것

B 포함판 배포 여부와 실행 출처(1b719645 jar c631cf4d)는 총괄 판단이다. U2 재기동은 사용자 확인이 필요하다. 운영 승인 범위는 바뀌지 않았다.
