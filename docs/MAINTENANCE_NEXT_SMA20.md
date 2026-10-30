# 계획 정비 절차 — 배포 후보 `DAILY_SMA20` (12월 제한 실거래 시험)

> ✅ **수행 — 개입 #14, 2026-10-30 09:07~09:41 KST.** `gateEnabled` false(09:03:06) 확인 뒤 리뷰 판정("`504ab81`은 기술 검토 통과 … backend 단독 배포로 진행", "이번 승인은 배포까지")대로 진행. 새 앱 기동 09:09:52, 복구 없음. 결과는 `SUPERTREND_VALIDATION_PREREG.md` 개입 기록 #14. 서버는 `504ab81` 에 고정. 🔴 §6 기대에 "상장 폐지 마켓 캔들 404(신호 품질 평가, 재기동마다 마켓별 1회)"를 적지 않았다 — 이번에 BLAST·BONK·STORJ 3줄이 나왔다(개입 #14 ERROR 행).

> 🔴 **이 절차서는 배포 후보 준비까지다.** 배포 실행·STAGE_1 등록·활성화·실거래 세션 생성의 승인이 아니다. 운영자가 완성 후보를 보고 배포와 활성화를 **차례로, 따로** 결정한다. 그 전까지 운영은 `98aaac4`, STAGE_1 은 비활성이다.
>
> 근거: `LIMITED_LIVE_TEST_PLAN.md` v1.3 §5-5·§5-7. 형식은 `MAINTENANCE_NEXT_98aaac4.md`(개입 #13, 10-29 수행)와 같다. 공통 함수·점검 조회·되돌림 판단은 그 문서와 같은 것을 쓰고, 다른 점만 적는다.
>
> 배포 후보: **`504ab8178f1e5fbc0770ac14e0a5f8855a9a7771`** (브랜치 `deploy/sma20-limited-live`, 2026-10-30 고정)
> - 만든 방법: `98aaac4` 위에 본 코드 5파일과 시험 3개를 `main` 판 그대로 올렸다(`8ea2ec0`). 본 코드 네 기존 파일은 `98aaac4` 와 `main`(`b9cd698`)이 같은 내용이었다.
> - 그 위에 시험 보완 1커밋(`504ab81`, 리뷰 2026-10-30): `DailySma20LiveOrderPathTest` 의 거래소 대역이 null 을 돌려줘 주문 스레드 예외 7건이 나던 것을 정리. **본 코드는 `8ea2ec0` 과 같다** — 앞 후보 `8ea2ec0` 은 쓰지 않는다.
> - 들어간 것: 전략 `DAILY_SMA20`(새 파일)·등록 1줄 · RANGE 관문 목록 1항목 · LIVE 세션 생성 요청의 `maxInvestment` 필드와 그 전달 1줄.
> - 빠진 것: `main` 의 다른 변경 전부(V95 · 세션 슬리피지 · WF·백테스트 · 대조 러너 · `build.gradle`).

**지난 정비(#13)와 다른 점**
- 새 클래스가 있다(`DailySmaTrendStrategy`). 이 클래스는 `strategy-lib` 모듈이라 `app.jar` 안의 **중첩 jar**(`BOOT-INF/lib/strategy-lib-*.jar`)에 들어간다. 그래서 4-1b 확인이 중첩 jar 를 연다.
- 운영 동작 변화: 새 전략이 **등록만** 된다. 이 전략으로 세션을 만들기 전에는 주문이 나가지 않는다. 기존 30개 전략의 거동 지문은 그대로다(`StrategyBehaviourFingerprintTest`).
- RANGE 관문 목록에 `DAILY_SMA20` 이 더해진다. 다른 전략의 판정은 바뀌지 않는다.
- `maxInvestment` 를 주지 않는 생성 요청은 종전과 같다.

🔴 **이번 정비에서 하지 않는 것**: 프런트엔드 · Docker·OS 패키지 정비 · STAGE_1 등록·활성화 · 세션 생성·시작 · WF 게이트 설정 변경 · DB 변경(마이그레이션 없음, V94 그대로).

## 0. 전제와 보류 조건

- **일시**: 운영자가 정비 시간 내내 직접 대응할 수 있을 때 정한다. 긴급 배포가 아니다. 10:00·22:00 정기 점검 보고를 확인한 뒤 시작하기를 권한다.
- **복구**: 스키마가 그대로이므로 **앱 이미지와 체크아웃만** `98aaac4` 로 되돌리면 된다(§5).
- **검증 세션**: 원래 66개(`paper_trading.virtual_balance` id 522~587) 그대로. 576·577·578 만 STOPPED, 나머지 63개 RUNNING. 재기동 1회는 66세션에 영향을 줄 수 있으므로 **개입 #14** 로 기록한다.
- 🔴 **새 셸을 열 때마다 먼저** 아래 변수를 정한다. `MAINTENANCE_NEXT_98aaac4.md` §0 과 같고 `NEW`·`OLD` 만 다르다.
  ```bash
  cd ~/crypto_trader/AutoTrader
  DC="docker compose -f docker-compose.prod.yml"; REPO=autotrader-backend; M=~/maint-$(date +%Y%m%d)
  NEW=504ab8178f1e5fbc0770ac14e0a5f8855a9a7771   # 🔴 배포 후보 — 브랜치 이름이 아니라 고정 SHA 로 체크아웃한다
  OLD=98aaac49d5b811a9679e59e863cf546dca4a495f
  QS=fa0691e647b61f6df4a85bdf796996f50470185e    # 점검 조회·확인 도구 — #12·#13 에서 쓴 것, origin/main 을 쓰지 않는다
  mkdir -p "$M"
  qc() { git show $QS:scripts/ops/maint_checks.sql | $DC exec -T db psql -U trader -d crypto_auto_trader -v maint_start="$(cat "$M/maint_start.txt")" -f - ; }
  sc() { $DC exec -T db psql -U trader -d crypto_auto_trader -At -F ' | ' -c "
    SELECT count(*) AS total,
           count(*) FILTER (WHERE status = 'RUNNING' AND id NOT IN (576,577,578)) AS running_63,
           count(*) FILTER (WHERE status = 'STOPPED' AND id IN (576,577,578))     AS stopped_3,
           coalesce(string_agg(id || ':' || status, ' ' ORDER BY id) FILTER (WHERE NOT (
               (id IN (576,577,578) AND status = 'STOPPED') OR
               (id NOT IN (576,577,578) AND status = 'RUNNING'))), '-') AS mismatch
      FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587" ; }
  svc() { for s in backend db redis db-backup frontend; do docker inspect --format "$s {{.Image}} {{.State.StartedAt}}" "$($DC ps -q $s)"; done ; }
  ```
- 각 단계의 실제 수행 시각을 `date` 로 남긴다.

## 1·2. 정기 점검과 직전 점검 — 기대와 다르면 연기

`MAINTENANCE_NEXT_98aaac4.md` §1·§2 명령을 그대로 쓴다. 다른 점:
- HEAD 기대값은 **`98aaac4`**, 추적 파일 변경 없음.
- 🔴 **HEAD 와 추적 파일 상태는 화면이 아니라 파일로 남긴다**(#13 에서 이 원문이 빠졌다). 그 문서 §2 의 `git rev-parse … ; git status …` 한 줄 대신 아래를 쓴다:
  ```bash
  { date '+%F %T %Z'; git rev-parse HEAD; git status --short --untracked-files=no; echo "(끝)"; } | tee "$M/git-before.txt"
  ```
  기대: 시각 다음 줄이 `98aaac49d5b811a9679e59e863cf546dca4a495f`, 그다음 바로 `(끝)`(추적 파일 변경 없음 = 빈 출력). 다르면 **연기**한다. 이 파일은 개입 #14 증빙으로 보존한다.
- 기대표(Q1 94 · Q3~Q6 0 · Q7 `RUNNING 63`·`STOPPED 3` · Q8 0 · Q9 0 · `ROLLBACK` · `sc` = `66 | 63 | 3 | -` · `svc` 다섯 줄)는 같다.

**배포 코드 확인**(기대와 다르면 멈춘다):
```bash
git diff --name-only $OLD $NEW
git diff --quiet $OLD $NEW -- web-api/src/main/resources scripts docker-compose.prod.yml web-api/Dockerfile crypto-trader-frontend build.gradle && echo 마이그레이션·설정·스크립트 변경 없음
git log --oneline $OLD..$NEW                                     # 커밋 2개(후보 + 시험 보완)
```
기대: 첫 명령은 정확히 아래 8줄(시험 파일 3개는 이미지에 들어가지 않는다). 둘째는 `마이그레이션·설정·스크립트 변경 없음`. 셋째는 두 줄 — `504ab81 배포 후보 시험 보완 …` 과 `8ea2ec0 배포 후보 …`.
```
core-engine/src/main/java/com/cryptoautotrader/core/selector/RangeRegimeGate.java
core-engine/src/test/java/com/cryptoautotrader/core/StrategyBehaviourFingerprintTest.java
strategy-lib/src/main/java/com/cryptoautotrader/strategy/StrategyRegistry.java
strategy-lib/src/main/java/com/cryptoautotrader/strategy/dailysma/DailySmaTrendStrategy.java
strategy-lib/src/test/java/com/cryptoautotrader/strategy/dailysma/DailySmaTrendStrategyTest.java
web-api/src/main/java/com/cryptoautotrader/api/dto/LiveTradingStartRequest.java
web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java
web-api/src/test/java/com/cryptoautotrader/api/service/DailySma20LiveOrderPathTest.java
```

**기존 `98aaac4` 이미지 보존** — 실행 중인 이미지 ID 에 직접 태그한다.
```bash
IMG=$(docker inspect --format '{{.Image}}' $($DC ps -q backend)); echo "$IMG" | tee "$M/image-98aaac4.txt"
docker tag "$IMG" $REPO:98aaac4
docker image inspect --format '{{.Id}} {{.Created}}' $REPO:98aaac4      # ID 가 image-98aaac4.txt 와 같아야 한다
$DC logs --no-color backend > "$M/backend-before.log"; wc -l "$M/backend-before.log"
```
- 기대 이미지 ID 는 개입 #13 에서 기록한 `98aaac4` 빌드다. 다르면 기록하고 보고한다. 되돌릴 대상은 **지금 실행 중인** 이미지다.

## 3. 체크아웃·배포 — 개입 #14 재기동

```bash
git checkout $NEW                                           # detached HEAD — 배포 버전 고정
date '+%F %T %Z' | tee "$M/t-checkout.txt"
{ date '+%F %T %Z'; git rev-parse HEAD; git status --short --untracked-files=no; echo "(끝)"; } | tee "$M/git-deploy.txt"   # 504ab81…, 바로 (끝)
date '+%F %T %Z' | tee "$M/t-build.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-build.utc"
$DC up --build -d --no-deps backend                         # 🔴 backend 만 — 의존 서비스(db·redis)는 손대지 않는다
date '+%F %T %Z' | tee "$M/t-start.txt"                     # 🔴 새 앱 기동 시각
docker inspect --format '{{.Image}}' $($DC ps -q backend) | tee "$M/image-new.txt"   # image-98aaac4.txt 와 달라야 한다
svc | tee "$M/svc-deploy.txt"
diff <(grep -v '^backend ' "$M/svc-before.txt") <(grep -v '^backend ' "$M/svc-deploy.txt") && echo "backend 외 이미지·기동 시각 그대로"
```
- `--no-deps` 와 서비스 이름 `backend` 를 둘 다 붙인다(#13 과 같은 이유).
- 감시 스크립트(`scripts/`)는 `98aaac4` 와 같다.

## 4. 기동·적용 확인

**4-1 공통 확인(기동 3~5분 뒤)** — `MAINTENANCE_NEXT_98aaac4.md` §4-1 명령과 기대를 그대로 쓴다(`Schema "public" is up to date` · `Started` · 격리 점검 "없음" · ERROR 는 공유 풀 미등록뿐 · 판정 재개 · 점검 조회 · `sc` 직전과 같음).

**4-1b 새 이미지 확인(배포에서만 — 복구 뒤에는 하지 않는다)**
```bash
docker cp $($DC ps -q backend):/app/app.jar "$M/app-new.jar"
python3 - "$M/app-new.jar" <<'EOF'
import io, sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
dto = z.read("BOOT-INF/classes/com/cryptoautotrader/api/dto/LiveTradingStartRequest.class")
print("있음" if b"maxInvestment" in dto else "없음", "LiveTradingStartRequest.maxInvestment")
libs = [n for n in z.namelist() if n.startswith("BOOT-INF/lib/strategy-lib")]
inner = zipfile.ZipFile(io.BytesIO(z.read(libs[0])))
names = set(inner.namelist())
print("있음" if "com/cryptoautotrader/strategy/dailysma/DailySmaTrendStrategy.class" in names else "없음", libs[0], "DailySmaTrendStrategy")
print("있음" if b"DAILY_SMA20" in inner.read("com/cryptoautotrader/strategy/StrategyRegistry.class") + inner.read("com/cryptoautotrader/strategy/dailysma/DailySmaTrendStrategy.class") else "없음", "DAILY_SMA20 이름")
EOF
```
- 기대: 세 줄 모두 `있음`. 로컬에서 후보 `8ea2ec0` 으로 만든 `bootJar` 로 같은 결과를 확인했다(2026-10-30, `web-api-0.1.0.jar` · `strategy-lib-0.1.0.jar`). `504ab81` 은 본 코드가 같다(시험 파일만 다름). `98aaac4` 의 `LiveTradingStartRequest` 에는 `maxInvestment` 가 없다.
- 하나라도 `없음` 이거나 파일을 못 찾는 오류가 나면 새 코드가 들어가지 않은 것이다. §5 로 되돌리고 보고한다.

🔴 **되돌림 사유**: `MAINTENANCE_NEXT_98aaac4.md` §4 와 같다(기동 실패·재시작 반복, 공유 풀 미등록 외 새 ERROR, Q3~Q6 ≠ 0, `sc` ≠ `66 | 63 | 3 | -`, 판정 재개 미확인) + 4-1b 에 `없음`.

## 5. 복구 — 스키마 그대로(V94), 앱과 체크아웃만 `98aaac4`

```bash
git checkout $OLD
date '+%F %T %Z' | tee "$M/t-rollback-checkout.txt"
docker tag "$(cat "$M/image-98aaac4.txt")" $REPO:latest
date '+%F %T %Z' | tee "$M/t-rollback.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-rollback.utc"
$DC up -d --no-build --no-deps --force-recreate backend      # 🔴 backend 만
docker inspect --format '{{.Image}}' $($DC ps -q backend); cat "$M/image-98aaac4.txt"   # 둘이 같아야 한다
svc | tee "$M/svc-rollback.txt"
diff <(grep -v '^backend ' "$M/svc-before.txt") <(grep -v '^backend ' "$M/svc-rollback.txt") && echo "backend 외 이미지·기동 시각 그대로"
```
- 복구 뒤 확인: 실행 이미지 ID = `image-98aaac4.txt`, HEAD 는 `{ date '+%F %T %Z'; git rev-parse HEAD; git status --short --untracked-files=no; echo "(끝)"; } | tee "$M/git-rollback.txt"` 로 저장해 `98aaac4…`·바로 `(끝)`, §4-1 공통 확인을 `S=$(cat "$M/t-rollback.utc")` 로 다시 실행(`sc` 는 `s-rollback.txt`). 4-1b 는 하지 않는다.
- 이 배포에는 DB 변경이 없다. 그래서 복구에 DB 작업이 없다. 배포 뒤 `DAILY_SMA20` 세션을 만들지 않았다면 되돌린 앱이 모르는 데이터도 없다.

## 6. 30분 뒤 재점검과 기록

`MAINTENANCE_NEXT_98aaac4.md` §6 명령과 기대·경보 네 갈래 구분을 그대로 쓴다.

**개입 #14 기록**(운영자 본인이 시각·결과를 채운다):
> 개입 #14 — (날짜) 계획 정비(백엔드 `504ab81`, `DAILY_SMA20` 등록 · LIVE 생성 요청 `maxInvestment`).
> 정비 전 상태: `git-before.txt` 원문(HEAD `98aaac4…`, 추적 파일 변경 없음). 배포 뒤 `git-deploy.txt`.
> 순서: `98aaac4` 이미지 보존(ID 기록) → 체크아웃 HH:MM:SS → 빌드 HH:MM → **새 앱 기동 HH:MM:SS**. 4-1b 는 세 줄 모두 `있음`.
> 검증 세션: `sc` 가 직전·기동 후·30분 모두 `66 | 63 | 3 | -`. backend 외 서비스의 이미지·기동 시각은 그대로.
> 경보: 기존 BLAST 반복 N회(시각) / 새 경보 (없음·내용) / ⚪ (없음·내용) / 실행 누락 (없음·내용).
> 66세션의 입력 수집과 판정 규칙은 바뀌지 않았다. 바뀐 것은 전략 등록 1개·RANGE 목록 1항목·LIVE 생성 요청 필드 1개다. 재기동에 따른 평가 공백의 영향은 미확정이며, T0·성과 구간은 유지한다.
> 결과: (정상 / 복구 — 사유·복구 시각들) — 운영자 / 시각

- 서버는 `504ab81`(복구했다면 `98aaac4`)에 고정한다. 무조건 `git checkout main` 하지 않는다.

## 7. 배포 뒤에도 하지 않는 것 — 활성화는 별도 결정

- STAGE_1 등록·활성화, `risk_config` 변경.
- `DAILY_SMA20` 세션 생성·시작. 생성 요청 본문(§5-1 값 + `"maxHoldHours":0` 명시)과 시작 명령은 **활성화 결정 뒤** 따로 드린다.
- WF 게이트 설정 변경(`LIMITED_LIVE_TEST_PLAN.md` §5-4 — 운영 `gateEnabled` 조회 결과에 따름).
- 프런트엔드, Docker·OS 패키지, 66세션·T0, DB 변경.
