# Crypto Auto Trader

업비트(Upbit) 현물 자동매매 시스템. 전략 백테스터가 아니라 **운영형 시스템**이다 — 캔들 수집,
전략 평가, 백테스트·Walk-forward 검증, 모의/실거래 세션, 주문 상태 관리, 리스크 통제,
LLM 분석·리포트, 대시보드, 모니터링, 배포까지 한 제품으로 묶여 있다.

Java 17 + Spring Boot 3.2 Gradle 멀티모듈 백엔드 + Next.js 16 / React 19 프런트엔드.

---

## 시작하기 전에 알아야 할 것 하나

**청산·리스크 규칙을 구현한 곳이 넷이다** — `LiveTradingService`, `DynamicTradingService`,
`PaperTradingService`, 그리고 `BacktestEngine`. 체결 오차와 저장소만 다를 뿐 TP/SL/시간 제한/
전략 폐기 판정은 네 곳에서 동일해야 한다.

과거 결함이 거의 전부 *"한 엔진에 규칙을 넣고 나머지를 잊는다"* 형태였기 때문에,
이 제약에는 감시 장치가 붙어 있다.

| 장치 | 무엇을 보는가 |
|---|---|
| `EngineParityTest` | 규칙이 **어느 엔진에 존재하는가** (소스 문자열 기반 정적 감사) |
| `PaperLiveAlignmentTest` | 파라미터 **값의 동등성** |

두 테스트는 보완 관계이며 어느 쪽도 다른 쪽을 대체하지 않는다. `EngineParityTest`는 호출의
*존재*만 보고 *올바름*은 보지 않는다는 점을 기억할 것.

**청산·리스크 규칙을 건드리면 네 곳을 함께 고치고 두 테스트를 갱신한다.**
배경과 실제 사고 사례는 [`docs/ENGINE_PARITY.md`](docs/ENGINE_PARITY.md).

---

## 모듈

```
strategy-lib/         전략 인터페이스·지표·전략 구현 14종 (프레임워크 의존 없음)
core-engine/          백테스트·Walk-forward·리스크·시장 국면·전략 선택
exchange-adapter/     업비트 REST / WebSocket / 주문 / JWT
web-api/              Spring Boot 진입점, REST API, JPA, 세션·주문 운영 로직
crypto-trader-frontend/   Next.js 운영 대시보드 (Gradle 밖 독립 Node 앱)
```

의존 방향은 `web-api → core-engine → strategy-lib`, `web-api → exchange-adapter → core-engine`.
도메인 계산이 HTTP/DB 구현으로부터 분리돼 있어 전략 단위 테스트와 거래소 교체가 상대적으로 쉽다.

---

## 로컬 실행

DB와 Redis만 컨테이너로 띄우고 애플리케이션은 호스트에서 직접 실행하는 전제다.
`docker-compose.yml`에는 backend/frontend 서비스가 없다.

```bash
# 1. 인프라 (TimescaleDB 5432, Redis 6379)
docker compose up -d

# 2. 백엔드 (http://localhost:8080)
./gradlew :web-api:bootRun

# 3. 프런트엔드 (http://localhost:3000)
cd crypto-trader-frontend && npm ci && npm run dev
```

### 테스트

```bash
./gradlew test                                  # 백엔드 (4개 모듈)
cd crypto-trader-frontend && npm run lint
cd crypto-trader-frontend && npm run test:e2e   # Playwright. 목 API 로 단독 실행되며 백엔드 불필요
```

---

## 배포

```bash
cp .env.example .env    # 실제 값으로 교체 후
docker compose -f docker-compose.prod.yml up -d
```

전체 스택(DB · Redis · backend · frontend · 일일 백업 · Prometheus · Grafana)이 올라간다.
`.env.example`은 `docker-compose.prod.yml`이 참조하는 환경변수와 **1:1로 일치**하게 관리한다 —
어긋나면 미설정 값이 조용히 기본값으로 떨어진다.

`REQUIRE_WALK_FORWARD_GATE`와 `STRATEGY_SIGNAL_EXIT_ENABLED`는 **운영 동작을 바꾸는 값**이다.
기본값은 현재 운영 상태와 같게 맞춰 두었고, 각 변수의 의미와 off 인 근거는 `.env.example`
주석에 적혀 있다. 특히 후자를 근거 없이 켜지 말 것.

### 모니터링

Prometheus는 `127.0.0.1:9091`(호스트 9090은 Cockpit이 점유) — 외부에 열지 않는다.

Grafana 접속 방식은 `.env` 로 고른다(기본값은 외부 노출 없음).

| 방식 | 설정 | 비고 |
|---|---|---|
| **SSH 터널** (기본) | 아무것도 안 넣음 | `ssh -L 3001:127.0.0.1:3001 <user>@<host>` → `http://localhost:3001` |
| **Cloudflare Tunnel** (권장) | `GRAFANA_ROOT_URL`·`GRAFANA_DOMAIN`·`GRAFANA_COOKIE_SECURE` | 서버 포트를 열지 않는다. `cloudflared` 가 내부 `127.0.0.1:3001` 로 붙는다 |
| **Cloudflare DNS 프록시** | 위 + `GRAFANA_BIND=0.0.0.0:3001` | 포트를 연다. **방화벽에서 Cloudflare IP 대역만 허용 필수** |

각 항목의 상세와 주의사항은 `.env.example` 의 Grafana 절에 있다.

> ⚠️ **`/actuator/prometheus` 와 `/actuator/health` 는 인증 없이 열려 있다**
> ([`SecurityConfig`](web-api/src/main/java/com/cryptoautotrader/api/config/SecurityConfig.java) 의 `permitAll`).
> Prometheus 가 긁어야 해서 그렇다. 그런데 backend 는 `8080:8080` 으로 전체 인터페이스에
> 바인딩돼 있으므로, **공유기에서 8080 을 포워딩했다면 외부에서 인증 없이 메트릭을 읽을 수 있다.**
> `bash scripts/security-check.sh` 로 점검할 것.
대시보드는 `monitoring/grafana/provisioning/dashboards/`의 JSON이 **원본**이며 프로비저닝된다.
UI에서 고친 내용은 컨테이너 볼륨에만 남고 재기동 시 덮어써지므로, 유지할 변경은 JSON에 반영할 것.

> ⚠️ **`monitoring/` 아래를 고쳤다면 Grafana를 따로 재시작해야 한다.**
>
> ```bash
> docker compose -f docker-compose.prod.yml restart grafana
> ```
>
> `up -d --build` 는 이미지를 빌드하는 서비스(backend·frontend)만 새 컨테이너로 교체하고,
> `grafana` 는 이미지가 그대로라 기존 컨테이너를 유지한다. 바인드 마운트라 파일은 컨테이너
> 안에서 보이지만 **Grafana 는 프로비저닝을 기동 시점에만 읽는다** — 파일은 있는데 대시보드는
> 없는 상태가 된다(2026-09-15 실제로 겪음). 등록 여부는 이렇게 확인한다:
>
> ```bash
> source .env && curl -s -u "admin:${GRAFANA_PASSWORD}" >   "http://127.0.0.1:3001/api/search?query=Crypto"
> ```
>
> 빈 배열 `[]` 이면 미등록이다.

---

## 문서

| 문서 | 내용 |
|---|---|
| [`docs/README.md`](docs/README.md) | **문서 색인 — 어느 문서를 믿어도 되는지 분류** |
| [`PROJECT_STRUCTURE_ANALYSIS.md`](PROJECT_STRUCTURE_ANALYSIS.md) | 저장소 전체 구조 분석 — 새로 왔다면 여기부터 |
| [`docs/ENGINE_PARITY.md`](docs/ENGINE_PARITY.md) | 4엔진 정합성 매트릭스와 사고 이력 |
| [`docs/DESIGN.md`](docs/DESIGN.md) | 초기 아키텍처와 설계 의도 |
| [`docs/PROGRESS.md`](docs/PROGRESS.md) | 작업 진행 및 운영 맥락 |
| [`docs/KILL_CRITERIA.md`](docs/KILL_CRITERIA.md) | 전략 중단 기준 |
| [`docs/SINGLE_STRATEGIES_GUIDE.md`](docs/SINGLE_STRATEGIES_GUIDE.md) | 단일 전략 안내 |
| [`docs/Deploying operating servers.md`](docs/Deploying%20operating%20servers.md) | 운영 서버 배포 |

### 문서를 믿어도 되는가

2026-09-15 에 문서를 셋으로 나눴다 — **현행** / **시점 한계가 있는 참조** / **이력**.
분류와 각 문서의 한계는 [`docs/README.md`](docs/README.md) 에 있고, 낡은 문서는 열면
최상단에 경고 배너가 보인다. 과거 기록은 [`docs/archive/`](docs/archive/README.md) 와
[`docs/old/`](docs/old/README.md) 에 모아 두었다.

낡은 문서가 현행처럼 읽혀 실제로 잘못된 근거가 인용된 적이 있다. **의심되면 문서보다
코드와 테스트를 믿을 것.**

---

## 저장소 관습

- 가격·금액 계산은 전부 `BigDecimal` (부동소수점 오차 방지)
- 주석과 로그 메시지는 한국어
- **주석에 줄 수·개수를 박아두지 않는다.** 갱신되지 않은 수치가 사실처럼 남아 실제로 혼선을
  일으킨 전례가 있다. 규모가 필요하면 그때 세고 기준을 함께 밝힌다.
- 코드 전수 집계는 `grep -a`로 한다 — `StrategyWeightOptimizer.java`가 맵 키 구분자로 NUL 문자를
  쓰기 때문에, 그냥 `grep`을 쓰면 이 파일을 binary 로 보고 조용히 건너뛴다.

## 스크립트

`scripts/`에는 상시용과 1회성이 섞여 있다. 파일명에 날짜(`_0902`, `_0908` 등)가 붙은 것은
특정 시점의 운영·A/B 작업용이므로 그대로 재실행하지 말 것.

상시용: `status.sh`(읽기 전용 상태 점검) · `security-check.sh` · `db-restore-drill.sh`
