# 12월 준비 4 — P7 v3 로컬 대조기: 입력 계약·출처 연결·영향 범위 보류·거래소 조회 범위 (총괄 v1.187 §3-1·§3-2)

2026-11-18 KST(11:48~). 기점 HEAD **c2a09f9c**. 파일 입력 시험만 했다. 운영 접속·DB·HTTP·Docker·PG·Java 시험·빌드·제품 수정은 없다. v2 원문([dec-reconcile-20261118/p7v2](../dec-reconcile-20261118/p7v2/))은 읽기만 했다(그 폴더 SHA256SUMS **74/74** 일치 확인 후 사용).

**판정 요청:** P7 v3 대조기를 로컬 초안으로 제출한다. 운영 점검 절차·RUNBOOK·F·N03 설치본은 바꾸지 않았다. 실제 관측 수집기(메타데이터 수집)는 만들지 않았다.

## 1. 결과 요약

| 실행 | 대상 | 결과 | 원문 |
|---|---|---|---|
| 실패 고정 | 같은 시험 94건을 **v2 대조기**(sha256 `1e54ac6b…`)에 | **통과40·실패54**. 저장35는 35/35 통과, 새 사례52 중 48 실패, 파일 입력7 중 6 실패 | [run-v2-red/cases.out.txt](p7v3/run-v2-red/cases.out.txt) |
| 보완 | **v3 대조기**(sha256 `98495f0b…`) | **94/94 통과** | [run-v3/cases.out.txt](p7v3/run-v3/cases.out.txt) |
| 변형 검사 | v3 새 계약 16개를 하나씩 사본에서 제거 | **16/16 검출**(각각 해당 사례 실패) | [mutation.out.txt](p7v3/mutation.out.txt) |

v2에서도 통과한 새 사례 4건(대조군 N00·40자 SHA 접두 N01f·기존 생성 시각 불일치 N10b·새 세션 규칙 N10c)은 v2가 원래 맞게 판정하던 경계다. 파일 입력 F01(정상 파일)도 같다.

**작업 순서를 그대로 적는다:** 시험(cases.py)과 v3 초안은 같은 세션에서 작성했다. v2 실패 기록은 최종 시험을 v2에 돌린 결과이며, "v3 작성 전에 시험을 먼저 돌렸다"는 의미가 아니다. 첫 변형 실행에서 V03·V15가 잡히지 않아(같은 코드를 다른 검사가 함께 내서 효과가 가려짐, 단계 누락 사례 없음) 시각 검사 코드를 `OBSERVATION_ORDER_INVALID`/`DB_TIME_OUTSIDE_COLLECTION`/`COLLECTION_OVERLAP`으로 나누고 N03g·N03h를 추가한 뒤 세 실행을 다시 했다. 위 수치는 그 마지막 실행이다. 실행기 자체 오류 2회(v2의 cp949 오류 출력 해석, 다른 드라이브 상대 경로)는 사례 판정 전 멈춘 것이라 결과에 넣지 않았다.

## 2. 총괄 반례 7개 → v3 결과

| 총괄 | v2 | v3 사례 | v3 결과 |
|---|---|---|---|
| P1 code_sha `wrong-code` | OK | N01a | ANOMALY `REGISTRY_INVALID`(16진 7~40자 아님) |
| (P1 보강) 형식은 맞고 관측과 다름 | OK | N01b | ANOMALY `REGISTRY_CONTRACT_MISMATCH`(code_sha≠관측 app_code_sha) |
| P2 지문 세 개 제거 | OK·stable=true | N02a·N02b(뒤만 제거)·N02c(빈 문자열) | HOLD `FINGERPRINT_MISSING`·stable=false / 빈 값은 `OBSERVATION_INVALID` |
| P3 현재 관측 2020년 | OK | N03a | HOLD `OBSERVATION_ORDER_INVALID` |
| P4 G2 −1.50원 + CLOSING | HOLD·이상0 | N04a | **ANOMALY** `ACCOUNT_KRW_MISMATCH` + HOLD `POSITION_CLOSING`, krw.status=불일치 |
| P5 F4 담보 부족 + CLOSING | HOLD·이상0 | N05a | **ANOMALY** `ACCOUNT_COIN_UNBACKED`, account.verdict=불일치 |
| (P5 출력 충돌) 원래 F4 | ANOMALY인데 verdict=일치 | F4 | ANOMALY·verdict=**불일치** |
| P6 거래소 완료 목록 비움 | OK | N06a | ANOMALY `DB_FILL_NOT_ON_EXCHANGE` |
| P7 규칙 상한 NaN | InvalidOperation 예외 | N07a | ANOMALY `REGISTRY_INVALID`(구조화된 거절) |

보류를 전부 이상으로 올리지 않았다. N04b(같은 입력 + KRW에 영향 주는 진행 중 매수)는 HOLD, `ACCOUNT_KRW_MISMATCH` 없음, krw.status=보류다. 저장 H2·H3·H7·H8·F3도 v2와 같은 HOLD다.

## 3. v3 계약 (파일: [reconcile.py](p7v3/reconcile.py))

### 3-1. 입력 구조·형식 (§3-1)

- **등록표:** `registry_version`·`observation_source{environment, db, exchange_account}` 필수. 세션 항목은 필드 12개(알 수 없는 필드 거절). id는 1 이상 정수(불리언 거절), 초기 자본 유한·양수, code_sha 16진 7~40자, 시각은 시간대 필수, `effective_to > effective_from`. 규칙 형식: `max_filled_buys_in_window` 0 이상 정수, `max_open_positions` 1 이상 정수, `max_buy_request_krw` 유한·양수, `excluded_coins` `KRW-XXX` 목록·중복 없음(N07a~j).
- **관측:** 세션·주문·포지션·계좌·입출금의 필수 필드와 형식, 숫자 유한·음수 거절, 실수형 거절(N08a~f). 키 중복: 세션(kind,id)·주문 id·포지션 id·주문의 거래소 uuid·거래소 목록 uuid(N09a~d). 파일의 JSON 키 중복·`NaN` 문자·실수형 숫자도 거절(F02~F04).
- **구분:** 등록표 내용 결함 = 이상(`REGISTRY_INVALID`), 등록표 읽기 실패 = 보류(`REGISTRY_UNREADABLE`, 이때 "모든 주문 미등록"으로 단정하지 않음, F05), 관측 결측 = `OBSERVATION_MISSING`, 관측 읽기 실패 = `OBSERVATION_UNREADABLE`, 관측 형식 결함 = `OBSERVATION_INVALID`, 조회 실패 = `QUERY_FAILED`(결측과 함께 보임, H4). 관측 구조를 믿을 수 없으면 계산·이상 판정을 내지 않고 보류로 끝낸다.
- **예외:** `evaluate()`가 내부 예외를 `overall=ERROR`로 돌려준다(N11). 명령줄 종료값 OK 0·HOLD 10·ANOMALY 20·ERROR 30(F01~F07).

### 3-2. 관측 구간·시각·순서

- 지문 세 값은 각각 md5 16진 32자여야 하며, 하나라도 없으면 `FINGERPRINT_MISSING`(결측끼리 같아도 안정 확인 아님).
- `meta.steps`는 정확히 `db_fp_pre → exchange_before → db_extract → exchange_after → db_fp_post` 다섯 단계이고 시각이 줄지 않아야 한다(N03c·d·g). DB 추출 시각은 거래소 앞/뒤 읽기 사이(`DB_TIME_OUTSIDE_COLLECTION`, N03h).
- 기준 DB 시각 < 현재 DB 시각(`OBSERVATION_ORDER_INVALID`), 현재 수집 시작 ≥ 기준 수집 종료(`COLLECTION_OVERLAP`), 스키마 버전 같음(`SCHEMA_CHANGED_IN_WINDOW`).
- **가정:** 수집기 시계와 DB `now()` 시계를 같은 축으로 비교한다. 운영에서 두 시계 차이는 측정하지 않았다.

### 3-3. 출처 연결

- 등록 `code_sha`는 현재 관측 `meta.app_code_sha`와 접두 일치해야 한다(7자 이상). 관측 값은 외부 수집 메타데이터이며 근거 `app_code_sha_ref`가 함께 있어야 한다. **DB에 세션별 코드 SHA가 있다고 가정하지 않았다.**
- 현재 관측에 코드 SHA가 없으면 `CODE_SOURCE_UNCONFIRMED`(보류, 일치로 쓰지 않음, N01c). 기준·현재 코드가 다르면 `CODE_CHANGED_IN_WINDOW`(기준 재설정 필요, N01d).
- 관측 출처(환경·DB·거래소 계정)는 등록표·기준·현재가 모두 같아야 한다(`OBSERVATION_SOURCE_MISMATCH`, 전체 보류, N01e).
- **등록 적용 시작 ≠ 세션 생성:** `session_created_at=null`은 새 세션(생성 ≥ 적용 시작). 값이 있으면 기존 세션의 승인된 신규 구간이다. 이때 DB 생성 시각이 등록값과 같아야 하며, 적용 시작은 생성 이후여도 된다. 구간 주문은 적용 시작부터 센다(N10a OK·N10b·c 불일치·N10d 형식 거절).

### 3-4. 영향 범위 보류·항목별 상태 (§3-2)

- 보류마다 막는 범위 `blocks`를 적는다. 관측 불안정·결측·출처 불일치는 전체(`*`), 진행/불명/반영 대기/정산 미확정 실자금 주문은 **그 코인과 KRW**, 입출금 이력 미확인은 증감식(KRW·코인)만 막는다. `POSITION_CLOSING`·코드 출처·거래소 조회 범위 보류는 계산을 막지 않는다. 매도가 진행 중이면 그 주문이 해당 통화를 막는다.
- 실보유 담보 검사는 입출금 이력과 무관하므로 이력 미확인으로 막지 않는다(N05b: 이력 없음 + 담보 부족 → ANOMALY, 증감 불일치 코드는 없음).
- 계좌 항목마다 `status`(일치/불일치/보류)를 둔다: KRW 증감·코인별 증감·세션 장부·통화별 담보. `account.verdict`는 불일치 > 보류 > 일치 순으로 정한다. 시험 계약(모든 사례): 계좌 이상이 있으면 verdict=불일치, verdict=불일치면 계좌 이상이 있음, verdict=보류면 OK가 아님.
- 주문 확정 조건을 좁혔다: `fill_accounting_pending`이거나 진행/불명 상태면 반영값이 있어도 미확정이다(v2는 반영값만 있으면 확정으로 셈).

### 3-5. 거래소 주문 조회 범위 (§3-2)

- 거래소 관측에 `done_query{from, to, complete, pages}`를 둔다. 구간에 바뀐 실자금 체결 주문은 DB uuid → 거래소 완료 목록 방향으로도 확인한다.
- 조회가 완료(`complete=true`)이고 주문이 조회 기간 안인데 목록에 없으면 이상 `DB_FILL_NOT_ON_EXCHANGE`. 조회 기간 밖이면 `EXCHANGE_COVERAGE_GAP`, 범위 없음·미완료면 `EXCHANGE_COVERAGE_UNCONFIRMED` 보류다. 미등록 주문으로도, OK로도 쓰지 않는다(N06b~d). 체결이 있는데 거래소 uuid가 없으면 이상(N06e).
- **가정:** 조회 기간은 주문 생성 시각 기준으로 비교한다. 실제 거래소 완료 주문 API의 기간 기준·페이지 한도는 확인하지 않았다(운영 조회 승인 전).

## 4. 저장 35시나리오 (§3-2 "제시된 35개와 새 반례를 함께")

[변환](p7v3/cases.py) `adapt()`는 저장 입력에 v3 필수 메타데이터만 덧붙인다. 출처는 `local-isolated`, 코드는 `0e1d2a02`, 단계 시각은 DB 시각 ±1~2ms, 조회는 `2026-10-01`부터 완료로 둔다. 원래 값은 바꾸지 않았고, 각 결과 파일에 원본 경로·sha256을 적었다. **이 메타데이터는 합성값이며 운영 관측이 아니다.**

- 35/35가 저장된 기대(전체 판정·필수 코드·금지 코드)를 만족한다. 전체 판정은 v2 저장 결과와 35개 모두 같다.
- v2 저장 결과 대비 바뀐 것은 세 가지다. C2·F4의 계좌 판정이 일치에서 **불일치**로 바뀌어 이상 코드와 맞춰졌다. E2(삭제된 세션의 진행 중 주문)에는 `ORDER_EFFECT_PENDING`(그 코인·KRW만 보류)이 추가됐다. 다른 시나리오의 코드 집합은 같다.

## 5. 파일과 실행 방법

| 파일 | 역할 |
|---|---|
| [p7v3/reconcile.py](p7v3/reconcile.py) | v3 대조기. 네트워크·DB 접속 없음 |
| [p7v3/cases.py](p7v3/cases.py) | v2 원문 체크섬 확인 → 변환 → 저장35 + 새 사례52 + 파일 입력7. `python -I cases.py --impl <reconcile.py> --out <폴더>` |
| [p7v3/mutate.py](p7v3/mutate.py) | 변형 16개. `python -I mutate.py <작업 폴더>`(변형 사본·출력은 작업 폴더에만, 결과 요약만 보존) |
| p7v3/run-v2-red/ · p7v3/run-v3/ | 회차별 사례 입력·결과(`cases/*.json`)와 파일 입력 시험 파일(`files/`) |

실행 환경: Windows Python 3.13 `-I`. 모든 산출물은 LF·UTF-8이다. 체크섬은 [SHA256SUMS](SHA256SUMS).

## 6. 한계·남은 것

- 모든 입력은 로컬 가정값이다. v3 정상 판정은 운영 실측이 아니다. 실제 메타데이터 수집(배포 이미지/커밋 출처, DB 별칭, 거래소 완료 주문 조회 범위)은 운영 조회 승인 범위가 정해진 뒤 별도 절차로 만든다. 현재 `extract.sql`·`fingerprint.sql`은 v2 그대로다.
- **§3-2의 격리 PG 실제 현금흐름 대조는 이번에 하지 않았다:** 매수→정산·부분 체결/취소·환불·후속 수수료 정정 중 모형만 둔 경로다. 지정가·매도 부분 체결 뒤 취소 세부도 v2 한계 그대로다. 다음 작업으로 남긴다.
- 거래소 미체결 주문(`EXCHANGE_OPEN_ORDERS`)은 통화를 알 수 없어 여전히 전체 보류다.
- v1.187 §3-3~6(모드·쿨다운 로컬 통합, 금액/BTC 독립 후보, 월 성적표, 알림 보완안)은 착수 전이다.
