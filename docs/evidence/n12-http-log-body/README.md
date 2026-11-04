# N12 HTTP 오류 응답 본문 로깅 보완 — 로컬 후보 (계획 §5-9 59)

2026-11-04 KST. 총괄 v1.90(§5-9 58)의 N12 지시에 따른 로컬 작업이다.

- 서버·운영 DB·외부 HTTP 에는 접속하지 않았다. 운영 설치·docker 빌드·jar 빌드도 하지 않았다.
- 기존 167건과 전체 리허설은 다시 돌리지 않았다.

## 후보

| 항목 | 값 |
|---|---|
| 기점 | `b1d7664db73222aec5d86f2e9137debef0ff4df3`(운영 중) |
| 브랜치 | `n12-http-log-body`(원격에도 푸시) |
| 실패 먼저 | `b7a8da15933899b5c296e556b8282f79e535f690` — 시험 파일만 추가 |
| **고정 후보** | **`6995c52259e3de4c7c922a32f9fb2aa9ddd49f00`** — 바로 앞 커밋이 `b7a8da1` |
| b1d7664 → 6995c52 변경 | 파일 3개, 줄 179 추가·3 삭제. 시험 파일 1개(175줄)와 코드 2곳. 원문은 `diff-b1d7664-6995c52.txt` |

## 1. 코드 변경 — HTTP 비200 분기 두 곳만

| 출처 | b1d7664 | 6995c52 |
|---|---|---|
| CryptoPanic | `log.warn("[CryptoPanicSource] HTTP {}: {}", 상태, response.body())` — 본문 전체 | `log.warn("[CryptoPanicSource] HTTP {} — sourceId={}", 상태, sourceId)` |
| UpbitNotice | `log.warn("[UpbitNoticeSource] HTTP {}: {}", 상태, 본문 앞 120자)` | `log.warn("[UpbitNoticeSource] HTTP {} — sourceId={}", 상태, sourceId)` |

- 새 문구는 RSS 의 기존 HTTP 줄(`[RssNewsSource] HTTP {} — sourceId={}`)과 같은 형식이다.
- 두 분기 모두 바로 다음 줄 `return NewsFetchResult.httpStatus(...)` 는 그대로다.

**바꾸지 않은 것**

- 네 출처의 `catch` ERROR 원문(`수집 실패: …`)과 점검 분류.
- NewsFetchResult 와 상위 `수집 완료`·`수집 미완료` 로그.
- 캐시, 시도 시각과 간격, 요청 URL·헤더, TLS, 재시도, DB, 설정.

`source-log-lines.txt` 에 네 출처의 모든 로그 호출을 두 판에서 뽑았다. 다른 줄은 줄 번호(CryptoPanic 주석 한 줄만큼 +1)만 다르다.

**읽기 대조만 한 것**

- RSS 는 상태 코드와 sourceId 만, CoinGecko 는 상태 코드만 쓴다. 본문은 쓰지 않는다. 지시대로 고치지 않았다(CoinGecko 줄에 sourceId 가 없는 것도 그대로 두었다).
- UpbitNotice 정상(200) 분기의 `공지 배열을 찾지 못함 — url={}` 은 설정 URL 을 쓰는 기존 줄이다. 응답 본문이 아니고 N12 범위(비200 분기) 밖이라 바꾸지 않았다.

## 2. 국소 시험 `NewsHttpErrorLogTest` — 8사례

외부 접속 없이 기존 127.0.0.1 대역(`LocalHttpStub`)을 썼다.

**구성**

- **실패 출처**: 403 또는 503 을 돌려준다. 본문은 다중 행이고 다음을 담는다.
  - HTML(`<!DOCTYPE html>`, `<html>`, `<p>`)
  - `\n` 과 `\r\n`
  - URL(`https://blocked.invalid/…?token=`)
  - 가짜 비밀 값 `FAKE-SECRET-N12-7f3a9c`. 둘째 줄 앞부분에 두어 Upbit 의 앞 120자에도 걸리게 했다.
- **다음 출처**: 같은 타입이고 정상 빈 응답(200)을 돌려준다.
- **CryptoPanic 설정**: 가짜 API 키 `FAKE-APIKEY-N12-0000`(요청 URL 의 `auth_token`).
- **실행 경로**: 실제 출처 → `NewsAggregatorService.collectAll()`.
- **로그 수집**: `com.cryptoautotrader.api.news` 아래의 모든 로그를 DEBUG 로 받는다.

**사례마다 보는 것**

1. 두 출처 모두 요청이 1회씩 갔다.
2. 어떤 로그에도 다음이 없다.
   - 비밀 값, API 키, `auth_token`
   - HTML 조각, 본문 문구, 대역 URL
   - 줄바꿈
3. 출처 WARN 은 1줄이다. CryptoPanic·Upbit 는 정확히 `[출처] HTTP n — sourceId=stub_fail` 이다.
4. 상위 기록에 `수집 미완료 — sourceId=stub_fail 결과=FAILED 종류=HTTP_n … 받은=0건 신규=0건` 이 있고, `수집 완료` 는 없다.
5. 다음 출처에는 `수집 완료 — sourceId=stub_ok 전체=0건 신규=0건` 이 있다. 이것이 정상 경로 대조다.
6. 두 출처 모두 `last_fetched_at` 이 시도 시각으로 갱신된다.
7. ERROR 줄이 없다.

**결과**

| 사례 | b7a8da1(코드는 b1d7664 그대로) | 6995c52 |
|---|---|---|
| CryptoPanic 403·503 | **실패** — WARN 에 본문 전체(HTML·`\n`·`\r\n`·URL·비밀 값) | 통과 |
| UpbitNotice 403·503 | **실패** — WARN 에 앞 120자(비밀 값·URL 포함) | 통과 |
| RSS 403·503 | 통과 | 통과 |
| CoinGecko 403·503 | 통과 | 통과 |

- 8건 중 4건 실패가 재현 결과다.
- 원문은 `before/`·`after/` 의 JUnit XML 이다. 사례 결과와 출처 WARN·상위 기록 줄은 `before-summary.txt`·`after-summary.txt` 에 정리했다.
- 요약의 `>>…<<` 사이가 실제 메시지다. 줄바꿈은 `\n`·`\r` 로 바꿔 적었다.
- 요약과 XML 표준 출력에서 `—` 가 `?` 로 보이는 것은 시험 JVM 표준 출력의 문자 집합 문제다. 3번 검사는 실제 메시지 문자열을 `—` 로 비교해 통과했다.

**같은 6995c52 에서 함께 돈 뉴스 패키지 시험(관련 국소)**

| 시험 | 결과 |
|---|---|
| `NewsFetchGapReproTest`(재현) | 3/3 |
| `NewsFetchResultContractTest`(계약) | 36/36 |
| `NewsResponseShapeTest`(경계) | 27/27 |
| `NewsHttpErrorLogTest`(새) | 8/8 |

실행 명령은 `./gradlew :web-api:test --tests 'com.cryptoautotrader.api.news.*' --offline` 이다.

## 3. 한계

- 운영 로그로는 확인하지 않았다. 운영 반영은 후보 검토 뒤 별도 절차다.
- 시험은 대역의 고정 본문만 본다. CryptoPanic 실제 403 본문에 비밀 값이 있었다고 확대하지 않는다.
- 운영 b1d7664 의 앱 로그(서버 `~/maint-n11/app-*.log` 와 docker 로그)에는 이미 찍힌 403 HTML 본문이 남아 있다. 이번 작업은 앞으로의 기록만 바꾸며, 남은 로그는 건드리지 않았다.
- CryptoPanic 403 의 원인은 여전히 미해결이다. 이 보완은 원인과 관계없다.

## 4. 파일

| 파일 | 내용 |
|---|---|
| `diff-b1d7664-6995c52.txt` | 후보 전체 diff(시험 파일 포함) |
| `source-log-lines.txt` | 네 출처 로그 호출의 두 판 대조 |
| `before/TEST-…NewsHttpErrorLogTest.xml` | b7a8da1 실행 원문(8건 중 4건 실패) |
| `after/TEST-*.xml` | 6995c52 뉴스 패키지 4개 시험 원문 |
| `before-summary.txt`, `after-summary.txt` | 사례별 결과와 출처 WARN·상위 기록 |
| `.gitattributes`(`* -text`), `SHA256SUMS` | 원본 바이트 보존과 체크섬 |

**사용 시간**: 15:01 KST 착수(시각 확인)부터 이 근거를 담은 커밋 시각(Git 기록)까지다. 이 근거를 담은 main 커밋의 SHA 는 적지 않는다(N06).
