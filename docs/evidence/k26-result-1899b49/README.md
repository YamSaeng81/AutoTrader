# 개입 #26(N20) 운영 실행 결과 — 실행 출처 de5a7e4 §98(UB=98), 후보 1899b49 (개발 실행 결과·총괄 판정 전)

2026-11-06 KST. 총괄 v1.131 승인([k26-accept-de5a7e4](../k26-accept-de5a7e4/README.md))에 따라 개발 에이전트가 이 PC에서 `ssh cat-prod`로 서버에서 실행했다. **N20 운영 반영 완료 판정과 F 변수·F1·N06 핀 갱신은 총괄 검토 뒤다.** D1·D2·§92 블록은 쓰지 않았다.

## 실행 방식

- **블록 출처**: `de5a7e4d0d5d4f6ac6820c57ce5a5dce15e8642a:docs/LIMITED_LIVE_TEST_PLAN.md` 원문을 `blocks/extract.py`로 꺼낸 추출본이다(`blocks/blocks.json`). F(색인 12)·G(27)·§98 U 여덟 블록(141~148)이다.
  - 커밋 전에 이 근거의 블록 파일 열 개 sha256을 blocks.json과 다시 대조했다. 모두 같다: F `0975f7c0`, G `4d7ff02a`, U변수 `761c8799`, U0 `1bdaffb2`, U1 `068b5c32`, U2 `c7b7ec9b`, U3 `b32d26fc`, U4 `61084acb`, U뒤관측 `7aa744f6`, U되돌림 `0f950151`.
- **보내는 방식**: `blocks/run.sh <블록> deploy`가 매번 실행 전 해시를 확인했다. 그다음 새 셸에서 F·G·U변수 뒤에 해당 블록을 붙여 `bash -c` 한 번으로 보냈다. 표준 입력은 비웠고 블록 내용은 고치지 않았다.
  - 출력은 `out-<블록>-deploy.txt`다. 실행 틀 거절(종료 2)과 중복 출력은 한 번도 없었다. 시도 꼬리도 쓰지 않았다.
- **운영자 확인**: U2 바로 전에 1회 받았다(`out/operator-confirm.txt`).
  - U1 결과를 구체적으로 제시했다: 새 전체 이미지, 서버 jar 두 해시·2항목·881 881, 보존 태그 두 개, 실행 중인 backend 200f2f57·재시작 0.
  - 답은 "재기동 진행"이었다. #25나 이전 확인은 다시 쓰지 않았다.
- **U되돌림·U1-되돌림**: 쓰지 않았다. 되돌림 조건이 나오지 않았다.
- **하지 않은 것**
  - 서버 `main` pull·checkout. U0의 `git fetch`(원격 목록 갱신)와 U1의 후보 SHA checkout만 블록 원문대로 했다.
  - 세션 시작·재시작.
  - 풀·한도·DB 설정·스키마·점검 설치본·장부·cron 변경.
  - 수집 API 호출, 강제 실행, 이상 주입.
  - F 변수·F1·N06 갱신. `~/maint-n19`와 과거 기록 수정.

## 단계별 결과

| 단계 | 시각(KST) | 결과 | 원문 |
|---|---|---|---|
| 직전 보고·상태(읽기만) | 13:32:19 | 서버 시각 13:32으로 허용 창 안이다. 최신 보고는 10:00 그대로이고 그 뒤 새 보고는 없다. `last_send` sha `ccf25e96…`가 이미 읽은 [n03-1106-1000](../n03-1106-1000/README.md) 값과 같다. 설치본 c669·cron f2dce280 그대로, HEAD ee509cf, 실행 이미지 200f2f57·재시작 0(#25 상태 그대로). 새 미해명 이상은 없다 | `pre/` |
| U0 | 13:32:31 → 13:32:38 | 통과 | `out-U0-deploy.txt` |
| U1 | 13:32:51 → 13:34:01 | 통과. jar 차이가 기대와 같다(아래). U0 뒤 30분 안에 시작했다 | `out-U1-deploy.txt`, `out/u1-present.txt` |
| 운영자 확인 1회 | 13:36:32 | 재기동 진행 | `out/operator-confirm.txt` |
| U2 | 13:36:33 → 13:36:53 | 통과. backend만 재생성했다 | `out-U2-deploy.txt` |
| U3(deploy) | 13:43:07 → 13:43:11 | 통과(기동 뒤 약 6분 30초) | `out-U3-deploy.txt` |
| U4 | 14:07:15 → 14:07:20 | 통과(기동 뒤 약 30분 40초) | `out-U4-deploy.txt` |
| U뒤관측 | 14:45:16 → 14:45:18 | 종료 0(기동 뒤 약 69분). 60분 출처의 첫 차례(14:39 무렵) 뒤에 1회 실행했다 | `out-U뒤관측-deploy.txt` |
| 경보 보완 읽기 | 14:45:26 | U4와 같은 명령(`start_66.py alerts --since <t-deploy.utc> --no-mark`)으로 기동 뒤 첫 정시 관측(14:19 KST)을 읽었다 | `out-alerts-supp-deploy.txt` |

### 시각 구분

- 정비 시작은 13:32:33 KST(`t-before`)다. 빌드 시작은 13:32:53(`t-build`)이고, 재기동 명령 직전은 13:36:36(`t-deploy`, 04:36:36Z)이다.
- 새 backend 컨테이너의 Created는 `2026-11-06T04:36:36.916419403Z`다. StartedAt은 `2026-11-06T04:36:38.159366843Z`(13:36:38 KST)이고 U2 출력의 시각이 이것이다.
- 14:46:57 KST에 다시 읽었다(`out/final-state.txt`): 이미지 426a09a2, 재시작 0, running, HEAD 1899b49.
- 앱 기동 완료는 04:36:54.292Z다("Started … in 15.248 seconds"). 시작 Catchup은 04:36:54.611 → 04:37:06.355에 끝났다.

### 핵심 값

- **U0 — 배포 전**
  - HEAD ee509cf855ff…, 실행 이미지 `200f2f57…`이며 IMGOLD·`~/maint-n19/image-new.txt`와 같다.
  - DB: TimeZone UTC, 세션 `66 | 63 | 3 | -`, Q3~Q9 기대값.
  - BTC `true|11-06 12:00|11-06 12:00|20/20`.
  - 기준선: 뉴스 ERROR 0줄, CryptoPanic HTTP 403 ×5(고정 한 줄·짝 5), HOLD 장기 대여 0, **연결 획득 시간 초과 0**. N15·N16·N17·N19·N20은 미관측이다(ee509cf에는 N20 문구가 없다).
  - 경보는 최근 1일 24회 모두 KRW-BLAST 1종이다(기존 분류).
- **U1**
  - HEAD가 `1899b49f633f8aefdc66b15c42debdaba8b3852c`로 바뀌었다.
  - 새 이미지는 `sha256:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1`이다.
  - app-old.jar `89b4ca170921…`는 #25 서버 jar와 같다. app-new.jar는 `10bf86285c6e5eaf7cbe740f060eff852cc943128e521eb3e2c0a42bd2db69a5`이다.
  - jar 차이는 `HeldMarkRefresher`·`HeldMarkRefresher$Book` 2항목이고 `(끝) 881 881`이다. 기대와 같다.
  - 보존 태그: `autotrader-backend:ee509cf` = `200f2f57…`(IMGOLD), `autotrader-backend:7a94472` = `e51b512a…`. 이때 실행 backend는 200f2f57·재시작 0이었다(읽기 확인).
- **U2**
  - backend `426a09a2…`, HEAD 1899b49.
  - db·redis·db-backup·frontend의 이미지와 기동 시각은 배포 전과 같다.
- **U3·U4**
  - 기동 완료 줄이 있다. 마이그레이션은 없다("Schema public is up to date").
  - 매도 격리 점검 이상 없음. BTC 관문 판정 불가 0.
  - Q3~Q9 기대값, 세션 그대로. BTC는 U3 `12:00`, U4 `true|11-06 13:00|…|20/20`.
  - 오류 파일 `errors-*-deploy/after30`은 모두 0줄이다.
  - HOLD 장기 대여 0, **연결 획득 시간 초과 0**. N15·N16·N17·N19·N20은 미관측 ⚪이다(유도하지 않는다).
  - U4의 "경보 (지금)" 구간은 관측 0회 ⚪였다. 기동 04:36Z 뒤 첫 정시 관측이 05:19Z라서다. 보완 읽기에서 그 회차를 확인했다.
- **U뒤관측**
  - 뉴스: 7개 출처 중 6개가 완료다(RSS 4·COINGECKO 각 1회, UPBIT_NOTICE 2회). CryptoPanic은 미완료 2(HTTP 403, 기존과 같음).
  - 뉴스 ERROR 0줄, HTTP 403 ×2(고정 한 줄·짝 2).
  - HOLD 0·시간 초과 0. N15~N20 미관측.
- **경보 보완**: 14:19 KST 관측 1회. 🔴는 KRW-BLAST 1종(기존 분류)이고, 🟢 21·⚪ 0이다.

### P0 확인 (일반 ERROR 멈춤과 별도로 원문 계수)

- 저장한 로그 네 개에서 `Connection is not available`을 직접 셌다. **모두 0건**이다. `Connection leak detection`(누수 경고)도 0건이다(`out/server-list-sha.txt` 머리).
  - `backend-before.log` 5566줄
  - `app-deploy.log` 416줄
  - `app-after30.log` 1177줄
  - `app-news-20261106-1445.log` 2281줄
- 로그와 jar는 크기 때문에 서버 `~/maint-n20`에만 보존한다. sha256은 `out/server-list-sha.txt`에 있다.

### 관측 (멈춤 아님)

- #25 때 본 기존 `Upbit ticker 404 … KRW-APENFT` WARN은 기동 뒤 로그에 1회(`app-news-20261106-1445.log`) 있다. 기존 UpbitRestClient 문구이고 jar 차이 2항목 밖이다. 분류는 바꾸지 않았다.

## 사본과 대조

- `maint-n20/`은 서버 `~/maint-n20`에서 jar·로그를 뺀 77개 파일의 사본이다. 로컬 sha256(`out/local-copy.sha256`)이 서버 목록과 77/77 같다. 서버에만 있는 7개는 jar 2개와 로그 5개다(`build.log` 포함).
- 토큰 형태 문자열은 받은 파일 모두에서 0건이다.
- `pre/`는 직전 확인의 읽기 스크립트 원문(`read.sh`)과 결과다.

## 복구 기준(바꾸지 않음)

- 복구 기준은 ee509cf / `200f2f57…`(`autotrader-backend:ee509cf` 태그)이다. 그 이전 태그 7a94472 / `e51b512a…`도 남아 있다.
- 현재 F1·N06은 ee509cf / 200f2f57… / `~/maint-n19/image-new.txt` 그대로다. 갱신은 총괄 판정 뒤다.

## 판단 요청

- 실행 HEAD 1899b49, 실제 이미지 `426a09a2…`, 서버 jar 2항목·881 881, 안전 확인 U3·U4·뒤 관측으로 N20 운영 반영을 판정해 주기 바란다.
- 판정되면 F1·N06 핀을 1899b49 / `426a09a2…` / `~/maint-n20/image-new.txt`로 갱신하도록 지시해 주기 바란다.
- N20의 자연 발생 줄은 미관측이다.
- 22:00 N03은 별도로 원본을 읽는다. 새 기동(Created 04:36:36Z·StartedAt 04:36:38Z) 기준으로 계획 기동 🔴와 로그 범위 미확보 ⚪가 나올 것으로 본다.

## 유지

SID 203 `CREATED`, 최종 S 미확정, 12-01 08:50 KST 목표. 점검 c669·V94·한도·풀·장부·cron은 그대로다. 휴대폰 수신은 미확인이다.
