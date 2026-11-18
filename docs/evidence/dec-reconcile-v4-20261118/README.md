# 12월 준비 5 — P7 v4 로컬 대조기: 총괄 v1.188 §2 추가 경계 U1~U3 보완 + 거래소 완료 주문 수집안

2026-11-18 KST. 개발 세션 제출(총괄 판정 전). 기점 커밋 294645f1(437차). [v3 원문](../dec-reconcile-v3-20261118/README.md)과 체크섬은 바꾸지 않았고, v3 세 파일을 이 폴더 `p7v4/`에 복사한 뒤 보완했다. 운영 접속·DB·Docker·Java 시험·빌드·제품 수정·실제 거래소 API 호출은 하지 않았다. 입력은 모두 저장된 가상 입력이며 운영 관측이 아니다.

## 1. 결과 요약

| 항목 | 결과 |
|---|---|
| 시작 사본 | v3 `reconcile.py` sha256 **98495f0b…5a799**(총괄 검토 대상과 같음) |
| 실패 먼저 고정 | 같은 시험 파일을 v3에 실행: **109건 중 12건 실패**([run-v3-red](p7v4/run-v3-red/cases.out.txt)). 총괄 U1·U2·U3 원형과 경계 변형 |
| 보완 후 | v4 `reconcile.py` sha256 **4706dc50…c9c5d27**: **109/109 통과**([run-v4](p7v4/run-v4/cases.out.txt)) |
| 기존 94건 | 전체 판정 94/94 v3 보존 결과와 같다. 코드가 바뀐 것은 조회 범위가 원래 없거나 미완료·범위 밖인 N06b·c·d 3건에 `EXCHANGE_WINDOW_UNCONFIRMED`가 더해진 것뿐(판정 HOLD 동일) |
| 변형 | 바꾼 검사만 대상으로 새 변형 **11/11 검출**(V17~V27, [mutation.out.txt](p7v4/mutation.out.txt)). v3 변형 V01~V16은 영향이 없어 다시 돌리지 않았다 |

새 시험 15건 = 순수 입력 12건(U1a~f, U2a~f) + 파일 입력 3건(F08~F10). 기존 94건(저장 35 + 새 사례 52 + 파일 7)과 합쳐 109건이다.

## 2. 세 경계의 보완

| 반례 | v3 | v4 | 보완 내용 |
|---|---|---|---|
| **U1** B1에서 거래소 전·후 `done_query` 제거(새 DB 체결 없음) | OK, query=null | **HOLD** `EXCHANGE_WINDOW_UNCONFIRMED` | 거래소→DB 방향 조회 범위를 **새 DB 체결 유무와 무관하게** 비교 구간 전체에 대해 독립 확인한다. 구간 = [기준 관측의 `exchange_before` 시각, 현재 관측의 `exchange_after` 시각]. 조회가 이 구간을 덮고 `complete=true`일 때만 `exchange_coverage.window.status=일치`. 범위 없음·미완료·구간 미포함이면 보류(blocks 없음, 계산은 계속). DB 쪽 무거래로 거래소 쪽 무거래를 증명하지 않는다 |
| **U2** A1 체결 주문 상태 `FILLED_TYPO` | OK(정산 완료로 계산) | **HOLD** `ORDER_STATE_UNRECOGNIZED` + `ORDER_EFFECT_PENDING` | 제품 상태 집합 10개(아래)와 정확히 일치해야 한다. 집합 밖이면 정산 완료로 세지 않고 그 코인·KRW와 **그 세션 장부**만 보류한다. 실제 `UNKNOWN`은 기존대로 `ORDER_UNKNOWN` 이상(U2c). 같은 기준으로 주문 종류(MARKET/LIMIT)·포지션 상태(OPEN/CLOSING/CLOSED) 밖의 값도 보류한다(U2d·U2e) |
| **U3** 잘못된 UTF-8 등록 파일 | rc1, traceback, JSON 없음 | **ANOMALY rc20** `REGISTRY_INVALID`(관측이면 **HOLD rc10** `OBSERVATION_INVALID`) | 바이트 읽기(`OSError` → 읽기 실패 `*_UNREADABLE`)와 UTF-8 디코딩(→ 내용 결함 `*_INVALID`)을 나눴다. 설명에는 바이트 위치·길이만 쓰고 입력 바이트·원문은 출력하지 않는다(F08·F09에서 stderr 없음·원문 미노출 확인). 디렉터리 경로는 읽기 실패로 남는다(F10). 파일 단계의 예상 밖 예외도 구조화된 ERROR rc30으로 낸다 |

**제품 상태 집합(검토 기준 main 294645f1):** `TradingConstants.ACTIVE_ORDER_STATES`(PENDING·INTENT_READY·SUBMITTING·UNKNOWN·SUBMITTED·PARTIAL_FILLED) + 서비스가 쓰는 종결 상태 FILLED·CANCELLED·FAILED·INTENT_REJECTED. 주문 종류는 서비스가 쓰는 MARKET·LIMIT, 포지션은 OPEN·CLOSING·CLOSED. 제품이 상태를 추가하면 이 집합도 같이 고쳐야 하며, 그 전까지 새 상태는 보류로 나온다.

**세션 장부 보류를 넣은 이유:** 첫 보완판에서 U2a는 `SESSION_LEDGER_MISMATCH`(이상)가 됐다. 상태를 모르는 주문을 "진행 중"으로 계산하면 이미 환급이 반영된 DB 장부와 0.02원 차이가 나기 때문이다. 이는 상태를 모르는 데서 생긴 결과이므로, 상태/종류 미확인 주문이 있는 세션의 장부 대조만 보류로 바꿨다(V24 변형이 이를 잡음).

**경계 규칙:** 거래소 조회 기간은 거래소 주문 생성 시각 기준이고 DB 의도 생성 시각과 같지 않다(거래소 생성 ≥ DB 의도 생성). 그래서 구간 끝을 DB 추출 시각이 아니라 거래소 뒤 읽기 시각까지 요구한다(U1d: 1ms 모자라면 보류, V19 변형이 잡음).

## 3. 거래소 완료 주문 수집안(총괄 v1.188 §3-3, 문서안 — 설치·호출 미승인)

[Upbit 종료 주문 조회](https://docs.upbit.com/kr/reference/list-closed-orders) 계약(총괄이 11-18 확인): 주문 생성 시각 기준 기간 필터, 요청당 최대 7일·최대 1,000건, 종료 상태 `done`·`cancel`.

1. **필터:** `market` 지정 없이 계정 전체, `state`는 done·cancel을 모두 조회한다(부분 체결 뒤 취소된 주문은 cancel이면서 체결량이 있다 — 체결량>0이면 체결 주문으로 대조).
2. **기간 분할:** 비교 구간 [기준 `exchange_before`, 현재 `exchange_after`]를 7일 이하 조각으로 나눈다. 조각 경계는 겹치게(앞 조각 끝 = 다음 조각 시작) 잡고 중복은 uuid로 제거한다.
3. **잘림·연속 조회:** 한 조각 응답이 1,000건이면 잘렸을 수 있으므로 그 조각을 더 작게 나눠 다시 조회한다. 응답이 1,000건 미만이 될 때까지 나누지 못하면 그 조각은 `complete=false`.
4. **기록:** `done_query`에 `from`·`to`(전체), `complete`(모든 조각 완료 여부), `pages`(조각·요청 수)를 적고, 조각별 `{from, to, count, truncated}` 목록을 수집 원문으로 보관한다(대조기 입력에는 합친 값만 쓴다).
5. **미확정 범위:** 실패·잘림 조각이 하나라도 있으면 `complete=false`로 내고 대조기는 `EXCHANGE_WINDOW_UNCONFIRMED`·`EXCHANGE_COVERAGE_UNCONFIRMED`로 보류한다. 생성 시각이 구간 앞이고 종료가 구간 안인 주문은 생성 시각 필터에 걸리지 않을 수 있다 — DB 쪽 변경 주문(DB→거래소 방향)과 거래소 미체결 목록(`open_orders`)으로 보완하고, 구간 시작 전 생성 주문은 조회 시작을 기준 관측보다 앞당겨 덮는다(얼마나 앞당길지는 운영 조회 승인 때 정한다).
6. 저장 시나리오의 `2026-10-01~`·`pages=1`은 합성값이다. 실제 한 번의 조회 증거로 쓰지 않는다.

## 4. 파일과 실행

| 파일 | 역할 |
|---|---|
| [p7v4/reconcile.py](p7v4/reconcile.py) | v4 대조기(v3 사본 + U1~U3). 네트워크·DB 접속 없음 |
| [p7v4/cases.py](p7v4/cases.py) | v3 시험 + U1a~f·U2a~f·F08~F10. `python -I cases.py --impl <reconcile.py> --out <폴더>` |
| [p7v4/mutate.py](p7v4/mutate.py) | V01~V27. `python -I mutate.py <작업 폴더> [이름…]` — 이번에는 V17~V27만 실행 |
| p7v4/run-v3-red/ · p7v4/run-v4/ | 회차별 사례 입력·결과와 파일 입력 시험 파일. `files/reg_is_dir.json/`은 F10용 빈 디렉터리라 git에 남지 않는다(실행 때 다시 만든다) |

실행 환경: Windows Python 3.13.14 `-I`. 산출물은 LF·UTF-8(F08·F09 입력 파일은 의도적으로 잘못된 UTF-8). 체크섬은 [SHA256SUMS](SHA256SUMS), 폴더는 `.gitattributes`(`* -text`)로 줄바꿈 변환을 막는다.

## 5. 한계·남은 것

- 모든 입력은 로컬 가정값이다. 운영 사용·실제 현금흐름 검증 완료는 주장하지 않는다.
- **격리 PG 실제 현금흐름 대조(v1.187 §3-2·v1.188 §3-1)는 이번에 하지 않았다.** 시계 차이 메타데이터안(§3-2)도 다음 결과로 남긴다. v4는 여전히 수집기 시계와 DB 시계를 같은 축으로 비교한다.
- 거래소 미체결 주문(`EXCHANGE_OPEN_ORDERS`)은 통화를 알 수 없어 전체 보류인 채다.
