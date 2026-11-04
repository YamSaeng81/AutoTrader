# N11 보완 후보 — 업비트 success·RSS 구조 두 판정 (계획 §5-9 51)

2026-11-04 KST, 총괄 98bbeda 검토(계획 §5-9 50, [`n11-review-98bbeda`](../n11-review-98bbeda/README.md))의 두 보완 지시에 따른 로컬 작업이다. 외부 접속·서버·DB·운영 설치는 없었다. 시험은 127.0.0.1 소켓 대역과 저장소 대역(mock)만 쓴다. 총괄 실행기는 네트워크에 나가지 않는 HttpClient 대역을 쓴다.

## 기점과 후보

| 항목 | 값 |
|---|---|
| 기점(실제 운영) | `ea4490099f0fa6b614fe71102534b0ba84c0eb7f` |
| 직전 후보(운영 적용 보류) | `98bbeda36910ca80f9def562eed8b132df6e12b6` |
| 실패 먼저 커밋 | `1ad38b4` — 경계 시험 파일 1개만 추가, 운영 코드 무변경 |
| **보완 후보** | **`b1d7664db73222aec5d86f2e9137debef0ff4df3`** |
| 브랜치 | `n11-news-fetch-result`(원격 푸시, 원격 머리 b1d7664 확인). main 에는 합치지 않았다 |

- `98bbeda..b1d7664.diff`: 이번 보완분. 운영 코드 3개(`NewsFetchResult`·`RssNewsSource`·`UpbitNoticeSource`)와 새 시험 1개, 추가 221줄·삭제 4줄.
- `ea44900..b1d7664.diff`: 후보 전체. 13개 파일(운영 코드 8개·시험 5개), 추가 1027줄·삭제 32줄. 운영 코드 파일 수는 98bbeda 와 같은 8개다.

## 1. 수정 전후 — 총괄의 같은 6입력

총괄 `N11Review.java` 를 **바꾸지 않고**(SHA-256 앞자리 `48e967e99ae8c0b6`, 총괄 근거본과 같음) 두 번 실행했다.

- **수정 전**: 98bbeda 의 원문 3개(`NewsFetchResult`·`RssNewsSource`·`UpbitNoticeSource`)를 `git show 98bbeda:` 로 꺼내 javac 로 덮어 실행. 종료 1, 3건 불일치. 총괄 관측과 같다.
- **수정 뒤**: b1d7664 빌드 그대로. 종료 0, 6/6 일치. 각 입력마다 대역 호출 1회.

| 입력 | 기대 | 98bbeda | b1d7664 |
|---|---|---|---|
| 업비트 `success:false`, 빈 배열 | FAILED·0 | SUCCESS·0 | **FAILED·0** |
| 업비트 `success:false`, 항목 1 | FAILED·0 | SUCCESS·1 | **FAILED·0** |
| 업비트 `success:true`, 빈 배열 | SUCCESS·0 | 같음 | 같음 |
| RSS 자리에 `<html><body><channel/></body></html>` | FAILED·0 | SUCCESS·0 | **FAILED·0** |
| 정상 rss/channel 빈 응답 | SUCCESS·0 | 같음 | 같음 |
| 정상 rss/channel/item 1건 | SUCCESS·1 | 같음 | 같음 |

출력 원본은 `n11review-{before,after}.{stdout,stderr,exit}` 다. 콘솔 인코딩은 UTF-8 로 지정했다. 이 실행은 출처의 반환값만 본다. 상위 수집기의 기록과 저장은 아래 경계 시험에서 본다.

## 2. 바꾼 판정

### 업비트 공지 — 본문 `success`

| 본문 `success` | 결과 | 출처 기록 |
|---|---|---|
| 없음 | 기존과 같다. 공지 배열(`data.notices` → `data.list` → `notices`)로 판정한다. 빈 배열은 정상 0건, 배열이 없으면 `INVALID_RESPONSE` | 기존과 같음 |
| `true` | 위와 같다 | 기존과 같음 |
| `false` | 배열·항목과 관계없이 **FAILED `SOURCE_REPORTED_FAILURE`**. 항목을 넘기지 않으므로 저장도 없다 | WARN `응답 success=false — sourceId=` |
| boolean 이 아님(문자열 `"true"`/`"false"`, 숫자, `null`) | **FAILED `INVALID_RESPONSE`** | WARN `success 값이 boolean 아님 — sourceId=` |

- 실패 종류 `SOURCE_REPORTED_FAILURE` 는 고정 상수다. 본문, 오류 이름, URL 은 담지 않는다.
- 이 경로는 예외가 아니므로 출처 ERROR 가 생기지 않는다. 기존 ERROR 줄(`[UpbitNoticeSource] 수집 실패: `)은 그대로다.

### RSS — 피드 구조

98bbeda 는 문서 어디에든 `channel` 이름이 하나 있으면 통과시켰다. 이제 아래 두 구조만 정상 응답이다(`RssNewsSource.isSupportedFeed`).

| 지원 형식 | 루트 | 조건 |
|---|---|---|
| RSS 0.9x/2.0 | 접두어 없는 `rss` | 루트 **바로 아래** `channel` 요소 |
| RSS 1.0 | 로컬 이름 `RDF`, 그 접두어의 xmlns 값이 `http://www.w3.org/1999/02/22-rdf-syntax-ns#` | 루트 **바로 아래** `channel` 요소 |

- 그 밖의 루트(`html`, Atom `feed`, 다른 네임스페이스의 `RDF`, 접두어 붙은 `rss`)와 `channel` 이 손자 이하에 있는 문서는 **FAILED `INVALID_RESPONSE`** 다. 출처 WARN `지원하는 RSS 구조 아님(루트 rss 또는 rdf:RDF 바로 아래 channel 필요)` 이 남는다.
- 파서 설정은 바꾸지 않았다. 기존처럼 네임스페이스를 해석하지 않는다. 그래서 미선언 접두어(`dc:creator` 등)가 있는 피드도 기존처럼 읽힌다. RDF 네임스페이스는 루트의 xmlns 속성 값을 직접 대조한다.
- 항목 읽기(`item` 태그, 제목·링크·guid·pubDate)는 바꾸지 않았다.
- **Atom 은 지원하지 않는다.** 기점 ea44900 에서 Atom 피드는 `item` 이 없어 오류 없이 0건으로 끝났다. 98bbeda 와 b1d7664 에서는 `INVALID_RESPONSE` 로 기록된다. 새 형식 지원은 넣지 않았다.

### 바꾸지 않은 것

- 출처 ERROR 호출 4개의 원문(98bbeda..b1d7664 diff 에 `log.error` 변경 없음). 상위 수집기 ERROR 추가 없음. 경계 시험 27건 모두 ERROR 0줄을 확인한다. 정기 점검 건수와 뉴스 미분류 🔴 분류는 그대로다.
- `last_fetched_at` 시도 기준, 수집 간격, 부분 결과 저장 정책, 캐시 삭제 없음, 수동 조회 API 필드.
- DB 마이그레이션, 재시도, 타임아웃·TLS, 운영 설정.

## 3. 시험

| 시험 | 98bbeda(수정 전) | b1d7664 |
|---|---|---|
| 경계 시험 `NewsResponseShapeTest`(새) | 27건 중 **13건 실패** — `pre-98bbeda-shape.xml` | **27/27** — `cand-b1d7664-shape.xml` |
| 계약 시험 `NewsFetchResultContractTest` | 36/36(앞선 근거) | 36/36 — `cand-b1d7664-contract.xml` |
| 재현 `NewsFetchGapReproTest` | 3/3(앞선 근거) | 3/3 — `cand-b1d7664-repro.xml` |
| 기존 `AiPipelineTest` | 11/11(앞선 근거) | 11/11 — `cand-b1d7664-aipipeline-*.xml` |
| 총괄 `N11Review` 6입력 | 3건 불일치, 종료 1 | 6/6, 종료 0 |

경계 시험 27건은 모두 실제 출처를 상위 수집기(`collectFromSourceManual`)에 붙여 결과, `수집 완료`/`수집 미완료` 기록, ERROR 0줄, 저장 여부, `last_fetched_at` 을 본다.

- **업비트 거부 8건**(수정 전 8건 모두 실패): `success:false` 4가지(빈 배열, 항목 1, `data.list` 항목, 배열 없음), boolean 아님 4가지(`"true"`, `1`, `null`, `"false"`). 항목이 있어도 저장 호출이 없음을 확인한다.
- **업비트 호환 6건**(수정 전에도 통과): `success:true` 빈 배열·1건, `success` 없이 `data.notices`·`data.list`·최상위 `notices`·빈 배열.
- **RSS 거부 7건**(수정 전 5건 실패): `html` 아래 channel, `html` 아래 channel·item, `rss` 손자 channel, 다른 네임스페이스 `RDF`, 접두어 붙은 `rss`. 나머지 2건(`rss` 아래 channel 없음, Atom)은 98bbeda 에서도 거부됐다.
- **RSS 유지 6건**(수정 전에도 통과): 2.0 빈 channel·1건, 0.91 1건, 미선언 `dc` 접두어 1건, 1.0(RDF) 빈 channel·1건.

기존 167건 점검 시험, 전체 리허설, 전체 backend 시험은 하지 않았다.

## 한계

- **실제 출처 미확인**: 현재 RSS 4개(coindesk·bloomberg·tokenpost·blockmedia)와 업비트 공지의 실제 응답은 외부 접속 없이 읽지 않았다. 위 결과는 대역 입력에 대한 판정이다. 실제 출처가 이 구조를 따르는지 검증한 것이 아니다. 어떤 RSS 출처가 Atom 이나 다른 루트를 준다면 그 출처는 `INVALID_RESPONSE` WARN 으로 기록된다. 그래도 ERROR 는 늘지 않고 주기도 바뀌지 않는다.
- **업비트 `success` 의미**: `success` 가 있고 true 일 때의 동작은 기존과 같다. 실제 업비트가 실패를 다른 필드로만 알리는 경우는 이번 범위가 아니다.

## 파일

- `pre-98bbeda-shape.xml`, `cand-b1d7664-*.xml`: JUnit 결과 원본이다. `hostname` 값만 `redacted` 로 바꿨다.
- `n11review-before.*`, `n11review-after.*`: 총괄 실행기 출력 원본과 종료 코드.
- `98bbeda..b1d7664.diff`, `ea44900..b1d7664.diff`: `git diff` 출력.
- `.gitattributes`(`* -text`), `SHA256SUMS`.

## 남은 것

- **운영 적용**: 총괄 검토 뒤 판단한다. 98bbeda 와 b1d7664 모두 운영에 설치하지 않았다.
- **N03 회복 확인**: 오늘 22:00 정기 보고를 읽을 때 보존 로그와 함께 확인한다.
- **사용 시간**: 10:58 KST 실패 먼저 시험 실행부터 11:00 보완 후보 푸시까지다. 근거와 문서 작성을 합쳐 약 0.3시간이다.
- 이 근거를 담은 main 커밋의 SHA 는 여기에 적지 않는다(N06).
