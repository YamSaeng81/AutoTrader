# N19 보완 — 로컬 후보 ee509cf (2026-11-06 새벽, 총괄 검토 전, 운영 미설치)

총괄 v1.123 §4([night-review-303079b](../night-review-303079b/README.md))의 보완 두 가지를 반영한 후보다.

- **보완 1**: 건너뜀 WARN 에서 REST 응답의 `market` 원문을 뺐다.
- **보완 2**: `market` 이 문자열이 아닌 항목만 건너뛴다.

**하지 않은 것**:

- 서버 접속·설치·재기동을 하지 않았다. 운영은 7a94472 그대로다.
- f6868a5 와 §90 UB=90 U 블록은 실행 승인되지 않았고, 이 판도 실행하지 않았다.
- 86/173/194 전체 시험과 바뀌지 않은 배포 비교기 변형 시험은 다시 돌리지 않았다.

## 1. 후보

| 항목 | 값 |
|---|---|
| 후보 SHA | **`ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8`**. 원격 `candidate/n19v2-rest-fallback-7a94472` 와 같다(`code/ls-remote.txt`). |
| 부모 | `f6868a587a921dbc8b4985793582bc764b0896b1`. 원격 `candidate/n19-rest-fallback-7a94472` 는 바꾸지 않았다. |
| 운영 7a94472 대비 | 2경로 그대로다(`code/7a94472-ee509cf.stat`): `LiveTradingService.java` +26/−3, 시험 `N19RestFallbackItemDefenseTest.java` +460. |
| f6868a5 대비 | `code/f6868a5-ee509cf.diff`: 운영 파일 +11/−5, 시험 +84. |
| 설정·마이그레이션·리소스 | 없음 |

### 바뀐 동작 — `pollRestTickerFallback` 응답 반복문 안만

**`market` 원문 출력 제거(보완 1)**:

건너뜀 WARN 세 종류는 아래 고정 문구만 남긴다.

1. `[§9] REST 폴백 시세 항목 건너뜀 — 가격 형식이 숫자가 아님`
2. `[§9] REST 폴백 시세 항목 건너뜀 — 가격이 아님 (부호=0|음수)`
3. `[§9] REST 폴백 시세 항목 건너뜀 — 코인 형식이 문자열이 아님` (새 문구)

- 셋 모두 `market` 원문·가격 원문·예외 문구를 싣지 않는다.
- 그래서 어느 코인이 건너뛰어졌는지는 로그로 알 수 없다. 지시(고정 사유·유형·부호만)에 따른 대가다.

**문자열이 아닌 `market`(보완 2)**:

- `(String)` 형변환을 `instanceof String` 확인으로 바꿨다. 문자열이 아니면 위 3번 WARN 을 남기고 그 항목만 건너뛴다.
- 예전(7a94472·f6868a5 둘 다)에는 `ClassCastException` 이 배치 `catch` 로 가서 ERROR 1줄이 남고 뒤 정상 코인 발행이 0이었다. 실패 먼저 실행(r3)의 실제 ERROR 원문은 `class java.lang.Integer cannot be cast to class java.lang.String …` 이었다.

**바꾸지 않은 것**:

- 누락·null 은 기존처럼 WARN 없이 조용히 건너뛴다(`market` null 은 ⑥-1 로 확인).
- 외부 조회 실패 ERROR, 발행기·처리기 내부 예외의 배치 `catch` 경로는 그대로다. 포괄 catch 를 더하지 않았다.
- 새 시장 지원 정책·정규식·세션 허용 목록은 만들지 않았다.
- 같은 파일의 `forceRefreshPrice`(N17)는 같은 `(String)` 형변환과 `market=`·`trade_price=` WARN 을 그대로 갖고 있다. 이번 범위 밖이라 바꾸지 않았다(§5).

## 2. 시험 — 실행별 폴더 분리, 실행 전 소스·해시 보존

[`tests/runtest.sh`](tests/runtest.sh)는 앞 N19 와 같은 스크립트다. 실행 폴더는 `/d/tmp/n19/runs/` 에 이어서 만들었다.

| 실행 | 시각(KST) | 기준 | 결과 |
|---|---|---|---|
| r3-failfirst-f6868a5 | 11-06 01:41:28~01:42:01 | f6868a5 운영 코드 그대로 + 새 시험 6건 추가(추적 파일 수정) | N19 클래스 23건 중 **실패 5**, 종료 1 |
| r4-fix-related | 11-06 01:43:14~01:43:45 | 두 최소 수정 + 같은 시험 | 관련 6클래스 **73/73**(실패·오류·건너뜀 0), 종료 0 |

### 새 시험(기존 17건은 그대로 두고 더함)

**⑤ 가짜 표지·줄바꿈이 든 `market`**:

- 대상 이상 항목 3종: 가격 `0`·`-1`·`abc`.
- `market` 값은 `KRW-N19FORGED` + 줄바꿈 + `12:00:00.000 [main] ERROR … REST ticker fallback 실패: 가짜표지` 다.
- 확인 항목:
  - 건너뜀 WARN 은 정확히 한 줄이고, 고정 문구 3종 중 하나다.
  - WARN 전체에 `N19FORGED`·`가짜표지`·`12:00:00.000`·`market=`·줄바꿈·앞뒤 코인 이름이 없다.
  - 앞뒤 정상 코인은 각각 발행 1, ERROR 0 이다.

**⑥ 숫자 `market`(맨 앞·가운데 2건)**:

- 그 항목만 발행 0, 앞뒤 정상 코인 각각 1이다.
- 배치 ERROR 0, WARN 은 정확히 `… 코인 형식이 문자열이 아님` 한 줄이다.
- WARN 에 숫자 원문과 `ClassCast` 가 없다.

**⑥-1 `market` null**: WARN 0, 앞뒤 정상 코인 발행 1. 기존 조용한 건너뜀을 지키는지 보는 대조군이다.

### r3 실패 5건(기대한 실패)

- ⑤ 3건: WARN 이 `… 가격이 아님 (market=KRW-N19FORGED` + 줄바꿈 + `12:00:00.000 [main] ERROR … 가짜표지, 부호=0)` 처럼 찍혔다.
  - 시험 출력에 **줄 머리가 가짜 ERROR 인 줄이 따로 생겼다**. r3 N19 XML 에서 `^12:00:00.000 [main] ERROR` 로 시작하는 줄이 6줄이다(로그 출력과 실패 메시지에 각각 실림). r4 XML 에서는 같은 줄과 `FORGED` 가 0줄이다.
  - 운영 응답에서 이런 값이 왔다는 뜻은 아니다. f6868a5 의 새 WARN 이 그 경로를 열었다는 재현이다.
- ⑥ 2건: 배치 ERROR(`ClassCastException` 문구)와 뒤 정상 코인 발행 0.
- ⑥-1 과 기존 17건은 수정 전에도 통과했다(기존 계약 유지).

### r4 관련 범위(앞 r2 와 같은 6클래스)

| 클래스 | 결과 |
|---|---|
| N19RestFallbackItemDefenseTest | 23 |
| LiveTradingSlWatchdogRecoveryTest | 6 |
| WsSubscriptionUnionTest | 3 |
| N16InvalidPriceReproTest | 13 |
| N17RestInvalidPriceTest | 24 |
| SlWatchdogFreshnessTest | 4 |

### 보존 소스와 커밋의 대조

- **시험 파일**: r3·r4 보존 사본 sha256 `e7ab3844…` 이 ee509cf 블롭과 같다.
- **운영 파일**: r4 보존 사본 `559d7266…` 은 작업 사본의 CRLF(autocrlf)다. CR 을 빼면 블롭 `53374c4f…` 와 같다.
- r3 의 운영 파일은 수정 전이다(상태 목록에 운영 파일 변경 없음).

## 3. 실제 로컬 jar 대조

- **빌드**: 커밋 뒤 깨끗한 작업 사본에서 했다(`build-status.txt` 추적 변경 0, HEAD `ee509cf…`).
  - 명령: `:web-api:clean :web-api:bootJar -x test --offline` 1회.
  - 시각: 01:44:18~01:44:27, 종료 0.
- **jar 해시**: `4f4f42aa058c34a15e438ba9048a9f94c49b2963a8bd72c5eb1ca3d5fbd6f4ec`
  - 기준 jar 2개는 다시 빌드하지 않고 재사용했다.
  - 7a94472 jar `ddb64359…`, f6868a5 jar `7c3a0655…` 다. 두 해시 모두 앞 근거의 `jar-sha.txt` 와 같다.
- **비교기**: 수용된 원문 비교기 `k23-prep-51dcee5/jars/jarcmp.py` 를 썼다.
  - `jars/diff-7a94472-ee509cf.txt`(#25 기준): **변경 3 · 추가/삭제 0 · `(끝) 881 881`**.
  - 바뀐 3항목 이름은 f6868a5 때와 같다. 그래서 U1 의 RARX 기대값은 그대로다.
  - `jars/diff-f6868a5-ee509cf.txt` 도 같은 3항목이다.
- **메서드 단위**(`jars/methods.py`: `javap -c -p`, 상수 풀 번호 제거, 메서드별 비교, `method-diff-7a94472-ee509cf.txt`)
  - `LiveTradingService` 164/164 메서드 중 **`pollRestTickerFallback` 하나만** 다르다.
  - `$1`·`$PriceSnapshot` 는 `javap -c -p` 가 같고, 줄 번호 표만 밀렸다(`javap-other-classes.txt`).
- **한계**: RARX 는 f6868a5 와 ee509cf 를 구분하지 못한다(바뀐 항목 이름이 같다). 두 판은 U0·U1 의 `NEW` 커밋·HEAD 검사로 구분된다.
- 후보 jar 파일 자체는 커밋하지 않는다(로컬 `/d/tmp/n19v2/jars/`).

## 4. #25 U 블록 초안 갱신 (UB=92) — 실행하지 않음

[`blocks/u-blocks.md`](blocks/u-blocks.md)(694줄)는 [`blocks/make_u.py`](blocks/make_u.py)가 만들었다.

- **입력**: 앞 판과 같다. §87 T 블록 원문과 #24 실제 실행판 해시(`k24-result-7a94472/blocks/blocks.json`)를 먼저 대조한다.
- **UB=90 판과의 차이**: `blocks/u90-to-u92.diff` 의 35줄뿐이다.
  - `UB=92`·`v1.124`·`§5-9 92` 판 표지.
  - `NEW=ee509cf…`, 출처 브랜치, 후보 7자리 문구.
  - RARX 주석.
  - U 되돌림 설명(문자열 아닌 market·고정 문구 추가).
  - `n19obs` 본문.
- **바뀌지 않은 것**: OLD·IMGOLD(#24 `e51b512a…`)·IMGPREV4/5·DIFFN(2경로)·RARX 3줄·끝 줄·되돌림 대상(7a94472)·나머지 관측 함수.

### `n19obs` — 새 고정 문구에 맞춤

- 세 고정 문구를 줄 끝까지 정확히 맞춘다.
- 출력은 `형식 이상 n·가격 아님 n·코인 형식 n` 과 시각뿐이다.
- 옛 f6868a5 문구(`market=` 포함)는 세지 않는다. f6868a5 는 운영에 올라간 적이 없다.

### 검사

- `bash -n`: 8/8([`blocks/bash-n.txt`](blocks/bash-n.txt)).
- 대역 확인: **22/22**([`blocks/check-u.txt`](blocks/check-u.txt)). 앞 판 21 항목에 "시험 로그 건너뜀 줄 market 원문 0"을 더했다.
  - n19obs 를 r4 실제 시험 로그에 돌렸다. 형식 6·가격 아님 8·코인 형식 2 로, 건너뜀 줄 전체 16 과 같다.
  - UTF-8 원문 1·1·1 을 셌다. N17 줄·옛 f6868a5 문구·기존 ERROR 는 세지 않았다.
- `check_u.sh` 의 상대 경로는 `/d/tmp/n19v2/blocks` 작업 배치 기준이다. 근거 폴더에서는 `../runs/` 가 `tests/` 다.

### 전제

- **실행 승인 필요**: 이 판의 실행 여부는 총괄 판정이다. #24 야간 배포 권한은 #25 에 승계되지 않는다.
- **운영 기준**: 운영이 7a94472/e51b512a… 가 아니면 U0·U1 이 멈춘다.

## 5. 남은 관찰(이번에 고치지 않음, 기존대로 인계)

- **`forceRefreshPrice`(N17)**: 같은 `(String) ticker.get("market")` 형변환이 있다. WARN 에 `market=`·`trade_price=` 원문(`toPlainString`)을 싣는다.
  - 조회 대상이 앱이 고른 코인 1개라 입력 경로는 폴백과 같지 않다.
  - #24 운영 판이라 바꾸지 않았다.
- **`HeldMarkRefresher`**: 항목 변환 실패가 배치 `catch` 로 가는 구조 그대로다. 국소 실행 없음.
- **WARN 반복**: WS 이상 동안 5초 주기로 같은 이상 항목 WARN 이 반복될 수 있다. 이제 문구에 코인이 없어 반복이 같은 항목인지도 로그로는 구분되지 않는다. 억제·주기 정책은 만들지 않았다.

작업 시간(KST, 11-06): 01:40 지시 읽기 → 01:41~01:42 실패 먼저 → 01:43 수정·관련 시험 → 01:44 커밋·푸시·jar → 01:46~ U 블록·근거. 이 근거를 담은 커밋 SHA 는 여기에 적지 않는다(N06).
