# O1·O3+N21 통합 후보 운영 실행 결과 — 출처 9aa617d D3(UB=O13N21D3), 후보 b5cd1d0 (개발 실행 결과·총괄 판정 전)

2026-11-10 KST. 총괄 v1.140 승인([o13n21-accept-9aa617d](../o13n21-accept-9aa617d/README.md))에 따라 개발 에이전트가 이 PC에서 `ssh cat-prod`로 서버에서 실행했다. **운영 반영 완료 판정과 F1·N06 핀 갱신은 총괄 검토 뒤다.** D1·D2·N21 단독 v1/v2는 쓰지 않았다.

## 실행 방식

- **블록 출처**: `9aa617d134e7bd51c2f97d16d0815cbdc6623a4e`에서 `blocks/extract.py`로 꺼냈다.
  - F: `docs/LIMITED_LIVE_TEST_PLAN.md`의 ```bash 색인 12
  - G: 같은 문서의 색인 27
  - D3 열 블록: `docs/evidence/o1o3-n21-d3-b5cd1d0/d3/blocks-d3/*.sh` 커밋 블롭. 같은 커밋의 `blocks-d3.sha256`과 대조했다.
  - 12/12 같다(`blocks/blocks.json`): F `eeab6c10`, G `4d7ff02a`, U변수 `0072c305`, U0 `7874a44e`, U1 `3df3e166`, U1되돌림 `73b7c46f`, U2 `7ad3574f`, U3 `5a53c33a`, U4 `abf0fc55`, U5 `889fe3f6`, U되돌림 `72adacd2`, U뒤관측 `01c93d25`.
  - `bash -n` 12/12, U변수에 `UB=O13N21D3`이 있다. 커밋 전에 이 근거의 블록 파일 12개를 blocks.json과 다시 대조했고 모두 같다.
- **보내는 방식**: `blocks/run.sh <블록> deploy`를 썼다.
  - 매번 실행 전 blocks.json 해시 12개를 다시 확인한다.
  - F·G·U변수 뒤에 해당 블록을 붙여 `bash -c` 한 번으로 보낸다. 표준 입력은 비우고 블록 내용은 고치지 않는다.
  - 출력은 `out-<블록>-deploy.txt`다. 같은 이름이 있으면 덮어쓰지 않고 종료 2로 멈춘다. 원격 종료값은 그대로 돌려준다.
  - 자체 시험은 가짜 ssh로만 했다(`blocks/runner-selftest.txt`, 8개 확인 모두 기대대로).
  - 실행 틀 거절(종료 2)·시도 꼬리는 한 번도 없었다.
- **운영자 확인**: U2 바로 전에 1회 받았다(`out/operator-confirm.txt`). 제시 내용은 `out/u1-present.txt`(12:45:05 KST 수집)이고, 답은 "재기동 진행"이었다.
- **U1되돌림·U되돌림**: 쓰지 않았다. 되돌림 조건이 나오지 않았다.

## 단계별 결과

| 단계 | 시각(KST) | 결과 | 원문 |
|---|---|---|---|
| 직전 보고·상태(읽기만) | 12:42:32 | 최신 보고는 `20261110-1000.txt` 그대로, `last_send` "10:00:04 KST 보고 전송 성공 · 🟢 점검 항목 정상". 4개 파일 sha256이 10:12 읽기 사본과 같다 | `pre/report-reread.txt` |
| U0 | 12:42:43 → 12:42:58 | 통과(rc=0) | `out-U0-deploy.txt` |
| U1 | 12:43:19 → 12:44:47 | 통과(rc=0). U0 뒤 1분 안, :14~:24 밖 | `out-U1-deploy.txt`, `out/u1-present.txt` |
| 13:19 cron 회차 | 13:19:01 시작 → 13:20:36 관측 행 | U1 뒤 첫 :19 회차. 새 `start_66.py`(b5cd1d0)로 돌았다 | U5 원문 |
| 운영자 확인 1회 | 13:48 | 재기동 진행 | `out/operator-confirm.txt` |
| U2 | 13:48:38 → 13:48:59 | 통과(rc=0). backend만 재생성 | `out-U2-deploy.txt` |
| U3(deploy) | 14:04:58 → 14:05:03 | 통과(rc=0). **기동 뒤 약 16분 — 지시한 5~10분 창보다 늦었다**(아래) | `out-U3-deploy.txt` |
| U5 | 14:05:15 → 14:05:16 | 통과(rc=0) | `out-U5-deploy.txt` |
| U4 | 14:19:41 → 14:19:47 | 통과(rc=0). 기동 뒤 약 31분 | `out-U4-deploy.txt` |
| U뒤관측 | 15:00:10 → 15:00:12 | 종료 0. 기동 뒤 약 72분, 60분 출처 첫 차례(계산상 14:51 무렵) 뒤 1회 | `out-U뒤관측-deploy.txt` |
| 최종 상태(읽기만) | 15:00:52 | 아래 대조 | `out/final-state.txt` |

### U3 지연 — 지시와 다른 점

- U2가 13:48:59에 끝난 직후 U5(읽기 전용)를 먼저 실행하려 했다. 이 PC의 Claude Code 자동 권한 분류기가 이를 "운영 배포"로 보고 거부했다.
- 거부를 우회하지 않고 멈춰 운영자에게 알렸다. 운영자가 U3·U4·U뒤관측·U5 `deploy` 실행만 허용하는 로컬 권한 규칙을 넣고 승인했다. 그 뒤 14:04:58에 U3를 실행했다.
- 그래서 U3는 기동(13:48:42) 뒤 **약 16분**에 돌았다. 지시한 5~10분 창은 지켜지지 않았다. 블록 자체는 "5분 이상"만 확인하고 상한은 없어서 블록 판정은 통과다.
- 이 사이 13:48:42~14:04:58 구간의 로그는 U3가 `--since t-deploy`로 모두 읽었다. 이 구간을 빠뜨린 것은 아니다. 다만 시각별 안전 확인이 늦었다는 사실은 그대로 남긴다.
- 권한 규칙은 운영자 로컬 설정(`.claude/settings.local.json`)이다. 이 커밋에는 넣지 않았다.

### 시각 구분

- 정비 시작 12:42:45 KST(Q8 기준). U1 checkout 직전 `t-apply` 03:43:21Z(12:43:21 KST). 재기동 명령 직전 `t-deploy` 04:48:42Z(13:48:42 KST).
- 새 backend 컨테이너: Created `2026-11-10T04:48:42.774887574Z`, StartedAt `2026-11-10T04:48:44.119959397Z`.
- 앱 기동 완료 04:49:00.349Z("Started … in 15.378 seconds"). 시작 Catchup 04:49:00.703 → 04:49:12.682.

## 핵심 값

### U0 — 배포 전

- HEAD `1899b49f633f…`, 추적 변경 0. `git fetch`로 origin/main이 9aa617d가 됐다(원격 목록 갱신만).
- 실행 backend `426a09a2…`, StartedAt 2026-11-06T04:36:38Z.
- DB TimeZone UTC. 세션 `66 | 63 | 3 | -`. Q3~Q9 기대값.
- BTC `true|11-10 11:00|11-10 11:00|20/20`.
- 기준선
  - 뉴스 ERROR 1줄: `09:10:23.613 … CryptoPanicSource - [CryptoPanicSource] 수집 실패: Connection reset`. 기존 미분류 🔴 그대로이며 해소로 쓰지 않는다.
  - HTTP 비200: CryptoPanic 403 ×188, UpbitNotice 403 ×7·504 ×1. 모두 고정 한 줄이고 짝이 맞는다.
  - HOLD 장기 대여 0, **연결 획득 시간 초과 0**. N15·N16·N17·N19·N20 미관측.
  - 경보: 최근 1일 24회 모두 KRW-BLAST 1종(기존 분류, 상장 폐지 마켓).
- freshness 회차 없음(pgrep 1). 기준 위치 `1573827 136814 38306 1039`.

### U1 — checkout·빌드·jar

- 회차 없음(pgrep 1). 적용 기준 위치 `1573827 136852 38306 1039`.
- HEAD → `b5cd1d0d47ea68ffe6ff476b89cf9fca5bef1b88`, 추적 변경 0. `start_66.py` `68d441ce…`, 새 시험 파일 있음.
- **새 이미지** `sha256:52e12428a60df5413d6e495024220bbd4c8fa07830549556f0b47bbe6ad39055`(= `~/maint-o13n21/image-new.txt`).
- **서버 jar**
  - app-old.jar `10bf86285c6e5eaf7cbe740f060eff852cc943128e521eb3e2c0a42bd2db69a5`. 1899b49 운영 jar로, k26 기록의 app-new와 같다.
  - app-new.jar `4a6853689be9c36a984b41e80b8c36b8885cd894707768bfd3ad336c6376239b`
- jar 차이 3항목: `DynamicTradingService.class`, `LiveTradingService$PriceSnapshot.class`, `LiveTradingService.class`, `(끝) 881 881`. 승인 문서의 기대와 같다.
  - 서버 jar 해시는 로컬 bootJar `a7ef759f…`와 다르다. 빌드 시각 등 파일 메타데이터 차이다. 판정은 항목 비교로만 한다.
- 보존 태그(`out/u1-present.txt`): 새로 만든 `autotrader-backend:1899b49` = `426a09a2…`(복구 기준).
  - 기존 태그는 그대로다: ee509cf `200f2f57`, 7a94472 `e51b512a`, 1103f48 `9818254c`, fe01207 `9cb5d84f`, 6995c52 `67294e80`, b1d7664 `fd3ac351`, ea44900 `61804654`.
  - 이때 실행 backend는 `426a09a2…`·재시작 0이었다.
  - 이 읽기에서 쓴 임시 pgrep이 자기 `bash -c` 줄(PID 3309043)에 맞았다. 실제 회차가 아니다. 블록의 pgrep 판정과는 무관하다.

### U2

- backend `52e12428…`, StartedAt 04:48:44.119959397Z. HEAD b5cd1d0.
- db·redis·db-backup·frontend 이미지와 기동 시각은 U0와 같다.

### 안전(U3·U4)

- 기동 완료 줄이 있다. 마이그레이션 없음("Schema "public" is up to date"). V94.
- 공유 풀·위험 한도 로그가 기대대로 있다(블록 검사 통과).
- 매도 격리 점검 이상 없음. BTC 관문 판정 불가·재개 줄 없음. 판정 불가 대상 0.
- Q3~Q9 기대값. 세션 `66 | 63 | 3 | -` 그대로. SID 203 검사 통과.
- BTC: U3 `true|11-10 13:00|11-10 13:00|20/20`, U4 같은 값.
- 오류 파일(`errors-deploy`·`errors-after30`) 0줄. 세 마켓 404·뉴스 밖 ERROR 없음.
- HOLD 장기 대여 0, **연결 획득 시간 초과 0**.
- N15·N16·N17·N19·N20은 미관측 ⚪다. 유도하지 않았다. 운영 반영은 HEAD·이미지·jar로만 확인한다.

### 뉴스(U3·U4·U뒤관측)

- U3·U4 시점(14:05·14:19)에는 모든 출처가 "계산상 차례 전"이었다.
- U뒤관측(15:00)
  - blockmedia·bloomberg·coindesk·tokenpost RSS, coingecko: 각 완료 1·미완료 0
  - upbit_notice: 완료 2
  - cryptopanic: 완료 0·미완료 2(`결과=FAILED 종류=HTTP_403`, 마지막 05:52:04Z). 배포 전 403 반복과 같은 형태다.
- 기동 뒤 뉴스 출처 ERROR 0줄.

### HTTP(N12)

- U3·U4: 비200 미관측.
- U뒤관측: `CryptoPanicSource HTTP 403 ×2 — 고정 한 줄 2 · 본문 형식/연속 줄 0 · 같은 스레드 상위 FAILED/HTTP_n/0건 짝 2 · 짝 미확인 0`.

### 경보(U4)

- 경보 종료코드 1(🔴 경보 있음, 블록 규칙상 이것만으로 멈추지 않음).
- 지금 구간(04:48:42Z~05:19:48Z): 관측 1회 `🔴 11-10 05:19 1종: KRW-BLAST`, 현재 🟢 21 / ⚪ 0 / 🔴 1.
- 배포 전 기준선(최근 1일): 24회 모두 KRW-BLAST 1종.
- 새 🔴·⚪ 종류는 없다.

### N21 — U5 원문

```
freshness 회차 없음(pgrep 종료 1)
2026-11-10 14:05:17 KST
b5cd1d0d47ea68ffe6ff476b89cf9fca5bef1b88
(끝)
기준 → 지금: 시스템 기록 inode 1573827→1573827 줄 136852→140004 · 실행 기록 줄 38306→38347 · 관측 행 1039→1040
== 적용 뒤 cron 시작 줄 1개
Nov 10 13:19:01 yhdeabba CRON[3324685]: (manicstr) CMD (cd ~/crypto_trader/AutoTrader && /usr/bin/python3 scripts/prospective/start_66.py freshness --save >> scripts/prospective/freshness_runs.log 2>&1)
== 적용 뒤 실행 기록 41줄 · 그중 Traceback 0줄
== 적용 뒤 관측 행 1개
2026-11-10T04:20:36.331905+00:00 🟢21 ⚪0 🔴1 재확인 1 칸 같음 N21 상태 0
✅ U5 — 적용 뒤 cron 시작 줄·새 관측 행·새 Traceback 0 확인(시작 줄과 행의 1:1 짝은 미확정). 재조회 실패 경로(net·404)는 자연 발생 전까지 미관측이다
```

- 새 관측 행의 🔴1이 기존 항목인지 읽기만으로 확인했다(`out/u5-red-reread-2.txt`).
  - 01:19·02:19·03:19 행과 04:20:36 행 모두 `redCoins ['KRW-BLAST']`다.
  - 04:20 행만 `rechecked ['KRW-CVC']`다. 재확인 경로가 실제 회차에서 한 번 돌았다는 뜻이다.
  - 재조회 실패(net·404) 경로는 자연 발생 전까지 미관측이다.
- U5는 14:19 회차 전에 돌았다. 그래서 구간 안 cron 시작 줄은 13:19 한 개다.
- 시작 줄과 관측 행의 1:1 짝(PID)은 확정하지 않는다.

### P0 확인 (일반 ERROR 멈춤과 별도로 원문 계수)

`~/maint-o13n21`의 로그 다섯 개에서 직접 셌다(`out/final-state.txt`). **연결 획득 시간 초과(`Connection is not available`)는 모두 0건이다.** 누수 경고(`Connection leak detection`)도 0건이다.

| 로그 | 줄 수 |
|---|---|
| `backend-before.log` | 222909 |
| `app-deploy.log` | 779 |
| `app-after30.log` | 1188 |
| `app-news-20261110-1500.log` | 2393 |
| `build.log` | 89 |

## 최종 대조 (15:00:52 KST, 읽기만)

| 항목 | 지금 | 기준 | 판정 |
|---|---|---|---|
| HEAD | `b5cd1d0d47ea…`, 추적 변경 0 | 후보 b5cd1d0 | 같음 |
| `start_66.py` | `68d441ce…` | b5cd1d0 판 | 같음 |
| backend | `52e12428…`, 재시작 0, running | image-new | 같음 |
| db·redis·db-backup·frontend | 이미지·StartedAt 그대로, 재시작 0 | U0 | 같음 |
| latest 태그 | `52e12428…` | image-new | 같음 |
| 복구 태그 | `autotrader-backend:1899b49` = `426a09a2…`(= image-before) | U1 | 같음 |
| crontab | `f2dce280…` | f2dce280 | 같음 |
| 설치본 | INSTALLED_SHA c669ccd, daily_check.sh `ff0a5d7d…`, daily_checks.sql `653b22ba…` | U변수 CK·CKSH·CKSQL | 같음 |
| 장부 | SID_LEDGER_SHA 8bfe8a9, sid_ledger.sql `02a85850…` | U변수 LS·LSUM | 같음 |
| Flyway | V94 | V94 | 같음(U3 검사) |

- 최종 읽기에서 `autotrader-backend:b5cd1d0` 태그도 조회했다. "No such image"가 나왔다. D3는 이 태그를 만들지 않으므로 기대와 같다.

## 사본과 대조

- `maint-o13n21/`은 서버 `~/maint-o13n21`에서 jar·로그를 뺀 98개 파일의 사본이다.
  - 로컬 sha256(`out/local-copy.sha256`)이 서버 목록과 98/98 같다.
  - 서버에만 있는 7개는 jar 2개와 로그 5개다. 크기 때문에 서버에만 둔다. sha256은 `out/final-state.txt` 끝에 있다.
- 토큰 형태 문자열 0건(`out/token-check.txt`). 비밀번호·키 형태 문자열도 0건이다.
- `pre/report-reread.txt`는 실행 직전 보고·전송 결과 읽기다.

## 하지 않은 것

- 서버 `main` pull·checkout. U0의 `git fetch`와 U1의 후보 SHA checkout만 블록 원문대로 했다.
- 세션 시작·재시작. 576/577/578은 STOPPED 그대로다.
- 풀·한도·DB 설정·스키마·설치본·장부·cron 변경.
- 수집 API 호출, 강제 회차, 이상 주입. 회차·프로세스 강제 종료.
- F1·N06 핀·최종 S 변경. 과거 근거 수정.

## 판단 요청

1. 다음 값으로 O1·O3+N21 운영 반영을 판정해 주기 바란다.
   - 실행 HEAD b5cd1d0
   - 실제 이미지 `52e12428…`
   - 서버 jar 3항목·881 881
   - 안전 U3·U4, 뒤 관측, U5
2. **U3가 기동 뒤 16분에 실행된 것**(5~10분 창 미준수)을 판정에 어떻게 반영할지 정해 주기 바란다.
3. 판정되면 D3 README §4 예정표대로 F1·N06 핀 갱신을 지시해 주기 바란다.
   - NEW b5cd1d0, IMGF1 `52e12428…`, 경로 `~/maint-o13n21/image-new.txt`, 복구 기준 1899b49 / `426a09a2…`
4. O1·O3 이상 가격과 N15~N20 자연 발생 줄, N21 재조회 net·404는 미관측이다.
5. 22:00 N03은 22:12 이후 별도로 원본을 읽는다.
   - 오늘 재기동(Created 04:48:42Z·StartedAt 04:48:44Z) 기준으로 계획 기동 🔴와 로그 범위 ⚪가 나올 것으로 본다. 실제 원문으로 판정한다.

## 유지

- SID 203 `CREATED`·20,200, 최종 S 미확정, 12-01 08:50 KST 목표.
- 설치 c669ccd·V94·한도·풀·장부 8bfe8a9·cron f2dce280은 그대로다.
- 11-09 접속 장애·218분 공백 원인, CryptoPanic 미분류 🔴, 휴대폰 수신은 미확정이다. 이번 반영이 이를 해결했다는 뜻이 아니다.
