# 계획 정비 절차서 — 2026-10-21(수) 13:00~16:00 KST

> 근거: `LIVE_RISK_IMPLEMENTATION_REVIEW.md` §6.13-⑩(정비 순서)·§6.15-④(선행 조건 5 — V94 는 STAGE_1 전 필수)·§6.16(이 정비의 준비 기록).
> 🔴 **이번 정비에서 STAGE_1 은 활성화하지 않는다.** 이 절차서는 배포·실거래 시작·STAGE_1 활성화 승인이 아니다.

## 0. 전제와 보류 조건

- 운영자가 13:00~16:00 동안 **직접 대응할 수 있을 때만** 한다. 자리를 비워야 하면 다른 평일 낮으로 미룬다(긴급 배포가 아니다 — 격리 대상 0행, 2026-10-20).
- 두 단계를 **순서대로, 각각 확인한 뒤** 넘어간다. 문제가 나면 그 단계에서 멈춰 원인을 구분한다.
  1. **Docker 단계** — 업그레이드 뒤 **현재 운영 버전 `3b781d8` 로 먼저** 기동·확인
  2. **앱 단계** — `39f994c` 배포(V94). 앱 코드는 실제 프로세스로 검증한 `0be46c5` 와 같다(로컬 `git diff` 차이 없음 확인)
- 16:00 까지는 확인·복구 여유다. 13:00 직전 점검이 기대와 다르면 **정비 전체를 보류**한다.
- 재기동 두 번(Docker 단계·앱 단계)은 66세션에 영향을 줄 수 있다 → **개입 #8** 로 실제 시각과 내용을 남긴다(§5).
- 아래 명령은 운영 서버의 저장소 디렉터리에서 실행한다. `DC="docker compose -f docker-compose.prod.yml"` 로 줄여 쓴다.

## 1. 10:00 정기 점검 (오늘 2026-10-21)

🔴 각 단계는 **실제 수행 시각**을 기록한다. 10:00 이 이미 지났으면 지금 하고 그 시각을 적는다. 13:00 전에 이 점검과 대응 시간을 확보하지 못했으면 정비를 미룬다.

평소 점검 그대로 — `$DC ps`(backend Up·재시작 횟수), 최근 ERROR(공유 풀 미등록·캔들 판정 불가 외), 아래 점검 조회. 이상이 있으면 정비를 보류한다.

**점검 조회 실행** (읽기 전용 트랜잭션, 서버의 체크아웃과 무관하게 최신 main 에서 읽는다):
```bash
git fetch origin
git show origin/main:scripts/ops/maint_checks.sql | $DC exec -T db psql -U trader -d crypto_auto_trader -f -
```
| 조회 | 기대 |
|---|---|
| Q1 Flyway | 93 · V87 `installed_on` 2026-10-16 05:07Z |
| Q2 실자금 세션 | 상태·가용·`invest_ratio` 를 **그대로 기록**(정비 뒤 같은지 본다) |
| Q3 실자금 보유 | 0행 |
| Q4 실자금 미결 주문 | 0 |
| Q5 정리 대기 | 0 |
| Q6 격리 대조 대상 | 0행 — 행이 나오면 **보류**(행은 곧 격리가 아니라 대조 대상, §6.13-⑩) |
| Q7 검증 66세션 | RUNNING 66 |
| Q8 정비 뒤 신규 매수 | 0 (13:00 이전엔 의미 없음) |
| Q9 격리 해제 기록 | V93: `(sell_quarantine_release 없음 — V93. Q9 건너뜀)` · V94: 0 |
| Q10 텔레그램 기록 | 참고 |

조회 파일은 `ON_ERROR_STOP` 으로 실행된다 — **오류가 나면 그 자리에서 멈추고 마지막 줄이 `ROLLBACK` 이 아니다.** 그때는 출력이 Q10 까지 왔는지 보고, 오류 줄을 그대로 보고한다
(예상된 오류는 없다).

## 2. 13:00 정비 직전

1. 기록 폴더: `mkdir -p ~/maint-20261021` — 명령은 저장소 디렉터리에서 실행하고 결과만 이 폴더에 둔다.
2. **점검 조회 재실행**(§1) → `> ~/maint-20261021/q-1300-before.txt`. Q3·Q4·Q5·Q6 이 0 이 아니면 **보류**.
3. **DB 백업**(기록용 — 🔴 복구 수단이 아니다. 정비 뒤 주문·체결이 생기면 덤프로 되돌릴 때 그 기록이 사라진다):
   ```bash
   $DC exec -T db pg_dump -U trader -d crypto_auto_trader -Fc > ~/maint-20261021/pre-maint-20261021.dump
   ls -l ~/maint-20261021/pre-maint-20261021.dump
   $DC exec -T db pg_restore -l < ~/maint-20261021/pre-maint-20261021.dump | head -5    # 목록이 읽히면 정상
   ```
4. **이전 버전 복구 준비**:
   ```bash
   git rev-parse --short HEAD                      # 3b781d8 이어야 한다
   git status --short                              # 변경 없음
   $DC images backend                              # 저장소 이름 확인 — 운영은 autotrader-backend (compose 프로젝트 autotrader, 10-21 점검 `ps` 로 확인)
   IMG=$(docker inspect --format '{{.Image}}' $($DC ps -q backend))    # 지금 실행 중인 컨테이너의 이미지 ID
   echo "$IMG" | tee ~/maint-20261021/image-3b781d8.txt
   docker tag "$IMG" <저장소 이름>:3b781d8          # 🔴 :latest 가 아니라 실행 중인 이미지 ID 에 직접 태그 (둘이 같다고 가정하지 않는다)
   docker image inspect --format '{{.Id}}' <저장소 이름>:3b781d8       # image-3b781d8.txt 와 같아야 한다
   docker image inspect --format '{{.Id}}' <저장소 이름>:latest        # 참고로 기록 — 달라도 태그는 위 ID 기준
   $DC logs --no-color backend > ~/maint-20261021/backend-before.log
   ```
   되돌릴 때는 다시 빌드하지 않고 이 태그의 이미지를 쓴다. 이미지 생성 시각이 10-19 `3b781d8` 배포 시각(13:00경)과 맞는지
   `docker image inspect --format '{{.Created}}' <저장소 이름>:3b781d8` 로 본다. 셸을 새로 열면 `IMG` 는 `image-3b781d8.txt` 에서 다시 읽는다.

## 3. Docker 단계 (기존 앱 `3b781d8` 유지)

1. **backend 를 멈추고 컨테이너를 지운다** — 시각 기록(개입 #8 재기동 ①).
   ```bash
   $DC stop backend && $DC rm -f backend
   $DC ps                                           # backend 없음, 나머지는 Up
   ```
   🔴 `stop` 만 하면 안 된다: backend 는 `restart: always` 라 **Docker 데몬이 재시작되면 수동으로 멈춘 컨테이너도 다시 뜬다** — 업그레이드 도중 DB 준비 전에
   앱이 저절로 기동할 수 있다. 컨테이너를 지우면 데몬 재시작이 되살릴 대상이 없다. 이미지·로그(`./logs/backend`)는 지워지지 않는다.
2. **Docker 관련 패키지만 업그레이드** — 🔴 `apt-get upgrade`(전체)는 쓰지 않는다. Docker 외 OS 패키지 정비는 별도 일정으로 잡는다.
   ```bash
   sudo apt-get update
   dpkg -l | grep -E 'docker|containerd|runc' | tee ~/maint-20261021/docker-pkgs-before.txt      # 설치된 Docker 관련 패키지
   apt list --upgradable 2>/dev/null | tee ~/maint-20261021/upgradable-all.txt                  # 참고 기록(이번에 올리지 않는 것 포함)
   apt list --upgradable 2>/dev/null | grep -E 'docker|containerd|runc' | tee ~/maint-20261021/upgradable-docker.txt
   ```
   `upgradable-docker.txt` 에서 올릴 패키지를 **승인한 목록만** 이름으로 지정한다(아래는 예 — 위 목록에 있는 것만 남긴다). 먼저 시뮬레이션으로 함께 바뀌는 패키지를 본다:
   ```bash
   PKGS="docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin docker-ce-rootless-extras"   # 승인 목록으로 바꾼다
   sudo apt-get install --only-upgrade -s $PKGS | tee ~/maint-20261021/apt-simulate.txt | grep -E '^(Inst|Remv)'
   ```
   `Inst`·`Remv` 줄이 **승인 목록 밖 패키지**를 포함하면 멈추고 그 목록을 보고한다(제거 `Remv` 는 하나라도 있으면 멈춘다). 목록 안이면:
   ```bash
   sudo apt-get install --only-upgrade $PKGS
   tail -40 /var/log/apt/history.log > ~/maint-20261021/apt-history.txt
   dpkg -l | grep -E 'docker|containerd|runc' > ~/maint-20261021/docker-pkgs-after.txt
   ls /var/run/reboot-required 2>/dev/null && cat /var/run/reboot-required.pkgs
   ```
   재부팅이 필요하다고 나와도 **이 정비에서는 재부팅하지 않는다**(계획에 없는 세 번째 재기동). 기록하고 따로 정한다.
3. **데몬·나머지 컨테이너 확인**:
   ```bash
   docker version --format '{{.Server.Version}}' ; systemctl is-active docker containerd
   $DC ps                                           # db·redis healthy, frontend·db-backup·prometheus·grafana Up
   ```
   db-backup 은 재시작될 때마다 덤프를 하나 만든다(`./backups`) — 정상.
4. **기존 앱 기동**(다시 빌드하지 않는다):
   ```bash
   $DC up -d --no-build backend
   docker inspect --format '{{.Image}}' $($DC ps -q backend)    # image-3b781d8.txt 와 같아야 한다
   $DC logs --since 5m backend | grep -E "Schema .* is up to date|Successfully applied|Started CryptoAutoTrader|ERROR"
   ```
   기대: `Schema "public" is up to date. No migration necessary.` · `Started CryptoAutoTraderApplication` · ERROR 는 **공유 풀 한도 미등록(실자금 진입 거절 — 의도)**·
   기동 직후 캔들 판정 불가 정도. 그 밖의 ERROR 는 앱 단계로 넘어가지 않는 사유다.
5. **점검 조회**(§1) → `~/maint-20261021/q-docker.txt`: Q1 **93** · Q2 정비 전과 같음 · Q3·Q4·Q5·Q6 0 · Q7 RUNNING 66 · Q8 0.

🔴 **여기서 문제가 나면 앱 배포로 넘어가지 않는다.** `3b781d8` 로 운영을 유지하고 결과를 보고한다.

## 4. 앱 단계 (`39f994c` — V94)

1. **배포 SHA 확정**:
   ```bash
   git fetch origin
   git checkout 39f994c                             # detached HEAD — 배포 버전 고정
   git diff --quiet 0be46c5 39f994c -- web-api core-engine exchange-adapter strategy-lib build.gradle settings.gradle gradle docker-compose.prod.yml crypto-trader-frontend && echo 앱 코드 동일
   ```
   `앱 코드 동일` 이 나오지 않으면 멈춘다.
2. **배포** — 시각 기록(개입 #8 재기동 ②):
   ```bash
   $DC up --build -d backend
   $DC logs --since 10m backend | grep -E "Migrating schema|Successfully applied|매도 격리 점검|판별 불가 매도|위험 한도 확인|Started CryptoAutoTrader|ERROR"
   ```
   기대(셋 모두):
   - `Migrating schema "public" to version "94 - sell quarantine release"` → `Successfully applied 1 migration to schema "public", now at version v94`
   - `매도 격리 점검: 반영 표시 없이 체결된 매도가 있는 열린 실자금 포지션 없음` (행이 나오면 🔴 — 배포했다고 해제하지 않는다. 대조 대상으로 보고)
   - 경계는 **조회로** 확인한다(Q1 의 V87 `installed_on` = 2026-10-16 05:07Z). `[판별 불가 매도] 경계 = V87 설치 시각 …` 로그 줄은 반영 표시 없는 매도를 **처음 판단할 때**
     한 번 나온다 — 실자금 보유 0 이면 정비 뒤에 나오지 않는 것이 정상이다(2026-10-20 리허설 로그에서도 기동만으로는 나오지 않음). 🔴 대신
     `[판별 불가 매도] 경계 시각을 정하지 못했다` ERROR·알림이 나오면 자동 반영이 보류된 상태다 — 보고
   - ERROR 는 공유 풀 미등록 외 없음. `위험 한도 확인` 줄은 풀 미등록이라 **나오지 않는 것이 정상**이다(STAGE_1 등록 뒤에만).
3. **점검 조회**(§1) → `~/maint-20261021/q-app.txt`: Q1 **94**(V94 `installed_on` 기록) · Q9 **0행** · Q2 정비 전과 같음 · Q3·Q4·Q5·Q6 0 · Q7 RUNNING 66 · Q8 0.
4. 🔴 서버는 `39f994c` 에 고정해 둔다. 다음 작업 때 무조건 `git checkout main` 하지 않는다.

**되돌리기(앱 단계 실패 시 — 기동 실패·마이그레이션 실패·주문 추적 이상·예상 밖 ERROR)**: 스키마는 V94 로 둔다(되돌리지 않는다).
```bash
git checkout 3b781d8
docker tag "$(cat ~/maint-20261021/image-3b781d8.txt)" <저장소 이름>:latest    # 기록한 이미지 ID 를 compose 가 쓰는 :latest 로
$DC up -d --no-build --force-recreate backend
docker inspect --format '{{.Image}}' $($DC ps -q backend)    # image-3b781d8.txt 와 같아야 한다
```
✅ 리허설(2026-10-20, `scripts/restart-test/rehearse_rollback_v94.py` **6/6**): `3b781d8` jar 가 V94 스키마에서 기동(`Successfully validated 94 migrations` ·
`Schema "public" is up to date`)·60초 생존·Flyway 이력 불변·스키마 관련 ERROR 없음, 그 뒤 후보 재기동도 추가 마이그레이션 없음. 되돌린 뒤 점검 조회는 Q1 이 **94** 인 것 외에
Docker 단계와 같다. 🔴 되돌린 운영에는 격리 우회 결함이 다시 남는다 — Q6 0행이 그 근거다.

## 5. 정비 후 (16:00 까지)

1. 앱 단계 30분 뒤 점검 조회 재실행 → `~/maint-20261021/q-after30.txt`: Q3·Q4·Q5·Q6·Q8 0 · Q7 RUNNING 66 · Q10 에서 재기동 뒤 알림 `success = t` 확인.
2. 66세션 점검: `python scripts/prospective/start_66.py alerts --since 2026-10-21T04:00:00Z`(정비 구간의 경보·실행 누락).
3. **개입 #8 기록**(운영자 본인이 시각·내용을 채운다 — 이 칸을 대신 채우지 않는다):
   > 개입 #8 — 2026-10-21 계획 정비. ① Docker 단계: backend 정지 HH:MM KST → Docker 관련 패키지 업그레이드(승인 목록 `PKGS` · 결과 `docker-pkgs-after.txt`) → `3b781d8` 재기동 HH:MM KST.
   > ② 앱 단계: `39f994c`(V94) 기동 HH:MM KST, Flyway V94 `installed_on` (조회값). 재기동에 따른 전략 메모리 초기화·평가 공백의 영향은 미확정이며 T0·성과 구간은 유지한다.
   > 결과: (정상 / 되돌림 — 사유) — 운영자 / 시각
4. 결과 파일(`~/maint-20261021/`)과 위 로그 줄을 보고하면 문서(§6.16·§6.4 판정표 5)에 반영한다. **판정표 5 의 전체 완료 표시는 그 확인 뒤 리뷰 판정으로** 한다.
5. 22:00 KST 정기 점검은 평소대로 한다.

## 6. 이번 정비에서 하지 않는 것

STAGE_1 등록·활성화 · `risk_config` 변경 · 세션 시작·정지·`invest_ratio` 변경 · 격리 해제 API 호출 · 재부팅 · **Docker 외 OS 패키지 업그레이드**(별도 정비) ·
`cloudflared-tunnel` 정리 · 66세션·T0 변경 · `RISK_LIMIT` 경보 재시도 보완(별도 변경 — 이번 배포 후보 `39f994c` 에 넣지 않는다).
