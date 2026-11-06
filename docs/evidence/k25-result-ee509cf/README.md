# 개입 #25(N19) 운영 실행 결과 — 실행 출처 342f461 §92(UB=92), 후보 ee509cf (개발 실행 결과·총괄 판정 전)

2026-11-06 KST. 총괄의 기존 #25 승인(v1.125~v1.128에서 유지)에 따라, 개발 에이전트가 이 PC에서 `ssh cat-prod`로 서버에서 실행했다. **운영 반영 완료 판정과 F 변수·F1·N06 핀 갱신은 총괄 검토 뒤다.** N20·f6868a5·UB=90은 섞지 않았다.

## 실행 방식

- **블록 출처**: `342f461359b9e3c9d5941f6df95b905b65dce9a7:docs/LIMITED_LIVE_TEST_PLAN.md` 원문 추출본이다(`blocks/blocks.json`, 141블록 중 F·G·U변수·U0~U4·U뒤관측·U되돌림).
  - 커밋 전 이 근거의 블록 파일 열 개 sha256을 blocks.json과 다시 대조했다. 모두 같다(F `2a2a8fcd`, G `4d7ff02a`, U변수 `1c0f5e61`, U0 `8930a016`, U1 `41870d97`, U2 `376045fb`, U3 `095a0128`, U4 `6681c5e4`, U뒤관측 `ff0242e1`, U되돌림 `cc8ada0b`).
- **보내는 방식**: `blocks/run.sh`가 매번 새 셸에서 F·G·U변수 뒤에 해당 블록을 붙여 `bash -c` 한 번으로 보냈다. 표준 입력은 비웠다. 블록 내용은 고치지 않았다.
- **운영자 확인**: U2 바로 전에 1회 받았다. U1 결과를 구체적으로 제시하고 "재기동 진행" 답을 받았다(`out/operator-confirm.txt`). 이전 승인은 다시 쓰지 않았다.
- **U되돌림·U1-되돌림**: 쓰지 않았다. 되돌림 조건이 나오지 않았다.
- **하지 않은 것**:
  - 서버 `main` pull·checkout. U1 블록의 후보 SHA 체크아웃만 했다.
  - 세션 시작·재시작.
  - 풀·한도·DB 설정·스키마·점검 설치본·장부·cron 변경.
  - 수집 API 호출, 강제 실행, 이상 주입.
  - F 변수·F1·N06 갱신.

## 단계별 결과

| 단계 | 시각(KST) | 결과 | 원문 |
|---|---|---|---|
| 직전 장부·정기 보고(읽기만) | 10:05:17 | 09:30 장부·10:00 보고 대조. 새 미해명 이상·⚪ 없음. 19:41 생성 공백 표지 사라짐. 리셋 1건은 기존 미분류 🔴, BLAST는 기존 분류 그대로 | [n03-1106-1000](../n03-1106-1000/README.md), `out/prev-report-1000.txt` |
| U0 | 10:10:26 → 10:10:34 | 통과 | `out-U0.txt` |
| U1 | 10:10:49 → 10:12:05 | 통과. jar 차이가 기대와 같다(아래) | `out-U1.txt` |
| 운영자 확인 1회 | U1 뒤 ~ 10:32 | 재기동 진행 | `out/operator-confirm.txt` |
| U2 | 10:32:36 → 10:32:56 | 통과. backend만 재생성했다. 기동 시각(StartedAt) `2026-11-06T01:32:41.140275884Z` | `out-U2.txt` |
| U3(deploy) | 10:38:41 → 10:38:45 | 통과(기동 뒤 약 6분) | `out-U3.txt` |
| U4 | 11:03:13 → 11:03:18 | 통과(기동 뒤 약 30분 35초) | `out-U4.txt` |
| U뒤관측 | 11:40:14 → 11:40:16 | 종료 0(기동 뒤 약 67분). 60분 출처 첫 회차(11:35 무렵) 뒤에 1회 실행했다 | `out-U뒤관측.txt` |
| 경보 보완 읽기 | 11:40:22 → 11:40:23 | U4와 같은 명령(`start_66.py alerts --since <t-deploy.utc> --no-mark`)으로, 기동 뒤 첫 정시 관측(11:19 KST)을 읽었다 | `out-alerts-supp.txt` |

### 핵심 값

- **U0 — 배포 전**
  - HEAD 7a944723, 실행 이미지 e51b512a(#24)이며 `~/maint-n17/image-new.txt`와 같다.
  - DB: TimeZone UTC, 세션 `66 | 63 | 3 | -`, Q3~Q9 기대값.
  - BTC `true|11-06 09:00|11-06 09:00|20/20`.
  - 기준선: 뉴스 ERROR 1줄(22:00:29 CryptoPanic Connection reset, 기존 미분류), CryptoPanic HTTP 403 ×28(고정 한 줄·짝 28), HOLD 장기 대여 0·**연결 획득 시간 초과 0**, N15·N16·N17·N19 미관측.
  - 경보는 KRW-BLAST 24회(기존 분류)다.
  - `git fetch`는 U0 블록 원문의 단계다. 원격 브랜치 목록만 갱신했다.
- **U1**
  - HEAD는 ee509cf로 바뀌었다.
  - 새 이미지 `sha256:200f2f5727e90204574fd9965a5cc06dfc5d317e98c13b200de2ac7201378ae6`.
  - app-old.jar `bb67b521…`, app-new.jar `89b4ca17…`.
  - jar 차이는 `LiveTradingService$1`·`$PriceSnapshot`·`LiveTradingService`의 3항목이고 `(끝) 881 881`이다. 기대와 같다.
  - 재기동은 없었다. 이때 실행 backend는 e51b512a·재시작 0이었다(읽기 확인).
- **U2**
  - backend `200f2f57…`.
  - db·redis·db-backup·frontend의 이미지와 기동 시각은 배포 전과 같다.
- **U3·U4**
  - 기동 완료 줄이 있고 마이그레이션은 없다("Schema public is up to date").
  - 매도 격리 점검 이상 없음. BTC 관문 판정 불가 0.
  - Q3~Q9 기대값. 세션 그대로. BTC 지금 `true|11-06 10:00|…|20/20`.
  - 오류 파일 `errors-*-deploy/after30`은 모두 0줄이다.
  - 기동 뒤 ERROR 3줄은 모두 기동 직후 SignalQuality의 상장 폐지 404(KRW-BLAST·BONK·STORJ)다. 블록의 기존 404 제외식으로 걸렀다.
  - HOLD 장기 대여 0, **연결 획득 시간 초과 0**. 시작 Catchup 01:32:58.152 → 01:33:10.043 완료.
  - N15·N16·N17·N19는 미관측 ⚪이다(유도하지 않는다).
  - U4의 "경보 (지금)" 구간은 관측 0회 ⚪였다. 기동 01:32Z 뒤 첫 정시 관측이 02:19Z라서다. 보완 읽기에서 그 회차를 확인했다.
- **U뒤관측**
  - 뉴스: 7개 출처 중 6개가 완료다(RSS 4·COINGECKO·UPBIT_NOTICE 2회). CryptoPanic은 미완료 2(HTTP 403, 기존과 같음).
  - 뉴스 ERROR 0줄. HTTP 403 ×2(고정 한 줄·짝 2).
  - HOLD 0·시간 초과 0. N15·N16·N17·N19 미관측.
- **경보 보완**: 11:19 KST 관측 1회. 🔴는 KRW-BLAST 1종(기존 분류), 🟢 21·⚪ 0.

### P0 확인 (총괄 v1.128 §96 — 일반 ERROR 멈춤과 별도로 원문 확인)

- U3·U4·U뒤관측에서 저장한 로그 세 개(`app-deploy.log` 416줄, `app-after30.log` 1178줄, `app-news-20261106-1140.log`)에서 `Connection is not available`을 직접 셌다. **모두 0건**이다. 누수 경고도 0건이다.
- 이 로그들은 서버 `~/maint-n19`에 보존돼 있다. 크기 때문에 이 근거에는 넣지 않았다. sha256은 `out/server-list-sha.txt`에 있다.

### 관측 (멈춤 아님)

- `Upbit ticker 404 (상장폐지 코인 포함 가능성): markets=KRW-APENFT — 개별 조회로 폴백` WARN이 기동 뒤 2회(10:40·11:19 KST, http 요청 스레드) 찍혔다.
  - 문구는 7a94472에도 있는 UpbitRestClient 기존 코드다. jar 차이 3항목 밖이다.
  - 배포 전 로그(`backend-before.log`)에도 같은 줄이 17회(매시 :19 무렵) 있다.
  - 이번 배포로 새로 생긴 것이 아니다. 분류·코드는 바꾸지 않았다.

## 복구 기준(바꾸지 않음)

- 이전 복구 태그는 모두 그대로다.
  - `autotrader-backend:7a94472` = e51b512a(#24, U1이 만듦)
  - `1103f48` = 9818254c, `fe01207` = 9cb5d84f, `6995c52` = 67294e80, `b1d7664` = fd3ac351, `ea44900` = 61804654
- `latest`는 200f2f57이다.
- 복구 기준은 7a94472 / e51b512a…이다. 실제 되돌림은 명시된 "되돌림 필요" 또는 총괄 판단 때만 한다.

## 파일

- `out-U0~U뒤관측.txt`·`out-alerts-supp.txt`: 로컬 출력 원문.
- `maint-n19/`: 서버 기록 폴더에서 로그·jar를 뺀 73개 파일.
  - `out/server-list-sha.txt`: 서버 목록과 sha256.
  - `out/local-copy.sha256`: 로컬 사본 sha256. 서버 값과 대조해 차이 0이다.
- `blocks/`: 실행 블록·추출 해시·`run.sh`.
- `out/operator-confirm.txt`, `out/prev-report-1000.txt`.
