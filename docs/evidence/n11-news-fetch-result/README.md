# N11 뉴스 수집 결과 구분 — 재현과 최소 후보 (계획 §5-9 49)

2026-11-04 KST, 계획 §5-9 48 지시에 따른 로컬 작업이다. 외부 접속·서버·DB·운영 설치는 없었다. 시험은 127.0.0.1 소켓 대역과 저장소 대역(mock)만 쓴다.

## 기점과 후보

| 항목 | 값 |
|---|---|
| 기점(실제 운영) | `ea4490099f0fa6b614fe71102534b0ba84c0eb7f` |
| 재현 커밋 | `05347f0` — 시험 2개 파일만 추가, 운영 코드 무변경 |
| 후보 커밋 | `98bbeda36910ca80f9def562eed8b132df6e12b6` |
| 브랜치 | `n11-news-fetch-result`(원격 푸시). main 에는 합치지 않았다 |

main 의 미배포 V95 는 이 후보에 들어 있지 않다. 후보 diff 는 `ea44900..98bbeda.diff`(12개 파일, 추가 810줄·삭제 32줄)이며, 그중 시험 4개 파일(새 3개와 기존 `AiPipelineTest`)을 뺀 운영 코드는 8개 파일이다.

## 1. 재현 — 운영 코드는 실패와 정상 0건을 구분하지 못한다

`NewsFetchGapReproTest` 는 공개 경로 `collectAll()` 과 로그·`last_fetched_at` 만 본다. 새 API를 쓰지 않아서 기점과 후보에서 **같은 바이트로** 돈다(05347f0 이후 무변경, `git diff --quiet` 확인).

- **대역**:
  - `/reset` — 요청을 다 읽고 100ms 뒤 SO_LINGER 0 으로 닫는다. 서버가 RST 로 연결을 강제 종료한다.
  - `/empty` — 정상 빈 응답이다(RSS 빈 channel, 업비트 `notices:[]`, CryptoPanic `results:[]`).
- **기점 ea44900 결과** — `base-ea44900-repro.xml`, 3건 중 3건 실패. 이것이 재현 결과다.
  - RSS·업비트 공지·CryptoPanic 세 출처 모두 같다. 출처 ERROR 한 줄이 남은 뒤 상위 수집기가 `[NewsAggregator] 수집 완료 — sourceId=stub_reset 전체=0건 신규=0건` 을 INFO 로 남긴다.
  - 정상 빈 응답의 기록도 `수집 완료 … 전체=0건 신규=0건` 이다. 두 경우를 기록으로 구분할 수 없다.
- **후보 98bbeda 결과** — `cand-98bbeda-repro.xml`, 3/3 통과.
- **한계 — 예외 문구**: 이 PC(Windows, JDK 17.0.12)에서 출처 ERROR 문구는 `HTTP/1.1 header parser received no bytes` 다. 운영 리눅스 로그의 `Connection reset` 문구 그대로는 아니다.
  - 상태 줄 일부를 보낸 뒤 RST 를 보내는 방법도 해 봤다. 이때 문구는 `parsing HTTP/1.1 header …` 였다.
  - 세 출처 모두 `catch (Exception e)` 한 곳에서 같은 처리를 한다. 그래서 상위 경로는 같다. 다만 운영 문구를 그대로 재현했다고 적지 않는다.
- **한계 — 재현 범위**: 운영에서 그 실패 뒤 실제로 `수집 완료` 줄이 찍혔는지는 이번 재현의 범위가 아니다. 그것은 N03 로그 읽기로 따로 본다.

## 2. 결과 계약 (후보)

| 결과 | 조건 | 상위 수집기 기록 | 항목 저장 | `last_fetched_at` |
|---|---|---|---|---|
| `SUCCESS` | 응답을 받아 파싱까지 끝남. 0건과 중복만 있는 경우 포함 | 기존 문구 그대로 `수집 완료 — sourceId= 전체= 신규=` (INFO) | 기존과 같음 | 시도 시각 |
| `FAILED` | 항목 없이 실패. 연결 예외, HTTP 비200, 필요한 배열·요소 없음, 파싱 예외 | `수집 미완료 — sourceId= 결과=FAILED 종류= 판정= 받은= 신규=` (WARN) | 없음 | 시도 시각 |
| `PARTIAL` | 항목을 일부 만든 뒤 예외 | 위와 같은 WARN, `결과=PARTIAL` | **받은 항목은 기존처럼 중복 확인 뒤 저장.** 기존 캐시는 지우지 않음 | 시도 시각 |

- **실패 종류**: `HTTP_<코드>`, `INVALID_RESPONSE`, 예외 클래스 단순 이름(예: `IOException`, `SAXParseException`, `JsonEOFException`), `UNSUPPORTED_TYPE`, `NULL_RESULT` 중 하나다. 판정 시각은 `decidedAt` 에 담는다. 토큰, 전체 URL, 응답 본문, 예외 메시지는 담지 않는다.
- **정상 빈 응답과 잘못된 응답의 구분**:

  | 출처 | 정상 0건(SUCCESS) | 잘못된 응답(INVALID_RESPONSE) |
  |---|---|---|
  | 업비트 공지 | 후보 위치(`data.notices`, `data.list`, `notices`) 중 하나가 빈 배열 | 세 위치 모두 배열이 아님 |
  | CryptoPanic | `results` 가 빈 배열 | `results` 가 없거나 배열이 아님 |
  | CoinGecko | `coins` 가 빈 배열 | `coins` 가 없거나 배열이 아님 |
  | RSS | `channel` 은 있고 항목이 없음 | `channel` 요소가 없음 |

  이 중 RSS 의 `channel` 확인만 새로 넣은 조건이다. 현재 RSS 4개(coindesk·bloomberg·tokenpost·blockmedia)는 RSS 주소로 등록돼 있다. 다만 실제 응답에 `channel` 이 있는지는 외부에 접속하지 않아 확인하지 않았다. 없으면 이 출처는 `INVALID_RESPONSE` 로 기록된다. 그래도 ERROR 는 생기지 않고 수집 주기도 바뀌지 않는다.
- **기존 ERROR 원문과 점검 분류**: 출처 자신의 ERROR 줄은 바꾸지 않았다.
  - 문구, 로거, 수준이 모두 같다. 시험에서도 기존 접두어를 확인한다.
  - 상위 수집기는 ERROR 를 더하지 않는다. 점검 스크립트는 ` ERROR ` 줄만 분류하므로 정기 보고의 건수와 분류는 바뀌지 않는다.
  - 뉴스 미분류 🔴 분류는 그대로다.
  - 출처 WARN 가운데 바뀐 것은 둘이다. 업비트의 빈 공지 배열은 이제 WARN 대신 DEBUG `수집 완료 — 0건` 이다. RSS 는 `channel 요소 없음` WARN 이 새로 생긴다.
- **주기**: 바꾸지 않은 것은 다음과 같다.
  - `fixedDelay` 15분과 출처별 `fetch_interval_min` 판정.
  - `last_fetched_at` 을 시도마다 갱신하는 방식. 실패 뒤 같은 주기 안에서는 다시 요청하지 않는다(시험).
  - 지원하지 않는 타입은 기존처럼 갱신하지 않는다.
- **넣지 않은 것**: DB 마이그레이션, 재시도, 타임아웃·TLS 변경, 성공 전용 시각, 실패 상태 저장소.
- **인터페이스와 호출부**: `NewsSource.fetch` 의 반환형을 `NewsFetchResult` 로 바꿨다. 기본 메서드는 두지 않았다. 옛 목록 반환 경로를 근거 없이 성공으로 바꿔 주는 길을 두지 않기 위해서다. 구현 4개(CoinGecko 포함)와 호출부를 모두 맞췄다.
  - **수동 조회** `POST /api/v1/admin/news-sources/{id}/fetch`: 기존 `sourceId`·`collected`·`titles` 를 유지하고 `status`·`failureKind` 를 추가했다. 프런트엔드는 이 응답 본문을 읽지 않는다(`page.tsx` 156행). 그래서 화면은 바뀌지 않는다.
  - **기존 시험**: 기존 `AiPipelineTest` 의 뉴스 시험 3건은 대역 반환값만 `NewsFetchResult.success(...)` 로 바꿨다.
  - **조회·브리핑 경로**: 이 경로들은 `getNewsSince`·`getRecentByCategory` 만 쓴다. 이번에 바꾸지 않았다.
- **바꾸지 않은 동작 — 출처의 예외**: 출처가 계약을 어기고 런타임 예외를 던지는 경우의 동작은 기존과 같다. 이때 순회가 중단되고 트랜잭션이 롤백된다. 현재 출처 4개는 모두 `Exception` 을 잡으므로 이 경로에 들어가지 않는다. 범위를 넓히지 않았다.

## 3. 국소 시험 (후보 98bbeda)

`cand-98bbeda-contract.xml` 은 36건 모두 통과했다. 실제 출처 4종을 대역에 붙여 확인했다.

- **출처마다 다음 7가지**:
  - 정상 0건
  - 1건
  - 중복만 있어 신규 0건
  - 연결 강제 종료: FAILED, 출처 ERROR 1줄의 기존 접두어 유지, 상위 ERROR 0
  - HTTP 503
  - 필요한 배열·요소가 없는 응답
  - 깨진 본문
- **출처 공통**:
  - 앞 출처가 실패해도 다음 출처 수집
  - 실패 뒤 시도 간격 유지
  - PARTIAL 저장 정책(캐시 삭제 호출 없음)
  - `null` 반환은 FAILED
  - 지원하지 않는 타입
  - 실패 기록에 토큰·URL·예외 메시지 없음
  - 결과 불변식
  - 수동 조회 응답 필드

그 밖의 실행 결과는 다음과 같다.

- `cand-98bbeda-repro.xml`: 3/3 통과.
- `cand-98bbeda-aipipeline-*.xml`: 기존 `AiPipelineTest` 11/11 통과(뉴스 3, LLM 3, 보고 5).
- `:web-api:compileJava` 와 `compileRestartHarnessJava` 컴파일 성공.

기존 167건 점검 시험, 전체 리허설, 전체 backend 시험은 반복하지 않았다.

## 파일

- `base-ea44900-repro.xml`, `cand-98bbeda-*.xml`: JUnit 결과 원본이다. `hostname` 속성 값만 `redacted` 로 바꿨다. 시험 본문의 `N11SECRETTOKEN` 은 시험용 가짜 값이며, diff 에만 있다.
- `ea44900..98bbeda.diff`: 후보 전체 diff.
- `.gitattributes`, `SHA256SUMS`.

## 남은 것

- **운영 적용**: 총괄 검토 뒤 판단한다. 이 후보는 운영에 설치하지 않았다.
- **N03 회복 확인**: 다음 22:00 정기 보고를 읽을 때 함께 한다. 뉴스와 PUNDIX·ELF·JUP 의 회복 근거를 이미 보존된 로그에서 확인한다.
- **사용 시간**: 10:33 KST 작업 사본 생성부터 10:40 후보 시험·푸시까지다. 문서 작성을 합쳐 약 0.3시간이다.
- 이 근거를 담은 커밋의 SHA 는 여기에 적지 않는다(N06).
