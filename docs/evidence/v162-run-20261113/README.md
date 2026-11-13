# v1.162 실행(404차 지시) — V161D4 U0 손실 관문 중단 (2026-11-13 14:16 KST, 개발 세션, 총괄 판정 전)

총괄 [v1.162 / 404차](../review-089e5e72/README.md) §4에 따라 고정 출처 **c8520c55**의 V161D4로 U0를 실행했다. **U0가 배포 전 손실 관문에서 멈췄다(rc=1).** U1·U2·DDL·UF2는 하지 않았다. 운영 서버의 HEAD·이미지·서비스·DB·cron·장부는 바뀌지 않았다.

## 1. 준비 (§4-1, 국소 확인)

- 추출: [`runner/extract.py`](runner/extract.py)가 c8520c55에서만 꺼낸다. F는 `LIMITED_LIVE_TEST_RUNBOOK.md` 첫 bash 블록, G는 `LIMITED_LIVE_TEST_PLAN_ARCHIVE_20261112.md`의 G 변수 한 블록(해시로 1개 선택), U 블록 10개는 `v161-20261113/d4/blocks-d4/` 커밋 블롭이다. **12/12 해시 일치**(F `b7e3fc2c`, G `4d7ff02a`, U변수 `47170e84`, U0 `052b73b9` …, [`runner/blocks/blocks.json`](runner/blocks/blocks.json)). `bash -n` 12/12, U변수에 `UB=V161D4` 1개.
- 전송 틀: [`runner/run.sh`](runner/run.sh). 직전 실제 사용 틀(o13n21-d3-result-b5cd1d0)에서 블록 이름만 바꾸고 종료값 기록을 더했다.
  - 원격 스크립트는 F·G·U변수·블록 원문을 붙인 것이다. 마지막 실행문은 원문 블록 `( … )`이고, 뒤에 `echo`를 붙이지 않는다.
  - 로컬에서 `PIPESTATUS[0]`로 ssh 종료값을 바로 잡아 출력 끝줄과 `out/exit-codes.txt`에 기록한 뒤 그 값으로 `exit`한다.
  - 출력 파일이 이미 있으면 덮어쓰지 않고 2로 끝난다. 블록 해시나 출처가 다르면 2로 끝난다.
- 가짜 ssh 자체 시험([`runner/runner-selftest.txt`](runner/runner-selftest.txt), 서버 접속 없음):
  - 결과: 성공 rc 0, 실패 rc 7, 같은 출력 거절 2(기존 파일 유지), 시도 꼬리 → 새 파일, 잘못된 구분·단독 금지 블록·없는 블록·해시 다름 → 각 2.
  - 원격 스크립트의 마지막 줄이 블록의 닫는 괄호임을 확인했다.
  - 실제 bash 재현: `( set -e; false )`로 끝나면 rc 1이고, 뒤에 `echo 종료 $?`를 붙이면 rc 0이다(지시서 §3의 우려와 같음).
  - 전체 회귀·빌드·대역은 다시 돌리지 않았다.

## 2. U0 직전 읽기 (§4-2)

- 14:16:06 KST(서버 시각). 최신 보고는 11-13 10:00 회차 그대로이고, `last_send`는 "10:00:04 KST 보고 전송 성공 · 🔴 이상 1건"이다. 보고·msg·raw·last_send 4개 sha256은 13:39 읽기 사본([n03-1113-1000](../n03-1113-1000/README.md))과 **4/4 같다**. 이 회차는 이미 읽었고 새 미해명은 없다. `~/maint-v161`은 없었다. 원문: [`pre/report-reread.txt`](pre/report-reread.txt).

## 3. U0 결과 — 손실 관문 판정 불가로 중단

원문 [`out/out-U0-deploy.txt`](out/out-U0-deploy.txt)(로컬 14:16:18 → 14:16:32, **rc=1**). 서버 `~/maint-v161`의 U0 기록 31개는 [`maint-v161/`](maint-v161/)에 사본으로 두었다. 서버 sha256과 31/31 같다([`server-sha256.txt`](server-sha256.txt)). `backend-before.log`(17MB)는 가져오지 않고 해시만 남겼다.

U0가 손실 관문 전까지 확인한 항목은 모두 통과했다.

| 항목 | 값 |
|---|---|
| 시작 창·직전 보고 | 14:16:19 KST, `20261113-1000.txt` |
| HEAD·추적 변경 | b5cd1d0d, 변경 없음 |
| 후보·차분 | 88f06345 있음, b5cd1d0의 자손, 102경로 일치 |
| backend 실행 이미지 | `52e12428…` = IMGOLD. 이전 복구 태그 8개와 latest 일치 |
| frontend 실행 이미지 | **`sha256:d825a9f5…f7f66` = FEIMGOLD**(`image-fe-before.txt`), 이름 `autotrader-frontend` |
| 풀·Flyway·TimeZone | STAGE_1 값 일치, 최고 94, DB `UTC` |
| SID 203·66세션 | 확인 통과, `66 \| 63 \| 3 \| -` |
| 설치 장부·점검·crontab | 8bfe8a9, c669ccdc, f2dce280 일치 |
| BTC 입력 | `true \| 11-13 13:00 \| 11-13 13:00 \| 20/20` |
| Q3~Q9 | 0·0·0·0행, RUNNING 63/STOPPED 3, 정비 뒤 실자금 매수 0, 격리 해제 0 |
| 로그 기준선 | 연결 획득 시간 초과 0, HOLD 장기 대여 0, N15~N20 미관측 |
| 경보 | 적재 뒤처짐 24회, 전부 KRW-BLAST(기존 모양) |
| freshness·start_66.py·V96 | 회차 없음, 원문 같음, V95 이후는 V96 하나·원문 일치 |

**손실 조회 v2**(GS 6385e1d5 원문·해시 일치, [`maint-v161/preflight-loss.txt`](maint-v161/preflight-loss.txt)):

```text
DYNAMIC REAL | 30d | -359.3885565600000000 | 0 | RUNNING DYNAMIC REAL | 0 | 15.00 | 판정 불가(DYNAMIC REAL 손실 있음·자본 0)
전체 REAL    | 30d | -359.3885565600000000 | 10000000 | 최종 폴백 10,000,000 | 0.00 | 15.00 | 허용
판정 | - | … | 🔴 중단: DYNAMIC REAL 30d 판정 불가(DYNAMIC REAL 손실 있음·자본 0)
```

- 나머지 8행(기존 1d·7d·30d, DYNAMIC REAL 1d·7d, 전체 REAL 1d·7d)은 손실 0이고 모두 허용이다.
- 블록 문구대로 배포 전에 멈췄다. `ok-U0`는 만들지 않았다. 한도·자본·세션·손실 행은 바꾸지 않았다. 값을 바꾸거나 같은 조건으로 U0를 다시 실행하지 않았다.
- **−359.38855656은 기존 기록의 SUI DYNAMIC 정산 1건과 같은 값이다.** `dynamic_sell_settlement` order_ref 6003fbf9…, 10-17 10:16:03.608 KST, 운영자 조회 2026-10-19([LIVE_RISK_IMPLEMENTATION_REVIEW.md](../../LIVE_RISK_IMPLEMENTATION_REVIEW.md) ④-1). 이번에 추가 DB 조회는 하지 않았으므로 같은 행이라는 것은 값 일치에 근거한 **추정**이다.
- 30일 창은 `asof − 720시간`이고, DYNAMIC 손실은 `sell_applied_at` 기준이다. 그 시각이 정산 시각과 같다면 이 손실은 **11-16 10:16 KST 무렵** 창 밖으로 나간다. `sell_applied_at`은 읽지 않았으므로 이것도 **추정**이다.
- 뜻: 손실 관문은 새 제품의 REAL 진입 검사를 그대로 따른다. 지금 상태에서 새 backend는 DYNAMIC REAL 손실과 자본 0 때문에 실자금 진입을 거절하게 된다(제품 주석상 "판정 불가 → 제품은 거절"). V159~V161 사전 확인은 대역·PG 사례로만 했고, 근거 폴더에서 찾은 범위로는 운영 DB에 이 조회를 실제로 돌린 것이 이번 U0가 처음이다(v159 README "운영 DB에는 접속하지 않았다").

## 4. 서버에 남은 변화

- `~/maint-v161/` 폴더와 U0 기록 파일 31개, `backend-before.log`가 새로 생겼다.
- U0의 `git fetch origin`이 원격 추적 참조를 갱신했다(새 원격 브랜치 19개, origin/main 9aa617d1→089e5e72). 체크아웃·작업 트리 변경은 없고 HEAD는 b5cd1d0d다.
- 서비스·이미지·DB·crontab·장부·한도는 바뀌지 않았다. 재시작도 없었다.

## 5. 총괄 판정이 필요한 것

1. 같은 V161D4로 U0를 다시 실행할지, 그렇다면 언제인지 결정해야 한다(예: 11-16 10:17 KST 뒤, 그 시점 직전 보고를 읽은 상태에서). `ok-U0`가 없으므로 블록상으로는 다시 실행할 수 있다. 다만 서버 `~/maint-v161`의 기존 U0 기록은 덮어써지며, 그 원문은 이 폴더에 보존돼 있다.
2. 다른 방식(관문·자본·손실 행 변경 등)은 개발 세션이 정하지 않는다. 블록 문구대로 "한도·자본·세션·손실 행을 바꾸지 않는다".
3. 새 세션 생성, SID 203 시작, F/F1/N06 핀 갱신은 계속 미승인이다. 과거 60초 제출 지연 원인도 미확정이다.

모든 파일의 체크섬은 [SHA256SUMS](SHA256SUMS)에 있다. 폴더 전용 `.gitattributes`(`* -text`)로 바이트를 보존한다.
