# 4a4a04a3 이후 변경 경계 — 커밋 전에 기록 (398차 지시 §3, 2026-11-13 09:1x KST)

4a4a04a3 전체 회귀(1회, 08:21:21–08:41:04, rc 1)와 로컬 프런트 운영 빌드에서 두 가지가 나왔다. 둘 다 원인을 확인한 뒤 최소 보완한다. 제품 Java·마이그레이션·백엔드 빌드 입력은 바꾸지 않는다.

| # | 발견 | 원인 확인 | 보완(최소) | 바뀌는 경로 |
|---|---|---|---|---|
| 1 | N16InvalidPriceReproTest ② LIVE·DYNAMIC "null 다음 280 → 보호 매도 1회" 기대 1·실제 0 (전체 회귀에서만, 단독 실행 13/13 통과) | 시험 보조 `drain` 의 판정(대기열 비어 있음·활성 0·작업 수=완료 수)이 막 넘긴 작업이 시작되기 전에 참이 될 수 있다. ⓐ 같은 판정식의 단독 진단: 작업자 있는 실행기 20,000회 중 8회 ⓑ 4a4a04a3 진단 사본 전체 회귀: ② DYNAMIC 에서 drain 반환 직후 매도 0·`active=1 task=59 done=58`·주문 INTENT_READY → 21ms 뒤 매도 1·SUBMITTED. 시험 스레드 트랜잭션 없음(sync=false·tx=false). 제품은 1회 제출 | 매도 1회를 단언하는 두 곳(①·②)에서 거래소 매도 호출을 최대 15초 기다린 뒤 실행기를 다시 비우고 **정확히 1회**를 단언. 기대값·0회 단언·제품 코드 그대로 | `web-api/src/test/.../service/N16InvalidPriceReproTest.java` |
| 2 | 프런트 운영 Docker 빌드(Dockerfile·.dockerignore 그대로, node:20-alpine) 실패: `playwright.paper-render.config.ts` 의 `./e2e/auth-fixtures` 모듈 없음(타입 검사). 로컬 `next build` 는 통과(e2e 폴더가 있어서) | `.dockerignore` 가 `e2e`·`playwright.config.ts` 는 빼지만 v1.153 G(5f9ef2ad)가 추가한 `playwright.paper-render.config.ts` 는 빼지 않는다. 대조: b5cd1d0 프런트 같은 방식 빌드 성공 | `.dockerignore` 에 `playwright.paper-render.config.ts` 한 줄(기존 `playwright.config.ts` 와 같은 취급). 작업 사본에서 같은 Docker 빌드 성공 확인 | `crypto-trader-frontend/.dockerignore` |

## 다시 검증할 항목

- 새 SHA 전체 회귀 1회(새 수집 폴더, 원문·체크섬 선고정). 4a4a04a3 결과와 합쳐 전체 통과로 쓰지 않는다.
- 시험 외·프런트 외 블롭 동일성(4a4a04a3 ↔ 새 SHA) → 같으면 실제 jar 0834382a… 를 재빌드 없이 연결. 같지 않으면 재빌드.
- 같은 SHA 의 PG 결과는 시험 외 블롭이 같고 바뀐 시험이 PG 시험이 아니므로 연결(4a4a04a3 에서 실행한 필수 PG·대조 도구·AbChainV4).
- 프런트: 새 SHA 의 Docker 빌드 1회 + 기존 렌더링 7/7 연결(프런트 런타임 경로 블롭 동일 확인).
- D4 의 NEW·DIFFN(경로 목록이 바뀜) 재생성.

## 바꾸지 않는 것

제품 Java·V96·build.gradle·Dockerfile·tsconfig·프런트 런타임 경로, 다른 시험의 같은 `drain` 패턴(현재 통과 — 같은 위험이 있는 파일 목록만 README 에 적는다), 4a4a04a3 회귀 원문·체크섬.
