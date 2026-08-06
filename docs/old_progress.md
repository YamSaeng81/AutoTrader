# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 작업이 끝나면 완료 내용을 [`docs/CHANGELOG.md`](CHANGELOG.md)에 추가하고, 이 파일의 해당 항목은 삭제한다.
> **변경 이력**: [`docs/CHANGELOG.md`](CHANGELOG.md)
> **마지막 갱신**: 2026-08-06 (**[P2 후속] DYNAMIC SL 워치독 신설 + 헬스체크 이력 화면 + MSW 미들웨어 버그 수정** — 결정 대기 항목 중 실행 가능한 것부터 처리. ① DYNAMIC엔 이제껏 LIVE의 §9류 SL 미점검 워치독 자체가 없었다 — WS 실시간 판정이 조용히 멎어도 아무도 몰랐다(2026-08-03 ELSA 2.1%p SL 이탈 사고가 이 사각지대와 무관하지 않음). LIVE와 동일 패턴(3분 미점검 시 경보 + 그 코인만 REST 강제 갱신)으로 신설. ② `daily_health_snapshot` 조회 화면 신설(`/admin/health-check`) — `GET .../history` API 추가 + Next.js 페이지, mock 데이터로 렌더링·트리거 버튼·상세 확장까지 브라우저 실검증 완료. ③ **부수 발견** — `proxy.ts`의 인증 미들웨어 matcher가 `mockServiceWorker.js`를 제외하지 않아, 로그인 전에는 MSW 서비스워커 등록 자체가 리다이렉트로 막혀 "Initializing MSW..."에 무한 대기하는 결함을 발견·수정(1줄). 신규 테스트 6건 + 전체 스위트 233건(스킵 1) 전부 통과. Walk Forward 게이트 활성화·LIVE time stop 활성화는 데이터 기반 운영 판단이 필요해 보류 결정)
>
> **이전 갱신**: 2026-08-06 (**[P2] 운영 헬스체크 자동화 + SL 워치독 대응 조치 추가** — ① 그동안 인시던트마다 psycopg2로 손수 돌리던 4대 점검(세션 잔고 정합성·주문 시퀀스 갭·유령 포지션·time stop 없이 24h+ 고착된 포지션)을 `OperationalHealthCheckService`가 매일 08:30 KST 자동 실행·`daily_health_snapshot`(V65)에 기록하고 이상 시 Discord 알림. **감지·알림만 하고 자동 정산은 하지 않는다**(LIVE 유령 포지션 자동 정산은 실거래 자금에 직접 손대는 범위 확장이라 이번 스코프 밖). ② LIVE의 §9 SL 미점검 워치독이 그동안 **경보만** 보내던 것을, 그 코인 하나만 REST로 즉시 강제 갱신해 SL 감시를 스스로 복구 시도하도록 확장 — 전역 WS 폴백(`isWsUnhealthy`)이 놓치는 "다른 코인은 정상인데 이 코인만 조용히 끊긴" 사각지대를 메운다. 신규 테스트 18건 + 전체 스위트 227건(스킵 1) 전부 통과)
>
> **이전이전이전 갱신**: 2026-08-06 (**[P1] LIVE/DYNAMIC 청산 엔진 통합 (2/2)** — SL/TP 공식(ATR 기반)과 time stop 판정을 신규 클래스로 추출해 두 서비스가 공유하도록 배선. LIVE는 이제껏 고정 stopLossPct만 쓰고 있었는데(세션 194 BTC 136시간 고착의 원인), 신규 진입부터 DYNAMIC과 동일한 ATR 기반 SL/TP를 쓴다. time stop은 마이그레이션으로 LIVE에도 컬럼 신설했으나 **기본값 0(비활성)이라 배포해도 트리거되지 않는다** — SL/TP 폭만 즉시 바뀐다. 기존 SL/TP 회귀 테스트 10건을 이름만 바꿔 통과시켜 이동 중 계산 결과 불변 확인 + 신규 9건 통과 + 기존 LIVE 스위트 무회귀)
>
> **이전이전이전이전 갱신**: 2026-08-06 (**[P1] 신호 기대값 검증 게이트 신설** — Walk Forward 검증과 실자본 배정이 분리돼 있던 문제. 전략별 최근 Walk Forward 결과(verdict·OOS 기대값)를 판정해 `DynamicTradingService`·`LiveTradingService` 세션 생성에 배선. 미리보기 API `GET /api/v1/strategies/walk-forward-gate-status` 신설. 🔴 **기본값 비활성**(`REQUIRE_WALK_FORWARD_GATE=false`) — 대부분의 기존 전략이 Walk Forward 이력이 없어 기본 on으로 배포하면 신규 세션 생성이 전면 중단된다. 신규 테스트 12건 + 전체 스위트 200건 전부 통과)
>
> **이전이전이전이전이전 갱신**: 2026-08-06 (**세션 카드 모바일 깨짐 수정 + MSW 목이 통째로 죽어 있던 문제 발견** — 사용자 실기기 제보. 세션 카드가 한 줄 고정(`flex-wrap`·`shrink-0` 없음)이라 좁은 화면에서 텍스트가 **글자 단위로 세로 배열**되고 버튼이 화면 밖으로 밀려 누를 수 없었다. 🔴 **앞선 "28라우트 이상 없음" 검증이 이걸 놓친 이유 = MSW 핸들러 22개가 `/api/v1/`에 등록됐는데 클라이언트는 `/api/proxy/api/v1/`로 호출** → 프록시 도입 이후 목이 전부 매칭 실패, 사실상 빈 화면만 검사한 셈. 경로 접두사 수정 + 동적 세션 핸들러 신설 + 운영 구성을 본뜬 픽스처 추가 후 **데이터가 실제로 렌더된 상태에서 재검증(28라우트 × 6폭 전부 통과)**)
>
> **이전이전이전이전이전이전 갱신**: 2026-08-06 (**[P0 보안] DB 초기화 비밀번호 하드코딩 제거** — `DbResetService`의 `RESET_PASSWORD` 상수를 `DB_RESET_PASSWORD` 환경변수로 이관(fail-closed + 상수시간 비교 + 503 분리 응답), 테스트 4건 통과. 🔴 **배포 시 운영 `.env`에 값 추가 필수이며, git 이력에 구 값이 남아 있으므로 새 값으로 교체할 것.** 이어서 **페이지 단위 반응형 일괄 정리** — 여백을 MainContent로 이관(18곳 루트 패딩 제거), 헤더 줄바꿈 20곳, 가로 스크롤 2곳, pill 그룹 4곳, 중복 h1 제거. **28라우트 × 6폭 자동 점검에서 넘침·잘림 0**)
>
> **이전이전이전이전이전이전이전 갱신**: 2026-08-06 (**FE 네비게이션 5개 대분류 재편 + 모바일 대응** — 사이드바에 브레이크포인트가 하나도 없어 모바일에서 화면 65%를 먹던 구조를 교체. `navConfig.ts` 단일 소스 + 데스크톱 사이드바(`hidden lg:flex`) / 모바일 상단앱바·하단탭바·바텀시트·드로어. 그룹은 백테스트·모의투자 / 실전매매 / 전략관리 / **분석(신설)** / 설정. 활성 판정 버그(`/backtest`가 `/backtest/new`에서도 활성)와 패딩 없던 대시보드도 수정. `next build` ✅ + 1440/390px 브라우저 실검증. ⚠️ **e2e는 `@playwright/test` 미설치로 원래부터 실행 불가** — 스펙·인증 셋업은 새로 붙였으나 미실행. ⚠️ 페이지 단위 반응형(헤더 74곳)은 미착수)
>
> **이전이전이전이전이전이전이전이전 갱신**: 2026-08-06 (**전 세션(동적 7 + 실전 2) 운영 DB 점검** — 기반 건전성은 전부 정상(9/9 RUNNING·36h 무결손 틱·잔고 정합성 0원·주문 갭 0·FAILED 0). 신규/악화 결함 4건: ① **무출구 고착이 1→3세션으로 확대**(39·45 DOGE 37h, 40 XRP 71h = 자본 34% 동결) ② **동적 세션 총자산이 원가 기준**이라 미실현손실을 MDD·서킷브레이커가 못 본다 ③ **LIVE 194 BTC 136시간 보유**(고정 5% SL, time stop 없음) ④ **손절 텔레그램 알림 1건 유실**(세션 41, 재시도 없음). 08-05 배포한 SL 축소는 **배포 후 신규 진입 0건 → 여전히 표본 0**)
>
> **이전이전이전이전이전이전이전이전이전 갱신**: 2026-08-05 (**"손절 5%인데 7~8%" 원인 규명 완료** — 버그가 아니라 07-31 개편으로 5%가 **하한**이 된 설계대로의 동작. SL 배수 2.0→1.5·상한 12%→8%, TP 절대 상한 8% 신설, **REST 폴백에서 동적 세션 코인이 통째로 빠져 있던 결함**(ELSA −8.33%의 실제 원인) 수정, BLACK_SWAN 진입가 가드 추가. 176건 통과 + 무력화 검증. **✅ 09:52 KST 배포 완료 — 재기동 건전성 이상 없음**. ⚠️ 단 **효과는 3건 미검증** — 배포 후 신규 매수 0건(장세가 전량 SELL 신호)이라 SL/TP 폭 판정 불가. **다음 동적 진입 1건이 나오면 판정 쿼리 실행할 것.** ⚠️ LIVE 세션은 여전히 고정 5% + 구형 SL 조임 ratchet 잔존 — 사용자 판단 대기)
>
> **이전이전이전이전이전이전이전이전이전이전 갱신**: 2026-08-04 (멀티코인 24h 운영 분석 + 코드 검증. **08-03 수정분 배포 확인** — 블랙스완 쿨다운·미실현손익 실동작 증거 확보. P0 3종(잔고 누수·유령 포지션·시퀀스 갭) **재발 0**. ⚠️ 오전 분석의 P1 2건은 코드 확인 결과 **오판 — 정정 완료**. `blocked_reason` 누락 수정은 **배포 완료·실동작 확인**. 그 재기동에서 **BLACK_SWAN 쿨다운 소실이 실제로 발생** → `strategy_log` 복원으로 수정, **🔴 2차 배포 대기**. 사용자 결정 대기: time stop 재활성화 여부)

---

## ✅ 2026-08-06 [P2 후속] DYNAMIC SL 워치독 + 헬스체크 이력 화면 + MSW 미들웨어 버그

> P2 완료 후 사용자에게 결정 대기 항목 5건에 대한 추천을 제시했고, 그중 코드로 바로 실행 가능한 두 건(③ DYNAMIC SL 워치독, ④ 헬스체크 화면)을 사용자가 승인해 진행. 나머지(① Walk Forward 게이트 활성화, ② LIVE time stop 활성화, ⑤ 신호 기대값 음수)는 데이터 기반 운영 판단이 필요해 보류 — 아래 "보류 결정" 절 참조.

### ③ DYNAMIC SL 워치독 신설 — [DynamicTradingService.warnStaleSlCheck](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java)

- **공백**: LIVE에는 P2에서 방금 대응 조치까지 넣은 §9 워치독이 있는데, **DYNAMIC엔 이런 워치독 자체가 처음부터 없었다.** WS 실시간 SL/TP 판정(`doOnRealtimePriceEvent`)이 조용히 멎어도 아무도 알아채지 못하는 채로 60초 폴링만 남을 수 있었다 — 2026-08-03 ELSA가 SL을 2.1%p 지나쳐서야 체결된 사고가 이 사각지대와 무관하지 않다.
- **구현**: LIVE와 동일 패턴 — 세션별 `lastSlCheckAt`을 `doOnRealtimePriceEvent`에서 기록, 60초 주기로 `POSITION_MONITORING` 세션 중 3분 이상 미점검인 것을 찾아 그 코인만 `forceRefreshPrice`(REST 강제 조회 → `RealtimePriceEvent` 발행, 정상 WS 틱과 동일 경로)로 즉시 복구를 시도하고 텔레그램으로 성공/실패를 구분해 알린다.
- `ApplicationEventPublisher`를 신규 생성자 의존성으로 추가(기존엔 없었음 — DYNAMIC이 이벤트를 직접 처리만 하고 발행한 적은 없었다).
- **테스트**: [DynamicTradingSlWatchdogRecoveryTest](../web-api/src/test/java/com/cryptoautotrader/api/service/DynamicTradingSlWatchdogRecoveryTest.java) 6건 — `forceRefreshPrice` 성공/실패, `warnStaleSlCheck` 복구성공·복구실패·신선세션스킵·SCANNING세션(보유없음)스킵.

### ④ 헬스체크 이력 화면 — `/admin/health-check`

- 백엔드: `GET /api/v1/admin/health-check/history`(`HealthCheckController`) 신규 — `daily_health_snapshot` 최신순 반환.
- 프런트: [admin/health-check/page.tsx](../crypto-trader-frontend/src/app/admin/health-check/page.tsx) 신설 — 스냅샷 카드 목록(잔고 정합성·시퀀스 갭·유령 포지션·무출구 고착 4개 배지, 이상 0건 시 녹색 "이상 없음"), "지금 점검 실행" 트리거 버튼, 이상 항목 상세 펼치기. 기존 `settings/db-reset` 페이지 패턴(헤더 구조, 배너, MainContent 루트 패딩 위임 등)을 그대로 따랐다. `navConfig.ts` 설정 그룹에 등록.
- `api.ts`에 `adminHealthCheckApi`, `mocks/handlers.ts`에 두 엔드포인트 모두 `/api/proxy` 접두사로 등록(과거 "MSW 핸들러 전멸" 사고 재발 방지 원칙 준수).

### 🔴 부수 발견 — MSW 서비스워커가 로그인 전엔 등록 자체가 안 됐다

브라우저 실검증(Playwright)을 위해 mock 모드로 개발 서버를 띄웠더니 "Initializing MSW..."에서 무한 대기했다. 원인: [proxy.ts](../crypto-trader-frontend/src/proxy.ts)의 인증 미들웨어 matcher(`/((?!_next/static|_next/image|favicon.ico).*)`)가 `/mockServiceWorker.js`를 제외 목록에 넣지 않아, **로그인 전 상태에서 서비스워커 스크립트 요청 자체가 `/login`으로 리다이렉트**되고 있었다. MSW의 `Service Worker script resource is behind a redirect` 오류가 콘솔에 떴다.

- **수정**: matcher에 `mockServiceWorker.js` 추가(1줄). 로그인 화면부터 MSW가 정상 등록됨을 확인.
- 이 버그의 영향 범위는 "개발자가 로그인 전 화면(로그인 페이지 자체 등)을 mock 모드로 검증하려 할 때"로 좁다 — 로그인 후에는 무관했을 가능성이 있으나, 확인 없이 방치하면 다음 사람이 또 이 문제로 시간을 쓸 것이므로 즉시 고쳤다.

### 검증

- `:web-api:compileJava`/`compileTestJava` ✅, 신규 테스트 6건 통과
- `next build` ✅, `tsc --noEmit`(신규 파일 기준 에러 0 — 기존 무관 파일 5건은 그대로)
- **브라우저 실검증(Playwright, mock 모드)** — 로그인 → `/admin/health-check` 이동 → 스냅샷 2건 렌더(이상 1건/이상 없음 배지 정확) → 상세 펼치기(`LIVE#194 posId=2378 KRW-BTC 보유 136시간` 정확히 표시) → "지금 점검 실행" 클릭 → POST/GET 재조회 정상, **콘솔 에러 0건**
- **전체 스위트 233건(스킵 1) 전부 통과** — 무회귀 확인

### ⚠️ 결정 보류 항목 (이번엔 손대지 않음)

- **Walk Forward 게이트 활성화(`REQUIRE_WALK_FORWARD_GATE`)** — 대부분 전략이 Walk Forward 이력이 없어 켜는 순간 신규 세션 생성이 전면 중단된다. 켜기 전에 운영 중인 전략들에 Walk Forward부터 돌려 통과시켜야 하는데, 이건 운영 판단·백테스트 실행이 필요한 별도 작업이라 이번엔 보류.
- **LIVE time stop 활성화** — 전역 기본값 0(비활성)은 유지. DYNAMIC도 처음엔 세션 하나(세션 40)만 테스트 삼아 켰던 전례를 따르는 게 안전하다 — LIVE는 유령 포지션 자동 정산이 아직 없어(이번 P2에서 감지만 추가) DYNAMIC보다 보수적으로 접근해야 한다. 필요한 세션에 `maxHoldHours`를 개별 지정하는 기존 API로 이미 가능하다.
- **신호 기대값 자체가 음수(BUY 4h −2.17%/24h −4.47%)** — 게이트(Walk Forward)로 못 고치는 전략/신호 모델 자체의 문제라 완전히 별개 과제로 남겨둠.

---

## ✅ 2026-08-06 [P2] 운영 헬스체크 자동화 + SL 워치독 대응 조치 추가

> P1(청산 엔진 통합 2건) 완료 후 사용자 확인 하에 진행한 P2. 두 후보 — ① 실시간 SL 감시 워치독이 알림만 하고 대응이 없는 문제, ② 인시던트마다 psycopg2로 손수 돌리던 운영 헬스체크 자동화 — 중 사용자가 "헬스체크 자동화부터"로 순서를 정해 ①→② 순으로 진행.

### ② 운영 헬스체크 자동화 — [OperationalHealthCheckService](../web-api/src/main/java/com/cryptoautotrader/api/service/OperationalHealthCheckService.java) 신설

`docs/PROGRESS.md`의 08-03~08-06 사고 기록을 보면 같은 4가지 SQL을 사고가 날 때마다 손으로 돌려 왔다(세션 잔고 정합성, 주문 시퀀스 갭, 유령 포지션, 무출구 고착 포지션). 이걸 매일 자동 실행 + 이력 저장 + 이상 시 Discord 알림으로 대체했다.

- **① 세션 잔고 정합성** — `reconcileDynamicSessionBalance`(DYNAMIC 전용, 자동 복원)와 같은 불변식(포지션·활성주문 없으면 `available == total`)을 **LIVE까지 포함해 감지만** 한다. LIVE는 지금까지 이 검사 자체가 없었다.
- **② 주문 시퀀스 갭** — `order_id_seq.last_value`와 실제 `MAX(id)`의 차이. 2026-07-29·07-31 P0(매수/매도 후처리 트랜잭션 롤백으로 주문 행이 소멸)가 전부 이 패턴이었다. 🔴 **Postgres 전용 쿼리** — H2 테스트 환경에서는 예외가 나므로 `checked=false`("확인 불가")로 안전하게 처리하고, 실제 갭 계산은 운영 DB에서만 유효하다. 0(정상)과 확인 불가를 컬럼으로 구분(`sequence_gap_checked`).
- **③ 유령 포지션** — 매도 FILLED인데 포지션이 아직 OPEN. DYNAMIC은 `reconcileDynamicGhostPositions`가 30초마다 자동 정산하므로 평소엔 0건이 정상 — 여기서 잡히면 그 안전망 자체가 고장난 신호다. **LIVE는 대응하는 자동 정산이 아예 없어 이 점검이 유일한 방어선.**
- **④ 무출구 고착 포지션** — `max_hold_hours ≤ 0`(time stop 비활성) + 24시간 이상 보유. LIVE 세션 194 BTC 136시간 고착이 이 패턴.
- **의도적으로 감지·알림만, 자동 정산은 하지 않는다** — DYNAMIC 유령 포지션 자동 정산은 이미 있고, 그 로직을 LIVE에 새로 복제하는 것은 실거래 자금에 직접 손대는 범위 확장이라 별도 검토가 필요하다고 판단해 이번 스코프에서 제외했다.
- **저장**: [V65](../web-api/src/main/resources/db/migration/V65__create_daily_health_snapshot.sql) `daily_health_snapshot` 테이블(건별 카운트 + JSONB 상세). **매일 08:30 KST** 자동 실행 + `POST /api/v1/admin/health-check/trigger`로 수동 트리거 가능([HealthCheckController](../web-api/src/main/java/com/cryptoautotrader/api/controller/HealthCheckController.java)).
- **테스트**: [OperationalHealthCheckServiceTest](../web-api/src/test/java/com/cryptoautotrader/api/service/OperationalHealthCheckServiceTest.java) 12건 — 4대 점검 각각의 탐지·오탐 방지(유예시간·보유중·time stop 활성) 케이스.

### ① SL 워치독에 강제 복구 조치 추가 — [LiveTradingService.warnStaleSlCheck](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java)

- **문제**: `pollRestTickerFallback`(2026-08-05)은 이미 WS 전역 폴백을 두고 있지만, `isWsUnhealthy`는 **전역 틱 신선도**만 본다. 다른 코인(BTC 등)은 계속 틱이 오는데 **이 세션의 코인만** 조용히 구독이 끊기면 전역 판정은 "정상"이라 폴백이 발동하지 않는다(`pollRestTickerFallback` 문서에 이미 "남는 한계"로 적혀 있던 사각지대). 그동안 `warnStaleSlCheck`는 3분 이상 SL 미점검을 발견하면 **경보만** 보내고 사람이 조치할 때까지 그 세션의 SL 감시가 비어 있었다.
- **수정**: `forceRefreshPrice(coinPair)` 신설 — 그 코인 하나만 REST로 즉시 시세를 가져와 `RealtimePriceEvent`를 발행한다. **정상 WS 틱과 완전히 동일한 경로**(throttle, SL/TP 판정)를 타므로 새 매매 로직이 아니라 "끊긴 틱 하나를 대신 채워주는" 것에 지나지 않는다. 이 강제 갱신 자체가 실패하면(거래소 API 오류 등) 알림 문구를 다르게 보내 사람 개입이 필요함을 명확히 구분한다.
- **범위**: LIVE만. DYNAMIC은 애초에 이런 SL-미점검 추적 자체가 없다 — 별도 과제로 남겨둠(이번엔 "기존 워치독에 조치 추가"로 범위를 좁혔다).
- **테스트**: [LiveTradingSlWatchdogRecoveryTest](../web-api/src/test/java/com/cryptoautotrader/api/service/LiveTradingSlWatchdogRecoveryTest.java) 6건 — `forceRefreshPrice` 성공/예외/빈응답 3건 + `warnStaleSlCheck` 통합(복구 성공 문구·복구 실패 문구·신선한 세션 스킵) 3건. `@MockBean UpbitRestClient`·`TelegramNotificationService`로 실제 거래소 API·텔레그램 호출 없이 검증.

### 검증

- `:web-api:compileJava`/`compileTestJava` ✅
- 신규 테스트 18건(헬스체크 12 + SL 워치독 6) 전부 통과
- **전체 스위트 227건(스킵 1) 전부 통과** — 무회귀 확인

### ⚠️ 후속

- [ ] `daily_health_snapshot` 이력을 볼 수 있는 화면이 아직 없다(API/테이블만 존재) — 필요해지면 `/settings` 계열에 조회 페이지 추가.
- [ ] 헬스체크가 잡아낸 유령 포지션·잔고 정합성 이상을 LIVE에서도 자동 정산할지는 별도 결정 필요 — 이번엔 의도적으로 감지·알림까지만.
- [ ] DYNAMIC에도 SL-미점검(§9류) 추적을 넣을지는 미착수 — LIVE와 달리 원래 이런 워치독 자체가 없었다.

---

## ✅ 2026-08-06 [P1] LIVE/DYNAMIC 청산 엔진 통합 (2/2) — SL/TP 공식 + time stop 공유

> P1 두 건 중 사용자가 먼저 선택한 "신호 기대값 검증 게이트"에 이은 두 번째 항목. 전체 리팩터링(리스크 큼) 대신, 실제로 갈라져 있던 두 지점 — **SL/TP 산정 공식**과 **time stop 유무** — 만 공유 함수로 추출하는 좁은 범위로 사용자가 직접 선택.

### 조사 결과 — 08-06에 이미 좁혀진 상태였음

블랙스완 발동 시 SL 조임은 이미 양쪽 다 제거됐다(08-06 앞선 작업). 남은 두 갈래:

1. **SL/TP 공식** — DYNAMIC은 `resolveStopLossPct`(ATR(14)×1.5배, 세션값을 하한으로 clamp, 상한 8%)를 쓰는데 LIVE는 여전히 고정 `stopLossPct` 그대로였다. LIVE 194 BTC가 136시간 물려 있던 원인.
2. **Time stop(`maxHoldHours`)** — DYNAMIC에는 있고(V62/V63, 현재 1세션만 활성) LIVE에는 컬럼조차 없었다.

### 구현 — [ExitRuleCalculator](../web-api/src/main/java/com/cryptoautotrader/api/service/ExitRuleCalculator.java) 신설

- `DynamicTradingService`에 있던 `resolveStopLossPct`/`resolveTakeProfitPrice`(2026-07-31 개편 + 2026-08-05 재조정 이력 포함)를 그대로 이 클래스로 이전. DYNAMIC은 이 함수를 호출하도록 바뀌었을 뿐 **계산 결과는 동일**(순수 이동).
- `shouldTimeStop(maxHoldHours, openedAt, now)` 신규 — time stop 판정도 이 클래스로 통합해 DYNAMIC·LIVE가 같은 함수를 쓴다.
- **LIVE만 동작이 바뀐다**: `executeSessionBuy`가 `evalCandles`를 받아 ATR 기반 SL/TP를 계산하도록 교체(기존엔 고정 `stopLossPct` + `takeProfitMultiplier`, 클램프 없음). 매수 시그널 평가에 이미 쓰던 `evalCandles`를 그대로 전달만 하면 됐다.
- **V64 마이그레이션** — `live_trading_session.max_hold_hours` 컬럼 신설, 기본값 0(비활성). `LiveTradingStartRequest.maxHoldHours` 필드 추가, 틱 루프의 익절 체크 직후에 time stop 체크 삽입(DYNAMIC과 같은 위치·같은 로그 포맷).

### 🔴 기본값은 비활성 — 배포해도 지금 당장은 SL/TP 폭만 조용히 넓어진다

Time stop은 `maxHoldHours` 미지정 시 0(비활성)이라 **기존 세션·신규 세션 전부 이전과 동일하게 동작**한다. 다만 **SL/TP 공식 자체는 즉시 바뀐다** — LIVE 신규 진입부터 고정폭이 아니라 ATR 기반 변동폭을 쓴다(이건 게이트가 아니라 07-31 개편을 뒤늦게 이식하는 것이라 별도 플래그를 두지 않았다. DYNAMIC은 이미 8일째 이 공식으로 운영 중이라 검증된 변경이다).

### 검증

- 순수 로직 이동 검증 — 기존 `DynamicStopLossWidthTest`(6건)·`DynamicTakeProfitCapTest`(4건)를 `ExitRuleCalculator` 호출로 이름만 바꿔 그대로 통과시켜, 이동 과정에서 계산 결과가 안 바뀌었음을 확인.
- `shouldTimeStop` 신규 단위 테스트 7건(`ExitRuleCalculatorTest`) — null/0/음수 비활성, 개설시각 없음, 경계값, 초과(세션 194 136시간 재현 시나리오).
- `maxHoldHours` 배선 테스트 2건(`LiveTradingMaxHoldHoursTest`) — 미지정 시 0 저장, 명시 시 그 값 저장.
- 기존 LIVE 회귀 스위트(`LiveTradingReliabilityTest`·`SessionCapitalGuardTest`·`SessionKindIsolationTest`) 전부 통과 — SL/TP 공식 교체가 다른 경로를 깨지 않았음을 확인.
- `:web-api:compileJava`/`compileTestJava` ✅, 전체 스위트 재확인 중.

### ⚠️ 후속

- [ ] LIVE에 time stop을 실제로 켤지는 사용자 결정 — DYNAMIC처럼 세션별로 필요할 때만 켜는 정책 유지 권장(V62 배포 직후 터진 매도 후처리 롤백 P0 전례 때문에 DYNAMIC도 기본 0으로 내렸다).
- [ ] 틱 루프에서 time stop이 실제로 트리거되는 E2E는 검증하지 않음 — 기존 DYNAMIC의 SL/TP 트리거도 틱 레벨 통합 테스트가 없고 실거래 모니터링으로 확인해 온 것과 동일한 컨벤션을 따름(순수 판정 함수는 단위 테스트로 잠금).
- [ ] LIVE 신규 진입의 SL/TP 폭이 ATR 기반으로 바뀐 효과는 다음 실제 진입 건에서 확인 필요(DYNAMIC 07-31/08-05와 같은 패턴).

---

## ✅ 2026-08-06 [P1] 신호 기대값 검증 게이트 신설 — Walk Forward를 실자본 배정에 연결

> P1 두 건(신호 기대값 검증 / LIVE·DYNAMIC 청산 엔진 통합) 중 사용자가 먼저 선택. 최근 7일 동적 세션 BUY 신호(n=50) 사후수익률이 4h -2.17%/24h -4.47% — **기대값이 음수인 신호가 그대로 실자본을 쓰고 있는 문제**의 근본 원인 조사 결과, "검증"과 "자본 배정"이 완전히 분리돼 있었다.

### 근본 원인

- `StrategyLiveStatusRegistry`(ENABLED/BLOCKED 매트릭스)는 **특정 시점 백테스트를 사람이 손으로 기록**한 것(예: "2026-04-30 백테스트")이라 실시간 성과와 무관하게 고정돼 있다.
- `StrategyDegradationWatchdog`는 6시간마다 실전 신호 품질을 감시하지만 `sessionType="REAL"`(LIVE)**만** 보고, **DYNAMIC은 아예 감시 대상이 아니었다.** 게다가 저하를 발견해도 Discord 알림만 보내고 아무것도 차단하지 않는다.
- `/backtest/walk-forward` 페이지는 Out-of-Sample 검증을 실행하고 `backtest_run.wf_result_json`에 결과(verdict·기대값)를 저장하지만, **그 결과를 세션 생성 어디서도 참조하지 않았다.** 검증 인프라는 있는데 게이트가 없었다.

### 구현 — [WalkForwardValidationGate](../web-api/src/main/java/com/cryptoautotrader/api/service/WalkForwardValidationGate.java)

전략명 기준(코인 무관 — 기존 `StrategyLiveStatusRegistry`와 동일한 세분화)으로 **가장 최근 Walk Forward 실행 결과**를 판정한다.

| 조건 | 판정 |
|---|---|
| 실행 이력 없음 | 차단 — "아직 증명되지 않음" |
| verdict = OVERFITTING | 차단 — 기대값이 양수여도 IS→OOS 하락폭이 커서 불신 |
| OOS 병합 거래 수 < 5 | 차단 — 표본 부족 |
| OOS `expectancyPct` ≤ 0 | 차단 |
| 그 외(ACCEPTABLE·CAUTION + 기대값>0 + n≥5) | 통과 |

- `DynamicTradingService.createSession()` · `LiveTradingService.createSession()`(→`createMultipleSessions`도 경유) 양쪽에 `StrategyLiveStatusRegistry.isBlocked()` 체크 바로 다음 줄로 배선 — 기존 거버넌스 차단 패턴과 동일한 자리.
- 미리보기 API `GET /api/v1/strategies/walk-forward-gate-status` 신설 — 게이트를 강제하지 않고도 전략별 통과/차단 여부를 확인할 수 있다(`gateEnabled` 플래그 + 전략별 `passed`/`reason`).

### 🔴 기본값은 비활성 — 배포해도 지금 당장은 아무것도 안 바뀐다

`strategy-validation.require-walk-forward-gate`(env `REQUIRE_WALK_FORWARD_GATE`) 플래그로 제어, **기본 `false`**. 이유: 기존 전략 대부분이 Walk Forward 실행 이력이 아예 없어서, 기본으로 켜서 배포하면 **신규 세션 생성이 전면 중단**된다. 켜기 전에 미리보기 API로 어떤 전략이 막히는지 먼저 확인하고 판단할 것.

- **[ ] 사용자 결정 필요**: 언제 켤지, 그 전에 최소한 현재 운영 중인 전략들(COMPOSITE_MOMENTUM_ICHIMOKU_V2 등)에 대해 Walk Forward를 먼저 돌려 통과시켜 둘지.

### 검증

- 순수 판정 로직 단위 테스트 7건(`WalkForwardValidationGateTest`) — 이력없음/OVERFITTING/표본부족/기대값 0 이하/정상통과/CAUTION 허용/경계값.
- Spring 통합 테스트 — 게이트 비활성(기본값) 시 기존 동작 무변경 확인 1건(`WalkForwardGateDisabledSessionCreationTest`), 게이트 활성 시 차단/통과 3건(`WalkForwardGateEnabledSessionCreationTest`: 이력없음 거부·기대값 양수 통과·기대값 음수 거부·OVERFITTING 거부).
- `:web-api:compileJava` ✅ / 신규 테스트 12건 전부 통과 / 전체 스위트 회귀 확인 중.

### ⚠️ 후속

- [ ] 미리보기 API를 `/strategies` 페이지에 배지로 노출(현재는 API만 있고 프런트 표시 없음).
- [ ] `StrategyDegradationWatchdog`를 DYNAMIC까지 확장하고, 저하 발견 시 Discord 알림에 그치지 않고 이 게이트와 연동해 자동으로 재차단할지는 별건 — 이번 스코프는 "사전(Walk Forward) 게이트"만.
- [ ] 코인별 세분화는 하지 않음(전략명 단위) — DYNAMIC은 워치리스트가 여러 코인이라 코인별 이력이 대부분 없어 세분화하면 사실상 전면 차단이 됨. 향후 코인별 근거가 쌓이면 재검토.

---

## ✅ 2026-08-06 세션 카드 모바일 레이아웃 깨짐 수정 + **MSW 목이 통째로 죽어 있던 문제**

> 사용자 제보(운영 배포 후 실기기 스크린샷): 메뉴는 좋은데 실전매매·동적 멀티코인의 **세션별 정보가 겹쳐 보인다.**

### 🔴 왜 앞선 "28라우트 × 6폭 이상 없음" 검증이 이걸 놓쳤나

**MSW 목 핸들러가 하나도 매칭되지 않고 있었다.** 핸들러는 `/api/v1/...`에 등록돼 있는데, 클라이언트는 `axios baseURL='/api/proxy'`([lib/api.ts](../crypto-trader-frontend/src/lib/api.ts))를 거쳐 `/api/proxy/api/v1/...`로 호출한다. 프록시 도입 시점부터 **22개 핸들러 전부가 죽은 채**였고, 목 개발 모드에서 모든 화면이 조용히 빈 상태로 떴다.
→ 이전 검증은 **세션이 0건인 빈 화면만 훑은 셈**이라 카드 레이아웃을 통과시켰다.

- **핸들러 22개 경로에 `/api/proxy` 접두사 부여** + 파일 상단에 재발 방지 주석.
- **동적 세션 핸들러 신설**(목록·상세) — 아예 없었다.
- [data.ts](../crypto-trader-frontend/src/mocks/data.ts)에 **운영 DB 구성을 본뜬 픽스처** 추가: 동적 7세션(`COMPOSITE_*` 긴 전략명, 감시목록 8종, POSITION_MONITORING 포함) + LIVE 2세션. 빈 배열이면 검증이 무의미하므로 **길고 많은 데이터**로 잡았다.

### 레이아웃 수정

**근본 원인:** 세션 카드가 `flex items-center justify-between` **한 줄 고정**인데 `flex-wrap`이 없고, 중앙 자산 블록에 `shrink-0`도 없었다. 좁은 화면에서 flex가 텍스트 컨테이너를 최소 폭까지 짜내 **글자 단위로 세로 배열**되고(스크린샷의 "포지션감시"·"원금:10,000KRW"), 버튼은 화면 밖으로 밀려 **누를 수 없었다.**

- [trading/dynamic](../crypto-trader-frontend/src/app/trading/dynamic/page.tsx) · [trading](../crypto-trader-frontend/src/app/trading/page.tsx) 세션 카드를 `flex-col gap-3 lg:flex-row lg:items-center lg:justify-between`으로 전환. 자산 블록에 `shrink-0`·`whitespace-nowrap`, 버튼 그룹에 `flex-wrap`, 긴 전략명에 `break-all` 추가. 데스크톱 한 줄 레이아웃은 그대로 유지.
- 모바일 밀도 개선: 요약 카드 1열→**3열**(동적)·2열(실전), 카드 패딩 `p-5`→`p-3 sm:p-5`, 숫자 `text-2xl`→`text-xl sm:text-2xl`. 중첩 패딩(본문16+목록20+카드16=52px)으로 콘텐츠 폭이 301px밖에 안 되던 것을 완화.
- 실전매매 헤더 버튼 3개(테스트/새 세션/EMERGENCY STOP ALL) 그룹에 `flex-wrap`.
- `/performance` 청산일 필터 행 `flex-wrap` + 라벨 `whitespace-nowrap`(360px에서 글자 단위 접힘).

### 검증 (이번엔 데이터가 실제로 렌더된 상태)

- **28라우트 × 6폭(360·390·430·768·1024·1440)** — 가로 넘침 0, 잘림 0, **글자 단위 세로 접힘 0**(신규 판정 기준 추가: 리프 노드 폭<28px & 높이>60px).
- 390px·1440px 스크린샷 육안 확인 — 카드가 모바일에선 스택, 데스크톱에선 기존 한 줄 유지.
- `next build` ✅ / 변경 파일 lint ✅.

### ⚠️ 남은 것

- [ ] 세션 **상세** 페이지(`/trading/[sessionId]`, `/trading/dynamic/[id]`)는 목 핸들러가 없어 이번에도 미검증. 상세용 픽스처 추가 필요.

---

## ✅ 2026-08-06 [P0 보안] DB 초기화 비밀번호 소스 하드코딩 제거

**근본 원인:** [DbResetService](../web-api/src/main/java/com/cryptoautotrader/api/service/DbResetService.java)가 `private static final String RESET_PASSWORD = "!Iloveyhde1"` 로 비밀번호를 소스에 박고 있었다. HEAD에 있었고 **git 이력에도 남아 있다.**

- **환경변수 `DB_RESET_PASSWORD`로 이관** — `application.yml`에 `db-reset.password: ${DB_RESET_PASSWORD:}` 추가, 생성자 주입(`@Value`). `docker-compose.prod.yml` backend 환경변수와 `.env.example`에도 반영.
- **fail-closed** — 미설정이면 `checkPassword`가 **항상 false**를 반환해 모든 초기화를 거부한다. 설정 누락이 "누구나 초기화 가능"으로 이어지지 않게 했다. 기동 시 WARN 로그 1회.
- **상수 시간 비교** — `String.equals` → `MessageDigest.isEqual`. 타이밍 공격 표면 제거.
- **컨트롤러 응답 분리** — 미설정 상태를 "비밀번호 틀림"(401)으로 뭉뚱그리면 원인 파악이 어려워, `SettingsController`에서 **503 + 원인 명시** 응답을 따로 준다.
- `DbResetPasswordTest` 4건 신규(일치/불일치/미설정 fail-closed/구 하드코딩 값 무효). **테스트 통과 ✅ · `:web-api:compileJava` ✅**

### 🔴 배포 시 필수 조치

- [ ] **운영 서버 `.env`에 `DB_RESET_PASSWORD` 추가** — 넣지 않으면 설정>DB 초기화 화면이 503으로 막힌다(의도된 동작).
- [ ] **비밀번호 자체를 새 값으로 교체할 것.** 기존 값은 git 이력에 남아 있어 코드에서 지웠다고 안전해지지 않는다.

---

## ✅ 2026-08-06 FE 네비게이션 5개 대분류 재편 + 모바일 대응 — **빌드 ✅ / 브라우저 실검증 완료**

> 사용자 요청: "메뉴 보기가 불편, 특히 모바일에서 사이드바가 보기 힘들다. 백테스트·모의투자 / 실전매매 / 전략관리 / 분석 / 설정으로 재구성, 웹·모바일 모두 고려."

**근본 원인:** [Sidebar.tsx](../crypto-trader-frontend/src/components/layout/Sidebar.tsx)가 `fixed w-64`로만 렌더되고 [MainContent.tsx](../crypto-trader-frontend/src/components/layout/MainContent.tsx)가 무조건 `ml-64`를 줬다. **브레이크포인트가 하나도 없어** 390px 화면에서 사이드바가 폭의 65%를 먹고 본문이 화면 밖으로 밀려났다. 접기(w-16) 상태를 매번 수동으로 만들어야 겨우 쓸 수 있는 구조.

### 메뉴 재편 (기존 6그룹 → 5그룹)

단일 소스 [navConfig.ts](../crypto-trader-frontend/src/components/layout/navConfig.ts) 신설 — 데스크톱/모바일 3개 UI가 같은 정의를 공유한다.

| 그룹 (모바일 탭) | 항목 | 이동 내역 |
|---|---|---|
| **백테스트 · 모의투자** (검증) | 백테스트 이력 · 새 백테스트 · 전략 비교 · 모의투자 · 모의투자 이력 · 데이터 수집 | 기존 `백테스트` + `모의투자` 그룹 병합 |
| **실전매매** (실전) | 실전 매매 · 동적 멀티코인 · 실전매매 이력 · 계좌 현황 · 리스크 설정 | 손익 대시보드를 `분석`으로 이관 |
| **전략관리** (전략) | 전략 관리 · Walk Forward · 자동 스케줄 · LLM 전략 설정 · 뉴스 소스 | 전략 관리를 1순위로, AI 설정류 흡수 |
| **분석** (분석) | 손익 대시보드 · 신호 품질 분석 · 전략 로그 · LLM 호출 로그 · Notion 보고서 | **신설** — 기존엔 `설정`에 로그류가 섞여 있었음 |
| **설정** (설정) | Upbit 연동 상태 · Upbit 주문 로그 · 텔레그램 이력 · Discord 설정 · 서버 로그 · DB 초기화 | 로그 분석류를 `분석`으로 빼고 운영 설정만 남김 |

- `excludePrefix` 활성 판정 버그도 함께 수정: 기존 `pathname.startsWith(item.href)`는 `/backtest`가 `/backtest/new`에서도 활성으로 잡혔다. `href + '/'` 비교로 교체하고 `/backtest`에 형제 경로 exclude를 명시.
- 접힌 사이드바(w-16)에 **그룹 구분선** 추가, 툴팁을 `그룹 › 항목`으로 변경(아이콘만 보일 때 소속을 알 수 있게).
- 라우트 이동 시 해당 그룹이 자동으로 펼쳐진다(`useEffect` + `activeGroup`). 기존엔 초기 렌더에만 반영돼 SPA 이동 시 접힌 채였다.

### 모바일 대응 ([MobileNav.tsx](../crypto-trader-frontend/src/components/layout/MobileNav.tsx) 신설, `lg` 미만)

- **상단 앱바(h-14)** — 햄버거 · 현재 위치(`그룹 › 항목` 2줄) · 테마 토글. 어느 화면에 있는지 항상 보인다.
- **하단 탭바** — 홈 + 5개 대분류. 탭하면 그 그룹의 하위 메뉴 **바텀시트**가 열려 엄지 범위에서 끝난다. 현재 경로가 속한 탭이 하이라이트.
- **햄버거 드로어** — 전체 메뉴(아코디언) + 로그아웃. 백드롭 클릭·ESC·라우트 이동 시 자동 닫힘, 열려 있는 동안 배경 스크롤 잠금.
- 터치 타깃 전부 44px 이상, `env(safe-area-inset-*)`로 노치/홈 인디케이터 회피.
- 데스크톱 사이드바는 `hidden lg:flex`로 완전히 숨김 → 모바일에서 DOM에는 있으나 화면을 먹지 않는다.

### 레이아웃/스타일

- `MainContent`: `ml-0 pt-14 pb-[calc(3.5rem+safe-area)]` → `lg:ml-16/64 lg:pt-0 lg:pb-0`. 기존 `overflow-hidden`은 `overflow-x-clip`으로 교체(내부 `sticky` 유지하면서 가로 넘침만 차단).
- `globals.css`: `body { overflow-x: hidden }` + 바텀시트 `slideUp` 키프레임.
- 대시보드 루트에 `p-4 sm:p-6` 추가 — **패딩이 아예 없어** 제목·카드가 화면 좌우 끝에 붙어 있었다(데스크톱에서도).
- 중복 `aria-label="주 메뉴"`(사이드바/하단탭) 해소 → 하단탭은 `빠른 메뉴`.

### 검증

- `next build` **✅ 컴파일 성공**, `tsc --noEmit` 신규/수정 파일 **에러 0**(그 외 18줄은 기존 무관 파일).
- Playwright 브라우저로 **1440px·390px 양쪽 실화면 확인** — 데스크톱 5그룹 렌더, 모바일 앱바/탭바/시트/드로어 동작, `documentElement` 가로 넘침 **0px**, `main` 상하 패딩 56px 적용 확인.

### 2차 작업 — 페이지 단위 반응형 일괄 정리 (같은 날 후속)

**근본 원인:** 페이지 루트 여백이 `p-6` / `p-8` / `py-6 px-4` / **아예 없음**으로 30개 페이지가 제각각이었다. 여백이 없는 페이지(대시보드·실전매매·전략로그 등)는 **데스크톱에서도** 제목이 화면 끝에 붙어 있었다.

- **여백을 MainContent로 일괄 이관** — `px-4 py-4 sm:px-6 sm:py-6`. 개별 page.tsx 18곳의 루트 패딩을 제거해 이중 패딩을 없앴다. 이제 모든 페이지가 모바일 16px / 데스크톱 24px로 통일된다.
- **헤더 줄바꿈 20곳** — 제목+액션버튼 행에 `flex-wrap gap-3` 추가. 좁은 화면에서 버튼이 제목 아래로 내려간다(기존엔 화면 밖으로 잘려 **누를 수 없었다**). `items-start`를 쓰던 2곳은 정렬 유지.
- **가로 스크롤 컨테이너 2곳** — `settings/telegram` 표(고정폭 컬럼 합 > 모바일 폭)를 `overflow-x-auto` + `min-w-[720px]`로 감싸고, `settings/server-logs`는 `overflow-y-auto` → `overflow-auto`로 바꿔 긴 로그 한 줄이 잘리지 않게 했다.
- **세그먼트 필터 pill 그룹 4곳**(`logs`, `logs/llm`, `logs/signal-quality`)에 `flex-wrap` — 360px에서 넘치던 마지막 항목.
- **중복 h1 제거** — 사이드바 브랜드가 `<h1>`이라 페이지마다 h1이 2개였다. `<span>`으로 교체.
- **lint**: 새 코드가 걸린 `react-hooks/set-state-in-effect` 2건은 effect 대신 **렌더 중 상태 조정** 패턴으로 해결(중간 프레임 깜빡임도 함께 제거).

**검증** — Playwright로 **28개 라우트 × 6개 폭(360·390·430·768·1024·1440)** 자동 점검: 문서 가로 넘침 0px, 스크롤 컨테이너 밖으로 잘린 요소 **0개**(전 조합). 네비게이션 동작도 실측 확인 — 그룹 자동 펼침(SPA 이동·직접 진입 양쪽), 시트/드로어 라우트 이동 시 자동 닫힘, ESC 닫힘, 배경 스크롤 잠금·복구. `next build` ✅ / 변경 파일 lint ✅.

### ⚠️ 후속 (미조치)

- [ ] `/backtest/new`·`/backtest/walk-forward`는 **h1이 아예 없다**(제목 요소 미사용). 접근성상 페이지 제목 추가 권장.
- [ ] **e2e 스위트가 실행 불가 상태였다** — `@playwright/test`가 `package.json`에 **아예 없다**(`npm run test:e2e`는 모듈 없음으로 즉시 실패). 게다가 `proxy.ts` 인증 우회 장치가 없어 모든 테스트가 `/login`으로 리다이렉트됐다.
  - 이번에 [navigation.spec.ts](../crypto-trader-frontend/e2e/navigation.spec.ts)를 새 메뉴 구조에 맞춰 재작성(데스크톱 12 + 모바일 6 케이스, 뷰포트별 `test.use`)하고, [global-setup.ts](../crypto-trader-frontend/e2e/global-setup.ts)·[auth-fixtures.ts](../crypto-trader-frontend/e2e/auth-fixtures.ts)로 로그인→`storageState` 재사용을 붙였다. `playwright.config.ts`의 `webServer`도 셸 프리픽스(`VAR=x cmd`, Windows 미동작) → `env` 주입으로 교체.
  - **단, 의존성이 없어 실제 실행은 못 했다.** `npm i -D @playwright/test && npx playwright install chromium` 후 검증 필요.
---

## ✅ 2026-08-06 전 세션 운영 DB 점검 (동적 7 + 실전 2) — **기반 정상 / 자본 34% 무출구 동결**

> 운영 DB 직접 조회(2026-08-05 23:47~2026-08-06 00:0x UTC, 읽기전용). 대상: DYNAMIC RUNNING 39~45, LIVE RUNNING 194·195.

### 세션별 현황 (평가액 = 원화잔고 + 보유수량 × 최종 signal_price)

| 세션 | 전략 | 상태 | 보유 | 평가액 | 손익 |
|---|---|---|---|---|---|
| 39 | MOMENTUM_ICHIMOKU_V2 | POSITION_MONITORING | DOGE @101 (37.2h) | 9,873 | **−1.27%** |
| 40 | MEANREV_BB | POSITION_MONITORING | XRP @1,543 (71.2h) | 9,813 | **−1.87%** |
| 41 | MTF_BTC_STRICT | SCANNING | — | 9,436 | **−5.64%** |
| 42 | MTF_CONFIRMED | SCANNING | — | 10,000 | 0.00% |
| 43 | MTF_BTC | SCANNING | — | 8,805 | **−11.95%** |
| 44 | PULLBACK_MTF | SCANNING | — | 10,088 | **+0.88%** |
| 45 | MOMENTUM_ICHIMOKU | POSITION_MONITORING | DOGE @101 (37.2h) | 9,873 | **−1.27%** |
| **동적 합계** | | | | **67,889 / 70,000** | **−3.02%** |
| 194 | MEANREV_BB / BTC | RUNNING | BTC @91,135,000 (**136.2h**) | 10,052 | +0.52% |
| 195 | MOMENTUM_ICHIMOKU_V2 / ETH | RUNNING | — (**6일간 진입 0**) | 10,000 | 0.00% |
| **전체** | | | | **87,941 / 90,000** | **−2.29%** |

- 07-31 재시작 이후 실현손익 **−1,671원**(청산 4건: +87.78 / −564.20 / −528.48 / −666.22 → **승률 25%**), 미실현 −440원.

### ✅ 기반 건전성 — 이상 없음

| 점검 | 결과 |
|---|---|
| 세션 상태 | 9/9 RUNNING, 서킷브레이커 발동 0 |
| 틱 규칙성 | 최근 **36시간 전 구간 9세션 전원 기록**(시간당 13~40건), 결손 시간대 **0** |
| 잔고 정합성 | `available + 보유원가 − total` = **7세션 전부 0.00원** |
| 주문 시퀀스 | id 8588~8630 **갭 0**(max=8630) |
| 주문 실패 | 최근 7일 FAILED 4건 **전부 07-31 세션 38 P0 잔재**, 재시작 이후 **0건** |
| `blocked_reason` | 정상 기록(SCANNING/POSITION_MONITORING 사유 모두 확인) |
| 4h/24h 수익률 백필 | **정상** — HOLD 제외·비HOLD 신호만 백필하는 설계대로 동작(세션 40 BUY 1건→1건, 41·43 SELL 10건→10건, 44 SELL 140건→108건, 42·39·45·194·195 비HOLD 0건→0건) |

### 🔴 [P1] 무출구 고착이 1세션 → 3세션으로 확대 (자본 34% 동결)

- 08-05 보고 시점엔 세션 40 XRP 1건(27h)이었으나, 지금은 **39·40·45 세 세션 모두** SL(−5%)·TP(+10%)·전략 SELL 게이트 어디에도 걸리지 않는 사각지대에 있다.
  - 39·45: DOGE @101 → 99.40 (**−1.58%**, 37.2시간) / 40: XRP @1,543 → 1,507 (**−2.33%**, 71.2시간)
- 전 세션 `max_hold_hours=0`(비활성) 유지 → **무조건 탈출구 없음**. 묶인 자본 24,000원 = 동적 자본의 **34%**.
- 세 세션 모두 `POSITION_MONITORING`이라 워치리스트 스캔을 하지 않는다(로그 24h: 39·40·45 각 **25건** vs 스캔 세션 41~44 **186~204건**).

### 🔴 [P1 · 신규] 동적 세션 총자산이 원가 기준 → **미실현손실을 MDD·서킷브레이커가 못 본다**

- 세션 39·40·45의 `total_asset_krw`가 전부 정확히 **10,000.00**(= 원화 2,000 + 보유**원가** 8,000). 실제 평가액은 9,813~9,873이다.
- 같은 시점 LIVE 194는 `total_asset_krw`가 시가 평가로 갱신된다(10,045~10,052로 분당 변동). **동적/실전이 서로 다른 기준을 쓴다.**
- 결과: `mdd_peak_capital`도 10,000에 고정 → `risk_config.mdd_threshold_pct=20` / `max_portfolio_drawdown_pct=15` 가 **포지션을 들고 있는 동안에는 원리상 발동할 수 없다.** 손실이 실현되는 순간에만 인식된다.
- 대시보드의 동적 세션 총자산도 같은 이유로 실제보다 낙관적으로 표시된다.

### 🟠 [P2] LIVE 194 BTC 136시간 보유 — LIVE는 여전히 개편 사각지대

- pos 2378: 07-31 08:00 진입, **136.2시간(5.7일)** 보유. SL 86,613,400 = 진입가 **정확히 −5.00%**, TP = **+10.00%** → 07-31 ATR 기반 개편이 LIVE에 적용되지 않았음이 재확인됨(08-05 보고와 동일, **미조치 유지**).
- LIVE에는 time stop 개념 자체가 없어 위 P1보다 고착 기간이 길다.

### 🟠 [P2 · 신규] 실시간 SL 감시 워치독 경보 + 손절 알림 유실

- `⚠️ SL 미점검 3분 초과: 세션 194 (KRW-BTC). WS 상태를 확인하세요.` — 08-03 **3건**, 08-04 **2건**, 08-05 **1건**. 08-05 00:51 건은 재기동 직후라 설명되지만 **08-03·08-04 5건은 재기동과 무관** = 실계좌 포지션의 실시간 손절 감시가 실제로 끊긴 구간이 있었다.
- 08-04 18:32 세션 41 `STOP_LOSS` 텔레그램 **전송 실패(success=false)**. 1초 뒤 세션 43의 동일 유형은 성공 → 동시 전송 레이트리밋으로 보이며 **재시도가 없다.** 가장 중요한 알림 유형이 조용히 유실된다.

### 🟡 [관찰] 08-05 배포한 SL 축소는 **여전히 표본 0**

- 마지막 신규 매수는 **08-04 11:01(주문 8627)**, 배포는 08-05 00:52 → **배포 후 진입 0건, 37시간 경과.**
- 현재 보유 3건의 SL은 전부 배포 전 산정값이며 셋 다 하한(−5.00%)에 걸려 ATR 항이 개입하지 않은 케이스라 판정에 쓸 수 없다. **다음 진입 1건이 나오면 즉시 SL 폭 판정할 것.**

### 🟡 [관찰] 신호 자체의 기대값이 음수 + 워치리스트 동질화

- 최근 7일 동적 세션 신호의 사후수익률: **BUY n=50 → 4h −2.17% / 24h −4.47%**, **SELL n=1,158 → 4h −0.02% / 24h +0.91%**. BUY는 역효과, SELL은 무정보.
- 워치리스트 `target_watch_size=10`인데 실제 편입은 **5~8종**. 게다가 7세션이 같은 종목에 수렴한다(24h 평가 기준 DOGE **6세션**, XRP 5, BTC·ETH·SOL·EUL·HYPER 각 4). 39·45는 **같은 시각(4초 차)·같은 가격(101)·같은 수량으로 DOGE를 동시 매수** → 단일 코인에 16,000원(동적 자본 23%) 집중. **세션 간 노출 상한이 없다.**
- 세션 42는 재시작 이후 6일간, LIVE 195는 07-31 이후 6일간 **진입 0건**.

### 조치 (사용자 승인 후 전부 적용 — 2026-08-06)

> 빌드·테스트 **184건 통과 / 실패 0**(신규 8건 포함). **✅ 08-06 00:35:52 UTC 배포·재기동 완료** — 아래 "배포 완료" 절 참조.

- **[x] ① 동적 총자산 시가 평가로 통일** — [DynamicTradingService.processMonitoringTick](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java)에 `totalAssetKrw = availableKrw + size × 현재가` 갱신을 넣고, `updateMddPeak`를 그 **뒤로** 옮겨 MDD가 시가 기준으로 움직이게 했다. LIVE의 `updateSessionUnrealizedPnl` 계산식을 그대로 이식했고 `size=0`(매수 미체결) 가드도 동일하다. 이제 mdd_threshold_pct(20%)·max_portfolio_drawdown_pct(15%)가 **보유 중에도 발동할 수 있다**.
- **[x] ② time stop — 세션 40만 36시간으로 재활성화** — 운영 DB `UPDATE dynamic_session SET max_hold_hours=36 WHERE id=40` **적용 완료(08-06 00:29 UTC)**. 나머지 6세션은 0(비활성) 유지. XRP가 71시간째라 다음 monitoring tick에서 즉시 청산되는 것을 알고 선택한 값이다.
  - **이 경로가 07-31 P0(매도 후처리 롤백 → 유령 포지션)가 터진 바로 그 지점**이라 세션 하나로 한정했다. 자동복구 ①②는 배포됐으나 실발화 표본 0.
  - **✅ 실발화 + 후처리 정상 확인 (00:30 UTC)** — 주문 `8631` SELL FILLED @1,508, 사유 `시간 초과 청산 — 보유 71시간 ≥ 36시간 (pnl -2.20%)`. `closing_at 00:30:11 → closed_at 00:30:21`(**10초**), 실현 **−185.37원**(수수료 3.91). 세션 40 → **SCANNING 복귀**(`current_coin_pair`·`current_position_id` 모두 NULL), 잔고 **9,814.63 = available = total, drift 0.00**, **유령 포지션 없음**, 주문 시퀀스 갭 0. **정상 경로가 그대로 돌아 자동복구는 개입할 필요조차 없었다** — P0 재발 없음이 실측으로 확인됐다(자동복구 자체의 표본은 여전히 0).
  - ⚠️ **시간 초과 청산에는 텔레그램 알림이 없다** — `STOP_LOSS`가 아니라 알림 유형에 안 잡힌다. 자본이 실제로 회수되는 이벤트인데 사용자에게 통지되지 않는다. 별건 과제.
- **[x] ③ 세션 간 동일코인 노출 상한** — `MAX_SESSIONS_PER_COIN = 1`. SCANNING의 BUY 게이트에 `countBySessionKindAndCoinPairAndStatusAndSessionIdNot`(신규 리포지터리 메서드) 기반 차단을 추가했다. 차단 시 `blocked_reason`에 보유 건수가 남아 사후 추적이 된다. 판정은 `crossSessionExposureBlockReason`으로 분리해 테스트로 잠갔다(`CrossSessionExposureTest` 4건).
- **[x] ④ 텔레그램 중요 알림 재시도** — `STOP_LOSS`·`SESSION_STOP`만 최대 3회(백오프 2s·5s) 재시도. 정기 요약은 다음 주기에 다시 오므로 대상에서 뺐다. 최종 실패 시 `🔴 … 유실됨` ERROR 로그를 남긴다(`TelegramCriticalRetryTest` 4건).
- **[x] ⑤ LIVE 구형 SL 조임 ratchet 제거** — [LiveTradingService](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java)에서 **2곳**을 제거했다. 고정 5% SL 자체는 유지(사용자 결정).
  - `BLACK_SWAN_GUARD` 발동 시 1×ATR 조임 — 동적 세션 07-31 개편과 동일하게 알림만 남기도록 변경.
  - **`급락 SL 조임`(WS 실시간 경로)** — 승인 범위(블랙스완 ratchet)보다 넓지만 함께 제거했다. **동적 세션에는 대응물이 없는 LIVE 전용 경로**이고, 마진이 `trailingSlMargin`(0.3%)이라 사실상 *급락을 감지한 순간 다음 틱 강제청산을 예약*하는 동작이었다. 이걸 남기면 블랙스완 쪽만 지워도 개편이 무력화된다. **급등 시 TP 트레일링은 이익을 잠그는 반대 방향이라 유지.**
- **[x] 빌드 차단 수정(무관 건)** — `DbResetService`의 커밋 안 된 작업본에서 `RESET_PASSWORD` 상수 선언이 필드 자리에서 메서드 본문 107행으로 잘려 붙어 **컴파일이 깨져 있었다**. 원위치 복구. ⚠️ 이 상수는 운영 DB 비밀번호를 소스에 하드코딩하고 있다(HEAD에도 존재) — **별건으로 환경변수화 필요.**

### ✅ 배포 완료 (2026-08-06 00:35:52 UTC 재기동) — 건전성 이상 없음

- **재기동 확정** — 00:36에 **7세션이 27건 일제 재평가**(정시 틱 00:01과 어긋난 버스트 = 인메모리 `lastEvaluatedCandle` 초기화 서명).
- **① 시가 평가 실동작 확인** — 세션 39·45의 `total_asset_krw`가 원가 고정 **10,000.00 → 9,873.27**(00:39:54) → **9,865.35**(00:41, DOGE 99.40→99.30)로 **가격을 따라 움직인다**. `mdd_peak_capital`은 10,000 유지(피크는 상승만) → **드로다운 1.35%가 이제 실제로 측정된다**. 배포 전에는 구조적으로 항상 0이었다.
- **재기동 후 건전성**

| 점검 | 결과 |
|---|---|
| 세션 상태 | 9/9 RUNNING, 전 세션 로그 정상 재개 |
| 잔고 정합성 | `available + 평가액 − total` = **7세션 전부 0** (39·45의 −0.003원은 두 쿼리 사이 DOGE 호가 변동분) |
| 유령 포지션 | **없음** (OPEN 3건은 전부 정상 — 2378 LIVE BTC, 2388·2389 DOGE) |
| 주문 | 재기동 전후 FAILED **0**, 시퀀스 갭 **0** |
| `blocked_reason` | 정상 기록 |
| 서킷브레이커 | 발동 0 (현 드로다운 1.35% vs 임계 15~20%) |

- **아직 관측 못 한 것 3건** — ③ 동일코인 노출 상한은 재기동 후 **BUY 신호 자체가 0건**이라 발화 기회가 없었다(세션 41~44가 DOGE를 워치리스트에 갖고 있으므로 DOGE BUY가 나오면 즉시 검증된다). ④ 텔레그램 재시도는 전송 실패가, ⑤ ratchet 제거는 급락/블랙스완이 있어야 관측된다. **셋 다 "실패 시에만 드러나는" 성격이라 무소식이 정상이다.**

### 다음 액션

- **[ ] 다음 신규 진입 1건으로 확인 2건** — ⓐ **08-05 SL 축소 판정**(여전히 표본 0) ⓑ ③ 동일코인 노출 상한 발화
- **[ ] 시간 초과 청산 알림 추가** — 위 ② 실측에서 드러난 누락.
- **[ ] LIVE 194 BTC 136시간 보유** — LIVE에는 time stop 개념 자체가 없다. 동적 ②의 결과를 보고 LIVE에도 넣을지 판단.
- **[ ] 신호 기대값 음수** — BUY 4h −2.17% / 24h −4.47%(n=50). 게이트를 아무리 조여도 진입 신호 자체가 역효과면 한계가 있다. 별도 과제.

---

## ✅ 2026-08-05 "손절 5%인데 7~8%에 잘린다" 원인 규명 + 청산 규칙 4건 수정 — **🔴 배포 대기**

> 운영 DB 직접 조회(08-05 00:0x UTC, 읽기전용). 대상: DYNAMIC RUNNING 7세션(39~45) + LIVE RUNNING 2세션(194·195).

### 운영 현황 (전 세션 정상 tick, 마지막 로그 08-05 00:00:47)

| 구분 | 세션 | 상태 |
|---|---|---|
| LIVE | 194 (MEANREV_BB / BTC) | BTC 1건 보유 119시간, 평가액 10,009 |
| LIVE | 195 (MOMENTUM_ICHIMOKU_V2 / ETH) | 5일간 **거래 0건** |
| DYNAMIC | 39·40·44·45 | 보유중 (DOGE·XRP·SHIB) |
| DYNAMIC | 41·43 | 스캔중, **9,436 / 8,805** — 손실 전액이 08-04 META2 1건 |
| DYNAMIC | 42 | 거래 0건 |

- 동적 7세션 합계 **70,000 → 68,094 (−2.7%)**. 5일간 청산 **3건 전부 손절, 승률 0%**(평균 −7.4%).

### 원인 — 5%는 손절폭이 아니라 **하한값**이었다 (버그 아님, 설계대로)

07-31 개편으로 `SL 폭 = clamp(ATR(14)/가격 × 2.0, 세션 stop_loss_pct, 12%)`가 됐다.
`stop_loss_pct=5.00`은 화면·DB에 그대로 남지만 실제로는 `max()`의 바닥값이라 ATR 2.5% 초과 종목은 자동으로 5%보다 넓어진다.

| 포지션 | 코인 | 실제 SL 폭 | ATR 역산 | 실현 |
|---|---|---|---|---|
| 2386/2387 | META2 | **−6.96%** | 3.48% | −7.05% / −7.08% |
| 2383 | ELSA | −5.70% | 2.85% | **−8.33%** |

- **META2의 −7%는 100% SL 폭 자체** — 초과분은 체결 오버슛 0.22% + 수수료 0.09%뿐.
- **ELSA의 −8.33%는 별개 문제가 겹친 것**: SL 5.70% + 매수 슬리피지 0.12% + **SL 이탈 2.21%p**(76.19를 뚫었는데 74.50에서야 감지) + 체결 슬리피지 0.40% + 수수료 0.09%.
- ⚠️ **LIVE 세션은 개편 대상이 아니다** — 여전히 고정 5%(pos 2378 = 정확히 −5.00%)이고, 07-31에 동적에서 제거된 "블랙스완 시 SL을 **좁히는**" 구형 ratchet이 [LiveTradingService](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java)에 그대로 남아 있다. **미조치 — 사용자 판단 대기.**

### [x] 수정 4건 (전부 회귀 테스트 + 무력화 검증 완료)

- **[x] ① SL 폭 축소** — `SL_ATR_MULTIPLIER` 2.0 → **1.5**, `SL_PCT_MAX` 12% → **8%**. 하한(세션 설정값)은 그대로라 저변동 종목 동작은 불변. META2 조건이면 6.96% → **5.2%대**.
- **[x] ② TP 절대 상한** — `TP_PCT_MAX = 8%` 신설, [`resolveTakeProfitPrice`](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java)로 분리. TP를 SL×2로 따라 키우다 META2가 **+14.10%**로 잡혀 사실상 도달 불가였다(5일간 익절 0건). 상한 구간에서 명목 손익비는 2:1 아래로 내려가지만 **도달하지 않는 TP의 손익비는 의미가 없다**는 결정.
- **[x] ③ REST 폴백에 동적 세션 코인 포함 — ELSA 감시 지연의 실제 원인** — [`pollRestTickerFallback`](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java)이 대상 코인을 `live_trading_session`에서만 뽑아 **동적 세션 보유 코인이 통째로 빠져 있었다**. WS가 멎으면 동적 세션 SL 감시는 60초 폴링만 남는다. 이제 [`WsSubscriptionManager.getSubscribedCoins()`](../web-api/src/main/java/com/cryptoautotrader/api/service/WsSubscriptionManager.java)(LIVE+DYNAMIC 합집합)를 쓴다.
  - 증거: ELSA 청산 사유가 `손절`(폴링)인데 하루 뒤 META2는 `실시간 손절(WS)`로 정상 동작.
  - 함께 수정: `recompute()`가 `wsClient == null`이면 즉시 리턴해 **합집합 상태 자체가 비어 있던** 결합을 분리(부수효과만 스킵).
  - ⚠️ **남는 한계**: `isWsUnhealthy`는 전역 틱 신선도만 본다. LIVE 코인이 계속 틱을 주는 동안 특정 코인 구독만 조용히 끊기면 폴백이 발동하지 않는다 — 코인별 틱 신선도 추적은 별도 과제.
- **[x] ④ BLACK_SWAN 진입가 가드** — 쿨다운(240분)은 진입을 **지연**시킬 뿐 가격을 보지 않아, "차단 가격에 안 사고 기다렸다가 더 비싸게 사서 차단 가격 아래로 손절"이 2회 재현됐다.

  | 코인 | 차단 시점가 | 해제 후 진입가 | 손절 체결가 |
  |---|---|---|---|
  | META2 (08-04) | 01:06 @ **8,630** | 06:00 @ **9,150** (+6.0%) | **8,495** ← 차단가보다 낮다 |
  | ELSA (08-03) | 4회 차단 | 02:00 @ 80.90 | 74.20 |

  [`evaluateBlackSwanGate`](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java)로 2단 게이트화 — 쿨다운(240분) 이후에도 **24시간(`BLACK_SWAN_PRICE_GUARD_MIN`) 동안 차단 시점가 초과 진입을 막는다**. 가격이 차단가 이하로 내려오면 즉시 허용하고 이력도 폐기한다(영구 차단 방지).
  - 재기동 복원도 확장: `findRecentBlockedCoins`가 `signalPrice`를 함께 돌려주고 조회 구간이 240분 → **1440분**으로 넓어졌다. 기준가 없는 구 로그는 쿨다운만 복원되고 진입가 가드는 자동 비활성.

- **[x] 회귀 테스트 — `:web-api:test` 176건 통과** (신규 3파일 / 기존 2파일 갱신)
  - 신규: [DynamicBlackSwanPriceGuardTest](../web-api/src/test/java/com/cryptoautotrader/api/service/DynamicBlackSwanPriceGuardTest.java) 7건, [DynamicTakeProfitCapTest](../web-api/src/test/java/com/cryptoautotrader/api/service/DynamicTakeProfitCapTest.java) 4건, [WsSubscriptionUnionTest](../web-api/src/test/java/com/cryptoautotrader/api/service/WsSubscriptionUnionTest.java) 3건
  - 갱신: DynamicStopLossWidthTest(새 배수/상한 + META2 실측 재현), DynamicBlackSwanCooldownRestoreTest(24h 창 + 기준가 복원 2건 추가)
  - **무력화 검증 완료** — 배수를 2.0/12%로 되돌리면 4건, TP 상한 `.min()`을 빼면 2건, 합집합 계산을 `wsClient` 뒤로 되돌리면 2건이 각각 실패함을 확인 후 복원.
  - ⚠️ `pollRestTickerFallback` **호출부 자체는 단위 테스트가 잠그지 못한다**(Spring 스케줄러 + REST 클라이언트 의존). 잠근 것은 합집합 계산까지다.

### [x] 배포 완료 (2026-08-05 09:52 KST 재기동) — 건전성 이상 없음, **효과는 아직 3건 미검증**

- **재기동 확정** — 00:52:31~49 UTC에 **9세션 전부가 워치리스트 전 코인을 일제히 재평가**(29건). 정시 틱(00:00·23:00)과 어긋난 버스트 = 인메모리 `lastEvaluatedCandle` 초기화 서명.
- **배포 후 5시간 건전성** — 9/9 RUNNING / 잔고 정합성 **7세션 0.00원** / 시퀀스 갭 **0**(seq=max=8630) / 주문 FAILED **0** / `blocked_reason` 정상 기록.

| 수정 | 판정 | 사유 |
|---|---|---|
| ① SL 폭 (1.5×ATR, 상한 8%) | **미검증** | 배포 후 **신규 매수 0건** |
| ② TP 상한 8% | **미검증** | 동일 |
| ③ REST 폴백 DYNAMIC 포함 | **미검증** | WS 정상이라 폴백 미발동 |
| ④ 진입가 가드 | **조건 성립 확인, 발동 장면 없음** | 아래 참조 |

- **④ 복원 대상은 실재했다** — KRW-META2 가드 차단(시점가 **8,630**)이 재기동 **1,432분 전**으로, 24시간 창까지 **8분** 남기고 들어왔다. 쿨다운 240분은 이미 지나 **진입가 가드만 걸리는 구간**이었고, 재기동 직후 세션 42가 평가한 META2 가격이 **8,815 > 8,630** — BUY 신호만 났으면 가드가 실제로 차단했을 조건이었다. 신호가 HOLD라 차단 로그는 남지 않았고, 창은 01:00 UTC에 닫혔다.
  - **[ ] 기동 로그 확인** — `docker compose -f docker-compose.prod.yml logs backend | grep "쿨다운 복원"` → `[Dynamic] BLACK_SWAN 쿨다운 복원: 1종 [KRW-META2] (진입가 가드 기준가 1종)`
- **[ ] ①② 판정 쿼리 — 신규 동적 진입 1건이 나와야 확정된다**
  ```sql
  SELECT id, coin_pair, avg_price,
         round(100*(stop_loss_price/avg_price-1),2) sl_pct,   -- ≥ -8.00 이면 ① OK
         round(100*(take_profit_price/avg_price-1),2) tp_pct  -- ≤  8.00 이면 ② OK
  FROM position WHERE session_kind='DYNAMIC' AND opened_at > '2026-08-05 00:52';
  ```
  보유 중인 3건(DOGE×2·XRP)은 **배포 전 진입분이라 옛 SL −5.00%/TP +10.00%를 그대로 유지**한다 — 판정 대상이 아니다.

### ⚪ 배포 후 첫 청산은 손절이 아니라 전략 SELL 익절 — 단, **이번 수정의 효과는 아니다**

- pos 2385(세션 44, KRW-SHIB) 08-05 02:00 청산. 사유 `전략 SELL — H1 EMA20 확정 이탈`, **+87.78원(+1.10%)**, 보유 36.0시간. 세션 44 → **10,087.78(+0.88%)**.
- 07-31 개편 이후 "청산 3건 전부 손절 / 전략 SELL 청산 0건"이던 상태가 깨진 첫 사례 — SL·TP 어느 쪽에도 닿지 않고 **세 번째 출구가 실제로 작동**했다.
- **다만 2385는 08-03 진입분이라 SL/TP가 옛 값이고, 전략 SELL 경로는 이번에 손대지 않았다.** 수정이 없었어도 나왔을 결과이므로 성과로 계산하면 안 된다.

### 🟡 [관찰] 신규 진입이 안 나오는 이유는 게이트가 아니라 **BUY 신호 자체의 부재**

- 배포 후 5시간 비-HOLD 신호 35건이 **전량 SELL**(41·43은 HYPER SELL 반복, 44는 청산 후 SCANNING 복귀했으나 전 종목 SELL). BUY는 1건뿐이고 그마저 `POSITION_MONITORING — 이미 보유 중`.
- ⇒ ①② 검증은 **장세가 돌아설 때까지 대기**. 진입 빈도 실측은 08-03 3건·08-04 4건·08-05 0건으로 하루 1~4건 수준이라, 하루 이틀 안에 표본이 생길 가능성은 있다.

---

## ✅ 2026-08-04 멀티코인(동적) 세션 39~45 24시간 운영 분석 — **배포 확인 + P0 전부 재발 없음**, 청산 규칙에 새 결함

> 운영 DB 직접 조회(08-04 09:3x~13:0x KST, 읽기전용). 대상: DYNAMIC RUNNING 7세션(39~45) + LIVE 194·195 참고.

### ✅ 08-04 13:0x 배포 완료 — 수정분 실동작 확인(before/after 대조)

- 사용자 배포·재기동. `blocked_reason` 수정이 **같은 세션·같은 코인·13분 간격**으로 깔끔하게 대조됐다.

| 시각 | 세션/코인 | 신호 | `blocked_reason` |
|---|---|---|---|
| 13:01:12 | 44 / KRW-SHIB | BUY | *(공란)* ← 배포 전 |
| **13:14:11** | 44 / KRW-SHIB | BUY | **`POSITION_MONITORING — 이미 보유 중(신규 진입 대상 아님)`** ← 배포 후 |

- **재기동 안전성 이상 없음** — 7세션 전부 RUNNING / 잔고 정합성 전부 **0.00원** / 보유 포지션 2건 무사(XRP −46.66, SHIB **+206.60**) / 주문 시퀀스 갭 **0** / 로그 정상 재개.

### 🔴 [재기동으로 실제 발생] BLACK_SWAN 쿨다운 소실 → **`strategy_log` 복원으로 수정 완료**

- **관측**: 08-04 10:00 KRW-META2가 가드로 차단돼(`거래량 급증 47.9배 + 1시간 내 하락 −2.15%`) 쿨다운이 **약 40분 남아 있었는데**, 13:0x 재기동으로 인메모리 맵이 비면서 **잔여분이 통째로 소실**됐다. 재기동 후 META2는 다시 평가되고 있다(현재 HOLD라 실피해는 없었다).
- **기존 판단이 틀렸다** — 08-03 노트는 "인메모리라 초기화되지만 가드 본체가 1차 방어를 하니 쿨다운은 2차 방어"라고 적었다. 그러나 **재기동 직후는 급락이 이미 지나가 가드 본체는 안 걸리고 쿨다운만 필요한 구간**이다. 즉 1차 방어가 없는 상태이고, **08-03 ELSA 사고(가드 4회 차단 → 해제 직후 진입 → −8.33%)의 창이 재기동마다 다시 열린다.**
- **[x] 수정 — 마이그레이션 없이 `strategy_log`에서 복원** — 차단 사실 자체는 이미 DB에 남으므로 별도 저장소가 필요 없다. [`restoreBlackSwanCooldown`](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java)(`ApplicationReadyEvent`)가 쿨다운 구간(240분) 내 차단 이력을 코인별 **최신 시각**으로 복원한다. 신규 쿼리 [`StrategyLogRepository.findRecentBlockedCoins`](../web-api/src/main/java/com/cryptoautotrader/api/repository/StrategyLogRepository.java).
  - **핵심 불변식 — 자기 연장 금지**: 복원 대상을 `BLACK_SWAN_GUARD 발동` 접두어로 **한정**한다. 쿨다운이 남긴 차단 로그(`BLACK_SWAN 쿨다운 …`)까지 포함하면 쿨다운이 스스로를 갱신해 **영구 차단으로 굳는다.**
  - 세션 종류(`DYNAMIC`) 필터로 LIVE 로그와 격리. 복원 실패는 기동을 막지 않는다(가드 본체는 독립).
- **[x] 회귀 테스트** — [DynamicBlackSwanCooldownRestoreTest](../web-api/src/test/java/com/cryptoautotrader/api/service/DynamicBlackSwanCooldownRestoreTest.java) **5건**(복원 / 최신 시각 채택 / **자기 연장 차단** / 240분 만료 제외 / 세션 종류 격리). **무력화 검증 완료** — 접두어를 `BLACK_SWAN`으로 넓히면 자기 연장 테스트 1건이, 복원을 no-op으로 만들면 2건이 각각 실패함을 확인 후 복원. `:web-api:test` 전체 **159건 통과**.
  - 주입 빈은 CGLIB 프록시라 필드가 비어 있어 `AopTestUtils.getUltimateTargetObject`로 타깃을 꺼내 검증한다.
- **[x] 2차 배포 완료 (08-04 13:31 재기동, 커밋 `0a26a62`)** — 재기동 직후 전 세션 재평가 버스트(34건) 정상, 잔고 정합성 **7세션 0.00원**, 보유 포지션 2건 무사(XRP −41.48, SHIB **+183.64**), 시퀀스 갭 **0**. `blocked_reason` 수정도 계속 동작(13:31:20 SHIB BUY).
- **[ ] ⚠️ 쿨다운 복원 자체는 아직 미검증 — 기동 로그 확인 필요** — 재기동 시점에 KRW-META2의 가드 차단이 **213.7분 전(240분 창 이내)** 이었으므로 **복원 대상이 존재했다**는 것까지만 DB로 확정된다. 그런데 META2가 계속 HOLD라 BUY 신호가 없어 **쿨다운이 실제로 차단하는 장면은 DB에 남지 않는다**(14:00에 창이 닫힌다).
  - 확인 방법: `docker compose -f docker-compose.prod.yml logs backend | grep "쿨다운 복원"` → `[Dynamic] BLACK_SWAN 쿨다운 복원: 1종 [KRW-META2]` 가 떠야 한다.
  - 다음 기회는 **다음 가드 발동 직후의 재기동** — 흔치 않으므로, 기동 로그로 지금 확인해두는 편이 낫다.

### ✅ 08-03 코드 수정분 배포 확인 (전날 "🔴 배포 대기" 해소)

`flyway`는 V63(08-03 10:13 KST)이 마지막이라 배포 시각을 직접 알 수 없으나, **런타임 동작 증거 2건으로 배포가 확인**된다.

- **③ 블랙스완 쿨다운 — 실전 첫 발동, 교차 세션으로 작동** — 08-04 10:00 세션 43이 `KRW-META2`를 `거래량 급증 47.9배 + 1시간 내 하락 -2.15%`로 차단 → **6분 뒤 10:06 세션 41이 같은 종목을 `BLACK_SWAN 쿨다운 — 5분 전 차단된 종목 (해제까지 235분)`으로 차단**. 코인 단위 전역 쿨다운이 의도대로 다른 세션까지 막았다. 08-03 ELSA 사고(가드 4회 차단 → 15분 뒤 해제 직후 진입 → −8.33%)와 **정확히 같은 형태가 이번엔 차단**됐다.
- **④ 미실현손익 복구** — DYNAMIC OPEN 포지션 2건이 전부 비-0(`2382` XRP −51.85원, `2385` SHIB +137.73원). 07-31~08-03 "전 이력 0건"이던 구조적 결함이 해소됐고, 동적 세션 상세 화면의 손익 분해도 이제 실값이 뜬다.
- ①②(유령 포지션 자동 정산 / FILLED 우선 채택)는 **해당 상태가 발생하지 않아 미발화** — 잠복 확인은 됐으나 작동 확인은 아직 표본 0.

### ✅ P0 3종 재발 0 — 회계·배관 전부 정상

| 점검 | 결과 |
|---|---|
| 세션 잔고 정합성 (`available + 보유원가 − total`) | **7세션 전부 0.00원** (08-03 P0의 −8,000원 누수 재발 없음) |
| 주문 시퀀스 갭 | `order_id_seq.last_value = MAX(id) = 8623` → **갭 0** |
| 유령 포지션 (매도 FILLED인데 OPEN) | **0건** |
| 주문 실패 | DYNAMIC 4건 전부 `FILLED`, **FAILED 0건** |
| 로그 갱신 | 7세션 전부 정상(최신 로그 2~58분 전, H1 캔들 주기와 일치) |

### ✅ 스캔 파라미터 원복 효과 지속 — 거래 정상 재개

- **워치리스트 6~10종 유지**(목표 10). 08-02의 1~3종 붕괴에서 완전 회복, 24시간 유지됐다. 평가 코인 16종.
- **BUY 신호 24h 12건 → 3건 체결(25%)**. "3일간 BUY 1건·체결 0건"이던 상태와 대조.
- 세션별 신호: HOLD 1,275 / SELL 153 / BUY 12. **SELL 153건은 전부 `SCANNING — 보유 포지션 없음`**(실행 불능 노이즈).

### 📊 24시간 손익 — 실현 −666원 / 평가 포함 −580원 (−0.83%)

| 세션 | 코인 | 진입 | 상태 | 손익 | 비고 |
|---|---|---|---|---|---|
| 43 | KRW-ELSA | 08-03 11:00 @80.90 | **CLOSED(손절)** | **−666.22원 (−8.33%)** | 보유 2.6h, SL폭 −5.82%(ATR) |
| 40 | KRW-XRP | 08-03 10:01 @1,543 | OPEN | −51.85원 (−0.65%) | **보유 27시간**, SL 1,465.85 / TP 1,697.30 |
| 44 | KRW-SHIB | 08-03 23:01 @0.00697 | OPEN | **+137.73원 (+1.72%)** | 보유 14h, SL 0.006631 / TP 0.007678 |

- 7세션 총자산 **69,333.78 / 70,000원**. 평가 포함 **−580.34원 (−0.83%)**.
- ELSA 건은 08-03 분석에서 이미 다룬 사건이며, 이번 24시간의 **유일한 청산**이다.

### ✅ 진입 게이트 — 차단 판단이 또 옳았음 (사후수익률로 확증)

- **차단 8건 평균 4h −6.05%** vs **체결 3건 평균 4h −3.00%** → 차단된 쪽이 더 나쁨 = **게이트 유효**.
  - `BLACK_SWAN_GUARD` ELSA 4건: 4h **−6.77~−6.90%**. 그런데 해제 후 진입한 체결분은 4h **−9.90%** → **가드가 옳았고 쿨다운 도입이 정당**했음이 수치로 재확인.
  - `EMA200 레짐 필터` STORJ 4건: 4h **−5.33%** → 차단 옳음.
- 다만 **체결 3건도 평균 마이너스**(−3.00%)다. 유일한 플러스는 SHIB(+1.72%).

### ⚠️ [정정] 최초 판정 2건이 코드 확인 결과 **틀렸다** — DB만 보고 내린 결론이었다

> 오전 분석에서 "보유 중 청산 신호 미산출(P1)"과 "SL 감시가 시간당 1회"를 올렸으나, `DynamicTradingService`를 읽어보니 둘 다 사실이 아니다. 아래가 확정 내용이다.

- **❌ "보유 중 청산 신호 경로가 없다" → 있다.** [`processMonitoringTick`](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java#L845-L876)은 보유 중에도 매 닫힌 캔들마다 전략을 평가하고, `SELL`이면 두 게이트(`MIN_HOLD_MINUTES=180분`, `MIN_PNL_PCT_FOR_SELL=+0.30%`)를 거쳐 `executeSell`까지 간다. 경로는 온전하다.
  - **세션 44에서 SELL이 없던 진짜 이유**: `POSITION_MONITORING`은 **보유 코인 1종(SHIB)만** 평가하는데, 그 SHIB의 신호가 계속 HOLD/BUY였을 뿐이다. 대조군으로 본 **SELL 132건은 전부 `SCANNING` 중 워치리스트 6~10종을 평가하며 나온 다른 코인의 신호**다. "필요 없을 때만 SELL이 쏟아진다"는 건 **평가 대상 개수 차이(N종 vs 1종)를 신호 유무로 오독**한 것이다.
- **❌ "SL 감시가 시간당 1회" → 아니다.** 시간당 1회인 것은 **전략 신호 평가**뿐이다(닫힌 캔들 게이팅, L846-851). **SL/TP 판정은 그 게이트보다 위(L795-826)** 에 있어 **60초 틱마다** 돌고, `fetchCandles`도 매 틱 Upbit REST를 새로 부른다. 여기에 **WS 실시간 경로**([`doOnRealtimePriceEvent`](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java#L1780), 코인당 5초 스로틀)가 따로 있다.
  - ⇒ **08-03 ELSA의 SL 2.1%p 이탈은 폴링 주기로 설명되지 않는다.** 남는 가설은 ①60초 안에 실제로 뚫린 급락, ②저유동 신규 상장이라 WS 체결 틱이 희박했음 — 둘 다 08-03에 넣은 이탈폭 계측이 배포됐으므로 **다음 손절 1~2건의 초과폭과 사유(`실시간 손절(WS)` 여부)로 판별**한다. 계측 없이 손대지 않는다.

### ✅ [수정 완료] 보유 중 무시된 BUY 신호가 사유 없이 기록되던 사각지대

- **이것이 위 오판의 실제 원인**이었다. 보유 중 BUY 신호는 실행 대상이 아닌데(추가 매수 미지원), `processMonitoringTick`이 `saveStrategyLog`만 하고 `updateSignalQuality`를 부르지 않아 **`was_executed=false` + `blocked_reason=NULL`** 로 저장됐다. **"차단된 신호"인지 "그냥 평가만 된 신호"인지 DB로 구분할 수 없다.**
  - 실제 사례: 세션 44가 SHIB 보유 중이던 08-04 01:00 `strategy_log 2038997`(BUY, 사유 공란).
- **[x] 수정** — 보유 중 BUY에 `POSITION_MONITORING — 이미 보유 중(신규 진입 대상 아님)` 을 기록한다. `SCANNING` 쪽 SELL 처리(`SCANNING — 보유 포지션 없음`)와 **대칭**을 맞춘 것으로, **매매 동작은 전혀 바뀌지 않는다**(기록만 추가). HOLD는 스캔 경로와 동일하게 사유를 남기지 않는다.
- **[x] `:web-api:test` 154건 통과**, `compileJava` ✅.
- ⚠️ **정직한 한계 — 회귀 테스트 없음.** 이 분기를 잠그려면 `processMonitoringTick`을 구동해야 하고, 그러려면 Upbit 캔들·전략을 목킹해야 한다(기존 동적 테스트들이 정적 헬퍼나 reconcile만 직접 호출하는 것과 같은 벽). **상수 문자열 하나를 확인하는 테스트를 위해 목 하네스를 세우는 것은 비용 대비 가치가 없다고 판단**해 넣지 않았다. 다음 배포 후 운영 DB에서 보유 중 BUY 로그에 사유가 붙는지로 확인한다.

### 🟡 [관찰] 세션 40 XRP — "본전 근처" 게이트 안에서 27시간 고착

- 현재 pnl **−0.65%**. SELL 게이트는 `+0.30% > pnl ≥ −1.00%` 구간을 "본전 근처"로 보고 **차단**하므로, XRP는 전략이 SELL을 내도 나갈 수 없는 구간에 정확히 들어가 있다. SL(−5.0%)·TP(+10.0%) 어느 쪽도 멀다.
- `max_hold_hours=0`(time stop 비활성)이라 **시간 기반 탈출구도 없다.** 07-31 세션 38 KRW-RLUSD가 42시간 고착됐던 것과 같은 형태다.
- **이것이 time stop 재활성화의 실제 논거**다(아래).

### 🟡 [사용자 결정: 08-04 보류 — 더 관찰] time stop 재활성화

- 전 세션 `max_hold_hours=0`(비활성) **유지**. 세션 40 XRP가 27시간째 −0.65%로 **SELL 게이트(본전 근처 −1.0~+0.3%)와 SL(−5%)·TP(+10%) 사이 어디에도 걸리지 않는 사각지대**에 있다.
- **논거 정리 (다음에 다시 꺼낼 때 이 순서로 볼 것)**:
  - **핵심 논거 = 무조건 탈출구의 부재.** SL·TP는 가격이 도달해야만 작동하는 **조건부** 청산이고, 전략 SELL도 신호+게이트 통과가 필요하다. time stop은 **"이 포지션은 반드시 언젠가 닫힌다"를 보장하는 유일한 장치**다. 07-31 세션 38 KRW-RLUSD(스테이블코인, ±5% 도달에 수개월 → SL/TP 수학적 도달 불가, 42시간 고착)가 그 극단 사례다.
  - **기회비용 논거는 이 시스템에서 일반적 경우보다 세다** — 동적 세션은 한 번에 한 종목만 들고, `POSITION_MONITORING` 중에는 **보유 코인 1종만 평가하고 워치리스트 스캔을 아예 하지 않는다.** 묶이는 건 8,000원이 아니라 **세션 하나 전체**다. 실측: 세션 40은 24h간 로그 25건, 스캔 세션들은 220~250건.
  - **⚠️ 다만 지금 시점의 기회비용은 실측상 거의 0이다** — 온전한 1만원으로 24시간 스캔한 세션 39·41·42·45가 **진입 0건**이고, 실제 진입 3건의 4h 사후수익률은 평균 **−3.0%**다. "풀어주면 기회를 잡는다"는 근거가 데이터로 뒷받침되지 않는다. **켜야 할 이유는 기회비용이 아니라 위 무조건 탈출구 쪽이다.**
- **비용/리스크 3가지**:
  1. **강제 조기청산의 역효과** — 07-31 결론이 "손절이 너무 빨라 회복할 포지션을 손실로 확정시켰다"(KAITO 손절 4h 뒤 +1.23%)였다. time stop도 같은 성격이므로 **폭이 짧으면 그 실수를 반복**한다.
  2. 왕복 수수료 약 0.1% — 본전 근처 청산은 손실 확정 + 수수료.
  3. **🔴 07-31 P0가 바로 time stop 발동에서 터졌다**(매도 후처리 롤백 → 유령 포지션 → 3일 세션 손상). 자동 복구 ①②는 배포됐으나 **아직 한 번도 실발화하지 않았다(표본 0)** — 켜는 것은 **미검증 안전망 위에서 사고 경로를 다시 여는 것**이다.
- **[ ] 켜기로 하면 권고 형태** — ①값은 **24h 이상**(평균 보유 7.6h이라 정상 거래는 안 자름. 단 XRP가 27h라 24h면 즉시 청산, 더 보려면 36~48h) ②**세션 40 하나만 먼저** — ①②의 실발화를 통제된 범위에서 확인할 수 있고(표본 0 해소), 잘못돼도 피해가 한 세션에 갇힌다. 적용은 운영 DB `max_hold_hours` UPDATE만으로 즉시(마이그레이션·배포 불필요).
- **[ ] 관찰 포인트** — XRP가 −1.0% 아래로 내려가면 "본전 근처" 차단이 풀려 전략 SELL이 가능해진다. 그 전에 SL(−5%)에 닿는지, 아니면 계속 밴드 안에서 표류하는지가 **사각지대가 실제로 얼마나 오래 지속되는지에 대한 표본**이 된다.

### ⚪ LIVE 세션 참고 — 195 "3일째 무갱신"은 **오탐이었음(정정)**

- **195 (MOMENTUM_ICHIMOKU_V2 / KRW-ETH)** — `live_trading_session.updated_at`은 91.6시간 전이지만 **`strategy_log`는 08-04 12:00까지 매시 정상 기록**(전량 HOLD, 포지션 0). 세션 행을 쓰지 않을 뿐 **평가는 살아 있다.** 07-31·08-03 노트의 "3일째 무갱신" 우려는 **`updated_at`만 보고 판단한 오탐**이다.
- **194 (MEANREV_BB / KRW-BTC)** — 07-31 17:00 @91,135,000 진입분 보유 중, 미실현 **−66.01원**. 48시간 내 LIVE 주문 0건.

### 후속 과제

- [x] ~~`blocked_reason` 수정 배포~~ — 08-04 13:0x 완료, 실동작 확인.
- [x] ~~쿨다운 복원 배포~~ — 08-04 13:31 완료(커밋 `0a26a62`), 재기동 건전성 확인.
- [ ] **기동 로그에서 `BLACK_SWAN 쿨다운 복원: N종 [...]` 확인** — DB로는 검증 불가(위 참조).
- [x] ~~time stop 값 결정~~ — **08-04 사용자 결정: 보류, 더 관찰.** 전 세션 `max_hold_hours=0` 유지. 논거·비용·재검토 조건은 위 섹션에 정리해둠.
- [ ] 다음 손절 1~2건의 **SL 이탈폭 계측 로그** 확인 → 60초 내 급락 vs WS 미수신 판별.
- [ ] ①②(유령 포지션 자동 정산) 실발화 대기 — 표본 0.
- [ ] 배포 후 보유 중 BUY 로그에 사유가 붙는지 운영 DB로 확인(회귀 테스트 대체).

---

## ✅ 2026-08-03 매도 롤백 P0 해소 + 블랙스완 쿨다운 + 미실현손익 복구 (4건)

> 배포 확인(`flyway V63 = 08-03 10:13:33 KST`) 후 후속 수정. **07-31부터 3일간 "로그 필요"로 멈춰 있던 P0를, 원인 규명과 분리해 자동 복구로 해소**했다.

- **[x] ① 🔴 유령 포지션 자동 정산 (07-31 P0 해소)** — [`reconcileDynamicGhostPositions`](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java) 신설(30초 주기). **매도 FILLED인데 포지션이 `OPEN`** 인 상태를 잡아 `finalizeDynamicSell`로 정산하고 세션을 SCANNING으로 풀어준다.
  - **기존 그물이 못 잡던 이유**: `reconcileDynamicClosingPositions`는 **CLOSING만** 순회한다. 매도 후처리가 롤백되면 `markClosingIfOpen`이 함께 사라져 포지션은 **OPEN**으로 돌아가므로 애초에 그 그물에 걸리지 않는다.
  - **이중 정산 방지**: 부분 체결분을 정산한 포지션도 `OPEN` + FILLED 주문을 유지하므로, ①`realizedPnl == 0`(정산 이력 없음) ②`filledQuantity == size`(전량) 두 조건을 **모두** 요구한다. 부분 정산 후엔 두 조건이 동시에 깨져 재진입이 불가능하다. + 체결 후 2분 유예(`SELL_FINALIZE_GRACE_MIN`)로 정상 경로를 침범하지 않는다.
  - **원인 규명과 분리한 판단**: 예외 지점은 여전히 미규명이지만(서버 로그 필요), **복구는 로그 없이도 구조적으로 가능**하다. 07-31에 손으로 계산해 UPDATE 2행을 넣었던 그 작업의 자동화다.
- **[x] ② FILLED가 최신 FAILED에 가려지던 버그** — `reconcileDynamicClosingPositions`가 `sellOrders.get(0)`(최신순)만 봐서, 유령 상태에서 쌓인 8611~8613(FAILED)이 8610(FILLED)을 가리고 **OPEN 롤백 분기**를 탔다. **체결은 되돌릴 수 없는 사실**이므로 FILLED가 하나라도 있으면 그것을 채택하도록 변경. 07-31에 "reconcile에 맡기지 못한 이유"가 바로 이것이었다 — 이제 맡길 수 있다.
- **[x] ③ BLACK_SWAN_GUARD 쿨다운 (`BLACK_SWAN_COOLDOWN_MIN = 240분`)** — 가드가 차단한 코인은 해제 후에도 4시간 진입 금지. 코인 단위 전역(급등락은 종목의 성질이지 세션의 성질이 아니다).
  - **근거**: KRW-ELSA가 01:00·01:14·01:42·01:45 **4회 차단**됐는데 **02:00 해제 직후 진입**해 −8.33%. 가드 판단은 옳았고 유지 시간만 짧았다. 4시간 = 손실 확정까지 걸린 2.6시간을 덮되 평균 보유 7.6시간보다는 짧게.
  - ⚠️ 인메모리라 재기동 시 초기화된다(`lastEvaluatedCandle`와 동일 방침). 1차 방어는 가드 본체가 계속 담당하므로 쿨다운은 2차 방어다.
- **[x] ④ 동적 포지션 미실현손익 복구** — `processMonitoringTick`에서 갱신. 기존에는 `PositionService.updateUnrealizedPnl`이 **호출부 없는 죽은 코드**라 동적 포지션의 `unrealized_pnl`이 전 이력 0이었다. 표시·분석용이며 SL/TP 판정은 원래 `currentPrice`로 직접 하므로 매매 안전성과 무관하다.
- **[x] ⑤ SL 이탈폭 계측 (고치지 않고 재기만)** — 손절 발동 시 `SL가 − 감지가` 초과폭을 로그에 남긴다. 08-03 ELSA는 2.1%p 초과였는데 07-29~31 6건은 수수료 수준(0.07~0.24%p)이었다. **갭인지 감시 지연인지 표본 없이 판단할 수 없어** 계측만 넣었다. 1%p 이상이 반복되면 감시 경로(WS 구독)를 손봐야 한다.
- **[x] 회귀 테스트** — [DynamicGhostPositionTest](../web-api/src/test/java/com/cryptoautotrader/api/service/DynamicGhostPositionTest.java) 4건(FILLED 우선 채택 / 유령 정산 + 세션 해제 / 유예 시간 존중 / 부분 체결 이중 정산 방지). **①②를 무력화하면 2건 실패함을 확인** 후 복원. `:web-api:test` 전체 **154건 통과**.
- **[ ] 배포 필요** — 마이그레이션 없음, 재빌드·재기동만.
- **[ ] 배포 후 time stop 재활성화 검토** — ①②로 유령 포지션 루프가 자동 복구되므로 07-31에 전 세션 0으로 꺼둔 `max_hold_hours`를 되돌릴 근거가 생겼다. 다만 **자동 복구는 사후 처리**이고 롤백 자체는 여전히 미규명이므로, 먼저 1~2일 관측 후 켤 것.

---

## ✅ 2026-08-03 15:08 KST 조치 후 5시간 추적 — **거래 재개 성공**, 대신 청산 규칙의 새 결함 노출

> 08-03 09:5x 조치(잔고 복원 + 스캔 파라미터 원복) 이후 정확히 5시간. **코드 수정분은 아직 미배포**이므로 아래 결과는 전부 **DB 조치만의 효과**다.

### ✅ 스캔 파라미터 원복 — 의도대로 작동

- **워치리스트 1~3종 → 6~9종 회복**(목표 10). 평가 코인도 13종(BTC·XRP·ETH·SOL·EUL·STORJ·SHIB·DOGE·HYPER·KAITO·ELSA·PIEVERSE·MANTRA)으로 넓어졌다.
- **BUY 신호 3일 1건 → 5시간 8건**, 그중 **2건 실제 체결**. 3일간 0건이던 매매가 즉시 재개됐다 ⇒ **원인이 전략이 아니라 스캔 파라미터였음이 확증**됐다.
- **잔고 누수 재발 없음** — 세션 39·41·42·44·45 전부 10,000원 유지, 7세션 정합성 **0.00원**. 매수 2건이 정상 커밋된 것도 확인(롤백이 상시 발생하는 현상은 아님).
  - ⚠️ **단, 이것은 수정이 작동한 증거가 아니다** — 코드가 미배포라 보상/reconcile은 아직 돌지 않는다. 5시간 동안 롤백이 안 났을 뿐이다.

### 🔴 [신규 P1] 세션 43 −6.66% — SL 가격을 **2.1%p 지나쳐서** 체결

- KRW-ELSA 매수 02:00(80.90) → **04:33 손절**, 보유 2.6시간, 실현 **−666.22원 / −8.33%**.
- **SL 폭 −5.82%인데 매도 신호는 −7.911%에서 났다** — 즉 SL 가격(76.19)을 터치한 시점이 아니라 **한참 지나친 74.50에서** 잡았다. 07-29~31 청산 6건이 "SL 폭보다 수수료(0.07~0.24%p)만큼만 나빴던" 것과 **완전히 다른 양상**이다.
  - `execution_drift_log`는 08-03 **0건** — 슬리피지가 아니라 **감시 지연**이다. 체결 자체는 4.6초에 끝났다.
  - **실시간(WS) 손절이 걸리지 않았다.** 07-31 청산 6건 중 3건은 `실시간 손절(WS)`이었는데 이번엔 폴링 경로(`손절`)로만 잡혔다. 저유동 신규 상장 코인이라 WS 구독/체결 틱이 희박했을 가능성 — **다음 사례에서 `실시간 손절(WS)` 사유가 다시 나오는지 확인 필요.**
- **🎯 [수확] ATR 기반 SL이 처음으로 실제 적용됐다** — SL 폭 **−5.82%**로 하한 5%를 넘겼다. 07-31 "정정: ATR SL은 하한 5%에 묻혀 무효" 노트의 미결 질문(H1 알트에서 2×ATR>5%가 되는가)이 **실측으로 해소**됐다. 스캔 파라미터를 되돌려 알트가 워치리스트에 다시 들어오자마자 유효해진 것 ⇒ **두 문제가 사실 하나였다.**
- **🔴 [구조적] BLACK_SWAN_GUARD가 옳았는데 15분 뒤 해제돼 손실이 났다**
  - ELSA는 01:00·01:14·01:42·01:45 **4회 차단**됐다(세션 41·43, 사유: `거래량 급증 11.2배 + 1시간 내 하락 −2.13%`). 그런데 **02:00에 가드가 풀리자 세션 43이 그대로 매수**했고 −8.3%로 끝났다.
  - **가드의 판단은 정확했고, 유지 시간이 짧았던 것이 문제다.** 급등락 종목은 가드 해제 직후가 가장 위험한 구간인데 현재 로직은 그때 진입을 허용한다.
  - **[ ] 후보 조치** — 가드 발동 종목에 **쿨다운**(예: 마지막 발동 후 N시간 진입 금지)을 부여. 진입 게이트 완화(`scan_*`)와 달리 이건 **가드가 이미 옳다고 판정한 종목**이라 표본이 적어도 근거가 있다.

### ⚪ 그 외 관측

- **세션 40 KRW-XRP 보유 중**(1543 진입, 8,000원, SL 1465.85 / TP 1697.30). 감시 정상 — 02:00~06:01 매시 평가 로그 존재. `updated_at`이 5시간 전인 것은 POSITION_MONITORING 중 세션 행을 쓰지 않기 때문으로 **정상**이다.
- **진입 게이트 정상 작동** — 06:01 세션 42·45의 STORJ BUY 2건이 `EMA200 레짐 필터 — 현재가 EMA200(−3.0%×2) 이하 딥 하락`으로 차단됐다.
- **[신규 발견] 동적 세션 포지션의 미실현손익은 구조적으로 항상 0** — `position.unrealized_pnl <> 0`인 DYNAMIC 행이 **전 이력 0건**. [`PositionService.updateUnrealizedPnl`](../web-api/src/main/java/com/cryptoautotrader/api/service/PositionService.java#L71)은 **호출부가 없는 죽은 코드**이고, 비-0 값을 쓰는 곳은 [`LiveTradingService`](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#L1317) 하나뿐이다(LIVE 전용). `DynamicTradingService`는 ZERO만 쓴다.
  - ⇒ 07-31 "②동일 코인 다세션 손익 오염 수정"의 회귀 테스트는 **서비스 메서드를 직접 호출해 통과**했지만, 운영에서 그 경로는 **아무도 부르지 않는다.** 동적 세션 상세 화면의 미실현손익·손익분해는 보유 중 항상 0으로 표시된다.
  - **[ ] 조치 필요** — 동적 감시 틱(`processMonitoringTick`)에서 이미 현재가를 갖고 있으므로 거기서 갱신하는 것이 자연스럽다. (SL/TP 판정 자체는 현재가로 직접 하므로 **매매 안전성에는 영향 없음** — 표시·분석용 결함)
- **LIVE 세션 194 −1.32%**(BTC 보유 중, 갱신 13초 전 정상). **195는 여전히 3일째 무갱신** — 07-31 분석의 미해결 항목 그대로.

---

## 🔴 2026-08-03 멀티코인(동적) H1 세션 39~45 3일 운영 분석 — **거래 0건 + 세션 3개 자본 증발**

> 운영 DB 직접 조회(08-03 09:0x KST). 07-31 01:50 재구성 이후 **정확히 3일**치. 결론: 배관도 전략도 아니고, **롤백 P0가 세션 절반을 조용히 못 쓰게 만들었다.**

### 🔴 [신규 P0] 세션 39·40·44 — 포지션·주문 없이 KRW 8,000원씩 증발 (**총 24,000원 / 70,000원의 34%**)

| 세션 | 전략 | available | total_asset | 오픈 포지션 | 주문 이력 | version |
|---|---|---|---|---|---|---|
| **39** | MOMENTUM_ICHIMOKU_V2 | **2,000** | 10,000 | 0건 | **0건** | 74 |
| **40** | MEANREV_BB | **2,000** | 10,000 | 0건 | **0건** | 74 |
| **44** | PULLBACK_MTF | **2,000** | 10,000 | 0건 | **0건** | 74 |
| 41·42·43·45 | (나머지 4개) | 10,000 | 10,000 | 0건 | 0건 | 72 |

- **정합성 진단**: `available + 보유원가 − total` = **−8,000.00원** (3세션 동일). 나머지 4세션은 0.00원. `position` 테이블에 세션 39~45 행은 **CLOSED 포함 단 1건도 없고**, `order` 테이블도 세션 32~38까지만 존재(마지막 DYNAMIC 주문 8618, 07-31 01:48 = 구세션 비상청산).
- **[원인 = 매수 트랜잭션 롤백 — 07-29·07-31 P0의 세 번째 발현, 이번엔 매수 경로]**
  - [`executeBuy`](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java#L872-L910) 순서: `position` INSERT → `submitOrderAfterCommit`(afterCommit 훅) → `balanceUpdater.apply`(KRW 차감).
  - `balanceUpdater.apply`는 [REQUIRES_NEW 별도 트랜잭션](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicSessionBalanceUpdater.java)이라 **즉시 커밋**된다. 이후 부모 tx(`processScanningTick`)가 롤백되면:
    포지션 INSERT 소멸 → afterCommit 미발화로 주문 INSERT **아예 없음** → **KRW 차감만 살아남는다.** 관측된 상태와 정확히 일치.
  - **`strategy_log`에 BUY 신호 자체가 없는 것이 결정적 증거** — 신호 로그도 같은 부모 tx에 있어 함께 사라졌다. (세션 39~45 `was_executed=true` **0건**)
  - **차감이 남긴 `POSITION_MONITORING`은 다음 틱이 되돌렸다** — [`processMonitoringTick`:682](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java#L682-L686)가 "포지션 없음 → SCANNING 복귀"만 하고 **KRW를 복원하지 않는다.** `version=74`(=차감 1 + 복귀 1, 나머지 세션 72)가 이 2단계를 그대로 증언한다.
  - **고아 정리 안전망이 닿지 않는다** — [`reconcileDynamicOrphanBuyPositions`](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java#L1359)는 **`position` 행을 기준으로 순회**한다. 이번엔 포지션 자체가 롤백돼 없으므로 **영원히 발견되지 않는다.** 07-31에 만든 안전망이 못 잡는 사각지대.
- **[결과 = 영구 매수 불능]** invest = `available × 0.8` = 2,000 × 0.8 = **1,600원 < 업비트 최소주문 5,000원**. 실제로 07-31 12:00 세션 44가 유일한 BUY 신호(KRW-UNI)를 냈으나 `blocked_reason = "가용 KRW 부족: 투자가능 1600원 < 최소 5,000원"`으로 차단됐다. **세 세션은 손으로 KRW를 복원하기 전까지 다시는 거래할 수 없다.**
### ✅ 조치 완료 (2026-08-03)

- **[x] ① 운영 DB 복원 (08-03 09:5x, 2건 UPDATE 커밋)** — 세션 39·40·44 `available_krw = total_asset_krw = 10,000`. 실제 코인 보유가 0이므로 **손실 보전이 아니라 회계 누수 원복**이다. 가드 `status='RUNNING' AND current_position_id IS NULL AND available_krw=2000 AND total_asset_krw=10000 AND 열린 포지션 0 AND 활성 주문 0` **rowcount=3 확인 후 커밋**. `version`도 +1 해 앱의 낙관적 락이 stale 엔티티를 덮지 않게 했다. 사후 7세션 전부 정합성 **0.00원**, 투자가능액 **8,000원** 회복.
- **[x] ② 롤백 보상 도입 (근본 수정)** — [`registerBuyDeductionCompensation`](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java) 신설. `executeBuy`의 KRW 차감 직후 `afterCompletion(STATUS_ROLLED_BACK)` 동기화를 등록해, 부모 tx가 롤백되면 차감을 되돌리고 `SCANNING`으로 복귀시킨다.
  - **차감을 afterCommit으로 미루지 않은 이유**: 그러면 커밋 후 차감 실패 시 포지션·주문은 살아있는데 KRW가 안 줄어 **이중 매수**가 가능해진다. 미차감(과투자)보다 과차감(보상 가능)이 안전하다는 판단. 낙관적 락 재시도를 위한 `REQUIRES_NEW` 구조는 그대로 유지된다.
- **[x] ③ 세션 기준 안전망 신설** — [`reconcileDynamicSessionBalance`](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java) (60초 주기). 불변식 **"포지션 없는 동적 세션은 `available == total`"** 위반을 세션 단위로 잡는다. 오탐 방지로 `updated_at` 3분 유예(`BALANCE_RECONCILE_GRACE_MIN`), 열린 포지션·활성 주문 있으면 스킵, **복원은 증액 방향만**(감액은 실제 코인 보유를 놓친 경우일 수 있어 경고만).
- **[x] ④ 무증상 분기에 흔적 추가** — `processMonitoringTick`의 "포지션 없음" 분기가 `available < total`이면 `ERROR` 로그를 남긴다. 이 분기가 조용히 지나가면서 3일간 누수를 가렸다.
- **[x] ⑤ 회귀 테스트** — [DynamicBalanceLeakTest](../web-api/src/test/java/com/cryptoautotrader/api/service/DynamicBalanceLeakTest.java) 4건: 롤백 보상 / reconcile 복원 / 보유 중 세션 스킵 / 유예 시간 스킵. **두 수정을 무력화하면 복원 검증 2건이 실패함을 확인** 후 복원. `:web-api:test` 전체 **150건 통과**.
  - ⚠️ **정직한 한계**: 테스트는 보상 헬퍼와 reconcile을 **직접 호출**해 잠근다. `executeBuy`가 그 헬퍼를 **호출한다는 사실 자체**는 잠그지 못한다(시세·전략 목킹 없이 매수 경로 진입 불가). 호출 한 줄이 지워지면 테스트는 통과한다.
- **[ ] 🔴 배포 대기** — 코드 변경만(마이그레이션 없음). 운영 백엔드는 원격 호스트(yhpapa)라 **이 작업 환경에서 배포 불가**. 재빌드·재기동 필요: `docker compose -f docker-compose.prod.yml up -d --build backend`. **배포 전까지 ②③④는 효력이 없고 ①의 복원만 적용된 상태**다.

### 🟡 [P1] 나머지 4세션도 3일간 매수 0건 — BUY 신호가 전 세션 통틀어 **1건**

- 3일간 `strategy_log` **946행**(세션당 130~145) 중 신호 분포: **HOLD 794 / SELL 121 / BUY 1**. 체결 0건.
- **차단 사유는 게이트가 아니라 점수 미달** — `blocked_reason`이 비어있는 794건이 전부 HOLD(진입 조건 자체 미달)다. 실제 차단은 "SCANNING—보유 포지션 없음"(SELL 신호 무시, 정상) 121건과 위 KRW 부족 1건뿐. **07-24~30처럼 스캔 게이트가 막은 게 아니다.**
- **하락장에서 감쇠·EMA 필터가 겹쳐 점수가 0으로 눌린다** — 반복 관측: `[TRANSITIONAL감쇠: buy 0.30→0.15] [EMA필터: 하락추세(EMA20<EMA50) BUY 0.15→0.11]`. 감쇠 후 임계에 못 미쳐 HOLD로 떨어지는 패턴이 지배적.
- **워치리스트가 10개 → 1~3개로 붕괴** — `target_watch_size=10`인데 08-02 23:3x 갱신 결과가 세션별 `["KRW-ETH"]` ~ `["KRW-EUL","KRW-ETH","KRW-SOL"]`. 신규 세션 스캔 파라미터가 구세션보다 **훨씬 빡빡하다**: `min_atr_pct` 0.3→**0.5**, `max_spread_pct` 0.15→**0.1**, `max_candidate_size` 50→**30**. 3일 누적 평가 코인도 13종뿐(SOL 295·ETH 228·EUL 160에 집중).
- **[판단]** 후보군이 메이저 2~3개로 줄면 "알트 변동성을 잡는다"는 동적 세션의 전제 자체가 무너진다. **표본 0인 상태로는 07-31 청산 규칙 개편(ATR SL·time stop)의 효과를 영원히 검증할 수 없다.**
- **[x] 조치 (08-03 09:5x, 운영 DB 커밋)** — 세션 39~45 **7개 전부** `min_atr_pct` 0.5→**0.3**, `max_spread_pct` 0.1→**0.15**(구세션 값)으로 원복. `watchlist_refreshed_at=NULL`로 비워 다음 틱에 즉시 재필터링되게 했다. rowcount=7 확인 후 커밋.
  - `max_candidate_size`는 **30 그대로 뒀다**(구세션 50). 워치리스트 회복이 ATR/스프레드 완화만으로 되는지 먼저 보고, 부족하면 그때 올릴 것 — 한 번에 두 다이얼을 돌리면 어느 쪽이 효과였는지 알 수 없다.
- **[ ] 관찰 과제** — 다음 워치리스트 갱신 후 종목 수가 10개에 근접하는지, BUY 신호가 생기는지 확인. 여전히 1~3개면 원인은 유동성 필터(`scan_min_trade_value_krw` 코드 기본 50억) 쪽이다.

### ⚪ 참고 — 확인된 정상 항목

- **주문 시퀀스 갭 0 유지** — `MAX(id)=8619`(LIVE 세션 194 BUY, 07-31 08:00). 07-31 이후 DYNAMIC 주문 자체가 없어 롤백 소멸 재발 여부는 **판정 불가**(표본 0).
- **구세션 잔여 정리 완료** — 세션 32~38 전부 `EMERGENCY_STOPPED`, 열린 DYNAMIC 포지션 0건.
- **ATR 기반 SL 유효성** — 신규 포지션 0건이라 `stop_loss_price` 분포 판정 **여전히 불가**(07-31 정정 노트의 미결 항목 그대로).

---

## 🆕 2026-07-31 세션 종류(LIVE/DYNAMIC) 격리 누수 수정 + 동적 세션 상세 보강

> 사용자 관찰: ①실전매매 세션 3개 전부 코인 보유 0인데 화면엔 포지션 보유가 뜬다 ②동적 멀티코인 수익률이 다른 세션 것과 섞여 보인다 ③동적 세션 상세에 보유 이력이 없다.

- **[근본 원인 = `position`/`"order"` 공용 테이블에 `session_kind` 필터 누락]** — 두 테이블은 실전매매와 동적 멀티코인이 공용으로 쓰고 `session_kind` 로만 구분되는데(V50~V52), 실전매매 전용 조회 경로 여러 곳이 필터 없이 집계하고 있었다. V61에서 FK를 걷어낸 뒤 DYNAMIC 행이 실제로 쌓이기 시작하면서(07-29 첫 실체결) 표면화됐다 — **07-29 이전에는 DYNAMIC 포지션이 0건이라 잠복**.
- **[x] ① 실전매매 화면 혼입 수정** — [`getGlobalStatus`](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#L725) 의 `openPositions`·`activeOrders`·`totalPnl` 3개 지표가 전부 kind 무필터였다. `countBySessionKindAndSessionIdIsNotNullAndStatus("LIVE", …)` 등으로 교체. 함께 고친 곳: [`GET /api/v1/trading/positions`](../web-api/src/main/java/com/cryptoautotrader/api/controller/TradingController.java#L190)(기본 LIVE, `?sessionKind=ALL` 로 전체 조회 가능 — 메인 대시보드가 이걸 쓴다), `getSessionOrders`·`getAllSessionOrders`(세션 주문 조회도 kind 무필터였음).
- **[x] ② 동일 코인 다세션 보유 시 손익 오염 수정** — `findByCoinPairAndStatus` 가 **`Optional` 반환**이라, 두 세션이 같은 코인을 동시에 들면 `NonUniqueResultException` 이 터지거나 남의 포지션을 채택했다. `findAllByCoinPairAndStatus`(List) 신설 후:
  - [`PositionService.updateUnrealizedPnl`](../web-api/src/main/java/com/cryptoautotrader/api/service/PositionService.java#L70) — 전건을 각자 평균단가 기준으로 갱신.
  - [`OrderExecutionEngine.findPositionForOrder`](../web-api/src/main/java/com/cryptoautotrader/api/service/OrderExecutionEngine.java) 신설 — 체결 콜백의 포지션 역추적을 `positionId` → `(session_kind, session_id, coin)` → 코인 단건 순으로 좁힌다. **후보가 2건 이상이면 매칭 포기**(오염보다 미반영이 안전 — 고아 정리 안전망이 처리). 기존 코드는 매수/매도 콜백 양쪽에서 무필터 코인 매칭으로 폴백하고 있었다.
  - `findByCoinPairAndStatus` 는 `@Deprecated` 로 표시해 신규 사용을 막았다.
  - **세션별 `returnPct` 자체는 섞이지 않는다**(각 세션 자기 컬럼 기반). 다만 `total_asset_krw` 는 매수 시 취득원가로만 갱신되고 **보유 중 시세 변동을 반영하지 않아**, 포지션을 들고 있는 동안 미실현손익만큼 어긋난다 → ③에서 분해 표시로 해소.
- **[x] ③ 동적 세션 상세 보강** — 신규 API [`GET /api/v1/dynamic-sessions/{id}/positions`](../web-api/src/main/java/com/cryptoautotrader/api/controller/DynamicSessionController.java): 세션이 거쳐온 **전 포지션을 최신순**으로, 각 건에 **매수/매도 사유**(주문 `signal_reason` — 체결건 우선), 실현/미실현 손익, 수익률, 보유시간, 레짐을 실어 반환(주문은 `findByPositionIdIn` 으로 일괄 조회, N+1 없음). 세션 상세(`GET /{id}`)에는 **손익 분해**(실현/미실현/합계/청산건수/승률)를 추가 — 위 `returnPct` 와 대조할 근거.
  - UI [동적 세션 상세](../crypto-trader-frontend/src/app/trading/dynamic/[id]/page.tsx): `PnlBreakdownPanel`(손익 분해, 표본 10건 미만이면 "통계적 의미 없음" 경고) + `PositionHistoryPanel`(코인별 카드에 매수🟢/매도🔴 사유 2줄) 신설. **`size=0` 인 CLOSED 는 "미체결 정리"로 별도 표기** — 고아 포지션을 성과로 오독하지 않게.
- **[x] 회귀 테스트** — [SessionKindIsolationTest](../web-api/src/test/java/com/cryptoautotrader/api/service/SessionKindIsolationTest.java) 2건 추가: "실전매매 전역 요약은 DYNAMIC을 집계하지 않는다"(LIVE 보유 0 + DYNAMIC 1건 상황 재현), "여러 세션이 같은 코인을 보유해도 미실현 손익은 각자 갱신된다". **수정을 되돌리면 2건 모두 실패함을 확인**했고, 두 번째는 `NonUniqueResultException` 으로 터져 ②의 원인이 추론이 아니라 실측으로 확증됐다. `:web-api:test` 전체 통과, 프론트 `tsc` 신규 에러 없음.
- **[ ] 미배포** — 코드 변경만(마이그레이션 없음). 재빌드·배포 필요.

---

## 🆕 2026-07-31 운영 DB 로그 상세 분석 (07-29~31, 3일치) — 배관은 전부 살았고, **이제 전략이 문제**

> 07-29 PROGRESS가 예정한 7단계 확인을 운영 DB 직접 조회로 수행(07-31 08:21 KST). **미검증 구간 3개 전부 성공 확정**, 대신 **승률 0%** 라는 새 국면이 드러남.

### ✅ 성공 확정 (예정 확인 항목)

- **[x] ① 🎯 매도 경로 — 검증 완료 (유일한 미검증 구간이었음)** — DYNAMIC `SELL` **6건 전부 FILLED**(8598·8599·8600·8601·8605·8607). 제출~체결 **0.6~1.1초**. 대응 포지션 전부 `status=CLOSED` + `realized_pnl` 확정 + `closed_at` 일관. **매도 경로를 의도적으로 미변경한 판단이 옳았음이 실측으로 확인됐다.**
- **[x] ② KRW 복원 정상** — RUNNING 7세션 중 6세션의 잔고 정합성(`available + 보유원가 − total`)이 **±0.004원**(반올림 오차). 2026-07-01 사고 패턴(복원 실패 → 5,000원 미만 영구 정지)은 재발하지 않았다. 예외는 세션 33 1건(아래 🔴).
- **[x] ③ 시퀀스 갭 0 유지** — `order_id_seq.last_value=8609` = `MAX(id)=8609`, **갭 0**. 중간의 8594는 **LIVE 주문**(세션192 KRW-BTC SELL)이라 내부 갭이 아님. 롤백 소멸이 3일간 유지됨.
- **[x] ④ LIVE 회귀 없음** — 07-28~30 LIVE 주문 3건 **전부 FILLED**, **FAILED 0건**. (07-28 이전 누적 FAILED 6,705건과 대조 — 오히려 개선)
- **[x] ⑤ 진입 분포 — 쏠림 없음** — 7세션 중 **6세션이 실제 진입**(32:4건 34:4 36:3 37:3 33:1 35:1 38:1). 특정 세션 독점 없음. 워치리스트도 3~10개로 회복 유지.

### 🔴 [신규 P1] 승률 0% — 청산 6건 **전부 손절**

- 청산 6건 / **승 0건** / 합계 **−2,181.31원** / 건당 평균 **−4.60%** / 평균 보유 **7.6시간**. 손절 사유는 `실시간 손절(WS)` 3건 + `손절` 3건 — **SL 로직 자체는 정확히 작동**했다(−3.0~−5.5%에서 잡음). 문제는 진입이다.
- **[판정] 07-28 완화(`scan_require_uptrend=false`·`scan_exclude_crashing=false`)는 실패** — 완화 이후 BUY 신호의 사후수익률: **체결 17건 → 4h −0.17% / 24h −3.81%**, 미체결(차단) 28건 → 4h −0.95% / 24h −7.04%.
  - 읽는 법: 차단된 쪽이 더 나쁘므로 **진입 게이트는 여전히 유효**(방어는 옳게 작동). 그러나 **통과한 것마저 24h −3.81%** = 완화로 유입된 종목군 자체가 하락 종목이다. 07-24~27 노트의 "차단이 옳았다"가 **재확인**됐고, 완화는 손실 매매만 늘렸다.

- **🎯 [원인 재판정] 진짜 범인은 완화가 아니라 SL 폭이다 — 6건 전부 SL 강제청산, 전략 SELL 청산 0건**
  - 청산 6건의 `stop_loss_price` vs `avg_price` 대비 실현률:
    | pos | 코인 | SL 폭 | 실현 | 보유 |
    |---|---|---|---|---|
    | 2368 | KAITO | **−3.54%** | −3.61% | 15.2h |
    | 2369/2370 | O | −5.27% | −5.51% | 8.5h |
    | 2371 | KAITO | −5.05% | −5.14% | 6.6h |
    | 2374 | RE | −4.61% | −4.71% | 0.8h |
    | 2375 | EDGE | **−2.96%** | −3.12% | 5.9h |
  - **실현률이 SL 폭보다 정확히 0.07~0.24%p 나쁠 뿐**(= 수수료). 즉 전략이 손실을 판단해 나간 게 아니라 **전량이 SL 터치로 강제청산**됐다. 전략 신호 SELL 청산은 **0건**.
  - **[결정적] 사후 4h 수익률은 ~0%** — 전체 평균 −0.17%, **KAITO는 +1.23%**(그런데 세션 32·36 둘 다 손절당함), NEAR +0.78, XRP +1.04, RLUSD +0.35. **손절만 안 했으면 최소 본전권**이었다는 뜻 = 교과서적 **휩쏘(whipsaw)**.
  - **[구조적 오류] 블랙스완 SL 조임이 방향이 반대다** — KAITO 2368(−3.54%)·EDGE 2375(−2.96%)는 기본 5%가 아니라 **조임이 걸린 값**이다. 변동성이 커질 때 SL을 **좁히면** 정상 등락에 확실히 걸린다(변동성↑ → SL은 **넓혀야** 함). 워치리스트가 ATR 기준을 통과한 고변동 알트인데 SL 3%는 **1 ATR 미만**이다.
  - **[체결품질 무관]** `execution_drift_log` 슬리피지 평균 **−0.008%** — 무시 가능. 손실은 체결이 아니라 청산 규칙에서 나왔다.
  - **[결론] 완화 원복은 잘못된 처방** — 원복하면 표본이 07-26~28의 BUY 0건 상태로 되돌아가 아무것도 못 배우고, 진짜 원인인 SL은 손도 못 댄다. 차단된 쪽이 24h −7.04%로 더 나쁘므로 **게이트는 이미 제 일을 하고 있다.** 표본 6건은 승률을 논하기엔 부족하지만, **"6/6 전부 SL 강제청산"은 승률이 아니라 메커니즘의 문제**라 표본이 적어도 판정 가능하다.
  - **[x] 개편 완료 (07-31) — 아래 "청산 규칙 전면 개편" 섹션 참조.** `scan_*` 완화 다이얼은 **손대지 않았다.**

---

## 🆕 2026-07-31 청산 규칙 전면 개편 — ATR 기반 SL + 블랙스완 조임 제거 + time stop

> 위 분석의 결론("손실을 만든 것은 전략이 아니라 청산 규칙")에 따른 조치. 사용자 승인: "보수적이지 않은 수준으로 진행".

- **[x] ① 손절폭을 ATR 기반으로 전환** — [`DynamicTradingService.resolveStopLossPct`](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java) 신설: SL 폭 = `clamp(ATR(14)/가격 × 2.0, 세션 stopLossPct, 12%)`.
  - **세션 `stopLossPct`(5%)의 의미가 상한 → 하한으로 바뀌었다.** 변동성이 큰 종목일수록 SL이 넓어진다(ATR 4% 종목 → SL 8%). 상한 12%는 초저유동 종목의 비정상 ATR로 손실이 무한정 커지는 것을 막는 안전판.
  - **TP도 실제 채택된 SL 폭 기준으로 재산출**해 손익비 2:1 유지 — SL만 넓히고 TP를 고정하면 손익비가 무너진다.
  - 전략 제안 SL/TP(`getSuggestedStopLoss/TakeProfit`)는 **더 넓은 쪽을 채택**한다. 제안값이 ATR 기준보다 타이트하면 그대로 휩쏘로 이어지므로, 전략의 의도는 방향에만 반영.
  - ATR 계산 불가(캔들 부족 등) 시 세션 설정값으로 폴백 — **진입을 막지 않는다.**
- **[x] ② 블랙스완 SL 조임 제거 (구조적 오류 교정)** — `processMonitoringTick`의 단방향 ratchet(`tightenedSlMargin` 기반 조임)을 **삭제**하고 경고 로그만 남겼다.
  - **변동성이 폭증할 때 SL을 좁히는 것은 방향이 정반대다.** 실측 피해: pos 2368(KAITO, 조임 후 SL −3.54%)·2375(EDGE, −2.96%)가 강제청산됐고 **KAITO는 4시간 뒤 +1.23%로 회복**했다. 조임이 없었다면 이익이었을 포지션을 조임이 손실로 확정시켰다.
  - 블랙스완 방어는 **신규 진입 차단(SCANNING 게이트)이 계속 담당**하며 그쪽은 유효하다(차단 신호 사후 24h −7.04%). 보유 포지션의 변동성 방어는 이제 진입 시점 ATR 기반 SL(2 ATR)이 맡는다.
  - ⚠️ `BlackSwanGuard.tightenedSlMargin` 자체는 **삭제하지 않았다** — LiveTradingService 등 다른 호출자 영향 범위를 넓히지 않기 위함. 동적 세션에서만 호출을 끊었다.
- **[x] ③ time stop 도입** — [V62](../web-api/src/main/resources/db/migration/V62__add_max_hold_hours.sql) `dynamic_session.max_hold_hours` (기본 **24시간**). 초과 시 손익과 무관하게 시장가 청산.
  - 세션 38 KRW-RLUSD 42시간 고착의 해법. SL/TP가 가격 기반이라 저변동 종목에서는 영원히 도달하지 않고, 전략 SELL도 "수익 0.3% 이상" 조건 때문에 구제책이 되지 못한다.
  - 24시간 근거: 실측 평균 보유 **7.6시간** — 정상 매매는 24시간에 한참 못 미치므로 정상 거래를 자르지 않는다. 하루가 지나도 방향이 안 나오면 자본 회전 기회비용이 더 크다.
  - 엔티티에 `@Builder.Default = 24` 적용 — `maxHoldHours` 없이 생성하는 경로가 NOT NULL 위반으로 깨지지 않게(테스트 4건이 실제로 이 문제로 실패해 발견).
- **[x] 회귀 테스트** — [DynamicStopLossWidthTest](../web-api/src/test/java/com/cryptoautotrader/api/service/DynamicStopLossWidthTest.java) 5건(변동성 비례 확대 / 하한 보장 / 상한 클램프 / ATR 계산 불가 폴백 4종 / ATR 2배 불변식). **개편 전 동작(고정 SL)으로 되돌리면 2건 실패함을 확인** 후 복원. `:web-api:test` 전체 **145건 통과**, 프론트 `tsc` 신규 에러 없음.
  - ⚠️ **정직한 한계**: 블랙스완 조임 제거(②)는 `processMonitoringTick` 내부라 **단위 테스트가 잠그지 못한다**. 테스트가 잠그는 것은 ①의 진입 시점 SL 계산뿐이다. ②는 코드 삭제로만 보장된다.
- **[x] UI 반영** — 동적 세션 상세: 손절/익절 표시를 **세션 설정값이 아니라 포지션에 박힌 실제 가격에서 역산**(설정값은 이제 하한일 뿐이라 그대로 쓰면 틀린 값이 나온다). 청산 조건 목록에 time stop 추가, 세션 설정에 "최대 보유" 행 추가.
- **[x] 배포 완료 (07-31 09:21 KST)** — V62 success=t 확인. ⚠️V58 사고 교훈: **적용 후 V62는 절대 수정 금지, 변경이 필요하면 V63 추가.**

---

## 🔴 2026-07-31 [P0 미해결] time stop 배포 직후 매도 후처리 롤백 — 유령 포지션 + 실패주문 루프

> 배포 1분 만에 time stop이 세션 38 KRW-RLUSD를 정확히 잡았으나(주문 8610 FILLED), **후처리가 롤백**돼 코인은 팔렸는데 DB는 보유 중인 상태가 됐다.

- **증상** — 매도주문 8610 `FILLED`(전량 5.60224089 @ 1417), 그런데 pos 2367은 `status=OPEN` / `closing_at=NULL`, 세션 38은 `POSITION_MONITORING` 유지. 다음 틱마다 time stop이 재발동 → 8611·8612·8613이 **69초 간격 FAILED**(업비트 HTTP 400 — 이미 판 코인). 약 4분간 반복.
- **[원인 분석 — 소거법으로 롤백 확정, 지점은 미규명]**
  - `executeSell`은 `markClosingIfOpen`(CLOSING+`closing_at` 기록) → `submitOrder`(@Async, 별도 tx) → `transitionToScanning`(REQUIRES_NEW) 순서다. 주문만 살아남고 앞뒤가 전부 없다 = **`processMonitoringTick` 트랜잭션 롤백**.
  - `reconcileDynamicClosingPositions`가 되돌렸을 가능성은 **배제** — 롤백 분기는 매도주문 FAILED/CANCELLED 또는 8분 타임아웃뿐인데, 8610은 5초 만에 FILLED였고 그 사이 reconcile은 PENDING을 보고 무동작할 시점이었다.
  - **07-29 P0의 정확한 거울상** — 그때는 async가 롤백되고 부모가 살았고, 이번엔 부모가 롤백되고 async가 살았다.
  - 예외 지점은 `submitOrder` 직후~`transitionToScanning` 구간으로 좁혀지나(후자의 `balanceUpdater`가 낙관적 락 12회 재시도 후 throw하는 경로가 유력), **DB 증거만으로는 특정 불가. 서버 로그 필요.**
- **[x] 긴급 조치** — 전 세션 `max_hold_hours=0`(time stop 비활성)으로 루프 차단. 세션 34(NEAR)·37(ETH)이 11:00·11:15에 24h 도달 예정이라 동일 루프 확산이 임박했었다.
- **[x] 유령 포지션 정리 (07-31 10:53, 운영 DB 2행 UPDATE 커밋)** — `finalizeDynamicSell`과 **동일 계산식**을 손으로 적용: proceeds=5.60224089×1417=7,938.375, fee=×0.0005=3.97, realizedPnl=net−(수량×평단1428)=**−65.59383746**. pos 2367 `CLOSED`, 세션 38 `available=total=9,934.41`(−0.66%) + `SCANNING` 복귀. 가드(`status='OPEN'`·`size` 일치·`current_position_id=2367`) rowcount=1 확인 후 커밋.
  - ⚠️ reconcile에 맡기지 못한 이유: 실패주문 8611~8613이 8610보다 최신이라 `latestSell`이 FAILED로 잡혀 **OPEN 롤백 분기**를 타게 된다.
- **[ ] 🔴 근본 원인 미해결** — 로그 확보 후 수정 필요. **이게 안 잡히면 time stop뿐 아니라 모든 매도 경로가 같은 위험을 안는다**(SL/TP/전략 SELL 포함). time stop은 그때까지 전 세션 비활성 유지.
  - 확인 명령: `docker compose -f docker-compose.prod.yml logs backend --since 3h | grep -iE "2367|시간 초과|SCANNING 복귀|Exception|ERROR|낙관적|Concurrency"`

---

## ⚠️ 2026-07-31 [정정] ATR 기반 SL은 현재 구성에서 **무효(no-op)** 였다

> 위 "청산 규칙 전면 개편 ①"의 효과 주장을 실측으로 정정한다.

- **실측 (candle_data 2026-03, BTC/ETH/XRP/DOGE)**: M15 ATR **0.568%**(2×=1.14%), H1 ATR **1.000%**(2×=2.00%). 둘 다 **하한 5%에 완전히 묻힌다.**
- ⇒ `resolveStopLossPct = max(2×ATR, stopLossPct)` 는 당시 전 세션(M15)에서 **항상 5.0을 반환**했다. "변동성 비례로 SL이 넓어진다"는 개편 의도는 **실현되지 않았다.**
- **실제로 효과를 낸 것은 ② 블랙스완 조임 제거뿐** — SL이 2.96~4.61%대에서 기준 5%로 복귀하는 효과. 07-31 세션 32 분석에서 청산 4건의 SL이 전부 조임값(−3.54/−4.61/−2.96/−3.60)이고 **설정값 5%가 한 번도 적용되지 않았음**이 확인됐다.
- **개념 오류** — 평균 보유 7.6시간인데 **15분 캔들 ATR**로 손절폭을 쟀다. 보유 기간에 대응하는 변동성(H1/H4)으로 재야 한다.
- **H1 전환 후에도 절반만 유효** — 메이저(H1 ATR 1%)는 여전히 하한 5% 적용. 워치리스트 알트(H1 ATR 2~4%, `SCAN_MAX_ATR_PCT` 상한 4%)에서만 2×ATR=4~8%로 하한을 넘어 실제로 물린다.
- **[ ] 수정 후보** — ①ATR 산출 타임프레임을 세션 타임프레임과 분리(H4 고정) ②배수 2.0 → 3.0 ③보유기간 스케일링(√t). **표본이 쌓이면 실제 `stop_loss_price` 분포로 판정할 것** — 전부 −5.00이면 여전히 무효.

---

## 🆕 2026-07-31 세션 전면 재구성 — M15 7개 → H1 7개 (사용자 실행)

- **구세션 32~38 전부 `EMERGENCY_STOPPED`**, 최종 성적: 32 **−11.57%** / 36 −6.86 / 35 −4.41 / 34 −2.54 / 37 −0.89 / 38 −0.66 / 33 0.00. **합계 약 −2,700원 / 7만원**.
  - 전 세션 잔고 정합성 **0.00원** 확인(유령 포지션 정리 후). 열린 DYNAMIC 포지션 **0건**.
- **신규 세션 39~45 (H1, 각 10,000원, RUNNING·SCANNING)** — MOMENTUM_ICHIMOKU_V2 / MEANREV_BB / MTF_BTC_STRICT / MTF_CONFIRMED / MTF_BTC / PULLBACK_MTF / MOMENTUM_ICHIMOKU.
  - ⚠️ 신규 생성은 `max_hold_hours` **기본값 24**가 붙어 time stop이 자동 활성화됐다 → 위 P0 미해결이라 **7세션 전부 0으로 재설정 완료**(07-31 10:5x). **코드 기본값도 0으로 변경**(아래).
- **[x] 코드 기본값 0으로 변경 — DB를 매번 손으로 고치는 운영 제거**:
  - [`DynamicSessionEntity.DEFAULT_MAX_HOLD_HOURS`](../web-api/src/main/java/com/cryptoautotrader/api/entity/DynamicSessionEntity.java) 상수 신설(**= 0**). 엔티티 `@Builder.Default`와 `createSession` 폴백이 모두 이 상수를 참조 — **한 곳만 고치면 되돌아온다.** 되돌리는 절차를 상수 javadoc에 명시.
  - [V63](../web-api/src/main/resources/db/migration/V63__default_max_hold_hours_off.sql) — DB 컬럼 기본값 24 → 0. **V62는 적용 완료라 수정하지 않고 새 버전으로 추가**(V58 체크섬 사고 교훈). **기존 행 값은 건드리지 않는다** — 사용자가 의도적으로 넣은 설정을 마이그레이션이 덮으면 안 되기 때문.
  - 요청에 `maxHoldHours`를 **명시하면 그 값이 그대로 적용**된다 — 기능 자체는 살아 있고 기본값만 꺼둔 것.
  - 회귀 테스트: [SessionKindIsolationTest](../web-api/src/test/java/com/cryptoautotrader/api/service/SessionKindIsolationTest.java) "신규 동적 세션은 time stop이 꺼진 상태로 생성된다" — 기본값 0 + 명시값 12 존중 2가지를 잠금. **상수를 24로 되돌리면 실패함을 확인** 후 복원. `:web-api:test` 전체 통과.
  - **[ ] 롤백 P0 수정 후 되돌릴 것** — 상수 24 + 신규 마이그레이션 `ALTER COLUMN max_hold_hours SET DEFAULT 24`. 저변동 종목 고착 방어는 여전히 필요한 기능이다.
  - **[ ] 미배포** — V63 포함이라 재빌드 필요.
- **[판단 근거] M15 → H1 전환은 타당** — 구세션 손실이 **거래 횟수에 거의 정비례**했다(32: 4거래 −11.57% vs 36 STRICT: 2거래 −6.86%, 33: 0거래 0%). 건당 −3~4%가 고정적으로 나오는 구조에서 빈도를 줄이는 것이 직접적 대응이다. 단 **진입 기준 자체(ATR_BREAKOUT 주도, MACD는 HOLD)** 는 그대로이므로, H1이 빈도만 줄일 뿐 승률을 올린다는 보장은 없다.
- **[ ] 관찰 포인트** — ①신규 진입의 `stop_loss_price` 분포(전부 −5.00이면 ATR 무효 지속) ②강제청산 비율(구세션 100%) ③거래 빈도가 실제로 줄었는지 ④매도 후처리 롤백 재발 여부(**time stop 없이도 SL/TP 경로에서 발생할 수 있음 — 최우선 감시**).
- **[ ] 배포 후 관찰** — ①SL이 실제로 종목별로 다르게 잡히는지(`SELECT coin_pair, avg_price, stop_loss_price, (stop_loss_price/avg_price-1)*100 FROM position WHERE session_kind='DYNAMIC' AND opened_at > 배포시각`) ②강제청산 비율이 떨어지는지(개편 전 6/6=100%) ③time stop 청산이 실제 발동하는지(세션 38 RLUSD가 첫 대상 — 이미 42h 초과라 배포 즉시 청산될 것) ④SL이 넓어진 만큼 건당 손실은 커지므로 **손실 합계가 아니라 승률·기대값**으로 판정할 것.
- **[x] 잔여 근본원인 ①(MACD 앵커) 재평가** — HOLD 사유는 **여전히 "점수 미달 buy=0.00" 지배적**(최근 2일: ATR_BREAKOUT 1,656 / MACD 599 / TREND 581건). 실체결이 도는 지금도 병목은 스코어 모델 그대로.

### 🔴 [신규 P1] 세션 33 잔고 8,000원 누수 — 영구 매수 불능 (07-29 세션 35와 동일 패턴 재발)

- 세션 33 `available_krw=**2,000**` / `total_asset_krw=10,000` / **보유 포지션 0건** → 정합성 **−8,000원**.
- 원인 잔재: 포지션 2363(KRW-ZAMA, `size=0` 고아, 07-29 07:15 진입 → 07:20 CLOSED), **대응 주문 0건** = 07-29에 규명한 "주문 INSERT 롤백" 시대의 마지막 피해. `REQUIRES_NEW` 잔고 갱신이 부모 롤백과 무관하게 커밋된 그 경로.
- **07-29 PROGRESS의 "RUNNING 7세션 전부 10,000원 균일 확인"은 세션 33에 한해 사실이 아니었다** — 당시 복구 UPDATE는 세션 35만 대상이었다.
- **결과**: 2,000 × investRatio 0.8 = 1,600원 < 업비트 최소주문 5,000원. **07-29 07:15 이후 세션 33 매수 0건**으로 확증(진입 분포 표에서 33만 `filled=0`).
- **[x] 복구 완료 (07-31, 운영 DB 1행 UPDATE 커밋)** — `available_krw` 2,000 → **10,000**. 가드로 `AND available_krw=2000 AND status='RUNNING' AND current_position_id IS NULL` 를 걸어 rowcount=1 확인 후에만 커밋. **낙관적 락 `version` 도 +1** 해서 진행 중이던 tick 의 stale 쓰기가 조용히 덮어쓰지 못하게 했다(07-29 복구에는 없던 조치). 복구 후 **RUNNING 7세션 전 정합성 ±0.004원** 확인.

### 🔴 [신규 P1] 세션 38 KRW-RLUSD 42시간 고착 — 스테이블코인에 시간 기반 청산 부재

- 포지션 2367이 07-29 14:16 진입 후 **42.1시간째 OPEN**. `avg_price=1,428` / `SL=1,356.6`(−5%) / `TP=1,570.8`(+10%).
- **RLUSD는 리플의 USD 스테이블코인** — KRW 환산이라 사실상 원/달러 환율만 따라간다. ±5% 도달에 수개월이 걸리므로 **SL·TP 어느 쪽도 영원히 안 걸린다**. 청산 조건이 가격 기반뿐이고 **최대 보유시간(time stop)이 없어 영구 고착**.
- **세션 38은 사실상 정지 상태**(진입 1건이 그대로 묶여 자본 8,000원이 잠김). 워치리스트도 3개로 최소.
- **[ ] 대응 후보** — ①최대 보유시간 초과 시 청산(time stop) 도입 ②워치리스트 구성 시 스테이블코인 계열 배제(ATR 하한이 이미 있으나 `min_atr_pct` 가 세션 설정에 있으니 값 점검 필요).

### 🔴 [확증] 동일 코인 다세션 동시 보유가 운영에서 상시 발생

- 실제 이력: **KRW-KAITO**(세션 32·36), **KRW-O**(34·35 — 07-30 01:45:46 / 01:45:49, **3초 차 동시 진입**), **KRW-UP2**(32·36 — **07-31 07:30 현재 진행 중**).
- 즉 위 07-31 수정 ②(`findByCoinPairAndStatus` Optional → NonUniqueResult)가 **가정이 아니라 상시 성립하는 조건**이었다. 회귀 테스트에서 되돌렸을 때 실제로 `NonUniqueResultException` 이 재현된 것과 일치.

### 🔴 [신규] 미실현 손익이 **한 번도 갱신되지 않는다** — 화면 수익률이 보유 중 무의미

- OPEN/CLOSING 포지션 **5건 전부 `unrealized_pnl=0`**(전 기간 최댓값도 0).
- 원인: [`PositionService.updateUnrealizedPnl`](../web-api/src/main/java/com/cryptoautotrader/api/service/PositionService.java#L70) 은 **호출자가 없는 죽은 코드**다(PaperTradingService는 자체 private 구현을 따로 갖고 있음). LIVE·DYNAMIC 어느 쪽도 미실현을 쓰지 않는다.
- **영향**: `total_asset_krw` 는 실현손익만 반영하므로(세션 32·34·35·36 전부 실현손익과 1원 단위까지 일치 확인) **`returnPct` 는 "확정 손익 기준"으로는 정확**하다. 그러나 보유 중 평가손익이 0이라, 07-31 신설한 손익 분해 패널의 "미실현 손익"도 현재는 항상 0으로 나온다.
- **[ ] 후속** — 미실현 갱신 스케줄러(또는 WS 틱 훅)에서 `updateUnrealizedPnl` 을 실제로 호출하도록 배선 필요. 배선 전까지 UI의 미실현 값은 "미집계"로 읽어야 한다.

---

## 🆕 2026-07-29 검증 결과 — 완화·V61 전부 성공, 그러나 **주문 INSERT 롤백**이라는 다음 벽 발견

> 07-28 16:54 `scan_*` 완화 적용 후 예정된 5단계 검증을 운영 DB 직접 조회로 수행(07-29 10:00 KST). **3개는 성공 확정, 대신 그 뒤에 숨어 있던 P0 버그가 드러남.**

- **[x] ① 워치리스트 회복 — 성공** — RUNNING 7세션 전부 재구축됨(07-29 00:33~00:56 UTC). `[]`×2 / 1~3개 → **4~10개**. 전 세션이 **KRW-BTC·ETH·XRP·SOL 메이저 포함**(s38만 4개). 완화(`scan_require_uptrend=false`·`scan_exclude_crashing=false`)가 의도대로 하락장 메이저를 되살렸다. 유동성 50억·ATR 4%는 NULL 유지 = 코드 기본값으로 잡코인 배제 계속 작동.
- **[x] ② BUY 신호 재개 — 성공** — 07-26~28 0건 → 완화 직후 **07-28 20:00부터 BUY 10건**(07-28 7 + 07-29 3). 차단사유가 **EMA200/BLACK_SWAN 게이트에서 완전히 사라짐**. 남은 미선택 사유는 `다른 코인 신호가 더 강함`(3건, 정상 경쟁) + `가용 KRW 부족`(1건, 아래 ④). 세션별로도 33·34·35·36·37이 고루 BUY 생성.
- **[x] ③ 🎯 V61 FK 수정 최종 검증 — 성공 확정** — **DYNAMIC position 6건 실제 생성**(id 2359·2360·2362·2363·2364·2365, 세션 33/34/36/37). FK가 살아 있었다면 INSERT 자체가 불가능했던 지점을 통과. `pg_constraint` 재확인 — position/"order"에 session_id FK 없음(남은 건 `position_strategy_config_id_fkey`·`order_position_id_fkey`). **`executeBuy` 경로가 처음으로 실행됨.**
- **[x] ⑤ LIVE 회귀 없음** — 세션 192 KRW-BTC가 07-28 21:05 정상 매수 체결(order 8587 FILLED, position 2361 OPEN, size 0.00008423). LIVE 주문 흐름 무손상.

### 🔴 [신규 P0] DYNAMIC 주문이 DB에 단 한 건도 남지 않는다 — 실체결 여전히 0건

- **증상** — DYNAMIC position 6건이 전부 `size=0`·`invested_krw=8000`으로 생성됐다가 **정확히 5분 뒤 CLOSED**. `"order"` 테이블의 DYNAMIC 행은 **0건**(`session_kind` 분포: LIVE만 FILLED 462·FAILED 6,705·CANCELLED 8). 즉 매수 주문이 거래소로 나가기는커녕 **DB 행조차 남지 않았다.**
- **[결정적 증거] 시퀀스 갭 = INSERT 실행 후 롤백** — `order_id_seq.last_value=8591` vs `MAX(id)=8587`. **4개 이상의 id가 소비됐으나 행이 없다.** LIVE 구간(8510~8587)은 갭이 **전혀 없음**(연속 +1) → 롤백은 DYNAMIC 주문에서만 발생. 반면 `trade_log_id_seq=MAX=14849`(미소비) → `recordTradeLog` 이전, 즉 **`orderRepository.save(order)` 그 자체가 실패**한 것으로 좁혀진다.
- **[유력 원인] `@Async` 주문 제출이 부모 트랜잭션의 미커밋 `position_id`를 참조** — [`executeBuy`](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java#L750)(`@Transactional`, 호출자 [`processTick`](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java#L377)도 `@Transactional`)가 `positionRepository.save(pos)` → **커밋 전에** [`submitOrder`](../web-api/src/main/java/com/cryptoautotrader/api/service/OrderExecutionEngine.java#L120)(`@Async`+`@Transactional`, **별도 스레드·별도 트랜잭션**)를 호출하고 `positionId`를 넘긴다. 별도 트랜잭션에서는 미커밋 position 행이 보이지 않아 `order_position_id_fkey` 검사가 부모 커밋까지 락 대기 → 타임아웃/데드락으로 async 측이 희생 → order INSERT 롤백. LIVE([`executeSessionBuy`](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#L1148))는 **구조는 동일하나** 트랜잭션 구간이 짧아 여태 타이밍상 통과해온 것으로 보인다(= LIVE도 잠재 위험).
- **[안전망은 정상 작동]** — [`reconcileDynamicOrphanBuyPositions`](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java#L1274)의 "주문 엔티티가 아예 없는 경우(async 스레드 DB 오류 등)" 분기(5분 경과 조건)가 정확히 발동해 포지션 CLOSED + KRW 복원. 세션 33·34·36·37 잔고 **10,000원으로 온전히 복구 확인**. 손실 0.
- **[x] 수정 완료 (코드) — 커밋 이후 발행으로 전환** — [`OrderExecutionEngine.submitOrderAfterCommit`](../web-api/src/main/java/com/cryptoautotrader/api/service/OrderExecutionEngine.java) 신설: 트랜잭션 활성 시 `TransactionSynchronizationManager.registerSynchronization`의 `afterCommit`에 제출을 등록하고, 트랜잭션 밖이면 즉시 제출로 폴백. **자기 프록시(`@Lazy self`) 경유** — `this.submitOrder(..)`로 부르면 `@Async`/`@Transactional`이 통째로 무시되므로 필수.
  - 적용 = **매수 2곳만** (신규 포지션을 참조하는 경로): [DynamicTradingService](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java#L816), [LiveTradingService](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#L1230). **매도 경로는 의도적으로 미변경** — SELL의 `positionId`는 이미 커밋된 기존 포지션이라 FK 대기가 없고, 손절 경로의 블래스트 반경을 넓히지 않기 위함.
  - 부수 효과: 트랜잭션이 롤백되면 주문이 아예 나가지 않는다 → **포지션 없이 주문만 거래소로 나가는 사고도 함께 차단**된다.
- **[x] 회귀 테스트** — [OrderSubmitAfterCommitTest](../web-api/src/test/java/com/cryptoautotrader/api/service/OrderSubmitAfterCommitTest.java) 3건(커밋 전 미발행 / 롤백 시 미발행 / 트랜잭션 밖 폴백). **수정을 무력화하면 3건 중 2건이 실패함을 확인**한 뒤 복원 — 실효성 있음. ⚠️ 첫 시도에서 "커밋 전 미발행" 단언이 무력화 상태에서도 통과했다(async가 아직 시작 전이라 0). 트랜잭션 안에서 **1.5초 대기 후 카운트**하도록 고쳐 실효화. `:web-api:test` 전체 통과.
  - H2 스키마엔 운영과 달리 해당 FK가 없어 **락 경합 자체는 재현 불가** — 테스트가 잠그는 것은 근본 원인인 **호출 시점**이다.
- **[x] 커밋·배포 완료** — 커밋 `26425ee`(5파일). 마이그레이션 없음(코드 변경만) — Flyway 무관.
- **[x] 🎉 운영 검증 완료 — 동적 세션 사상 첫 실체결 성공 (07-29 14:16 KST)**: 배포 후 약 4시간 감시(60초 폴링) 끝에 첫 매수가 통과.
  - **주문 8592** — 세션 38(COMPOSITE_MEANREV_BB) `KRW-RLUSD` BUY **FILLED**. `PENDING → SUBMITTED → FILLED` 3단계 전이가 `trade_log`(14850~14852)에 온전히 기록. 체결수량 5.60224089, 체결금액 7,999.99999092원. 제출~체결 **2.4초**.
  - **포지션 2367** — `size`가 **0 → 5.60224089로 갱신**(체결 콜백 정상 동작), `status=OPEN` 유지(고아 정리 대상 아님), avg_price 1,428, SL 1,356.6 / TP 1,570.8 세팅됨.
  - **세션 38** — `scan_state=POSITION_MONITORING`, `current_coin_pair=KRW-RLUSD`, `current_position_id=2367`, `available_krw` 10,000 → **2,000**(8,000 정상 차감). 상태 전이 완결.
  - **🎯 시퀀스 갭 4 → 0** (seq=8592, max=8592) — **롤백이 완전히 사라졌다.** 수정 전 "INSERT 후 롤백" 패턴의 소멸을 수치로 확인.
  - 이로써 07-27 노트의 미검증 항목("`executeBuy`+체결콜백 DYNAMIC 분기는 프로덕션 실행 이력 0")도 **함께 해소**. 코드리뷰가 아니라 실체결로 검증됨.
- **[x] 원인 진단 사실상 확증** — 로그 없이 DB 증거만으로 좁힌 추론이었으나, **수정 후 갭이 0이 되고 주문이 정상 발행**된 것으로 인과가 확인됐다. `docker logs` 확인은 불필요해짐.
- **[ ] 후속 관찰 — 📅 2026-07-31(금) 오후 로그 분석 예정 (사용자 합의)**. 07-29~31 3일치 실매매 데이터를 쌓은 뒤 판단한다. 지금은 표본 1건이라 손익·승률을 논할 단계가 아님. **확인 순서**:
  1. **🎯 매도 경로 (최우선 — 유일한 미검증 구간)** — SELL은 이번 수정에서 **의도적으로 미변경**(기존 커밋된 포지션 참조라 FK 대기 없음)이라 실제 청산이 정상인지 확인이 필요하다. `SELECT * FROM "order" WHERE session_kind='DYNAMIC' AND side='SELL'` → 주문 발행·FILLED 도달 여부. 대응 포지션의 `status=CLOSED`·`realized_pnl` 확정·`closing_at/closed_at` 일관성. 세션 `available_krw`가 **매도 대금만큼 복원**되는지(`ReconcileClosingPositions` 5초 스케줄러 담당 — 복원 실패 시 몇 차례 매매 후 5,000원 미만으로 영구 정지하는 것이 2026-07-01 기록된 과거 사고 패턴).
  2. **시퀀스 갭 0 유지** — `SELECT last_value, (SELECT MAX(id) FROM "order"), last_value - (SELECT MAX(id) FROM "order") FROM order_id_seq`. **갭이 다시 벌어지면 롤백 재발** = 이번 수정으로 안 잡힌 다른 경로가 있다는 뜻(매도 경로 유력).
  3. **진입 분포** — 7세션 중 몇 개가 실제 진입했는지, 특정 세션/코인에 쏠리는지. `SELECT session_id, coin_pair, COUNT(*) FROM position WHERE session_kind='DYNAMIC' GROUP BY 1,2`.
  4. **손익·체결품질** — 청산된 포지션의 `realized_pnl` 합계와 승률(표본 적으면 수치보다 **부호와 이유**를 볼 것). `execution_drift_log`에 DYNAMIC 행이 쌓이는지 + 슬리피지(LIVE BTC는 0.1% 수준 — 소형 알트는 더 클 것이므로 과도하면 최소주문/유동성 기준 재검토).
  5. **완화 조치 사후평가** — 07-28 완화(`scan_require_uptrend=false`·`scan_exclude_crashing=false`)로 들어온 종목의 4h/24h 사후수익률. 07-24~27에는 "차단이 옳았다"(사후 -3~-7%)가 결론이었으므로, **이번엔 완화가 옳았는지 실측으로 재판정**한다. 나쁘면 되돌리는 게 아니라 유동성/ATR 기준을 조이는 방향 우선(진입 게이트가 이미 3단계 감액으로 리스크를 통제하므로).
  6. **LIVE 회귀 없음** — LIVE 주문이 계속 정상 FILLED 되는지(매수 경로를 함께 바꿨으므로 반드시 확인).
  7. **잔여 근본원인 ①(MACD 앵커 신호율 0.6%)** — 실체결이 돌기 시작했으니 이제야 **의미 있는 재평가가 가능**하다. HOLD 사유가 여전히 "점수 미달 buy=0.00" 지배적인지 재집계.
  - 접속: 운영 DB `yhpapa.iptime.org:8432 / crypto_auto_trader / trader` (비밀번호는 사용자에게 요청). psql 없음 → psycopg2 사용 가능 확인됨(스크립트는 스크래치패드에).

### 🔴 [신규] 세션 35 잔고 8,000원 누수 — 사실상 매수 불능

- `dynamic_session` id=35(COMPOSITE_MTF_CONFIRMED) `available_krw=**2,000**`(타 세션 전부 10,000). `total_asset_krw`는 10,000 그대로라 **UI상 정상으로 보인다.**
- **position 이력 0건**(`WHERE session_id=35` 전 기간·전 kind 무결과)인데 KRW만 8,000 차감 → `balanceUpdater`가 `REQUIRES_NEW`([DynamicSessionBalanceUpdater](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicSessionBalanceUpdater.java#L46))라 **부모 트랜잭션 롤백과 무관하게 커밋**된 것이 유력. 07-28 07:45(V61 적용) 이전 FK 위반 시대의 잔재로 추정.
- ⚠️ **PROGRESS 07-28 기재 "잔고 오차감 없음"은 이 건에 한해 오류** — 롤백이 전부를 되돌린다는 전제가 `REQUIRES_NEW` 잔고 갱신에는 성립하지 않았다.
- **결과**: 2,000 × investRatio 0.8 = 1,600원 < 업비트 최소주문 5,000원 → 07-29 00:30 KRW-O BUY가 `가용 KRW 부족`으로 차단. **이 세션은 영구 매수 불능 상태.**
- **[x] 복구 완료 (07-29, 운영 DB 1행 UPDATE 커밋)** — `available_krw` 2,000 → **10,000**. 가드로 `AND available_krw=2000`을 걸어 rowcount=1 확인 후에만 커밋. **RUNNING 7세션 전부 10,000원 균일 확인.** `total_asset_krw`는 원래 10,000이라 변경 불필요.

---

## 🆕 2026-07-28 동적 세션 매수 전면 실패 — `position_session_id_fkey` FK 위반 수정

> 사용자 관찰: 운영 로그에 `[Dynamic] 세션 tick 오류 (id=36/33)` + `violates foreign key constraint "position_session_id_fkey"` 반복. 원인 규명 요청.

- **근본 원인 = V12의 FK가 다형 참조로 바뀐 뒤에도 남아 있었음** — [V12](../web-api/src/main/resources/db/migration/V12__create_live_trading_session.sql#L21)가 `position.session_id`/`"order".session_id`에 `REFERENCES live_trading_session(id)` FK를 걸었는데, V50(dynamic_session) 도입 후 이 컬럼은 **두 세션 테이블 공용**이 되고 구분자로 `session_kind`만 추가됐다(V51/V52). **FK는 끝내 DROP되지 않음**(전체 마이그레이션에 `DROP CONSTRAINT` 0건). → DYNAMIC 포지션 INSERT 시 DB가 `dynamic_session.id`(33/36)를 `live_trading_session`에서 찾다 실패.
- **왜 지금 터졌나** — 동적 세션은 진입 게이트(EMA200/BLACK_SWAN/BTC guard)에 전량 막혀 **실거래 0건**이라 FK가 잠복해 있었다. 2026-07-15~07-24 진입 완화로 실제 매수가 시작되며 매 tick 실패로 표면화. 즉 **동적 매매가 단 한 건도 성사되지 못하는 상태**였음.
- **왜 테스트가 못 잡았나** — `schema-h2.sql`의 `session_id` 6곳에 **FK가 애초에 없음**. 운영 DB만 테스트와 다른 상태였다.
- **[x] 수정 ① [V61](../web-api/src/main/resources/db/migration/V61__drop_polymorphic_session_id_fk.sql)** — `position_session_id_fkey` / `order_session_id_fkey` DROP. 다형 참조의 실제 키는 `(session_kind, session_id)` 쌍이라 SQL FK로 표현 불가 → 애초에 **틀린 제약**. 무결성은 `findBySessionKindAnd...` 계열이 보장하고, 세션 삭제는 양쪽 모두 soft-delete(`status='DELETED'`)라 부모 행이 사라지는 경로도 없음.
- **[x] 수정 ② [DbResetService](../web-api/src/main/java/com/cryptoautotrader/api/service/DbResetService.java)** — 조사 중 발견한 **별개 버그**: `resetLiveTrading()`의 `DELETE FROM position/"order" WHERE session_id IS NOT NULL`에 `session_kind` 필터가 없어 **"실전매매 초기화"가 동적 세션 데이터까지 삭제**. FK 때문에 동적 포지션이 존재한 적이 없어 잠복해 있던 것 — ①을 고치면 실제 피해가 된다. reset·stats 쿼리 전부 `AND session_kind = 'LIVE'` 추가.
- **[x] 회귀 테스트** — [SessionKindIsolationTest](../web-api/src/test/java/com/cryptoautotrader/api/service/SessionKindIsolationTest.java)에 "실전매매 초기화는 DYNAMIC 포지션/주문을 지우지 않는다" 추가. **수정 되돌리면 실패함을 확인**(테스트에 실효성 있음) 후 복원. `:web-api:test` 전체 통과.
- **데이터 오염 없음** — 예외가 `processTick`의 `@Transactional` 안에서 발생해 전부 롤백됐다. 잔고 오차감 없음.
- **[x] 운영 배포·검증 (07-28 16:50, 운영 DB 직접 조회)** — ① `flyway_schema_history`: **V61 success=t, 2026-07-28 07:45:26 적용**. ② `pg_constraint`: **public.position / public."order" 에 session_id FK 없음**(남은 건 `position_strategy_config_id_fkey`·`order_position_id_fkey`, 그리고 `paper_trading` 스키마의 virtual_balance FK — 전부 정상·다형 아님). ③ 배포 후 9시간 동안 DYNAMIC 전략로그 367건(HOLD 329/SELL 38, 마지막 16:48) — **틱 정상 순환, FK 오류 재발 없음**. ⚠️V58 사고 교훈대로 **V61은 적용 후 절대 수정 금지**.
- **[x] FK 수정 경로 검증 완료 (07-29)** — DYNAMIC position 6건 실제 INSERT 성공. **V61 성공 확정.** 단 그 다음 단계인 주문 INSERT가 별도 원인으로 롤백됨 → 위 07-29 섹션 참조.
- **🔴 [신규 발견] 워치리스트 고갈 — BUY 신호가 7/24부터 죽었다** (FK와 별개, 더 근본적):
  - 일별 DYNAMIC BUY: 7/20~23 **10·9·9·13건** → 7/24 **1건** → 7/25 **1건** → **7/26~28 0건**. HOLD 평가수도 5,500~6,100/일 → **1,283/일**로 급감.
  - 현재 워치리스트(RUNNING 7세션): `[]`(2개), `[ZAMA]`, `[STORJ]`, `[ZAMA,STORJ]`, `[ZAMA,KAITO,STORJ]` — **목표 10개 대비 0~3개**, 두 세션은 완전히 빈 상태.
  - 시점이 **V57 워치리스트 품질 큐레이션(7/24)과 정확히 일치**. `risk_config`의 `scan_*` 컬럼은 **전부 NULL** → 코드 기본값(거래대금 50억·ATR 4%·상승추세 필수·급락 제외)이 그대로 적용 중.
  - **역설**: 잡코인 배제가 목적이었는데, `requireUptrend`가 하락장의 메이저(BTC/ETH)를 전부 탈락시켜 **살아남는 게 오히려 펌핑 중인 소형 알트(ZAMA·STORJ·KAITO)뿐**. 의도와 정반대 결과.
  - **[진단 확정] V57이 7/21~22 완화를 조용히 되돌렸다** — SCANNING 진입 경로는 EMA200을 `buySizeMultiplier`(3단계 1.0/0.5/0.0, 마진 3%), 급락을 `BlackSwanGuard.entryGate`(3단계 통과/감액/차단)로 **완화 적용**한다. 그런데 [WatchlistQualityGate](../core-engine/src/main/java/com/cryptoautotrader/core/selector/WatchlistQualityGate.java)는 **같은 두 검사를 하드 버전으로 앞단에서** 수행한다(`allowsBuy(candles, null)` — 마진 null / `BlackSwanGuard.check` — 3단계 아님). 앞단 하드 차단이라 코인이 **평가 자체를 못 받고**, 뒤의 완화 로직이 무용지물이 됐다.
  - **[x] 완화 적용 (07-28 16:54, 운영 DB `risk_config` id=1 UPDATE, 1행 커밋)** — `scan_require_uptrend=false`, `scan_exclude_crashing=false`. **유동성(50억)·ATR(4%)은 NULL 유지=코드 기본값** — 잡코인 배제를 실제로 담당하는 건 이 둘이고 진입 게이트가 대체할 수 없는 앞단 고유 기준이라 그대로 둔다. 다른 설정(서킷브레이커 on·MDD 20%) 무영향 확인. `getRiskConfig()`는 캐시 없이 매 틱 최신 행을 읽으므로 **재빌드·재시작 불필요**.
  - **[x] 검증 완료 (07-29) — 완화 성공, 워치리스트·BUY 모두 회복.** 결과는 위 07-29 섹션 참조. 아래는 당시 세운 확인 순서(기록 보존):
    1. **워치리스트 회복** — `SELECT id, watchlist_refreshed_at, watchlist_json FROM dynamic_session WHERE status='RUNNING'`. 07-28 16:50 기준 `[]`×2 / 1~3개였음. 목표 10개에 근접하고 메이저(BTC/ETH) 포함되는지.
    2. **BUY 신호 재개** — `SELECT DATE(created_at), signal, COUNT(*) FROM strategy_log WHERE session_type='DYNAMIC' AND created_at >= DATE '2026-07-28' GROUP BY 1,2`. 07-26~28 BUY 0건 → 07-29에 나오는지 (7/20~23 수준은 9~13건/일).
    3. **🎯 FK 수정 최종 검증** — `SELECT * FROM position WHERE session_kind='DYNAMIC'`. **1건이라도 생기면 V61 성공 확정** (현재까지 0건 = `executeBuy` 미실행이라 미검증 상태). 동시에 `"order"` 의 DYNAMIC 주문도 함께 확인.
    4. **차단사유 분해** — BUY가 여전히 0이면 `SELECT blocked_reason, COUNT(*) ... WHERE blocked_reason IS NOT NULL GROUP BY 1`. 이번엔 워치리스트가 아니라 진입 게이트(EMA200/블랙스완/BTC가드/손실쿨다운) 중 어디서 막히는지 나온다.
    5. **회귀 없음 확인** — LIVE 세션 포지션·주문이 정상인지, FK 오류가 다른 형태로 재발하지 않는지.
    - ⚠️ **판정 주의**: BUY strategy_log 건수는 과거 데이터에선 과소집계다(아래 참고 항목). 07-28 16:54 완화 적용 **이후** 구간만 유효한 비교 대상.
    - 접속: 운영 DB `yhpapa.iptime.org:8432 / crypto_auto_trader / trader` (비밀번호는 사용자에게 요청). psql 없으므로 JDBC 드라이버 직접 사용: `~/.gradle/caches/.../postgresql-42.6.2.jar`.
  - **[참고] 설정 보존 확인** — `updateRiskConfig()`가 `scan_*` 9개 필드를 모두 복사하므로, 이후 UI/API로 리스크 설정을 바꿔도 이 값은 유지된다.
- **[참고] 과거 BUY 건수는 과소집계** — FK 예외가 `processTick` 트랜잭션 전체를 롤백시켜, **게이트를 통과해 executeBuy까지 간 BUY의 strategy_log 행도 함께 사라졌다.** 즉 7/20~23의 "BUY 9~13건"은 게이트에 차단된 것만 남은 수치.

---

## 🆕 2026-07-27 (오후) 숏(선물) 도입 설계 스케치 — [docs/DESIGN-short-futures.md](DESIGN-short-futures.md)

> 사용자: 롱 전용 현물이라 하락장 수익 불가 → 숏 검토(선물 필요). 실제 구축 전 설계 스케치 요청.

- **핵심 발견**: `ExchangeAdapter`는 **시세만** 추상화, **주문은 업비트 하드코딩**(`OrderExecutionEngine`→`UpbitOrderClient` 직접). 포지션 롱 전용. → 숏은 "기능 추가"가 아니라 **주문 추상화 신설 + 신규 거래소 + 파생 리스크** 3워크스트림.
- **거래소 추천 = Bybit**(v5 API·테스트넷 우수). ⚠️한국 접근성(KYC) 사용자 확인이 Phase 0 관문. ⚠️견적통화 KRW→USDT 불일치 회계 고려.
- **설계 완료(문서)**: `OrderGateway` 인터페이스 초안(intent+reduceOnly로 숏 표현), Bybit v5 엔드포인트 매핑, 데이터모델 확장(exchange/market_type/leverage/liquidation_price), 숏 SL/TP·청산 역산, Phase 1 실행순서(테스트넷 무자본 검증 우선).
- **재사용**: 전략 신호(SELL→숏진입 매핑)·세션·레짐·리포팅. **де-리스킹**: OrderGateway 추상화부터(숏 무관 이득)→Bybit 테스트넷.
- **[ ] 미착수** — 스케치 단계. 진행 시 Phase 0(접근성 확인)부터.

---

## 🆕 2026-07-27 (오후) SL 미점검 워치독 경고 — WS "조용한 정지" 사각지대 수정

> 사용자 관찰: 텔레그램에 `⚠️ SL 미점검 3분 초과: 세션 192(KRW-BTC)/193(KRW-ADA). WS 상태를 확인하세요.` 반복. 원인 규명 + 근본 수정.

- **경고 정체 = [§9 워치독](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java) `warnStaleSlCheck`** — OPEN 포지션 있는 RUNNING 세션이 3분 넘게 SL 점검(=실시간 가격 이벤트)을 못 받으면 경고.
- **근본 원인 = WS "조용한 정지" 감지 사각지대** — SL 점검은 `RealtimePriceEvent`(WS 틱) 수신 시 실행되고, REST 폴백은 `isWsDownLongerThan`(=`webSocketConnected` 플래그)로만 발동. **WS가 "연결됨"인데 틱만 멎으면**(구독 사망/무데이터) 플래그는 true라 폴백이 안 켜지고 SL 감시가 굶음. KRW-BTC는 초유동성이라 "시장이 조용해서"가 아니라 **파이프라인 정지**(정황상 오늘 반복 재시작 후 재구독 실패 유력).
- **[a] 노출도 확인(07-27)** — 둘 다 소액(~8천원)·안전: BTC +0.97%(SL까지 +5.9%), ADA -1.23%(SL까지 +3.8%). 당장 위험 없음.
- **[x] 근본 수정 — 틱 신선도 기반 감지**: [ExchangeHealthMonitor](../web-api/src/main/java/com/cryptoautotrader/api/service/ExchangeHealthMonitor.java)에 `lastWsTickAt` + `markWsTick()`(WS 리스너에서만 호출) + `isWsStale(sec)`(연결됨인데 틱 끊김) + `isWsUnhealthy=다운 OR 정지` 추가. 연결 시 틱시각 리셋(재연결 오탐 방지). [LiveTradingService](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java): WS 틱 리스너에 `markWsTick()` 훅, `pollRestTickerFallback` 발동조건을 `isWsUnhealthy`로 → **조용한 정지에도 REST 폴백이 켜져 SL 감시 유지**. [WsFallbackTest](../web-api/src/test/java/com/cryptoautotrader/api/service/WsFallbackTest.java) 2건 추가·통과, `:web-api:compileJava` 통과.
- **[정정] "never-connected" 진단은 오류였음** — 앞선 로그 조각(05:20 런)에 startup이 안 잡혀 WS 미연결로 오판했으나, 재시작 startup 로그(05:27) 확인 결과 **WS 정상 연결·구독됨**: `Upbit WebSocket 연결 성공` + `구독 메시지 전송 coins=[KRW-ADA,KRW-BTC,KRW-XRP]`. 확인 없이 단정한 오류 정정.
- **[x] 실제 원인 = 이전 컨테이너의 WS 조용한 정지(연결 후 드롭/틱정지, 재연결·재구독 실패)** — 이번 재시작에선 WS가 깨끗이 붙어 복구. **SL 경고가 startup 과도기(14:27:31, "기록없음") 이후 1.7분+ 신규 0건**으로 멎음 = 틱 흐름·`recordSlCheck` 재개 확인.
- **[x] 틱 신선도 기반 폴백 수정 — "안전망"으로 유효**: `ExchangeHealthMonitor` 생성자 `lastWsTickAt=now` 초기화 + `isWsStale` 연결게이트 제거(연결 여부 무관, 틱 안 오면 stale) → WS가 또 조용히 멎어도 REST 폴백이 SL 감시 유지. `WsFallbackTest` 갱신, 컴파일·테스트 통과. (이번엔 WS 자체가 살아나 안전망까지 안 감)
- **[ ] 후속 관찰** — WS가 재차 조용히 멎는지(간헐 드롭 재발 여부) 모니터링. 재발 시 `[§9] … REST ticker fallback 활성화` 로그로 안전망 작동 확인. 반복되면 WS 재연결/재구독 로직(하트비트·핑퐁) 보강 검토.

---

## 🆕 2026-07-27 (오후) 모닝 브리핑 시스템 진단 — "AI 요약"이 며칠째 죽어 있었음 + 텔레그램/48h 확장 계획

> 사용자 요청: "매일 아침 5시 대형/중형 코인 이슈·추세(48h) 간단 리포트". 조사 결과 **해당 파이프라인이 이미 80% 구현·운영 중**이었으나 핵심 AI 부분이 조용히 고장나 있었음을 발견.

- **[발견] 모닝 브리핑은 이미 매일 07:00 KST Discord로 발송 중** — `MorningBriefingScheduler`(cron 07:00) → `MorningBriefingComposer` 4채널(TRADING_REPORT·CRYPTO_NEWS·ECONOMY_NEWS·ALERT). `discord_send_log` 07-24~27 전부 SUCCESS. 시장 레짐·BTC/ETH 흐름·전략성과·차단사유 + LLM 서술 포함.
- **[🔴 핵심 버그] 모든 LLM 호출이 며칠째 실패** — `llm_call_log` 전건 `success=false`, 원인 `HTTP 429 insufficient_quota` (**OpenAI 크레딧 소진**). 결과: 브리핑의 "AI 시황 분석"·"뉴스 이슈 요약"이 전부 오류 폴백 문자열로 발송됨. 즉 사용자가 원한 "이슈+추세 분석"의 핵심이 빈 껍데기였음.
- **[구조 확인] LLM은 100% DB 설정 구동, 런타임 즉시 반영** — `LlmTaskRouter`가 `llm_task_config`(task→provider) + `llm_provider_config`(key/enabled/model) 조회. `ClaudeProvider` 코드 이미 구현·완성. 관리 API `LlmConfigController`(`PUT /api/v1/admin/llm/providers/CLAUDE`, `/tasks/{...}`, `POST /test/provider`) 존재. → **재빌드·재시작 0, 설정만으로 복구 가능.**
- **[결정] Claude로 전환 + 키는 .env로 관리** (사용자 결정) — 시크릿 관리 방식 확인 결과 인프라/거래소 키는 `.env`(docker-compose env 주입), **LLM 키만 예외적으로 DB 평문 저장**이었음. 일관성·보안 위해 Anthropic 키를 `.env`로 이관하기로 결정.
- **[정정] Discord 토큰 "git 커밋" 경고는 오류였음** — `application-local.yml`은 `.gitignore:20`에 등록된 **로컬 전용·미추적** 파일(히스토리 0건). 운영은 `${DISCORD_BOT_TOKEN}` env로 정상. 실제 유출 없음. (확인 없이 단정한 오류를 정정)
- **[부수] 뉴스 소스 4개 중 2개만 활성** (CoinDesk·Bloomberg RSS ON, CryptoPanic·CoinGecko 트렌딩 OFF) — 코인 이슈 커버리지 얇음. 활성화 검토.
- **[x] Phase 1 코드 완료 — LLM 복구 (.env 방식, 재빌드 필요)**: ① [docker-compose.prod.yml](../docker-compose.prod.yml) backend에 `ANTHROPIC_API_KEY` 주입, ② [application.yml](../web-api/src/main/resources/application.yml) `anthropic.api-key: ${ANTHROPIC_API_KEY:}`, ③ [ClaudeProvider](../web-api/src/main/java/com/cryptoautotrader/api/llm/provider/ClaudeProvider.java) `@Value` env 키 우선·DB 폴백(`resolveApiKey`)+`isAvailable` 갱신, ④ [V58 마이그레이션](../web-api/src/main/resources/db/migration/V58__switch_llm_to_claude.sql) CLAUDE enable + 4 task 라우팅 전환 **+ model=claude-sonnet-5**(사용자 결정: 전부 Sonnet, 요약·분석 품질 상향·비용 미미, Opus는 일일 브리핑에 과잉이라 미채택). `:web-api:compileJava` 통과. `.env.example`도 갱신.
- **[x] 배포 사고·복구 — V58 체크섬 불일치 (부팅 실패)** — V58이 07-27 03:33 이미 적용된 뒤(체크섬 `-1050168288`, model NULL) model 라인을 추가 수정 → Flyway 검증 실패로 백엔드 부팅 중단. **복구: V58을 배포본과 바이트 동일하게 원복(CRC32로 `-1050168288` 일치 검증)**, 모델 설정은 [V59](../web-api/src/main/resources/db/migration/V59__set_llm_model_sonnet.sql)로 분리. 교훈: **적용된 마이그레이션은 절대 수정 금지, 항상 새 버전 추가.**
- **[x] temperature deprecated 오류 수정 (ClaudeProvider)** — 크레딧 충전 후 sonnet-5 호출 시 `HTTP 400: temperature is deprecated for this model`. Claude 5 계열은 temperature 파라미터를 거부. 선택 파라미터이므로 **요청 body에서 완전히 제거**(생략 시 모델 기본값, 요약용도엔 충분). `:web-api:compileJava` 통과. 오류가 "credit too low"→"temperature"로 바뀐 것은 **크레딧 충전 완료** 신호.
- **[x] 빈-모델 버그 수정 (ClaudeProvider)** — 라우터가 `llm_task_config.model`을 요청에 싣는데, 값이 빈 문자열("")이면 `getModel()!=null`이 true라 빈 모델명이 Anthropic에 전송돼 400 발생 가능(테스트 경로는 model 미지정→default라 통과했으나 실제 브리핑 경로는 실패할 뻔). `getModel()`이 blank면 `default_model`로 폴백하도록 수정. V58이 model을 명시 세팅하므로 이제 sonnet-5 사용.
- **[x] Phase 1 배포·파이프라인 검증 완료 (07-27)** — 운영 재빌드 후 `test/provider`로 CLAUDE 호출 성공. 응답: 바깥 `success:true`(인증·라우팅·env키 배선 전부 정상, Anthropic 결제 단계까지 도달), 안쪽만 `HTTP 400 credit balance too low`. **키 유효(401 아님)·V58 라우팅 정상 확인.** 인증은 컨테이너 내부 `$API_AUTH_TOKEN`으로 해결(호스트 셸 추출 시 CR/따옴표 섞임 주의).
- **[x] Phase 1 완전 종료 — 운영 검증 완료 (07-27 13:25 KST)**: 크레딧 충전 후 `llm_call_log`에 REPORT_NARRATION·NEWS_SUMMARY `success=true`, model=`claude-sonnet-5`, 토큰 소모 기록(779/887 등). Discord 07:00 브리핑에 실제 AI 시황·뉴스 요약 정상 표시 확인(사용자 + Claude 플랫폼 토큰 확인). 오류 진행: 429(OpenAI쿼터)→400(temperature)→success. **LLM 두뇌 완전 복구.**
- **[x] Phase 2 코드 완료 — 텔레그램 05:00 아침 브리핑 (Discord 07:00 유지, 텔레그램 신규 추가)**: 4개 신규 클래스, `:web-api:compileJava` + 테스트 2건 통과.
  - [MarketTrendScanner](../web-api/src/main/java/com/cryptoautotrader/api/report/MarketTrendScanner.java) — **대형/중형 추세 스캔 신규**. Upbit 24h 거래대금 상위 N(기본 8)개의 48h/24h 변화율·중기추세(시간봉 EMA200 대비)·변동성(ATR%) 산출. 읽기 전용, 매매 무관. 테스트 [MarketTrendScannerTest](../web-api/src/test/java/com/cryptoautotrader/api/report/MarketTrendScannerTest.java) 2건(계산 검증·데이터부족 제외).
  - [TelegramBriefingComposer](../web-api/src/main/java/com/cryptoautotrader/api/report/TelegramBriefingComposer.java) — `LogAnalyzerService.analyze`를 **48h 윈도우**로 호출(엔진은 원래 윈도우 파라미터화됨, `btcPriceChange12h` 필드명만 레거시) + 추세스캔 + 뉴스 요약을 텔레그램 메시지 3건(①AI시황+추세 ②시스템 자기진단 ③뉴스 이슈)으로 조립. `TelegramNotificationService.sendMarkdown` 사용. LLM 실패 시 수치 리포트는 정상 발송, AI 서술만 폴백 문구.
  - [TelegramBriefingScheduler](../web-api/src/main/java/com/cryptoautotrader/api/report/TelegramBriefingScheduler.java) — `@Scheduled(cron "0 0 5 * * *", Asia/Seoul)`.
  - [BriefingController](../web-api/src/main/java/com/cryptoautotrader/api/controller/BriefingController.java) — `POST /api/v1/admin/briefing/telegram/trigger` 수동 트리거(배포 후 즉시 테스트용).
- **[x] Phase 2 배포·검증 완료 (07-27)**: 재빌드 후 `POST /api/v1/admin/briefing/telegram/trigger`로 **텔레그램 3건 수신 확인**(①AI 시황+대형/중형 추세 ②시스템 자기진단 ③뉴스 이슈). AI 서술·뉴스 요약 sonnet-5로 실내용 표시. 매일 05:00 KST 자동 발송 활성. Discord 07:00은 그대로 병행. **컨테이너에 curl 없어(슬림 JRE) 트리거는 호스트 curl로 실행.**
- **[x] Phase 3 브리핑 보완 — 코드 완료·컴파일·테스트 통과 (사용자 요청: 셋 다 + 전문가 시선)**:
  - **뉴스 확대** [V60](../web-api/src/main/resources/db/migration/V60__expand_news_sources.sql): ① **버그 발견·수정** — cryptopanic·coingecko가 `source_type='API'`로 잘못 설정돼 레지스트리(CRYPTOPANIC/COINGECKO) 매칭 실패 → 여태 수집 불가였음. 타입 교정+활성화. ② 한국 크립토 RSS 2종(토큰포스트·블록미디어) 추가. ③ **업비트 공지 소스 신규** [UpbitNoticeSource](../web-api/src/main/java/com/cryptoautotrader/api/news/source/UpbitNoticeSource.java)(상장/유의/거래중단 — 업비트 트레이더 최대 이슈원). ※RSS URL·업비트 API는 실패 시 graceful 0건, 운영 검증 후 조정.
  - **무거래 퍼널 자가진단** [LogAnalyzerService.buildNoTradeFunnel](../web-api/src/main/java/com/cryptoautotrader/api/report/LogAnalyzerService.java): DYNAMIC "왜 거래 없나"를 HOLD(점수미달 vs 기타) / 매수신호→게이트차단→체결로 분해. 우리가 매번 psycopg2로 깠던 그 퍼널을 브리핑이 자동으로. buy=0이면 "병목은 스코어" 자동 판정.
  - **추세 강화** [MarketTrendScanner](../web-api/src/main/java/com/cryptoautotrader/api/report/MarketTrendScanner.java): 거래량 급증률(최근24h vs 직전24h)·EMA 이격률(추세강도)·시장폭(breadth 상승개수·평균)·BTC대비 상대강도 추가.
  - **전문가 지표**: 공포·탐욕 지수(alternative.me) 추가 — AI 서술 프롬프트에도 반영.
  - `:web-api:compileJava` + MarketTrendScannerTest 통과. **배포 시 V60 자동 적용.**

---

## 🆕 2026-07-27 주말 운영 체크 — V57 큐레이션 유니버스 정화 성공, 병목이 "신호 생성"으로 이동

> 운영 DB 분석(07-27 08:00 KST): 07-24 V57 배포 후 주말 내내 동적 세션 7개(32~38) 전부 RUNNING·SCANNING, **실체결 0건**(position DYNAMIC 전무, was_executed 로그 0). 자본 7×10,000원 그대로, 손실 0·CB 발동 0.

- **V57 큐레이션은 유니버스 측에서 정확히 의도대로 작동** — BUY 신호가 급락 잡코인에서 대량 발생하던 구조가 소멸. 일별 BUY: 07-20~23 **9~13건/일** → 07-24(배포) **1** → 07-25 **1** → 07-26~27 **0**.
- **워치리스트가 대형/중형 상승추세주로 좁혀짐** — 현재 구성 `BTC·ETH·XRP·SOL·SHIB·LPT·BLEND·MORPHO·PENGU·PIEVERSE·VVV`. 07-24 노트의 펌프-덤프 잡코인(NEO/BIRB/SOPH/ZKC/BONK) **전량 배제**. 세션당 6~10개 확보 — "과도하게 비어 무거래 회귀"는 s38(MEANREV_BB) 2개(ETH/MORPHO)뿐, 나머지는 건강.
- **주말 BUY 2건(07-24 KRW-O, 07-25 KRW-UP2)마저 잡코인** → BLACK_SWAN·RANGE 게이트가 차단, 사후수익률 4h **-7.47% / -3.82%**로 **차단 정당**(방어 성공). SELL 789건은 전량 `SCANNING 보유없음` 노이즈.
- **무거래의 진짜 원인 = 신호 생성(스코어) 측으로 이동** — HOLD 지배 사유 여전히 **"점수 미달 buy=0.00 sell=0.00"**(서브전략 무투표). 유니버스를 정화하니 남은 대형주에서 이 추세·모멘텀 프리셋들이 신호를 못 만듦(PROGRESS 예측대로 대형주는 HOLD/SELL만). **잔여 근본원인 ①(MACD 앵커 신호율 0.6%)이 이제 단독 병목.**
- **[판단] 유니버스 완화(scan_* 다이얼)는 권장하지 않음** — 07-24 노트의 "무거래 지속 시 `scan_require_uptrend=false` 등 완화"는 이번 데이터상 **역효과**. 완화하면 걸러낸 급락 잡코인이 유니버스로 재유입 → 잡코인 BUY 재발 → BLACK_SWAN 재차단(사후 -7%대로 차단이 옳음 입증)일 뿐. 병목이 유니버스가 아니라 스코어 모델이므로 완화는 정화 성과만 되돌림.
- **[정정] 07-23 재스케일은 이미 배포됨 (07-27 확인)** — PROGRESS 구 메모의 "미배포"는 오류. 재빌드(07-24 커밋 `6200773`)에 포함, 운영 런타임에 재스케일 강도 실측(BOLLINGER 100·VWAP 57·VD 100). 배포로 풀 문제 아님 — 현 장세에서 서브전략이 SELL만 내서 buy=0.00. **시장 상승 전환 대기.**
- **[ ] 잔여 근본원인 ①(MACD 앵커 0.6%)** — 재스케일·유니버스 정화 후에도 남는 유일한 코드 레버리지. 추세 지속 투표 모드 or adxThreshold 25→20(휩쏘 검증 필요) 별도 파기. 단 지금은 장세가 BUY 셋업을 안 주는 국면이라 이걸 만져도 즉효는 제한적일 수 있음 — 상승 전환 후 재평가가 합리적.
- **[x] 동적 BUY 체결 경로 검증 완료 (07-27) — 구조적 버그 없음, "18일 0건"은 100% 게이트 차단**:
  - **로그(결정적)**: 전 기간 동적 BUY 신호 **87건 전부 게이트에서 사망**(BLACK_SWAN 69·EMA200 15·RANGE 3), `executeBuy` 도달 **0건**(후보/최소주문/중복/체결 사유 전무). 레짐 애매함이 아니라 전량 급락/추세이탈 차단이며 사후수익률(-3~-7%)로 정당성 입증.
  - **체결 엔진 정상**: 동적이 쓰는 `orderExecutionEngine.submitOrder`는 라이브가 지금도 쓰는 동일 엔진(라이브 BUY 268·SELL 6,904건, 최근 07-26 체결). 엔진 자체는 살아있음.
  - **코드 경로 정독**: `executeBuy`(최소주문검증→포지션 저장 size=0/positionId→submitOrder→KRW 차감→MONITORING 전환) → 체결 콜백 [`OrderExecutionEngine`](../web-api/src/main/java/com/cryptoautotrader/api/service/OrderExecutionEngine.java):447(positionId 직접 조회→setSize/평균단가, 부분체결 KRW 복원 **DYNAMIC 전용 분기 존재**) → `reconcileDynamicOrphanBuyPositions`(CANCELLED/FAILED 안전망). 전 구간 sessionKind 인지, 완결.
  - **유일한 한계**: `executeBuy`+체결콜백 DYNAMIC 분기는 프로덕션 실행 이력 0 — 코드리뷰+공용엔진 논리로 검증했을 뿐 실체결 미검증. 완전히 닫으려면 **소액 1건 강제 체결**뿐(현 하락장엔 불필요, 상승 전환 시 자연 체결로 검증됨).
- risk_config `scan_*` 전부 NULL → 코드 기본값(유동성 50억·ATR 4.0·상승추세 ON·급락제외 ON) 정상 작동 중.

---

## 🆕 2026-07-24 동적 워치리스트 품질 큐레이션 (무거래 근본원인 — 유니버스 측)

> 운영 DB 재분석(07-24): 동적 세션 여전히 실체결 0건. BUY 신호 86건 전량 진입 게이트 차단 — **BLACK_SWAN_GUARD 79%·EMA200 게이트 17%**. 원인 분해: ① 대형주(BTC/ETH/XRP/SOL)는 BUY 신호를 **한 번도** 안 냄(HOLD/SELL만) ② BUY 신호는 전부 급락 중인 소형·신규상장 잡코인(NEO/BIRB/SOPH/ZKC/BONK…)에서 발생 → "거래대금 상위" 원시 유니버스가 펌프-덤프 잡코인으로 채워져 **신호 발생 조건(급락 후 이격)과 급락 가드가 구조적으로 상쇄**. 이전 세션들이 손댄 스코어 모델(07-23 conf 재스케일)·게이트 티어링(07-21)과 **직교하는 유니버스 측 수정**.

- **처방 #1(진입 방향)+#2(유니버스 큐레이션)을 워치리스트 앞단 큐레이션으로 통합 구현.** 진입 게이트(사후 차단)가 아니라 유니버스 자체를 걸러 상쇄 구조를 해소.
- **신규 순수 게이트** [`WatchlistQualityGate`](../core-engine/src/main/java/com/cryptoautotrader/core/selector/WatchlistQualityGate.java): 4기준 AND — ① 유동성(24h 거래대금 ≥ 하한) ② 변동성 상한(ATR% ≤ 상한) ③ 상승추세(종가 > EMA200, `Ema200RegimeGate` 재사용) ④ 비급락(`BlackSwanGuard.check` 미발동). BlackSwanGuard/Ema200RegimeGate와 동일한 순수 정적 클래스, 단위 테스트 7건.
- **워치리스트 통합** [`WatchlistFilterService`](../web-api/src/main/java/com/cryptoautotrader/api/service/WatchlistFilterService.java): 기존 파이프라인(거래대금상위→스프레드→ATR하한) 뒤에 품질 게이트 추가. 캔들 1회 조회(EMA200용 210개)로 ATR·EMA200·급락 공용. 기존 시그니처는 `QualityCriteria.disabled()`로 위임(하위호환).
- **설정화** [`risk_config`](../web-api/src/main/resources/db/migration/V57__add_watchlist_quality_to_risk_config.sql) V57: `scan_min_trade_value_krw`(기본 50억)·`scan_max_atr_pct`(기본 4.0)·`scan_require_uptrend`(기본 true)·`scan_exclude_crashing`(기본 true). NULL이면 `DynamicTradingService` 코드 기본값. 재빌드 없이 SQL/API로 조정 가능(V56 패턴).
- **효과 예측**: 유니버스가 상승추세·정상변동성 대형/중형주로 좁혀져 dip 전략은 "상승추세 눌림목", 모멘텀 전략은 트렌드 코인을 잡음 → 급락 가드와의 상쇄 급감 → 진입 발생. 유니버스가 비면 기존 "워치리스트 empty → 틱 스킵"이 처리.
- **테스트**: `WatchlistQualityGateTest` 7건 + `:web-api:test` 전체 통과, `:core-engine`·`:web-api` 컴파일 통과 ✅.
- **[x] 커밋·배포 완료** — 커밋 `6200773`. 운영 DB Flyway **V57 적용 완료(2026-07-24 04:28 UTC)**, `scan_*` 4컬럼 생성 확인. web-api 새 바이너리로 재기동됨(Docker 멀티스테이지 bootJar). `risk_config.scan_*`는 전부 NULL → **코드 기본값 사용**(유동성 50억·ATR상한 4.0·상승추세 ON·급락제외 ON).
- **[x] 큐레이션 동작 확인(초기)** — 배포 직후 세션 38 워치리스트가 재계산되며 ETH/SOL/NEAR → `KRW-KAITO` 1개로 축소(상승추세 필터로 하락 코인 배제). 게이트가 유니버스를 실제로 좁히는 것 확인. (나머지 세션은 1시간 갱신 주기라 순차 반영)
- **[x] 주말 운영 → 월요일(2026-07-27) 오전 체크 완료** — 위 07-27 섹션 참조. 실체결 0건, 워치리스트는 대형/중형 상승추세주로 정상 축소(과도한 empty 아님), 병목이 유니버스→신호생성으로 이동. **완화 다이얼(scan_require_uptrend=false 등)은 역효과로 판단해 미적용** — 다음 순서는 07-23 재스케일 배포(신호 측).
- **[ ] 배포 버전 병행 검증** — 07-23 conf 재스케일·07-21 게이트 티어링이 이번 재배포에 함께 포함됐는지 운영 로그 게이트 사유 wording(entryGate 3단계 vs 구 check 2단계)으로 확인.

---

## 🆕 2026-07-23 VOLUME_DELTA·BOLLINGER confidence 재스케일 (동적 세션 무거래 근본원인 수정)

> 운영 DB 재분석(07-23): 동적 세션 7개가 여전히 실체결 0건. 퍼널 정량화 결과 **HOLD의 79.6%가 "점수 미달 buy/sell=0"** — 게이트가 아니라 **스코어가 임계(0.20)를 못 넘는 것**이 병목. 서브전략 신호율 집계로 원인 특정: ① 가중 0.5 앵커 **MACD 신호율 0.6%**(크로스 순간만 투표하는 이벤트형 → 연속형 스코어 모델과 불일치), ② **VOLUME_DELTA(conf 15.2)·BOLLINGER(conf 14.5)** 의 strength 정규화가 도달 불가능한 극값(비율 1.0 / %B −0.8) 기준이라 발화해도 스코어 기여 0.05~0.08의 "무의미 표". MEANREV_BB는 BOLLINGER+RSI 동시 발화해도 0.08+0.09=0.17 < 0.20이라 수학적으로 진입 불가였음.

- **이번 범위 = ②만 수정 (가장 레버리지 크고 되돌리기 쉬운 것). ①(MACD 로직 변경)은 휩쏘 위험 커 보류.**
- **VOLUME_DELTA** [`VolumeDeltaStrategy`](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/volumedelta/VolumeDeltaStrategy.java): strength를 도달 가능한 포화점 `strengthSaturationRatio`(기본 **0.40**) 기준으로 재정규화. `(ratio−threshold)/(saturation−threshold)×100`. 포화점≤임계 시 구 동작(1.0 기준)으로 안전 폴백. 비율 0.25 → conf 5.5 → **50**으로 상승.
- **BOLLINGER** [`BollingerStrategy`](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/bollinger/BollingerStrategy.java): `strengthSaturationDepth`(기본 **0.35**) 도입. `(buyThreshold−%B)/depth×100`. 하단 밴드 터치(%B≈0)가 conf 20 → **약 57**로 상승. SELL 대칭.
- 두 파라미터는 [`VolumeDeltaConfig`](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/volumedelta/VolumeDeltaConfig.java)·[`BollingerConfig`](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/bollinger/BollingerConfig.java)에 노출(params override 가능).
- **효과 예측**: MEANREV_BB의 BOLLINGER+RSI 합의가 0.20 돌파(진입 가능화), 추세추종 프리셋의 VD 확인표도 유효화. **scan_weak_threshold(0.20)는 그대로 — 재스케일만으로 2전략 합의가 임계를 넘어 임계 완화 불필요.**
- **테스트**: `VolumeDeltaStrategyTest`·`BollingerStrategyTest`에 포화점 단조성 락 테스트 각 1건 추가. `:strategy-lib:test`+`:core-engine:test` 통과, `:web-api` 컴파일 통과 ✅.
- **[x] 커밋·배포 완료 (07-24 재빌드에 함께 반영, 07-27 확인)** — ~~미커밋/미배포~~ 표기는 07-23 당시 메모였고 갱신 누락. 실제로는 재스케일이 커밋 `af49342`에 들어가 **배포 커밋 `6200773`(V57, 07-24 배포)에 포함**(`strengthSaturationRatio=0.40`·`strengthSaturationDepth=0.35`). **운영 런타임 확인**: 주말 로그에 재스케일 강도 실측 — `BOLLINGER:SELL(100)`·`VWAP:SELL(57)`·`VOLUME_DELTA` 최대 100(구 로직이면 동일 입력에서 도달 불가). → 배포 갭 없음.
- **[관찰 07-27] 진입 미발생은 배포 문제 아님 — 레짐 이슈** — 재스케일은 발화 시 confidence를 키울 뿐 발화 여부는 불변. 주말 대형주 장세에서 서브전략 강신호가 **전부 SELL**(`buy=0.00`), 형성된 sell 0.74도 SCANNING(보유없음) + TRANSITIONAL 감쇠(0.5×)로 0.37. BUY 셋업 자체가 없어 진입 0. **시장 전환(상승 레짐) 대기 국면.**
- **[ ] 잔여 근본원인 ①(MACD 앵커 0.6%)** — 미해결. 추세 지속 투표 모드 or 내부 adxThreshold 25→20은 별도 작업(휩쏘 검증 필요).

---

## 🆕 2026-07-21 EMA200 게이트: 하드 차단 → 사이즈 감액 진입 (동적 세션, "너무 보수적이지 않은 거래")

> 운영 DB 재분석(07-21): 동적 세션 7개 12일간 실체결 0건 — BUY 신호는 나오나 EMA200 게이트(마진 3%)가 전량 하드 차단. 07-20 결정("게이트 완화 대신 직교 전략 추가")과 달리, 사용자가 명시적으로 "덜 보수적인 거래 + 소액 데이터 확보"를 요청. **전량 제거가 아니라 리스크를 사이즈로 통제하는 감액 진입**으로 절충 — 트레이더 원칙 "나쁜 레짐에선 끊지 말고 줄여라".

- **게이트 3단계화**: [`Ema200RegimeGate.buySizeMultiplier`](../core-engine/src/main/java/com/cryptoautotrader/core/selector/Ema200RegimeGate.java)(신규) — 기존 마진 하나(`scan_ema200_buy_margin_pct`)로 밴드 파생. 종가 > EMA200×(1-margin%) → **1.0(정상)**, EMA200×(1-margin%)~(1-2·margin%) → **0.5(감액)**, 그 아래 딥 하락 → **0.0(차단, 나이프 캐칭 방어)**. 기존 `allowsBuy`(하드 차단) 대비 **단조 완화** — margin~2·margin 구간만 차단→감액으로 바뀜.
- **적용 범위 = 동적 스캐닝만**: [`DynamicTradingService`](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java) 게이트→`BuyCandidate`→`executeBuy` 경로에 `sizeMultiplier` 전달, `investAmount = availableKrw × investRatio × multiplier`. **라이브([`LiveTradingService`](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java):918)·백테스트([`BacktestEngine`](../core-engine/src/main/java/com/cryptoautotrader/core/backtest/BacktestEngine.java):227)는 기존 `allowsBuy` 유지 — 실돈 블래스트 반경 최소화.** MEANREV_BB는 여전히 게이트 면제(변화 없음).
- **최소주문 보정**: 자본 1만원·investRatio 0.8 세션은 감액(0.5) 시 4,000원 < 업비트 최소주문 5,000원이라 "가용 KRW 부족"으로 헛차단됨. 정상 사이즈가 최소주문을 넘는 한 감액분을 **5,000원으로 올려** 진입을 살림.
- **테스트**: `Ema200RegimeGateTest`(사이즈 티어 4건: 정상/감액/딥차단/캔들부족 추가) + `DynamicScanSelectionTest`(레코드 시그니처 갱신). `:core-engine:test`+`:web-api:test` 컴파일·통과 ✅. **코드는 미커밋/미배포.**
- **[ ] 배포 필요** — 미배포 상태에선 구 하드 차단 로직이 계속 작동. 배포 후에야 감액 진입 발생.
- **[ ] 선택적 설정 다이얼**: 더 공격적으로 원하면 `risk_config.scan_ema200_buy_margin_pct`(현재 NULL=기본 3.0)를 4.0~5.0으로 상향 → 감액 밴드 확대. `PUT /api/v1/trading/risk/config` 또는 SQL로 재배포 없이 조정, 되돌리기도 1줄.
- **[ ] 2~4주 후 재평가** — 감액 진입분의 4h/24h 사후수익률로 EV 확인. 감액 진입이 순손실이면 밴드 축소 또는 회수. (07-20 note의 "차단이 사후평가상 옳았다"와 상충 가능 — 실측으로 판정.)
- **[ ] 미해결(이번 범위 밖, 별도 판단 필요)**: ① 세션 33(COMPOSITE_PULLBACK_MTF)이 보유 포지션 없이 SELL 543건 남발(전량 정상 차단이나 로직 스멜) ② 라이브 세션 188·190(KRW-XRP)이 13일 신호 0건 — 좀비 세션 정리 검토.

---

## 🆕 2026-07-20 신호품질 분석 후속 — 야간/TRANSITIONAL 감쇠 + 차단사유 그룹핑 버그 수정

> 신호품질 페이지(30일·전 세션) 직접 DB 분석 결과 3건 반영. **표본의 88%(9,070/10,295)가 동적 세션의 "SCANNING — 청산 대상 아님" SELL 로깅 노이즈**라 완전한 실전 인과 검증은 아직 아님 — 그래서 하드 차단이 아닌 EMA필터와 동일한 점수 감쇠 방식으로 반영(사용자 확인 후 결정).

- **차단사유 그룹핑 버그 수정** — [`BlockedReasonNormalizer`](../web-api/src/main/java/com/cryptoautotrader/api/report/BlockedReasonNormalizer.java)(신규) + [`LogController.buildBlockedVsExecuted`](../web-api/src/main/java/com/cryptoautotrader/api/controller/LogController.java). `BLACK_SWAN_GUARD 발동 — 1시간 내 급락 -6.80%(현재 6.72)`처럼 급락률·현재가가 본문에 그대로 박힌 사유는 콜론이 없어 기존 `split(":")[0]` 그룹핑이 전혀 안 먹혀, 화면의 "차단 사유별" 표가 거의 전부 1건짜리 행으로 수십 줄 늘어졌던 원인. 괄호 제거 + %/배 수치 제거로 정규화.
- **야간(KST 20~23시) + TRANSITIONAL 레짐 신호 감쇠** — [`SignalQualityDampenGate`](../core-engine/src/main/java/com/cryptoautotrader/core/selector/SignalQualityDampenGate.java)(신규) + [`CompositeStrategy.evaluate`](../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeStrategy.java) 통합.
  - 근거(30일): KST 20~23시 4h 승률 31~38%·평균 -0.25~-0.59% (06~09시는 승률 56~62%·평균 +0.36~+0.58%). TRANSITIONAL 레짐 24h 승률 17.4%·평균 -1.21% (TREND +1.05%·RANGE +0.05% 대비 최악).
  - 기존 EMA 방향 필터(`emaFilterDampenFactor`)와 동일한 패턴 — buy/sellScore를 threshold 비교 **전에** 비례 감쇠(기본 야간 0.6배·TRANSITIONAL 0.5배)시켜 강신호는 통과 여지를 남김. `nightDampenFactor`/`transitionalDampenFactor` params로 override 가능(1.0=무감쇠). 모든 `CompositeStrategy` 기반 프리셋(동적·라이브 공용)에 자동 적용 — 별도 옵트인 불필요.
  - TRANSITIONAL 감지는 `RangeRegimeGate`와 동일하게 `MarketRegimeDetector.detectRaw`(stateless) 재사용, 캔들 50개 미만이면 스킵(무감쇠).
- **테스트**: `SignalQualityDampenGateTest`(6, 시간 경계·레짐 분기) + `BlockedReasonNormalizerTest`(6, 그룹핑 키 정규화) + `CompositeStrategyTest`(야간 감쇠 통합 3건 추가). `:core-engine:test`+`:web-api:test` 전체 통과 ✅. **코드는 미커밋/미배포.**
- [ ] 배포 후 2~4주 뒤 신호품질 재분석 — 동적 세션 SCANNING 노이즈를 제외한 표본으로 야간/TRANSITIONAL 패턴이 재현되는지, 감쇠 계수(0.6/0.5)가 적정한지 재검증.
- [ ] 필요 시 계수를 risk_config로 이관해 재배포 없이 튜닝 가능하게 (현재는 코드 상수 + params override만 지원).

---

## 🆕 2026-07-20 COMPOSITE_MEANREV_BB 평균회귀 프리셋 추가 (하락·횡보장 표본 확보용)

> 같은 날 운영 DB 분석(아래 섹션)의 후속 조치: 동적 세션 6개가 전부 추세추종 계열이라 하락·횡보장에서 동시 침묵(11일 매수 0건). 게이트 완화 대신(사후평가상 차단이 옳았음) **직교(평균회귀) 전략 1종 추가**로 구성 빈틈을 메운다.

- **프리셋**: [`CompositePresetRegistrar`](../web-api/src/main/java/com/cryptoautotrader/api/config/CompositePresetRegistrar.java) — `COMPOSITE_MEANREV_BB` = BOLLINGER(0.55) + RSI(0.30) + VWAP(0.15), stateless.
  - BOLLINGER: %B 하단 이탈 매수 (자체 ADX '상한' 필터 + Squeeze HOLD 내장), RSI: 과매도+피봇 다이버전스, VWAP: 할인 매수.
  - **VWAP 가중 0.15는 의도된 설계** — 단독 만점(100)으로 weak 임계(0.19~0.20) 미달, BOLLINGER/RSI와 합의해야 진입 (추세추종 프리셋의 "VWAP(100) 단독 BUY 0.21~0.30" 남발 패턴 차단).
  - EMA 방향 필터 OFF(역추세 매수가 전제) / Composite ADX '하한' 필터 OFF(BOLLINGER 상한 필터와 정반대).
- **게이트 계약**: [`Ema200RegimeGate`](../core-engine/src/main/java/com/cryptoautotrader/core/selector/Ema200RegimeGate.java)에 `isExempt()` + `EXEMPT_STRATEGIES={COMPOSITE_MEANREV_BB}` 신설 — EMA200 아래 과매도 진입이 전제인 평균회귀와 게이트가 논리 상충. 동적([`DynamicTradingService`](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java))·라이브([`LiveTradingService`](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java)) 양쪽 호출부에 면제 반영. RangeRegimeGate는 비차단(횡보장이 주 무대). **BLACK_SWAN·BTC_MARKET_GUARD·손실쿨다운·SL/TP는 그대로 적용** — 나이프 캐칭 방어 유지.
- **전략 목록 API 노출 수정**: [`StrategyController`](../web-api/src/main/java/com/cryptoautotrader/api/controller/StrategyController.java)의 `isStrategyImplemented` 하드코딩 목록에 없는 전략은 `SKELETON` 상태 → 프론트 동적 세션 콤보박스(`AVAILABLE && isActive` 필터)에서 안 보임. 4개 스위치(`isStrategyImplemented`/`isCompositeStrategy`/`getDescription`/`getRecommendedCoins`)에 MEANREV 추가. **신규 프리셋 추가 시 이 4곳 누락 주의** — 통합 테스트로 회귀 방지 추가.
- **테스트**: Ema200RegimeGateTest(면제 계약) + CompositeStrategyTest(가중 불변식 2건: VWAP 단독 미달 / BOLLINGER+RSI 합의 진입) + CompositeMeanRevPresetTest(등록·게이트·스모크 3건) + StrategyControllerIntegrationTest(AVAILABLE 노출 회귀 1건). `:strategy-lib:test`+`:core-engine:test`+`:web-api:test` 전체 통과 ✅. **코드는 미커밋/미배포.**
- [x] 배포 후 동적 세션에 COMPOSITE_MEANREV_BB 1개 생성(M15, 10,000원)해 추세추종 6개와 병행 관찰 — **07-20 09:49 KST 세션 38 가동 확인.** 첫 스캔부터 서브전략 3종 개별 투표 정상(BOLLINGER:BUY(8)/SELL(33) 등, 점수 미달 HOLD). 워치리스트 4코인(XRP/ETH/SOL/NEO)만 통과 — 시장 필터 결과로 정상이나 표본 적으면 min_atr_pct 완화 검토.
- [ ] 2주 후 신호품질(was_executed·4h/24h 사후수익률)로 평균회귀 게이트 면제가 옳았는지 재평가 — 나이프 캐칭 손실 패턴 보이면 EMA200 면제 회수 검토.

## 🆕 2026-07-20 운영 DB 멀티코인(동적) 로그 분석 — 신호 생성 회복, 실행은 게이트가 전량 차단(정당)

> 동적 세션 6개(32~37, M15) 07-09부터 RUNNING, **11일째 매수 체결 0건**, 자본 6×10,000원 그대로. 전 세션 SCANNING, 워치리스트 갱신 정상(30분 주기).

- **BUY 신호 생성은 07-15 완화 + 07-16 ①② 수정 이후 뚜렷이 회복**: 07-15 1건 → 16 4 → 17 7 → 18 12 → **19 30건**. 07-09~14는 6일 연속 0건이었음.
- **그러나 54건 전량 진입 게이트 차단 → 실행 0건**: BLACK_SWAN_GUARD 39건 + EMA200 레짐 필터 15건. (RANGE/BTC급락/쿨다운/KRW부족 차단은 0건.)
- **사후 수익률 기준 차단은 정당 (24h 방어 판정)**: 블랙스완 차단분 avg 4h **-3.96% / 24h -6.79%**, EMA200 차단분 4h +1.00% / **24h -4.22%**. 하락장에서 게이트가 자본 보호 — 완화 롤백/게이트 완화 불필요, 시장 전환 대기 유지.
- 신호 구조는 여전히 얕음: BUY 대부분 VWAP(100) 단독 0.30(EMA 감쇠 시 0.21), MACD/GRID 무투표. 07-19 30건 중 20건이 세션 34(ICHIMOKU)의 KRW-BIRB 반복(EMA200 차단 반복).
- HOLD 주 사유 변함없이 "점수 미달 buy=0.00 sell=0.00" — 3일간 HOLD 1.4만+건.
- ⚠️ **risk_config.scan_weak_threshold 현재 NULL** (updated_at 07-16 08:33 KST) — 07-16 스탑갭 0.19가 남아있지 않고 코드 기본 0.20 폴백 중. ①② 수정(`>=` 비교)은 커밋됨 — **운영 배포 여부 확인 필요** (BUY 급증 추세로 보아 반영됐을 가능성 높으나 서버에서 미확인).
- 참고: 고정코인 LIVE 세션은 정상 거래 중 (drift log: 192 BTC / 193 ADA, 슬리피지 -0.03~+0.42%).
- [ ] 서버에서 07-16 코드(①② 수정) 배포 여부 확인 — 미배포면 재빌드·재시작.
- [ ] 시장 전환(상승 추세 복귀) 후 게이트 차단률·진입 재개 여부 재점검.

## 🆕 2026-07-16 24h 운영 로그 분석 — 07-15 배포분 검증 완료

> V55/V56 마이그레이션 07-15 13:54 KST 적용 확인. 실전 4개(188/190/192/193)·동적 6개(32~37) 세션 가동 중.

- **실전 24h 거래: 익절 2건, 합계 +257.5원. 손실 0건.** 현재 오픈 포지션 없음.
  - 192(BTC): 07-14 21:25 진입 93,259,000 → 07-15 21:25 신호 청산(H4 SELL 0.30, ATR_BREAKOUT), **+165.0원 (+2.05%)**
  - 193(ADA): 07-14 21:35 진입 238 → 07-16 05:45 신호 청산(H4 SELL 0.40, ATR_BREAKOUT), **+92.5원 (+1.2%)**
- **07-15 배포분 검증 결과 (전 항목 정상)**:
  - ✅ **차단 BUY 저장 방식 전환** — VANA BUY(score 0.283)가 HOLD 덮어쓰기 없이 BUY+was_executed=f+blockedReason으로 저장, signal_price 포함. **4h 사후수익률 -1.73% 자동 평가됨 → 첫 사례부터 게이트 방어 성공 판정.**
  - ✅ **SCANNING SELL 사유 명시** — 24h 184건 전부 "SCANNING — 보유 포지션 없음(청산 대상 아님)" + signal_price 저장 (145건 4h 평가 완료). 통계 오염 종료.
  - ✅ **서킷 브레이커/손실 쿨다운(V55)** — 스키마 적용 확인. 발동 0건 (동적 손실 자체가 0건이라 정상 대기).
  - ✅ **V56 설정화** — risk_config 4컬럼 생성, 전부 NULL → 코드 기본값(0.20/0.40/0.70/3.0%) 폴백 작동.
  - ⚠️ **PAPER signal_price 저장** — 검증 불가: PAPER 세션이 07-01 이후 미가동. 다음 페이퍼 세션 가동 시 확인.
  - ✅ **DOGE EMA200 면제 제거** — 배포 후 DOGE 로그 0건(워치리스트 자체에 미포함), 부작용 없음.
- **BLACK_SWAN_GUARD 오탐 수정(07-08 배포) 잔여 관찰 종료** — 07-08 이후 발동 전건이 실제 급락(-5%~-17%) 동반. 거래량 버스트형은 07-14 VANA "16.9배 + 하락 -2.63%" AND 조건으로만 발동. **SL 강화 텔레그램 알림 0건**(보유 중 발동 사례 없음). 오탐 수정 확정.
- **동적 세션 진입 여전히 0건 (완화 후 만 1일)** — 24h 스캔 32코인/5,705로그 중 BUY 후보 1건(VANA)뿐, 그마저 블랙스완 차단(사후 -1.73%로 정당). HOLD 5,429건의 주 사유는 "점수 미달 buy=0.00"(서브전략 무투표) — 하락~전환장에서 신호 자체가 없는 상태. 완화 롤백 판단은 시장 전환 후로 유보.
- 🔴 **동적 매수 0건 정밀 퍼널 분석 (07-16) — 완화가 안 먹히는 구체 원인 2건 확정**:
  - 가동 전체(07-09~) 41,172건 평가 → buy점수>0: 4,421건 → **BUY 신호: 단 1건** → 실행 0건.
  - 완화 이후 임계(0.20) 도달 13건의 사망 경로: **① 경계값 버그 8건** — [`CompositeStrategy.finalSignal`](../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeStrategy.java)이 `buyScore > weakThreshold` **strict 비교**라 MACD 단독 BUY(100)의 정확히 0.20이 전부 "점수 미달" 처리 (ETH/HBAR/B3 반복). **② 상충 신호 규칙의 완화 역효과 4건** — weak 0.25→0.20 인하로 감쇠된 SELL 0.21이 상충 판정 범위에 들어와 **buy=0.50 STRONG_BUY 2건(B3/VIRTUAL)을 HOLD로 사살** (완화 전 기준이면 sell 0.21<0.25로 상충 아님 → STRONG_BUY 통과였음). ③ 나머지 1건(VANA)만 BUY → 블랙스완 차단(사후 -1.73%, 정당).
  - 구조 관찰: 세션 33(PULLBACK_MTF)은 buy점수>0이 **0건**(하락장에서 완전 침묵), 37(ICHIMOKU_V2)은 348건 있으나 max 0.17로 구조적 임계 미달. 6전략 전부 추세추종 계열이라 하락장 관망 자체는 정상 — 문제는 위 ①②로 "정상적으로 나온 후보"까지 죽는 것.
  - [x] **①② 수정 완료 (07-16, 상세: [`CHANGELOG.md`](CHANGELOG.md) 2026-07-16)** — `CompositeStrategy.finalSignal` 임계 비교 `>`→`>=` + 상충 판정에 강도 등급 비교 추가(한쪽만 strong이면 그쪽 우세). 테스트 2건 신규, `:core-engine:test`+`:web-api:test` 전체 통과. **코드는 미커밋/미배포.** 운영엔 스탑갭으로 `scan_weak_threshold=0.19` SQL 적용됨(07-16 08:27 KST, 무재시작 즉시 효력).
  - [x] ~~배포 후 `scan_weak_threshold` NULL 원복~~ — **완료 (07-16 08:34 KST)**: 배포 재기동 확인(08:30~08:34 DYNAMIC 전 코인 일괄 재평가 버스트 = 캐시 리셋) 후 NULL 원복, SELECT로 확인. 이제 `>=` 코드 + 기본값 0.20이 유효.
  - [ ] 관찰: ①② 효과로 일 ~10건 후보가 게이트(블랙스완/EMA200/쿨다운)에 도달할 전망 — 진입 발생 여부와 품질(사후수익률) 확인.
  - [ ] (c) 하락/횡보장용 평균회귀 전략 편입은 별도 검토 (33/37은 이 장세에서 구조적 침묵).
- ⚠️ **신규 관찰: "SL 미점검 3분 초과" 경고 증가 추세** — 07-13 4건 → 07-15 8건 → 07-16 오전에만 17건 (192/193, 특히 07-16 02:17~05:29 ADA 보유 중 연속 발생). WS 티커 수신 불안정 의심. 포지션 청산엔 지장 없었으나 SL 실시간 감시 공백이므로 WS 재연결 로직/헬스체크 점검 필요.

---

## 🆕 2026-07-15 운영 DB 분석 — 동적 세션 6일째 매수 0건 근본 원인 확정 + 실전 현황

> 동적 세션 32~37(M15, 07-09 10:06 시작) **6일간 매수 0건** (DYNAMIC 주문/포지션 0, BUY 신호가 실행 경로 도달 0회).
> 07-09 완화(weak 0.25/strong 0.40 + EMA200 마진 1%)는 **배포·작동 확인됨** (SELL score 0.25~0.29 로그 존재 = 완화 임계 적용 증거).
> 완화에도 매수가 0인 이유 = **동일 방향 추세필터 4겹이 하락장(BTC 96.4M→93.2M)에서 전부 닫힘**:

- **정량 분해 (총 평가 35,456건, 6세션 × ~10코인 × 15분)**:
  - 서브전략이 BUY를 내도 CompositeStrategy **내부 EMA20<EMA50 필터가 BUY 점수 0화: 5,218건** (VWAP:BUY(100)→0.30→0.00 패턴 다수).
  - **ADX<25 횡보 차단: 1,976건** (24.0~24.9 근소 미달 다수).
  - 단독 SUPERTREND:BUY(50) → buyScore 0.15 < 0.25: **1,415건** — MACD(가중 0.4 앵커)가 전체 평가 중 BUY 147건(0.4%)만 투표해 복합점수가 구조적으로 임계 미달.
  - 필터를 뚫고 BUY로 확정된 ~323건은 **EMA200 게이트 283건(마진 1%에도) + BLACK_SWAN 40건이 전량 차단**. 매일 23~71건씩 꾸준히 차단됨.
- **부수 발견**: SCANNING 중 SELL 신호 3,391건이 was_executed=f·blocked_reason=NULL로 저장(포지션 없어 무의미) → 신호품질 통계 오염. 세션 33(PULLBACK_MTF)은 로그의 65%가 SELL(3,257 vs HOLD 1,749).
- **실전 라이브(188~193, 07-08 시작) 대조**: 거래한 세션은 대부분 손실 — 189(DOGE) 5전 5패 **-591원, 07-14 비상정지** (DOGE만 EMA200 게이트 면제라 하락장에 유일하게 계속 진입한 것이 직접 원인), 191(ETH) -208원 07-10 비상정지(기록됨), 193(ADA) -407원 + ADA 재보유 중(평가손), 192(BTC) +51원 + BTC 보유 중(평가익 ~+240). 188/190(XRP)은 7일간 거래 0 (동적과 동일 사유). **→ 동적 세션의 관망은 이번 주 하락장에선 결과적으로 손실 회피였음.**
- [x] **필터 스택 구조 결정 — 거래 빈도 확보로 2차 완화 (2026-07-15, 사용자 결정: 관망 대신 빈도↑)** — SCANNING 진입 경로 한정, 청산 경로 불변. **07-15 배포 완료.**
  - `DynamicTradingService`: `SCAN_EMA_DAMPEN_FACTOR=0.7` 신설 → SCANNING evaluate params에 `emaFilterDampenFactor` 주입 (역추세 BUY 점수 완전 소멸 → 30% 감쇠. VWAP:BUY(100) 0.30→0.21 통과 가능, 약신호 0.15→0.105는 여전히 차단).
  - `SCAN_WEAK_THRESHOLD` 0.25→0.20 (strong 0.40 유지). `EMA200_BUY_MARGIN_PCT` 1.0→3.0.
  - **ADX 필터는 의도적으로 유지** — adxThreshold 완화(15.0)가 횡보장 손실 확대로 2026-06-30 제거된 전력 존중.
  - `:web-api:compileJava` ✅, selector 테스트 + `DynamicScanSelectionTest`/`SessionKindIsolationTest` ✅.
  - [x] **07-15 배포 완료** — 재기동 후 운영 DB 로그로 적용 확인 (감쇠 로그 "BUY 0.30→0.21", score 0.21~0.50 BUY 후보 생성 시작). 새 병목: 전략 내부 Ichimoku TK/Chikou 확인 게이트·RSIVeto가 후보 차단 중(STRONG_BUY 0.50도 차단) — 전략 정체성이라 미완화, 관찰.
  - [ ] 배포 후 관찰(진행 중): 진입 발생 여부 + 진입 품질. 완화로 189/191/193 손실 패턴(하락장 역추세 진입 반복 손절) 재현 시 감쇠 0.7→0.5 또는 EMA200 마진 3%→2% 롤백 검토. **07-16 1일차: 진입 0건 — BUY 후보 1건(VANA, 블랙스완 차단·사후 -1.73% 방어 성공). 하락장에서 서브전략 무투표(buy=0.00)가 지배적, 롤백 사유 미발생.**
- [x] **동적 세션 서킷 브레이커 + 손실 쿨다운 (2026-07-15)** — 진입 완화의 안전장치 세트. **07-15 13:54 배포 완료.**
  - **공백 발견**: 연속 손실 서킷 브레이커(`RiskManagementService.checkCircuitBreaker`)가 라이브 전용이었음 — 동적 세션은 완화 후 무제한 반복 손실 가능했다. 손실 후 재진입 쿨다운도 없었음 (risk_config.cooldown_minutes는 존재하나 어디서도 미사용).
  - `RiskManagementService.checkCircuitBreaker(DynamicSessionEntity)` 오버로드 — MDD·연속손실 공통 로직 추출. 동적은 **이번 가동(startedAt) 이후 청산분만** 연속 손실 집계(재시작 시 과거 이력으로 즉시 재발동 방지).
  - **LIVE 잠복 버그 수정**: 기존 연속 손실 집계가 sessionId만으로 조회해 LIVE/DYNAMIC id 충돌 시 교차 오염 가능 → kind-aware 쿼리(`findBySessionKindAndSessionIdAndStatusOrderByClosedAtDesc`)로 교체.
  - `DynamicTradingService.processTick`: CB 발동 시 `circuit_breaker_triggered_at/reason` 기록(V55 + entity) + `emergencyStop` + 텔레그램 🚨 알림.
  - `processScanningTick`: **손실 청산 쿨다운** — 직전 청산이 손실이면 `cooldown_minutes`(기본 60분) 동안 동일 코인 재진입 차단 (191 반복 손절 패턴 방지). 진단 카운터 `손실쿨다운차단` 추가.
  - 테스트: `DynamicCircuitBreakerTest` 4건 신규 (5연속 손실 발동 / 재시작 리셋 / LIVE-DYNAMIC kind 격리 / MDD 발동). `:web-api:test` 전체 통과.
  - [x] ~~배포 필요~~ — **07-15 13:54 KST 배포 완료 (V55 적용 확인). 07-16 기준 CB/쿨다운 발동 0건 (동적 손실 0건이라 정상 대기).**
- [x] **신호품질 사후수익률 측정 공백 수리 + SCANNING SELL 로그 정리 (2026-07-15, P1-2/P1-3)** — **07-15 13:54 배포 완료.**
  - **진단 (운영 DB)**: 백필 스케줄러(`SignalQualityService`) 자체는 정상 가동 중(미평가 잔량 11건, 전부 최신). "거의 null"의 실체는 ① PAPER 경로가 signal_price를 아예 저장 안 함(24,820건 전량 NULL — 평가 쿼리에서 영구 제외), ② 4/10 이전 LIVE 10,597건 NULL(과거분), ③ **게이트 차단 BUY가 HOLD로 덮여 저장**되어 BUY/SELL만 평가하는 백필 대상에서 원천 제외 — 차단의 방어/기회비용을 측정할 수 없던 구조.
  - `PaperTradingService`: 전략 로그에 signalPrice(평가 캔들 종가) 저장 추가.
  - `DynamicTradingService` SCANNING: 게이트(EMA200/RANGE/블랙스완/BTC가드/손실쿨다운) 차단 시 signal을 HOLD로 덮지 않고 **BUY 그대로 저장 + wasExecuted=false + blockedReason=게이트 사유** — 이후 4h/24h 사후수익률이 자동 평가되어 "차단된 BUY가 실제로 올랐나(기회비용) 떨어졌나(방어)"를 신호품질 통계로 판정 가능. 실행 후보 선정 로직은 불변(차단 BUY는 후보 제외). ※ 전략로그 화면에서 차단 BUY가 이제 HOLD가 아닌 BUY(미실행+사유)로 보임 — 리포트/신호통계의 BUY 집계도 이에 맞게 증가.
  - SCANNING 중 SELL 신호(보유 없음, 3,391건 오염 원인)에 blockedReason="SCANNING — 보유 포지션 없음" 명시 (P1-3).
  - `SignalQualityService.evaluateLoop`: 실패 행(상장폐지 코인 등)이 정렬 헤드에 쌓이면 같은 배치를 무한 재조회하고 뒤의 정상 행이 영영 평가 안 되던 잠복 버그 → 실패 존재 시 페이지 전진으로 수정.
  - `:web-api:test` 전체 통과. 라이브(`LiveTradingService`) 경로의 게이트 차단 BUY도 같은 방식(HOLD 덮어쓰기)이나, 실돈 매수 실행 분기와 얽혀 있어 이번 범위에서 제외 — 후속 검토.
  - [ ] (선택) 과거 NULL signal_price 1회성 백필 — LIVE 4/10 이전 1.1만 건은 신호 시각 캔들 종가로 보정 가능하나 분석 가치 낮아 보류.
- [x] **P2 일괄 처리 (2026-07-15)** — **07-15 13:54 배포 완료.**
  - **DOGE EMA200 게이트 면제 제거** (`Ema200RegimeGate`) — 면제 근거("EMA200 아래 수익 패턴" PoC)가 실전 반증됨: 세션 189(DOGE)가 하락장에서 유일하게 면제 덕에 계속 진입, 5연속 손절 -591원(-5.9%) 후 07-14 비상 정지. 라이브·백테스트·동적 모든 경로에 적용(단일 진실 소스). `Ema200RegimeGateTest` 계약 반전.
  - **SCANNING 진입 완화 파라미터 설정화** (V56) — `risk_config`에 scan_weak_threshold / scan_strong_threshold / scan_ema_dampen_factor / scan_ema200_buy_margin_pct 추가. NULL이면 코드 기본값(0.20/0.40/0.70/3.0%) 폴백. `PUT /api/v1/trading/risk/config` 또는 SQL로 재빌드 없이 조정. 손실 쿨다운(cooldown_minutes)도 같은 조회로 일원화.
  - PROGRESS 잔무: 포지션 1232 SL 원복 항목 폐기(이미 CLOSED 확인).
  - [x] **라이브 188/190(XRP, 7일 거래 0) 처리 결정** — 사용자 결정(07-15): **유지(관망)**. 비용 0, 상승 전환 시 자연 진입 기대. 라이브 경로 완화는 미적용.
- [x] ~~DOGE EMA200 게이트 면제 재검토~~ — P2에서 면제 제거로 완료, 07-15 배포됨.
- [x] ~~SCANNING SELL 로그 정리~~ — P1-3에서 완료, 07-15 배포·작동 확인(07-16).

---

## 🆕 2026-07-10 운영 DB 로그 분석 — 세션 191 비상정지 (5연속 손절 서킷브레이커)

> 실전 세션 188~193(07-08 10:21 KST 시작, 각 10,000원) 중 **191(ETH, COMPOSITE_PULLBACK_MTF)이
> 07-10 08:16 KST EMERGENCY_STOPPED** — 5거래 전부 손절(-40.7/-49.7/-52.5/-31.1/-34.2원, 합 -208원, -2.08%),
> `consecutive_loss_limit=5` 서킷브레이커 정상 발동. 패턴: ETH 261만 원 부근 횡보(TRANSITIONAL)에서
> "눌림목 회복 BUY"(RSI 51~54, ADX 22 내외, H4:상승) 반복 진입 → ATR 기반 SL이 진입가 -0.4~-0.6%로 매우
> 타이트해 30분~2.5h 내 실시간 손절(WS) 반복. 쿨다운(60분)은 지켜졌으나 같은 가격대 재진입을 막지 못함.

- [ ] **세션 191 재기동 여부 결정** — 재기동 전 검토: ① 횡보(ADX<25)에서 눌림목 진입 차단 또는 ADX 임계 상향,
  ② SL 하한(예: 진입가 -1% 미만으로 조여지지 않게 클램프 — BLACK_SWAN_GUARD의 ATR 클램프와 동일 발상),
  ③ 동일 가격대(±0.5%) 재진입 차단. 동적 세션 33도 같은 전략이므로 결과 공유.
- 기타 세션 현황(07-10 09:15 KST 기준): 188(XRP)·190(XRP)·193(ADA) 거래 0건 대기 중,
  189(DOGE CMI_V2) -2.23%(1패 -150원 + DOGE 109원 재진입 보유 중), 192(BTC STRICT) +0.64%(BTC 보유 중).
  189는 MACD:BUY(100) 단독 + EMA필터의 SELL점수 0화로 score 0.50 진입 — 단일지표 진입 편향 재확인
  (CMI_V2→V1 교체 결정 항목과 연계).

---

## 🆕 2026-07-09 동적 멀티코인 진입 완화 (매수 0건 문제)

> **운영 DB 분석 결과**: 동적 세션(id 26~31, H1)은 가동 이후 **매수 0건** (position에 DYNAMIC 레코드 전무).
> 재기동(07-08 10:21 KST) 후 DYNAMIC 로그 783건 중 BUY 0 — HOLD 대부분이 buy=0.00~0.15 점수 미달,
> 드물게 나온 BUY(전 기간 ~21건)는 EMA200 게이트(0.2~0.7% 근소 차단)·BLACK_SWAN 거래량 오탐(수정 전)·
> RANGE 레짐이 전부 차단. 워치리스트도 목표 10개 대비 3~6개만 통과.

- [x] **코드 완화 (SCANNING 진입 경로 한정, 청산 경로는 기본값 유지)** — **미커밋 / 운영 미배포.**
  - `Ema200RegimeGate.allowsBuy(candles, coinPair, marginPct)` 오버로드 신설 — `DynamicTradingService`
    SCANNING에서 마진 1% 적용 (EMA200의 -1%까지 BUY 허용). 라이브·백테스트는 기존 시그니처(마진 0) 유지.
  - `DynamicTradingService` SCANNING evaluate params에 `weakThreshold=0.25`(기본 0.3),
    `strongThreshold=0.40`(기본 0.5) 주입.
  - `CompositeStrategy.finalSignal()` 버그 수정 — STRONG_SELL/BUY(weak)/SELL(weak) 분기가 params
    override 대신 상수를 참조해 `weakThreshold`/`strongThreshold` override가 절반만 적용되던 문제.
  - 테스트: `Ema200RegimeGateTest` 마진 2건 추가, `:core-engine` selector 테스트·`:web-api` Dynamic 테스트 통과.
- [x] **세션 설정 변경 (사용자가 UI에서 직접)** — 완료. 신규 동적 세션 32~37(M15, 6전략) 07-09 10:06 KST 시작.
- [ ] **배포 후 관찰** — 진입 발생 여부·진입 품질(완화로 인한 저품질 진입 손실) 1주 관찰.
  약세장에서 거래 빈도 증가는 손실 횟수 증가와 동행할 수 있음(실전매매 최근 48h 3전 3패 참고).
  - **2026-07-10 운영 DB 관찰 (가동 ~23h)**: 세션 32~37 전부 SCANNING 유지·**매수 0건**(DYNAMIC 주문/포지션 0).
    신호 로그 세션당 900~1,000건(10코인 워치리스트, 15분 주기 스캔 정상). BUY 신호 자체가 0 — 최근 4h 표본에서
    최고 buyScore 0.20(MACD 단독 BUY)으로 완화 임계값 0.25에도 미달. ATR_BREAKOUT/VOLUME_DELTA가 거의 전부
    HOLD(0). 세션 33(COMPOSITE_PULLBACK_MTF)은 SELL 562 vs HOLD 275로 SELL 신호 과다 성향(포지션 없어 전부 무시)
    — 라이브 191의 SELL 399건과 동일 패턴, 전략 자체의 SELL 편향.

---

## 🆕 2026-07-02 전략/실전매매/동적멀티코인 종합분석 — P0(N-1/N-2) + DM-1 + L-2 + S-1 + BLACK_SWAN_GUARD 완료

> 신규 발견 결함(N-1~N-3) 중 P0 2건 + DM-1 개선 + L-2 백테스트 게이트 추가 + S-1 검증 +
> BLACK_SWAN_GUARD 신규 구현 완료(상세: [`CHANGELOG.md`](CHANGELOG.md) 2026-07-02 항목 5건).
> `:web-api:test` 116건, `:strategy-lib:test`+`:core-engine:test` 231건 전체 통과. **미커밋 / 운영 미배포.**

- [ ] **DOGE 전략 교체 운영 결정** — CMI_V2 → CRR/V1 교체 여부 (90일 분석에서 V1이 전 레짐 압도 확인, 병행 비교 목적 종료).
- [ ] **DM-1 배포 후 관찰** — 워치리스트 전체 평가로 SCANNING 틱당 REST 호출이 늘어난다(기존엔 첫 BUY에서 조기 종료). DM-4(rate limit 여유) 항목과 연계 모니터링.
  - **2026-07-08 운영 DB 관찰**: 동적 세션 5개(id 26~30, 전부 H1·REAL, 07-06 23:57 UTC 시작) 약 24h 경과. 전 세션 SCANNING 유지·포지션/주문 0건·자본 10,000원 그대로. 시간당 5~7회 스캔·워치리스트 60분 갱신 정상(큰 공백 없음). BUY 신호 0건 — 최고 buyScore 0.30(세션29 JTO)·0.28(28 SOL)·0.18(26 NEAR)으로 전부 점수 미달, EMA 하락추세 필터·H4 Supertrend 하락이 진입을 막는 중(하락장 정상 방어로 판단). 참고: 27·30이 동일 전략(COMPOSITE_PULLBACK_MTF) 중복 — 워치리스트만 미세 차이(NEAR vs JTO), 신호 거의 동일하여 병행 실익 재검토 필요.
- [ ] **L-2 후속 — CB/CRR 외 다른 전략·기간에서도 게이트 무영향인지 추가 검증** — 아래 결과 참조. 100일 BTC/ETH/SOL/XRP에서 CB·CRR은 게이트 ON/OFF 완전 동일(= SL/TP가 모든 청산을 선점, 전략SELL 경로 자체가 발동 안 함). 다른 전략·기간·타임프레임에서도 동일한지는 미검증 — 배포 전 최소 1~2개 추가 조합 확인 권장.
- [ ] **S-1 후속 — 더 긴 기간·더 많은 코인으로 재검증 권장** — 아래 결과 참조. 100일 4코인 표본에서 WEAK 0.3/0.4가 완전 동일 결과를 냈으나, 거래수가 코인당 2~11건으로 극히 적어 경계값 통과 사례 자체가 없었을 가능성. 3년 전체기간 다코인으로 재실행하면 다른 결론이 나올 수 있음.
- [x] **BLACK_SWAN_GUARD 오탐 수정 배포 + 잔여 확인 — 완료 (07-08 배포, 07-16 관찰 종료: 발동 전건 실제 급락 동반, SL 강화 알림 0건)** — 07-08 오탐 2건(세션 186/187 조기 손절, 거래량 5배 단독 발동 → SL 0.3% 조임)의 수정 완료: 거래량 조건 AND화(-2% 하락 동반 필수) + SL 강화 폭 ATR[1.2%, 5%] 클램프 + 텔레그램 알림. **같은 배포에 DriftAlert 오탐 수정 포함** — SELL drift 기준가 버그(매수 평균단가 사용 → slippage가 포지션 손익률로 기록, 매시간 반복 알림) 수정 + V54(order.signal_price 신설, 잘못 측정된 SELL drift 레코드 삭제) + 알림 쿨다운 24h + BUY drift 기록 신설. 상세 근거·정량 측정은 [`CHANGELOG.md`](CHANGELOG.md) 2026-07-08 항목 2건.
  - [ ] 배포 후 `[BlackSwanGuard] SL 강화` 텔레그램 알림 빈도 관찰 (AND 조건으로 대폭 감소 예상 — 잦으면 -2% 임계 재조정).
  - [x] ~~포지션 1232(BTC, 세션 186) SL 원복 여부 결정~~ — **폐기 (2026-07-15)**: 운영 DB 확인 결과 1232는 07-08 세션 186 정지 시 이미 CLOSED(-22.6원). 원복 대상 없음.
- [ ] **BLACK_SWAN_GUARD 백테스트 미반영** — 이번 구현은 라이브/동적 세션에만 적용. `BacktestEngine`에는 넣지 않았다(L-2처럼 기존 전략 검증 수치 전체를 다시 흔들 수 있어 범위 확대를 보류). 필요 시 별도 A/B로 영향 측정 후 반영 검토.
- [ ] **BLACK_SWAN_GUARD 시스템 전체 차단 버전 (설계 보류)** — 현재는 코인별 게이트만 구현(사용자 확인). "임의의 한 코인 급락 시 전 세션 진입 차단" 버전은 기준자산 선정·세션 간 상태 공유 등 더 큰 설계가 필요해 이번 범위에서 제외.

### S-1 검증 결과 — WEAK_THRESHOLD 0.3 하향, 이 표본에서는 무영향

`CompositeStrategy`의 `WEAK_THRESHOLD`(0.4→0.3, 코드는 이미 0.3으로 반영돼 있었으나 검증 이력 없음)를
`weakThreshold`/`strongThreshold` params로 override 가능하게 만든 뒤(기존 `adxThreshold` override 패턴과
동일), `WeakThresholdAbBacktestRunner`로 CMI_V1(CRR 주력 delegate)·COMPOSITE_BREAKOUT을 BTC/ETH/SOL/XRP
100일 H1에서 A(0.3)/B(0.4) 비교. **8개 조합(2전략×4코인) 전부 수익률·거래수·MDD·Sharpe 완전 동일** — 이
표본에서는 하향이 매매빈도에 아무 영향을 주지 않았다. 거래수가 코인당 2~11건으로 매우 적어(레짐 필터·
ADX 필터·EMA 필터 등 상위 게이트가 대부분을 이미 걸러냄), buyScore/sellScore가 0.3~0.4 구간에 걸리는
경계 사례 자체가 이 표본에는 없었던 것으로 보인다. "매매빈도 부족을 해소하려는 조정"이라는 원래 의도를
이 표본은 뒷받침도 반박도 하지 못한다 — 결론을 내리려면 더 긴 기간·더 많은 코인의 표본이 필요하다.

### L-2 검증 결과 — BacktestEngine에 전략 SELL 게이트 부재 확인 및 수정

`ExitRuleConfig`(백테스트·실전 공통 리스크 설정 클래스, "실전매매 현행 설정 기준"이라 명시돼 있음)에
`minHoldMinutesForSignalExit`/`minPnlPctForSignalExit`/`lossEscapeThresholdPct` 필드가 **아예 없었고**,
`BacktestEngine`의 전략 SELL 처리(구 250번째 줄)는 SL/TP와 달리 신호가 나오면 즉시 체결했다. 반면
`LiveTradingService`/`DynamicTradingService`는 최소보유 180분 + 본전청산차단(-1.00%~+0.30%)으로 전략 SELL을
적극적으로 억제한다 — 즉 지금까지의 모든 백테스트 수치(BTC +106.71% 등)는 실전에 없는 조기 청산 타이밍으로
산출된 것이었다.

`ExitRuleConfig`/`ExitRuleChecker.allowsSignalExit()`/`BacktestEngine`에 게이트를 추가해 수정했다.
**실제 영향 측정** (`SignalExitGateAbBacktestRunner`, BTC/ETH/SOL/XRP 100일 H1, COMPOSITE_BREAKOUT +
COMPOSITE_REGIME_ROUTER): 게이트 ON/OFF 결과가 **전 코인·전 전략에서 완전히 동일**했다. 즉 이 두 전략은
SL/TP가 이미 모든 포지션을 청산해버려 전략 SELL 분기 자체에 도달하지 못하고 있었다 — 청산 경로 분포 계측
(S-2, 이미 배포됨)이 실전에서 예측했던 "SL/TP 지배" 가설을 백테스트 쪽에서도 뒷받침하는 결과. 좋은 소식은
이번 수정이 기존 Tier1 배포 권고 수치(CB/CRR 계열)를 무너뜨리지 않는다는 것이지만, 다른 전략(특히 SL/TP
폭이 넓거나 트레일링에 덜 의존하는 전략)에서는 영향이 다를 수 있다.

### 이번에 수정한 것 (N-1, N-2, DM-1)

1. **N-1 (🔴): live_trading_session ↔ dynamic_session 별도 BIGSERIAL sessionId 충돌 시 포지션 교차 오염** —
   `position.session_kind`(V51)가 있음에도 `DynamicTradingService`·`LiveTradingService`의 포지션 조회 12곳이
   전부 `sessionId`만으로 조회해, ID가 우연히 겹치면 동적 세션이 라이브 포지션을 매도하거나
   세션 정지 시 다른 종류 세션 포지션까지 청산될 수 있는 결함. `PositionRepository`에
   `findBySessionKindAndSessionId(AndStatus/AndCoinPairAndStatus)` 신규 추가, 두 서비스의 전체 조회 경로 교체.
2. **N-2 (🔴): 전략 거버넌스(`StrategyLiveStatusRegistry.isBlocked()`)가 실제로는 어디에도 강제되지 않던 결함** —
   `LiveTradingService`는 필드만 주입하고 미사용, `DynamicTradingService`는 필드조차 없었음(2026-06-01 감사
   §11 갭1이 "완화된 미검증 단독지표 허용"만 지적했지만, 실제로는 BLOCKED 전략조차 라이브·동적 양쪽 다
   실돈 세션 생성이 가능했던 더 심각한 상태). 양쪽 `createSession`에 `isBlocked()` 검사 추가.
3. **부수 발견**: H2 테스트 스키마(`schema-h2.sql`)에 `dynamic_session` 테이블 자체가 없어 동적 세션 관련
   통합 테스트가 원천적으로 불가능했음(§ 2026-07-02 동적 시스템 보완 항목의 "동적 세션 전용 테스트 부재"의
   근본 원인). V50 마이그레이션 기준으로 테이블 추가.
4. **DM-1 (🟡): 동적 세션 "첫 BUY 승자독식" 개선** — `DynamicTradingService.processScanningTick`이 워치리스트를
   거래대금 내림차순으로 순회하다 첫 BUY 신호에서 즉시 진입해, 실제로는 신호 품질이 아니라 "거래대금 순위"가
   진입 코인을 결정하고 있었다. 워치리스트 전체를 평가(게이트·로그 포함)한 뒤 BUY 후보 중 신호 강도
   (`StrategySignal.getStrength()`)가 가장 높은 코인 하나만 진입하도록 변경. 선택되지 않은 후보들은
   신호품질 로그에 "다른 코인 신호가 더 강함" 사유로 기록. 선택 로직은 `pickBestBuyCandidate()`로 분리해
   네트워크·전략 평가 없이 순수 단위 테스트 가능하게 함.
5. **신규 회귀 테스트**: `SessionKindIsolationTest`(4건) — 같은 sessionId를 가진 LIVE/DYNAMIC 포지션이
   kind-aware 조회로 격리됨을 확인 + BLOCKED 전략의 라이브/동적 세션 생성 거부 + ENABLED 전략 정상 생성.
   `DynamicScanSelectionTest`(3건) — 여러 BUY 후보 중 최고 강도 선택, 단일 후보, 동률 시 첫 후보 유지.

---

## 🔬 2026-07-02 전략/실전매매/손익대시보드 종합 감사 — 후속 과제

> D-1~D-5(부분체결 평균단가·session_kind·부분매도 취소·타임아웃 race·매수수수료), P-1(DELETED 세션 제외),
> P-3(closedSince 필터), S-2(청산 경로 분포 계측)는 수정 완료(상세: [`CHANGELOG.md`](CHANGELOG.md) 2026-07-02 종합감사 항목).
> `:web-api:test` 109건 전체 통과. **미커밋 / 운영 미배포 — V52 마이그레이션 포함, 배포 전 DB 반영 필요.**

- [ ] **S-1 CompositeStrategy WEAK_THRESHOLD 검증 이력 확인** — 코드가 `0.4`가 아닌 `0.3`으로 이미 낮춰져 있음. 사용자 확인상 매매빈도 부족으로 의도된 조정. 백테스트 검증(다코인 H1) 이력이 있는지 확인하고 없으면 사후 검증 권장.
- [ ] **오염된 CLOSED 손익 레코드 자체 보정 (보류 판단)** — P0(2026-06-23) 이전 청산 포지션은 원 체결가가 애초에 저장되지 않아 소급 재계산이 불가능. `closedSince` 기간 필터로 회피만 가능한 상태 — 완전한 보정은 불가로 결론.
- [ ] **DYNAMIC 세션 성과를 손익 대시보드에 노출** — 현재 `performance` 페이지는 live/paper 탭만 존재. 동적 멀티코인 세션 운영이 시작됐으므로 세 번째 탭 또는 통합 뷰 필요.
- [ ] **전역 리스크 지표(MDD/Sharpe) 분모 왜곡 검토** — `getPerformanceSummary`의 글로벌 지표가 시기가 겹치지 않는 과거 세션 원금까지 동시 투자로 가정해 계산됨. 활성 세션 한정 또는 시점별 실투입자본 기준으로 재정의 검토.
- [ ] **Stateful 전략(CRR 등) 재시작 시 상태 소실** — `sessionStatefulStrategies`가 인메모리 맵이라 배포/재시작마다 레짐 hysteresis(3캔들)가 리셋됨. 장기적으로 상태 스냅샷 저장 검토.

---

## 🔍 2026-07-02 실전 4대 전략 검토 (CRR / CB / HAS / CMI_V2) — 반영 완료

> ATR 거래량 필터 결함 수정 + 관찰 항목 일괄 반영 완료(상세: [`CHANGELOG.md`](CHANGELOG.md) 2026-07-02 항목 2건).
> A/B 백테스트 러너: [`StrategyReviewAbBacktestRunner`](../core-engine/src/test/java/com/cryptoautotrader/core/backtest/StrategyReviewAbBacktestRunner.java)
> (`-Dreview.backtest.dir=d:/tmp`, 100일 H1 × BTC/ETH/SOL/XRP). **미커밋 / 운영 미배포.**

- [ ] **CRR RANGE/TRANSITIONAL(비화이트리스트) 진입 수학적 희소 — 모니터링 유지** — RANGE(ADX<20)에서 V1 위임 시 MACD(0.5)는 자체 ADX(25) 필터로 항상 HOLD → 진입은 VWAP(0.3)+GRID(0.2) 동시 고신뢰 필요. 90일 분석의 RANGE WR 66~69%가 이 희소·이중확인 구조 덕일 수 있으므로 **완화는 백테스트 검증 전 금지**. 발화 빈도만 모니터링.
- [ ] **CMI_V2 존속 판단 (운영 결정 필요)** — 2026-06-30 90일 분석에서 V1이 전 레짐 V2 압도 확인(CRR도 V1로 개편됨). V1 병행 비교 목적이 끝났으면 V2 단독 실전 세션을 V1 또는 CRR로 교체 권장 — 세션 교체는 운영자 판단 사항.
- [ ] **배포 후 관찰** — HEIKIN_ASHI_STOCH 강도 게이트 해제(기본 0)로 신호 빈도가 늘어난다. 실전 신호품질 로그로 승률·빈도 재확인 (구 기본 70은 `strategyParams.minStrengthPct=70`으로 복원 가능).

---

## 🔄 2026-07-02 동적 멀티코인 시스템 보완 — 후속 관찰/과제

> 결함 6건 수정 완료(상세: [`CHANGELOG.md`](CHANGELOG.md) 2026-07-02). 컴파일·mock 테스트 통과, **미커밋 / 운영 미배포**.

- [ ] **배포 후 관찰**: 매도 FAILED 시 "매도 롤백 포지션 재결속" 로그 + 세션이 POSITION_MONITORING으로 복귀해 재매도하는지. 텔레그램 "재결속 불가" 경고가 오면 수동 청산.
- [ ] **동적 세션 전용 테스트 부재 (부분 해소)** — H2 테스트 스키마에 `dynamic_session` 테이블이 아예 없어 통합
      테스트가 원천 불가능했던 근본 원인은 해소(2026-07-02, `schema-h2.sql`에 V50 기준 테이블 추가 — 상세는
      위 §"P0 수정 완료" 참조). 단, `reconcileDynamicClosingPositions`/재결속/이중매도 가드 자체의 단위 테스트는
      여전히 미작성.
- [ ] (기존 설계 한계, 미변경) 보유 중 `totalAssetKrw`가 미실현 손익을 반영하지 않아 MDD 피크가 실현 기준으로만 추적됨 — 필요 시 모니터링 tick에서 시가평가 갱신 검토.
- [ ] (기존 설계 한계, 미변경) SCANNING 60초 tick마다 워치리스트 10코인 × 캔들 250개 REST 조회 — Upbit rate limit 여유 모니터링, 필요 시 캔들 캐시 도입.

---

## 🚨 2026-05-31 실전 로그 분석 (docs/logs/ 3종 교차 분석)

> `live_trading_sessions/positions_20260531.csv` + `signal_quality_30d_20260531.csv` 분석.
> LIVE 세션 4개(143 ETH / 144 SOL / 145 XRP / 148 BTC) 모두 ~2개월 운영했으나 사실상 본전.
> **P0는 코드 수정 적용 + 테스트 통과. P1은 조사만 완료(코드 변경은 백테스트 검증 후).**

### 현황 수치
- **세션 수익률**: BTC -0.04% / ETH -0.12% / SOL -0.16% / XRP +0.65% → 전부 본전권.
- **포지션 192건**: 실이익 2건 / 수수료만 손실(-4원≈-0.05%) 146건 / 미체결·size0 44건.
- **신호 568,338건/30일**: HOLD 99.6%(566,027) / SELL 1,351 / BUY 960 / **실제 체결 42건**.

### 🔴 P0 — 청산/PnL 정확성 (돈 직결) — ✅ 핵심 수정 완료 (2026-06-23, CHANGELOG 참조)
근본 원인 확정: 매도 체결가 미산출 → `realizedPnl`이 -매도수수료로만 기록되던 "가짜 본전".
당초 가설(ord_type/side 문자열 매칭 실패)이 아니라, **Upbit `GET /v1/order` 응답이 체결 금액을
최상위 `executed_funds`가 아닌 `trades[]` 배열로 내려주는데 DTO가 이를 파싱하지 않아
`executedFunds`가 영원히 null**이던 것이 진짜 원인. 결과적으로 시장가 매도가 `FILLED`로
전이되지 못하고 `SUBMITTED`에 무한 정체(실전 로그 다수 주문 확인).
- [x] **수정 완료** — `OrderResponse.resolveExecutedFunds()`(trades 합산 폴백) 추가 + `applyFillPrice`/`syncOrderState`에서 사용. 회귀 테스트 통과. 상세: [`CHANGELOG.md`](CHANGELOG.md) 2026-06-23 항목.
- [ ] **운영 관찰** — 배포 후 정체됐던 매도 주문들이 FILLED로 정리되고 신규 매도가 정상 체결·기록되는지 확인. PnL 재수집.
- [ ] **청산 정책 표준화 검증** — SL/TP는 DB(`ExitRuleConfig`) 동적 설정(현 SL 5%/TP +10%). P0 수정 반영된 실거래 재수집 후 조정.

### 🟠 P1 — 신호 발생률 (⚠️ 실거래 진입 빈도 변경 = 백테스트 검증 필수)

#### ✅ 죽은 하위지표 조사 완료 (2026-05-31, 읽기 전용)
결론: **MACD/VWAP/GRID는 "죽은" 게 아니라, 합산 임계와 가중치·필터가 수학적으로 어긋나
"거의 항상 HOLD"가 강제되는 구조다.** 핵심 메커니즘 3가지:

1. **단일 보조지표로는 임계 돌파가 수학적으로 불가능.**
   [`CompositeStrategy.finalSignal`](../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeStrategy.java#L182) 임계 `WEAK=0.4`. `score=Σ(weight×confidence)`, `confidence=strength/100` ([StrategySignal](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/StrategySignal.java#L46)).
   - SUPERTREND 가중 0.3 → 추세전환 strength 70(conf 0.7)이어도 기여 **0.3×0.7=0.21 < 0.4**. 지속 신호(≤50)면 ≤0.15.
   - VWAP 0.3 / GRID 0.2 도 동일 — **단독 최대 기여가 임계 미만.**
   - 따라서 진입하려면 **반드시 MACD(0.5)가 동반 발화**해야 함 = 사실상 MACD 단일 의존.

2. **그 MACD가 4중 필터로 거의 침묵한다.** [`MacdStrategy`](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/macd/MacdStrategy.java#L66): (a) 골든/데드 **크로스 순간**에만, (b) ADX≥25, (c) 제로라인, (d) 히스토그램 확대 — 4조건 동시 충족 캔들만 발화. 그 외 전부 HOLD(0).

3. **레짐↔임계 불일치 (설계 결함, 가장 치명적).**
   [`CompositeRegimeRouter`](../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeRegimeRouter.java#L100)는 **TRANSITIONAL(ADX 20~25)** 구간을 CMI_V1(MACD0.5+VWAP0.3+GRID0.2)에 위임한다. 그런데 MACD의 ADX 필터 임계가 **25.0** → TRANSITIONAL에서는 **주력 MACD가 100% 차단됨.** 남은 VWAP+GRID=0.5는 둘 다 풀강도 동방향이어야만 0.4 돌파인데 (역추세+평균회귀라) 거의 불가 → **TRANSITIONAL은 구조적으로 진입 거의 불가능.** 로그의 `[TRANSITIONAL] buy=0.00 sell=0.00 [MACD:HOLD(0) VWAP:HOLD(0) GRID:HOLD(0)]` 14.8만 건이 이 경로. `[TREND] sell=0.15 [SUPERTREND:SELL(50)]` 5.4만 건은 메커니즘 1(보조지표 단독 0.4 미달).

#### 권고 (백테스트 검증 후 적용 — 코드 미변경)
- [ ] **MACD ADX 임계를 레짐별로 정합화** — TRANSITIONAL 위임 시 MACD `adxThreshold`를 20 이하로 내리거나(params override), TRANSITIONAL→CMI_V1 위임 자체 재고. (라우터가 params로 MACD adxThreshold를 낮춰 주입하는 방식이 영향 최소)
- [ ] **보조지표 단독 진입 가능하도록 가중치 또는 임계 조정** — 예: WEAK_THRESHOLD 0.4→0.3, 또는 SUPERTREND/VWAP 가중 0.3→0.4. 단 오탐↑ 위험 → 반드시 `BacktestEngine` 다코인 검증.
- [ ] **SUPERTREND 추세지속 strength 상향 검토** — 현재 지속 신호 ≤50(conf≤0.5)이라 단독 기여 미미.
- [ ] **SELL/RANGE 편중 점검** — 롱 전용 구조, RANGE(ADX<20) 무조건 HOLD 분류 비율 점검.

### 🟡 P2 — 측정 인프라 (재진단: 일부는 이미 정상)
- [x] ~~4h/24h 백필 / CSV 따옴표~~ — 확인 결과 [`SignalQualityService`](../web-api/src/main/java/com/cryptoautotrader/api/service/SignalQualityService.java)는 이미 과거 시점 캔들(`getCandles(targetTime)`)로 정확히 백필하고, [`CsvExportService`](../web-api/src/main/java/com/cryptoautotrader/api/service/CsvExportService.java)도 이미 `q()`로 RFC4180 인용 처리 중. **기존 115MB 파일의 손상 행은 과거 버전 산출물**로 추정(현재 코드 버그 아님).
- [ ] **HOLD 로그 비대 — 실제 원인** = [`LiveTradingService`](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#L804) 가 매 평가마다 `StrategyLogEntity`를 HOLD 포함 전량 저장(30일 56만 행). HOLD 제외/요약 적재 검토(단, HOLD 비율 리포트 의존성 확인 후).
- [ ] **미라벨 신호 재조사** — PAPER 신호 `signalPrice=null`이면 백필 스킵되는 점 등.

---

## 📝 2026-04-30 주요 전략 분석 문서 작성

[docs/주요전략분석_v20260430.md](./주요전략분석_v20260430.md) — `COMPOSITE_BREAKOUT`,
`COMPOSITE_MOMENTUM_ICHIMOKU` (V1), `COMPOSITE_MOMENTUM_ICHIMOKU_V2` 3종 종합 분석.
구조·필터(ADX/EMA/Ichimoku)·하위 전략 가중치·V1↔V2 차이(VWAP→SUPERTREND)·
2026-04-24 백테스트 비교(KRW H1, ETH/SOL/XRP/MOVE/USDT/IP/FLOCK)·
코인별 전략 선택 의사결정 트리 포함.

---

## 🧨 2026-04-30 전략 분석 비판 기반 개선 로드맵

> 분석 문서([docs/주요전략분석_v20260430.md](./주요전략분석_v20260430.md))의 한계를
> 신랄하게 재검토한 결과, 다음 갭들이 식별됨. 우선순위별로 정리.

### 식별된 핵심 갭

1. **승률 11~19%, 백테스트 vs WF 13배 격차** → 사실상 long-tail 운에 베팅하는 lottery 구조. 통계적 검증 부재.
2. **MDD 미개선 (V1 = base, -25.62%)** → Ichimoku 필터는 위험관리가 아닌 노이즈 필터에 불과. 문서가 이 한계를 약하게 다룸.
3. **V1→V2 동기 미검증** → 거래수↑ + 손실 코인↑ 패턴이 "구조적 개선"이 아닌 단순 진입 빈도 증가일 가능성. HOLD 비율, 평균 보유시간, 익/손 분포 비교 미수행.
4. **RSI(0.2) 수학적 무의미** → 단독 sellScore>0.4 만들려면 confidence>2.0 필요(불가). "반쯤 죽은 가중치"를 그대로 둠.
5. **EMA 이중 카운팅** → EMA 방향 필터(EMA20/50) ↔ 하위 EMA_CROSS(EMA20/50) 동일 지표 중복. 가중치 0.1이 사실상 더 큰 영향.
6. **청산 정책 통째로 누락** → 14% 승률이면 SL/TP·trailing이 PnL 거의 전부를 결정하나 분석에 빠짐.
7. **ADX 필터의 자기모순** → 4/27 핫픽스 기록상 BREAKOUT 4개 세션 100% 차단됨. 그런데 분석은 ADX를 핵심 무기로 칭송.
8. **Ichimoku 절반만 사용** → 가격↔구름만 사용. Tenkan/Kijun 크로스, **Chikou Span**, 구름 두께/twist 모두 미사용.
9. **Regime 엔진 3중화** → BREAKOUT(자체 ADX), V1/V2(Ichimoku), `RegimeAdaptiveStrategy` 따로 작동. 통합 평가 부재.
10. **통계 유의성 검정 부재** — Sharpe CI, Profit Factor CI, t-test 한 줄도 없이 거래수 6~10건짜리를 결론에 사용.

### 🔴 P0 — 즉시 (검증 데이터 보강, 결론 재해석)

- [ ] **거래수 30건 미만 결과 본문 결론에서 분리** — 분석 문서 v2026-04-30 의 KRW-SUPER(6건), KRW-IP(7건) 등을 "참고" 섹션으로 이동. v2 개정.
- [ ] **MDD / Sharpe / Profit Factor / Calmar 컬럼 추가** — 수익률 단일 지표 결론 탈피. backtest_history 컬럼은 이미 존재 → 분석 문서 표 보강만 필요.
- [ ] **연도별 분리 백테스트** (2022/2023/2024/2025) — 어느 해에 어느 전략이 실제로 망하는지 노출. 현재 시장 사이클 통합 수치만 존재.
- [ ] **HOLD 비율 / 평균 보유시간 / 평균 익절·손절 비율 측정** — V1 vs V2 의 "진짜" 차이 정량화. BacktestEngine 결과 객체에 해당 메트릭 추가 또는 trade-level CSV로 후처리.
- [ ] **백테스트-WF 격차 95% CI 산출** — Bootstrap 1000회로 격차 신뢰구간 제시. 13배 격차가 우연 가능성 평가.

### 🟠 P1 — 단기 (전략 자체 개선)

- [x] **EMA 이중 카운팅 제거** — EMA_CROSS(0.1) → MACD(0.2) 교체. 가중치 ATR 0.5 / VD 0.3 / MACD 0.2 재조정. `CompositePresetRegistrar` 반영 완료.
- [x] **RSI(0.2) 재설계** — RSI 가중치 제거 + `RsiVetoStrategy` 래퍼 신규 구현. RSI>75 BUY 강제차단 / RSI<25 SELL 강제차단. `COMPOSITE_BREAKOUT` 및 `COMPOSITE_BREAKOUT_ICHIMOKU` 적용 완료.
- [x] **ADX 임계값 동적화** — `IndicatorUtils.adxList()` + `adxPercentileThreshold()` 신규. 최근 60캔들 ADX 30th percentile, [15, 25] 클램프. `CompositeStrategy` 적용 완료.
- [x] **Ichimoku 5요소 사용 확장** — `IchimokuFilteredStrategy` 3-레이어로 확장: (1) 구름 내부 차단, (2) Chikou Span vs 26봉전 가격, (3) Tenkan/Kijun 방향. 최소 캔들 52→78. 완료.
- [ ] **청산 정책 표준화** — 진입가 -3% 손절 / +6% 익절 후 ATR×2 trailing stop 으로 통일. 현재 `MIN_HOLD_MINUTES_FOR_SIGNAL_EXIT=30분` 만 존재 → 분석 문서·실전 모두 명시.
- [x] **분석 문서 v2 개정** — P0 결과 반영, "Ichimoku = 노이즈 필터 (위험관리 아님)" 명시, MDD 미개선을 메인 평가 섹션에 못박기. 완료.

### 🟡 P2 — 중기 (구조 통합)

- [ ] **Regime 엔진 통합** — `MarketRegimeDetector` 를 단일 진입점으로 만들어 BREAKOUT / V1 / V2 모두 동일 regime 신호를 입력으로 사용. 3중화 해소.
- [ ] **Walk-Forward 자동 재최적화 활성화** — `StrategyWeightOptimizer` 인프라 이미 구축됨 ([WeightOptimizerSnapshotEntity](../web-api/src/main/java/com/cryptoautotrader/api/entity/WeightOptimizerSnapshotEntity.java)). 90일마다 가중치 자동 재조정 스케줄러 활성화.
- [ ] **앙상블 메타 전략** — BREAKOUT / V1 / V2 출력 시그널을 voter 로 묶어 majority + confidence-weighted 최종 신호. (§ 새 전략 §1 `COMPOSITE_REGIME_ROUTER` 또는 그 상위 ensemble.)
- [ ] **Deflated Sharpe / PBO** — 다전략 튜닝 선택 편향 보정 (장기 검토 항목 승격).

---

## 🆕 2026-04-30 새로운 전략 / 기능 제안

> 비판 분석에서 도출된 신규 전략 7종 + 보호 메커니즘. ROI 우선순위 ★표시.

### ★ 1. `COMPOSITE_REGIME_ROUTER` (메타 전략) ✅ 구현 완료

단일 시점에서 ADX/ATR 변동성에 따라 BREAKOUT vs MOMENTUM 자동 위임.

```
VOLATILITY  (ATR > SMA×1.5, ADX < 25) → COMPOSITE_BREAKOUT  (ATR spike 돌파)
TREND       (ADX > 25)                 → CMI_V2              (강한 추세 모멘텀)
TRANSITIONAL (ADX 20~25)               → CMI_V1              (전환 구간 보수적)
RANGE       (ADX < 20)                 → HOLD                (횡보 진입 금지)
```

- Hysteresis 3회 연속 감지 시 전환 (MarketRegimeDetector 재사용).
- GRID stateful + RegimeDetector stateful → `registerStateful` 등록.
- 구현: [CompositeRegimeRouter.java](../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeRegimeRouter.java)

### ★ 2. `COMPOSITE_MTF_CONFIRMED` / `COMPOSITE_MTF_BTC` / `COMPOSITE_MTF_MOMENTUM` (멀티 타임프레임) ✅ 구현 완료

H1 진입 신호 + H4 Supertrend 추세 동의 시에만 진입. `CandleDownsampler.java` 재사용.
- `COMPOSITE_MTF_BTC`: CB(H1) + Supertrend(H4) — **ETH +127.70%**, DOGE +82%, AAVE/CHZ 흑자 전환
- `COMPOSITE_MTF_MOMENTUM`: CMI_V2(H1) + Supertrend(H4) — **BLUR +48.06%**, DOGE +83%
- `COMPOSITE_MTF_CONFIRMED`: CRR(H1) + Supertrend(H4) — 범용, **XRP +3.37%** (유일 흑자)
- 구현: [MtfConfirmedStrategy.java](../core-engine/src/main/java/com/cryptoautotrader/core/selector/MtfConfirmedStrategy.java)

### ★ 3. `BLACK_SWAN_GUARD` (전 전략 공통 서킷 브레이커)

1시간 내 -5% 하락 또는 거래량 평균×5 초과 시 **전 신규 진입 차단 + 보유 trailing stop 0.3% 강화**.
LUNA/FTX 류 사건 방어 — 어떤 모멘텀/돌파 전략도 단독 방어 불가.

### 4. `COMPOSITE_BREAKOUT_VOL_ADAPTIVE`

ATR multiplier 1.5 고정 → 코인별 변동성 분포로 적응:
```
multiplier = 1.0 + (현재 ATR / ATR 90일 평균)
ADX threshold = ADX 90일 30th percentile
```
4/27 핫픽스(ADX 20→15) 의 영구 자동화.

### 5. `BAYESIAN_WEIGHT_TUNER`

정적 0.5/0.3/0.2 → 베이지안 사후확률 갱신:
```
매 100거래마다: weight_i ← weight_i × (실제 승률_i) / (예측 confidence 평균_i)
                재정규화 (합 1.0)
```
[WeightOverrideStore](../core-engine/src/main/java/com/cryptoautotrader/core/selector/WeightOverrideStore.java) 인프라 활용. 코인별 자동 가중치 분화.

### 6. `CVD_DIVERGENCE`

기존 VolumeDeltaStrategy 의 다이버전스 모드를 *진입 신호 격하* → *역방향 진입 신호로 승격*.
가격 신고점 + CVD 신저점 = 약세 다이버전스 → 적극적 SELL. 횡보장에서도 매매 가능.

### 7. `KELLY_SIZED_COMPOSITE`

전략 신호 동일, 포지션 크기를 Kelly Criterion 으로:
```
Kelly% = W − (1−W)/R    (W=최근 30거래 승률, R=평균 익/손 비율)
실제 베팅 = Kelly% × 0.25  (Half-Kelly)
```
14% 승률 + R=8 이면 Half-Kelly ≈ 1.6%. 현재 동일 비중 베팅의 통계적 비효율 해소.

### 우선순위 권고

| 우선순위 | 전략 | 사유 |
|---------|------|------|
| ⭐⭐⭐ | `COMPOSITE_REGIME_ROUTER` | 기존 3전략 자산 재활용, 코인별 분리 운영 단순화 |
| ⭐⭐⭐ | `COMPOSITE_MTF_CONFIRMED` | 14% 승률 → 25%+ 가능, 인프라 존재 |
| ⭐⭐⭐ | `BLACK_SWAN_GUARD` | 모든 전략 공통 안전망. 비용 낮고 효과 큼 |
| ⭐⭐ | `COMPOSITE_BREAKOUT_VOL_ADAPTIVE` | 핫픽스 영구화 |
| ⭐⭐ | `KELLY_SIZED_COMPOSITE` | 자금 효율 개선 |
| ⭐ | `BAYESIAN_WEIGHT_TUNER` | 인프라 있으나 검증 필요 |
| ⭐ | `CVD_DIVERGENCE` | 기존 VD 보강 수준 |

---

## 🔧 2026-04-27 라이브 분석 기반 핫픽스

30일 라이브 데이터 분석 결과 승률 1.63%, 121건이 "동가 청산 → 수수료만 손실" 패턴. 다음 3건 적용:

1. **SELL 신호 최소 보유시간 가드** (`LiveTradingService`)
   - `MIN_HOLD_MINUTES_FOR_SIGNAL_EXIT = 30분`
   - 진입 30분 이내의 전략 SELL 신호는 차단 (SL/TP는 항상 작동)
   - 신호품질 로그에 차단 사유 기록
2. **CompositeStrategy ADX 필터 파라미터화** (`CompositeStrategy`)
   - `adxThreshold`, `adxPeriod`, `skipAdxFilter` params로 override 가능
   - LiveTradingService가 RANGE 레짐 자동 감지 시 `adxThreshold=15.0`으로 완화
   - 4월 24일 시작 BREAKOUT 세션 4개(SOL/XRP/ETH/BTC)가 ADX(16~18)<20 으로 100% 차단되던 문제 해소
3. **BUY 차단(이미 보유) 시 신호 강도/보유 손익 비교 로깅**
   - 향후 피라미딩/교체 정책 설계용 데이터 수집
   - blockedReason: "이미 포지션 보유 중 (신규신호강도=X, 보유포지션 pnl=Y%, 보유시간=Z분)"

후순위(미적용): MACD_STOCH_BB SELL 조건(72% SELL 편향) — 현재 미사용 전략이라 보류.

---

## 📊 graphify 코드베이스 분석 결과 (2026-04-21)

> `graphify` 지식 그래프 파이프라인으로 프로젝트 전체를 분석한 결과. 산출물: `graphify-out/GRAPH_REPORT.md`, `graphify-out/graph.html`, `graphify-out/obsidian/`

### 분석 규모

| 항목 | 수치 |
|------|------|
| 전체 파일 | 414개 |
| 전체 단어 | ~283,501 words |
| 추출 노드 | 2,518개 |
| 추출 엣지 | 5,700개 |
| 커뮤니티 | 228개 |

### ⚠️ God Node — 단일 장애점 식별

| 노드 | 엣지 수 | 의미 | 권고 |
|------|---------|------|------|
| `of()` (CoinPair.of) | 206개 | 전체 시스템이 단 하나의 팩토리 메서드에 집중 | 불변 값 객체로 충분하나 테스트/모킹 시 취약. 향후 코인 추가 시 파급력 큼 |
| `LiveTradingService` | 48개 | 실전매매 로직 전체 집중 | OrderExecution / SessionLifecycle / RiskMonitor 3분리 권고 (§7로 부분 완화됨) |
| `BacktestEngine` | ~40개 | 백테스트 핵심 엔진 | Tier1-3 수정으로 개선 완료. 현재 수준 수용 가능 |

### 🏘️ 주요 커뮤니티 현황

| ID | 이름 | 핵심 노드 | 상태 |
|----|------|----------|------|
| C0 | Walk-Forward Validation | WalkForwardTestRunner, OOS metrics | ✅ §2 완료 |
| C1 | Live Trading Core | LiveTradingService, SessionBalanceUpdater | ✅ §7-10 완료 |
| C2 | Strategy Registry & Routing | StrategyLiveStatusRegistry, StrategySelector | ✅ §11 완료 |
| C3 | Risk Management | RiskManagementService, PortfolioSyncService | ✅ §5,8 완료 |
| C4 | Exchange Adapter | UpbitWebSocketClient, ExchangeHealthMonitor | ✅ §9 완료 |
| C5 | Backtest Metrics | MetricsCalculator, PerformanceReport | ✅ §1,13 완료 |
| C14 | Backtest Performance Results | 복합전략 5코인 × 8전략 백테스트 결과 | ✅ DB 저장 완료 |
| C18 | AI Pipeline & News Feed | LLM Task Router, NewsCollector | 🔵 구현 확인됨 |
| C21 | Discord Morning Briefing | DiscordNotificationService, MorningBriefingScheduler | 🔵 구현 확인됨 |
| C25 | Spring Security Config | SecurityConfig, JWT Filter | ⚠️ Bearer 토큰 인증 있으나 완전한 Security 구성 미검증 |

---

## 프로젝트 개요

- **서비스**: 업비트 기반 가상화폐 자동매매 시스템
- **운영 환경**: Ubuntu 서버, Docker Compose (`docker-compose.prod.yml`)
- **기술 스택**: Spring Boot 3.2 (Java 17) + Next.js 16.1.6 / React 19.2.3 (TypeScript) + TimescaleDB + Redis

### 모듈 구조

```
crypto-auto-trader/
├── web-api/          # Spring Boot 백엔드 (Gradle 멀티모듈)
│   ├── core-engine/      # 백테스팅 엔진, 리스크, 포트폴리오
│   ├── strategy-lib/     # 전략 22종 (단일 11 + 복합 11)
│   ├── exchange-adapter/ # Upbit REST/WebSocket
│   └── web-api/          # REST API, 스케줄러, 서비스
├── crypto-trader-frontend/  # Next.js 16.1.6 / React 19.2.3 프론트엔드
├── docs/                    # 설계 문서 및 진행 기록
└── docker-compose.prod.yml  # 운영용 (backend + frontend + db + redis + db-backup)
```

### 구현된 전략 22종

**단일 전략 (11종)**: VWAP / EMA Cross / Bollinger Band / Grid / RSI / MACD / Supertrend / ATR Breakout / Orderbook Imbalance / Stochastic RSI / Volume Delta

**복합 전략 (11종)**:

| 전략 | 구성 | 실적합 코인 | 요약 |
|------|------|------------|------|
| COMPOSITE | Regime 자동 선택 | 범용 | — |
| COMPOSITE_MOMENTUM | MACD×0.5 + VWAP×0.3 + Grid×0.2, EMA 필터 | ETH·SOL | ETH +53.6%, SOL +59.8% |
| COMPOSITE_ETH | ATR×0.5 + OB×0.3 + EMA×0.2 | ETH | 구버전 평균 +48.7% (재검증 필요) |
| COMPOSITE_BREAKOUT (CB) | ATR×0.5 + VD×0.3 + MACD×0.2, EMA+ADX+RSI Veto 필터 | **BTC·ADA** | BTC **+106.71%**, ADA **+86.98%** |
| COMPOSITE_MOMENTUM_ICHIMOKU (CMI_V1) | CB_MOMENTUM + Ichimoku 필터 | XRP | XRP +1.04% (유일 양수) |
| COMPOSITE_MOMENTUM_ICHIMOKU_V2 (CMI_V2) | MACD×0.5 + SUPERTREND×0.3 + Grid×0.2 + Ichimoku 필터 | **DOGE** | DOGE **+124.77%** |
| COMPOSITE_BREAKOUT_ICHIMOKU | CB + Ichimoku 필터 | — | ⚠ CB와 동일 (ADX 중복) |
| COMPOSITE_REGIME_ROUTER (CRR) | ADX/ATR 레짐 → CB/V1/V2 자동 위임 | **SOL·ETH** | SOL **+65.38%**, ETH +65.09% |
| COMPOSITE_MTF_BTC | CB(H1) + Supertrend(H4) | **ETH·AAVE·CHZ** | ETH **+127.70%**, AAVE +28.15% |
| COMPOSITE_MTF_MOMENTUM | CMI_V2(H1) + Supertrend(H4) | **BLUR·DOGE** | BLUR **+48.06%**, DOGE +83.40% |
| COMPOSITE_MTF_CONFIRMED | CRR(H1) + Supertrend(H4) | **XRP** 범용 | XRP **+3.37%** (유일 흑자) |
| MACD_STOCH_BB | MACD + StochRSI + 볼린저 6조건 AND | ❌ 비활성화 | BTC -2.32%, 거래 극희소 |

### 2026-04-30 신규 전략 H1 FULL 백테스트 비교 (7전략 × 17코인)

> **조건**: 2022-01-01 ~ 2026-04-30, 초기자금 1,000만, 슬리피지 0.1% + 수수료 0.05%, H1.
> CB=COMPOSITE_BREAKOUT, V1=CMI_V1, V2=CMI_V2, CRR=COMPOSITE_REGIME_ROUTER,
> MTF_B=COMPOSITE_MTF_BTC, MTF_M=COMPOSITE_MTF_MOMENTUM, MTF_C=COMPOSITE_MTF_CONFIRMED.
> **굵게** = 코인별 1위 전략.

| 코인 | CB | V1 | V2 | CRR | MTF_B | MTF_M | MTF_C |
|------|-----|-----|-----|-----|-------|-------|-------|
| **BTC** | **+106.71%** | +5.68% | +13.80% | +14.33% | +29.66% | +14.26% | +14.26% |
| **ETH** | +30.90% | +50.73% | +58.00% | +65.09% | **+127.70%** | +75.79% | +75.79% |
| **SOL** | +62.79% | +17.30% | +42.32% | **+65.38%** | +43.40% | +60.20% | +60.92% |
| **XRP** | -1.60% | +1.04% | -10.35% | -0.48% | -21.74% | +2.71% | **+3.37%** |
| **DOGE** | +17.75% | +48.86% | **+124.77%** | +59.89% | +82.06% | +83.40% | +83.40% |
| **ADA** | **+86.98%** | +5.89% | +12.52% | +8.85% | -25.78% | +10.03% | +11.05% |
| **AAVE** | -24.52% | -48.90% | -40.71% | -3.80% | **+28.15%** | +11.34% | +12.10% |
| **BLUR** | -17.23% | +23.24% | +10.52% | +33.19% | +38.69%⚠ | **+48.06%** | **+48.06%** |
| **CHZ** | -23.77% | -12.77% | -18.38% | -26.94% | **+14.09%** | -23.28% | -23.28% |
| MOVE | -2.88% | — | — | -2.88% | -2.18% | -7.19% | -7.19% |
| SUPER | — | — | — | -4.16% | +5.61%⚠ | -4.16% | -4.16% |
| IP | — | — | — | +12.99%⚠ | +11.77%⚠ | +13.94%⚠ | +13.94%⚠ |
| FLOCK | — | — | — | -8.49% | -9.18% | -8.49% | -8.49% |
| AXL | — | — | — | -11.37% | -13.41% | -8.18% | -8.18% |
| BIO | — | — | — | -3.49% | -4.00% | -3.49% | -3.49% |
| KERNEL | — | — | — | -5.78% | -8.62% | -4.38% | -4.38% |
| USDT | — | — | — | +0.55% | -6.73% | +1.10% | +1.10% |

> ⚠ 거래 수 15건 미만 — 통계적 신뢰성 부족.
> SUPER/IP/FLOCK/AXL/BIO/KERNEL: 모든 전략 거래수 1~6건으로 결론 도출 불가 (참고만).

### 2026-04-30 신규 MTF 전략 — 코인별 MDD 비교

| 코인 | 1위 전략 | 수익률 | MDD | Sharpe | 거래수 | 이전 대비 |
|------|---------|--------|-----|--------|--------|---------|
| **BTC** | COMPOSITE_BREAKOUT | **+106.71%** | -8.88% | 1.24 | 79 | 유지 |
| **ETH** | COMPOSITE_MTF_BTC | **+127.70%** | **-7.24%** | 1.35 | 61 | ↑ 대폭 개선 (CB +30% → MTF_B +127%) |
| **SOL** | COMPOSITE_REGIME_ROUTER | **+65.38%** | -14.93% | 0.76 | 101 | ↑ 소폭 개선 (CB → CRR) |
| **XRP** | COMPOSITE_MTF_CONFIRMED | **+3.37%** | -15.67% | 0.13 | 62 | ↑ 유일 흑자 코인 |
| **DOGE** | CMI_V2 | **+124.77%** | -30.75% | 0.87 | 173 | 유지 (MTF 근접하나 MDD 더 나쁨) |
| **ADA** | COMPOSITE_BREAKOUT | **+86.98%** | -14.14% | 0.96 | 46 | 유지 (MTF_BTC -25.78%로 역효과) |
| **AAVE** | COMPOSITE_MTF_BTC | **+28.15%** | -31.01% | 0.48 | 62 | ↑ 흑자 전환 (기존 -24.52%) |
| **BLUR** | MTF_MOMENTUM/CONFIRMED | **+48.06%** | -11.91% | 0.91 | 39 | ↑ CRR +33% → MTF_M +48% |
| **CHZ** | COMPOSITE_MTF_BTC | **+14.09%** | -14.88% | 0.32 | 48 | ↑ 흑자 전환 (기존 -23.77%) |

---

### 2026-04-24 백테스트 & Walk-Forward 재실행

> **소스**: `docs/backtest_history_20260424.csv` (H1 FULL, 필터 없음), `docs/backtest_history_20260424_local.csv` (H1 FULL, **EMA200 필터 적용**), `docs/walk_forward_history_20260424_local.csv` + `(3).csv` (EMA200 필터 WF).
> **공통 조건**: 2022-01-01 ~ 2026-04-24, 초기자금 1,000만 (WF는 100만), 슬리피지 0.1% + 수수료 0.05%.
> **선행 조치**: M15 결과는 전면 폐기 (오버트레이딩으로 -99% 속출). 모든 후속 분석은 H1 기준.
> **EMA200 레짐 필터**: `BacktestEngine.isAboveEma200()` 구현 완료. 현재가 > EMA200일 때만 BUY 진입 허용. SELL(청산)은 레짐 무관.

#### H1 FULL 백테스트 — EMA200 필터 적용 후 코인별 최고 성과

| 코인 | 최고 전략 | 수익률 | MDD | Sharpe | 거래수 | 변화 |
|------|-----------|--------|-----|--------|--------|------|
| **BTC** | COMPOSITE_BREAKOUT | **+106.71%** | -8.88% | 1.24 | 79 | ↑ (+7%, MDD 개선) |
| **ETH** | COMPOSITE_MOMENTUM_ICHIMOKU_V2 | +58.00% | -13.31% | 0.75 | 150 | ↑ 소폭 개선 |
| **SOL** | COMPOSITE_BREAKOUT | +62.79% | -20.45% | 0.66 | 58 | ↑ (전략 교체) |
| **XRP** | COMPOSITE_MOMENTUM_ICHIMOKU | +1.04% | -24.22% | 0.09 | 104 | ↓ **EMA200 역효과** |
| **DOGE** | COMPOSITE_MOMENTUM_ICHIMOKU_V2 | +124.77% | -30.75% | 0.87 | 173 | ↓ 소폭 감소 |
| **ADA** | COMPOSITE_BREAKOUT | **+86.98%** | -14.14% | 0.96 | 46 | 🆕 신규 발굴 |

> FAIR_VALUE_GAP은 H1에서도 BTC -69%, ETH -82%, ADA -77% 등 메이저 코인 전부 대파. **전략 자체 구조 문제로 판단, 배포 금지.**
> XRP는 EMA200 아래 구간에서도 수익 패턴이 존재 → EMA200 필터가 역효과. XRP는 CB 전략 자체 엣지로 운영.

#### Walk-Forward AGG_OUT — EMA200 필터 적용 (2022-01-01 ~ 2026-04-24)

| 코인 | CB | CM | CMI | CMI_V2 | 최고 | 비고 |
|------|-----|-----|------|--------|------|------|
| **BTC** | +3.68% | +1.86% | +1.99% | +1.99% | CB | 필터 후 WF 감소 (2026 기간 차이) |
| **ETH** | **+4.17%** | -4.63% | -4.63% | -5.64% | CB | CB만 양수 |
| **SOL** | +24.30% (MDD -8.2%) | +26.25% | **+26.64%** | +20.30% | CMI | 전략 모두 양수 ✅ |
| **XRP** | **+25.98%** | +1.37% | -5.70% | -7.97% | CB | CB만 양수 |
| **DOGE** | -22.44% | -5.19% | -11.96% | **+2.57%** | CMI_V2 | 필터 역효과 전반적 |
| **ADA** | **+34.76%** (MDD -4.0%) | -8.32% | -8.32% | -12.54% | CB | ⚠️ 거래수 12건, 신뢰성 낮음 |

#### 시장 레짐별 윈도우 패턴 (전 전략 공통)

| 윈도우 | 기간 | Out-Sample 경향 |
|--------|------|----------------|
| W0 | 2022 하반기 (하락장 끝) | 대부분 손실 (-5~-15%) |
| W1 | 2023 여름~가을 (횡보·약세) | **전 전략 손실** (-2~-10%) |
| W2 | 2024 여름 (회복 초입) | 혼재 |
| W3 | 2025 상반기 (강세장) | **전 전략 수익** (+5~+29%) |
| W4 | 2025 Q4~2026 Q1 (변동성 확대) | 코인별 혼재 |

> EMA200 필터로 SOL은 전 전략 WF 양수 전환. DOGE·XRP 일부 전략은 필터 역효과 — 코인별 특성 고려 필요.

---

## 🟢 배포 권고 / 🚨 배포 금지 (2026-04-30 MTF 백테스트 기준)

> H1 FULL 2022~2026-04-30 백테스트 결과 기반 (WF 재검증 미수행). MTF 3종 신규 전략 반영.

### Tier 1 — 백테스트 검증 통과, 소액 투입 가능

| 코인 | 권장 전략 | FULL 수익률 | MDD | Sharpe | 근거 |
|------|-----------|------------|-----|--------|------|
| **BTC** | **COMPOSITE_BREAKOUT** | **+106.71%** | -8.88% | 1.24 | 7전략 중 압도적 1위. MDD 최저 수준. |
| **ETH** | **COMPOSITE_MTF_BTC** | **+127.70%** | -7.24% | 1.35 | 7전략 중 1위 + MDD 최저. 기존 CB +30%에서 대폭 개선. |
| **SOL** | **COMPOSITE_REGIME_ROUTER** | **+65.38%** | -14.93% | 0.76 | CB +62.79%를 근소 상회, 레짐 자동 적응. |
| **DOGE** | **CMI_V2** | **+124.77%** | -30.75% | 0.87 | MTF 근접(+83%)하나 MDD 더 나쁨. 기존 전략 유지. |
| **ADA** | **COMPOSITE_BREAKOUT** | **+86.98%** | -14.14% | 0.96 | MTF_BTC -25%로 역효과. CB 압도적. |

### Tier 2 — 흑자 전환·신규 발굴, 관찰 후 투입

| 코인 | 권장 전략 | FULL 수익률 | MDD | 판단 |
|------|-----------|------------|-----|------|
| **XRP** | **COMPOSITE_MTF_CONFIRMED** | **+3.37%** | -15.67% | 모든 전략 손실 또는 근0 중 유일 흑자. 소액 관찰. |
| **AAVE** | **COMPOSITE_MTF_BTC** | **+28.15%** | -31.01% | 기존 -24.52% → 흑자 전환. MDD -31% 주의. |
| **BLUR** | **COMPOSITE_MTF_MOMENTUM** | **+48.06%** | -11.91% | Sharpe 0.91 양호. 거래수 39건 수용 수준. |
| **CHZ** | **COMPOSITE_MTF_BTC** | **+14.09%** | -14.88% | 기존 -23.77% → 흑자 전환. 소액 관찰. |

### 🚨 배포 금지

| 조합 | 사유 |
|------|------|
| **전 코인 × M15 타임프레임** | 오버트레이딩 + 수수료 잠식으로 -99% 속출. M15 전면 비활성화 |
| **전 코인 × FAIR_VALUE_GAP** | H1에서도 메이저 코인 -69~-82%. 전략 로직 자체 구조 문제 |
| **ETH × CB / V1 / V2** | CB +30%, V1/V2 +50~58%. MTF_BTC +127%에 크게 열위 |
| **XRP × MTF_BTC** | -21.74% — 가장 나쁜 조합. 절대 금지 |
| **ADA × MTF_BTC** | -25.78% — ADA에는 역효과 큼 |
| **CHZ × CRR / MTF_M / MTF_C** | -23~-27%. MTF_BTC만 흑자 |
| **AAVE × CB / V1 / V2** | -24~-48%. MTF_BTC만 흑자 전환 |
| **MOVE/SUPER/FLOCK/AXL/BIO/KERNEL** | 거래수 1~17건으로 통계 신뢰성 없음. 배포 금지 |

### 운영 세션 조치 사항 (2026-04-30 갱신)

- 🆙 **ETH 전환**: CB → **COMPOSITE_MTF_BTC** (+127.70%, MDD -7.24% — 7전략 최고)
- 🆙 **SOL 전환**: CB → **COMPOSITE_REGIME_ROUTER** (+65.38%, 자동 레짐 적응)
- 🟢 **BTC**: CB 유지 (+106.71%)
- 🟢 **DOGE**: CMI_V2 유지 (+124.77%)
- 🟢 **ADA**: CB 유지 (+86.98%)
- 🆕 **XRP**: MTF_CONFIRMED 소액 시작 (+3.37%, 유일 흑자)
- 🆕 **AAVE**: MTF_BTC 소액 시작 (+28.15%, 흑자 전환)
- 🆕 **BLUR**: MTF_MOMENTUM 소액 시작 (+48.06%, Sharpe 0.91)
- 🆕 **CHZ**: MTF_BTC 소액 관찰 (+14.09%, 흑자 전환)

---

## 다음 할 일

### 🔴 P1-1 — 전략 고도화

- [x] **FVG A단계 구현 완료**: `FairValueGapStrategy` + `FairValueGapConfig`. EMA 필터·최소 공백 크기 필터 포함.
- [x] **FVG A단계 5코인 × H1 3년 백테스트**: SOL +224% MDD -34% 유일 유의미. BTC/DOGE/XRP/ETH 모두 현재 전략 대비 열위.
- [ ] **FVG 전략 — B단계**: 평균 회귀 방식. FVG 존(상·하한) 상태 관리 → 이후 가격이 공백 구간 재진입 시 신호 발생. 오래된 존 만료 처리 포함.
- [ ] **STOCHASTIC_RSI 구조적 개선** — StochRSI + RSI 다이버전스 결합. RSI 다이버전스 발생 + StochRSI 과매도 탈출 동시 충족 시 고신뢰 매수 신호.
- [x] **VOLUME_DELTA 테스트 작성** (13개 전체 통과)

---

### 🔴 P1-2 — Self-Audit 미완 서브항목 (`docs/20260415_analy.md` 기반)

> Tier1~4 구현은 완료됐으나 각 항목의 세부 서브태스크 중 미구현 항목.

- [x] **SL/TP intra-H1 path 정확도 향상** (§3) — `ExitRuleChecker.checkCandleExitWithPath()` 신규 구현. OHLC 4-point 경로 재구성으로 H1 캔들 내 SL/TP 도달 순서를 결정. BacktestEngine에서 `checkCandleExit` 대체.
- [x] **SL/TP 동시 터치 Monte Carlo** (§3) — `resolveByMonteCarlo()` 구현. 경로 재구성으로도 순서 불확정 시(Doji 등) Monte Carlo 200회 시뮬레이션으로 SL/TP 선도 확률 결정.
- [x] **리스크 구간 손실 재정의** (§5) — 글로벌 포트폴리오 드로우다운 체크 추가. `RiskEngine.check()` 6-파라미터 오버로드, `RiskManagementService.calculatePortfolioDrawdownPct()`, V48 마이그레이션, `RiskConfigEntity` 필드 추가.
- [x] **WeightOverrideStore DB 이력 저장** (§6) — `weight_optimizer_snapshot` 테이블(V49), `WeightOptimizerSnapshotEntity`, `WeightOptimizerSnapshotRepository`, `StrategyWeightOptimizer.saveSnapshot()` + `restoreFromSnapshot()` 구현.
- [~] **단일 전략 백테스트 기간 분리 문서화** (§12) — 복합 전략은 2026-04-24 Walk-Forward(In-Sample 학습/Out-of-Sample 검증 5윈도우)로 과적합 여부 정량 평가 완료 (SOL/CMI_V2 -98% 저하 등 식별). **단일 전략 11종에 대한 동일 WF 실행은 미진행** — 필요 시 별도 태스크.
- [x] **2022 약세장 데이터 수집 + 재백테스트** (§13) — 2026-04-24 FULL 백테스트 및 WF 모두 **2022-01-01 시작**. W0(2022 하반기 하락장 말미) OOS 구간에서 전 전략 손실(-5~-15%) 확인 → 레짐 필터 필요성으로 연결. 크립토 Winter 견고성 평가 완료.
- [ ] **테스트 커버리지 보강** (§15) — `BacktestJobService` · `PaperTradingService` · `SignalQualityService` 전용 테스트 작성.
- [ ] **세션별 에러 카운트 대시보드** (§16) — Prometheus Counter 기존 구성됨. Grafana 대시보드 패널 추가 필요.
- [ ] **로그 중앙화** (§16) — Loki 또는 CloudWatch Logs 연동. 현재 Docker logs grep 수준 → 운영 스케일 부족.
- [ ] **API key rotation 정책 수립** (§18) — Upbit Access/Secret Key 주기적 교체 프로세스 + IP 화이트리스팅 적용 여부 재확인.

---

### 🟡 P2-0 — 실전 테스트 및 전략 검증 (2026-04-24 EMA200 필터 WF 재검증 반영)

> EMA200 레짐 필터 적용 후 WF 재검증 결과 기반. 이전 가이드 폐기.

- [ ] **ETH 전략 전환: CB → COMPOSITE_MTF_BTC** — FULL +127.70%, MDD -7.24%, Sharpe 1.35. 7전략 중 압도적 1위.
- [ ] **SOL 전략 전환: CB → COMPOSITE_REGIME_ROUTER** — FULL +65.38%, 레짐 자동 적응. CB +62.79% 근소 상회.
- [x] **XRP COMPOSITE_BREAKOUT 유지** — 필터 후에도 CB +25.98% 유지. 운영 변경 없음.
- [ ] **DOGE CMI_V2 유지 + EMA200 예외 처리 검토** — DOGE는 EMA200 아래에서도 수익 패턴 존재. 코인별 필터 on/off 설정 기능 또는 DOGE 전용 예외 로직 필요.
- [ ] **ADA COMPOSITE_BREAKOUT 소액 관찰** — FULL +87%, WF +34.76% 우수하나 WF 거래수 12건으로 신뢰성 부족. 소액 세션 시작 후 거래 누적 관찰.
- [ ] **FAIR_VALUE_GAP 전략 코드 리뷰 또는 폐기 결정** — 모든 타임프레임 × 모든 메이저 코인에서 구조적 손실. B단계 구현 전에 A단계 로직 방향성 재검증 필수.
- [ ] **M15 타임프레임 전 세션 비활성화** — H1 전용으로 운영 표준화.
- [x] **EMA200 레짐 필터 PoC (백테스트)** — `BacktestEngine.isAboveEma200()` 구현 완료. SOL 전 전략 WF 양수 전환 확인. DOGE 역효과 확인 → 코인별 예외 처리 과제로 분리.
- [x] **EMA200 레짐 필터 실전 적용** — `LiveTradingService.isAboveEma200Live()` 구현 완료. CANDLE_LOOKBACK 100→250 증가. DOGE 예외 처리 포함 (coinPair.contains("DOGE") 조건). SELL 신호 영향 없음.
- [ ] **실전매매 금액 증액** — 소액 1만원 → 5만원 → 10만원 단계적 증액. 기준: 2주 이상 운영 + 승률 ≥ 50% + MDD < 10%

---

### ⏳ 장기 검토

**전략·엔진 고도화**
- [ ] **멀티 타임프레임** — 1H 방향 + 15M 진입. 아키텍처 변경 큰 편.
- [ ] **동적 가중치 완성** — 인프라(`WeightOverrideStore` + `StrategySelector`) 구축 완료. 100거래 이상 샘플 기반, 하한선 0.05, 스무딩 70/30 적용 예정.
- [ ] **칼만 필터 스캘핑 전략 (5m/15m)** — H1은 노이즈 적어 효용 낮음. 선행 조건: 수수료 시뮬레이션 + FVG A/B 완료 후.
- [ ] **LiveTradingService 분리** (graphify God Node) — OrderExecutionService / SessionLifecycleService / RiskMonitorService 3분리.

**통계·검증 고도화** (analy.md Tier5-A)
- [ ] **Deflated Sharpe / PBO** — 다전략 튜닝 선택 편향 보정.
- [ ] **Bootstrap 신뢰구간** — 3년 백테스트 결과 95% CI 산출.
- [ ] **Benchmark 비교** — HODL BTC·ETH 대비 alpha·beta 분리.

**포트폴리오 확장** (analy.md Tier5-B)
- [ ] **포트폴리오 알로케이션** — 다중 코인/전략 correlation 기반 자금 분배 (현재 코인당 독립).
- [ ] **Risk Parity / Kelly Fractional** — 현재 고정 `investRatio` 개선.
- [ ] **라이브 A/B 테스트 프레임워크** — 새 전략 소액 병행 + 통계적 차이 자동 판정.

**데이터·인프라** (analy.md Tier5-C/D)
- [ ] **Historical data 2018~2022** — 약세장 데이터 적재 (최소 BTC·ETH).
- [ ] **Auto re-optimization** — 주간 walk-forward 재실행 → StrategyRegistry 자동 업데이트 제안.
- [ ] **Telegram/Discord 명령어** — `/stop ETH`, `/pnl today`, `/emergency` 원격 제어.

---

## 서버 명령어

### 로컬 (Windows)

```bash
docker compose up -d                                                       # DB + Redis 시작 (로컬은 비밀번호 없음)
./gradlew :web-api:bootRun --args='--spring.profiles.active=local'        # 백엔드 (포트 8080)
cd crypto-trader-frontend && npm run dev                                   # 프론트엔드 (포트 3000)
```

### 운영 (Ubuntu)

```bash
cd ~/crypto-auto-trader

# 재빌드 & 재시작
docker compose -f docker-compose.prod.yml up -d --build           # 전체
docker compose -f docker-compose.prod.yml up -d --build backend   # 백엔드만
docker compose -f docker-compose.prod.yml up -d --build frontend  # 프론트엔드만

# 로그 실시간 확인
docker compose -f docker-compose.prod.yml logs -f backend
docker compose -f docker-compose.prod.yml logs -f frontend

# 오류 원인 분석 (ERROR/Exception 필터링)
docker compose -f docker-compose.prod.yml logs backend > /tmp/backend.log 2>&1
grep -n "ERROR\|Caused by\|Exception" /tmp/backend.log | tail -30
```

---

## 🔬 2026-06-01 전략 전체 분석 (Strategy-wide Audit)

> 범위: 기본 지표 14종 + 복합 프리셋 11종 + 라이브 신호 파이프라인 전체.
> 목적: 실전 ~본전/약손실 + 99.6% HOLD의 구조적 원인 규명 및 우선순위 도출.

### 1. 인벤토리 (실제 등록 기준)
- **기본 지표 14종** (`StrategyRegistry`): VWAP, EMA_CROSS, BOLLINGER, GRID*, RSI, MACD, SUPERTREND, ATR_BREAKOUT, ORDERBOOK_IMBALANCE, VOLUME_DELTA, STOCHASTIC_RSI, FAIR_VALUE_GAP, MACD_STOCH_BB*, TEST_TIMED  (*=stateful)
- **복합 프리셋 11종** (`CompositePresetRegistrar` @PostConstruct): COMPOSITE, COMPOSITE_REGIME_ROUTER, COMPOSITE_MOMENTUM, COMPOSITE_ETH, COMPOSITE_BREAKOUT, COMPOSITE_MOMENTUM_ICHIMOKU(_V2), COMPOSITE_BREAKOUT_ICHIMOKU, COMPOSITE_MTF_CONFIRMED/_BTC/_MOMENTUM
- ⚠️ 이전 메모상의 `StrategyFactory`/`CMI_V1`/`TADA`는 **실제 코드에 없음** (오기억 정정). CompositeRegimeRouter는 내부에서 delegate를 직접 생성.

### 2. 거버넌스 갭 (StrategyLiveStatusRegistry)
- ENABLED(4): COMPOSITE_BREAKOUT, COMPOSITE_MOMENTUM, COMPOSITE_MOMENTUM_ICHIMOKU, _V2
- BLOCKED(4): STOCHASTIC_RSI, MACD, MACD_STOCH_BB, COMPOSITE_BREAKOUT_ICHIMOKU / DEPRECATED(1): TEST_TIMED
- **갭1**: `isBlocked()=BLOCKED||DEPRECATED` 만 검사 → "단독 미검증(EXPERIMENTAL)" 단일지표(VWAP·RSI·BOLLINGER·GRID 등)도 라이브 세션 생성 **그대로 허용**. 라벨이 강제력 없음.
- **갭2**: 주력 메타전략 `COMPOSITE_REGIME_ROUTER` + MTF 3종이 매트릭스 **미등록** → 기본 EXPERIMENTAL.

### 3. 신호 파이프라인 — 앙상블 아님
- 라이브(`LiveTradingService.evaluateAndExecuteSession` ~775행)는 세션의 단일 `strategyType` 하나만 `evaluate()`.
- **`StrategySelector`의 레짐별 가중 앙상블(BREAKOUT 0.65+MOMENTUM 0.35 등)은 라이브 실행 경로에서 호출되지 않음** (가중치 최적화용일 뿐). 문서/설계 ↔ 실행 불일치.

### 4. 구조적 HOLD 편향 (처리량 킬러)
라이브 BUY 1건에 필요한 직렬 게이트(곱셈적 누적):
1. 레짐: RANGE→즉시 HOLD / TRANSITIONAL→V1인데 MACD adxThreshold=25라 MACD 무조건 침묵 → 남은 0.5로 0.4 임계 돌파 불가
2. CompositeStrategy 동적 ADX 필터(15~25)
3. 하위지표 합산 score>0.4 (단일 보조지표 단독 돌파 수학적 불가 — P1 기확인)
4. EMA20/50 방향 필터
5. Ichimoku 구름 필터(V1/V2)
6. RSI Veto(>75, BREAKOUT)
7. LiveTradingService EMA200 필터 (**BUY만** 차단 → 매수 비대칭)

중복: **EMA 3중**(EMA_CROSS 하위 / EMA20-50 / EMA200), **ADX 2중**(Composite / MACD내부).

### 5. 죽은/휴면 코드
- 단일지표 5종(BOLLINGER, STOCHASTIC_RSI, FVG, MACD_STOCH_BB, ORDERBOOK_IMBALANCE)은 어떤 ENABLED 복합의 하위지표도 아님 → 실질 휴면
- `SignalEvaluationService` 참조 0건 → 데드코드 후보
- StrategySelector(RANGE 매매) ↔ CompositeRegimeRouter(RANGE 진입금지) 모순 공존

### 6. 작업 우선순위 (BacktestEngine 검증 후 적용 원칙)
- [x] **P1-A**: TRANSITIONAL 위임 시 MACD adxThreshold를 **코인 선택적(BTC/SOL만 25→20)** 으로 주입(CompositeRegimeRouter, putIfAbsent·원본 불변). 전역 20은 XRP를 망가뜨려(검증), 검증서 개선 확인된 코인만 화이트리스트. 3년 H1 다코인 검증 통과 — BTC·SOL 개선·나머지 무변동(§7). ✅ 라이브 반영 가능.
- [x] **P1-B**: EMA200 게이트를 core-engine `Ema200RegimeGate` 단일 진실 소스로 통합. LiveTradingService·BacktestEngine 중복 제거, DOGE 예외를 게이트에 명문화 → 백테스트↔라이브 정합(이전엔 라이브에만 DOGE 예외 존재). 회귀 테스트 5건 통과, 전체 빌드 그린(2026-06-01). ⚠️ 백테스트에 DOGE 예외가 새로 반영되므로 DOGE 백테스트 수치 재확인 필요
- [x] **P2-A** (등재만): ROUTER/MTF_BTC/MTF_MOMENTUM를 배포 티어1 근거로 ENABLED 등재, MTF_CONFIRMED는 티어2라 EXPERIMENTAL 명시. isBlocked()는 현행 유지(EXPERIMENTAL 미차단 → 운영 리스크 회피). 회귀테스트 보강·전체 빌드 그린(2026-06-01)
- [x] **P2-B** (문서화): StrategySelector는 데드코드 아님 — `COMPOSITE`(RegimeAdaptiveStrategy) 전략·COMPOSITE 백테스트(BacktestService)가 실사용. 실제 사실은 **레짐 앙상블 2중 구현 공존**: ① StrategySelector 기반 `COMPOSITE`(가중 투표, WeightOverrideStore 동적가중) ② `CompositeRegimeRouter`(레짐별 단일 delegate 위임). 세션 단일 strategyType 평가 경로는 어느 앙상블도 안 거치고 지정 전략만 evaluate. 삭제는 빌드 파손 → 보류. 향후 통합 과제로 남김(범위 큼, 백테스트 재검증 필요).
- [x] **P3** (기록만, 코드 미변경): 휴면 단일지표 5종(BOLLINGER·STOCHASTIC_RSI·FVG·MACD_STOCH_BB·ORDERBOOK_IMBALANCE)은 어떤 ENABLED 복합의 하위지표도 아님 = 실질 휴면이나, 단독 EXPERIMENTAL로 라이브·백테스트 생성은 가능. 향후 복합전략 재료가 될 수 있어 **제거하지 않고 보존**. `SignalEvaluationService`는 코드 전체 참조 0건 확인 — 별도 데드코드 정리 후보로 기록(이번엔 미변경).

### 7. 백테스트 검증 결과 (2026-06-01, 실DB H1 2023~2025, 5코인)
> 하니스: `web-api/.../backtest/P1ChangesBacktestVerification.java` (JDBC 직접 로드, DB 미접속 시 자동 skip). CompositeRegimeRouter를 adxThreshold=25 명시(=변경전) vs 미주입(=변경후 자동20)으로 비교.

**P1-A (TRANSITIONAL adxThreshold 25→20) — 코인 선택적 효과, 전역 적용 부적절:**
| 코인 | 변경전(25) | 변경후(20) | 판정 |
|---|---|---|---|
| BTC | +13.7% T72 MDD-12.7% | +20.0% T96 MDD-13.0% | ✅ 개선 |
| ETH | +42.3% T81 MDD-13.9% | +45.3% T94 MDD-17.0% | ⚠️ 수익↑/MDD악화 |
| SOL | +70.5% T77 MDD-13.6% | +91.3% T91 MDD-12.1% | ✅ 수익↑+MDD↓ |
| XRP | +0.6% T56 MDD-14.3% | **-14.4%** T73 MDD-18.5% | ❌ 명확 악화 |
| DOGE | +54.5% T106 MDD-25.3% | +52.2% T116 MDD-26.5% | ⚠️ 소폭 악화 |
- 진입 빈도는 전 코인 증가(예상대로). BTC·SOL 명확 개선, **XRP는 망가짐**(추세 모호 코인).
- → **코인별 차등 적용으로 수정 완료** (`ADX_RELAX_COINS = [BTC, SOL]` 화이트리스트). 화이트리스트만 20, 그 외 25 유지.

**P1-A 차등 재검증 (2026-06-01, 동일 하니스):**
| 코인 | 변경전(25) | 차등적용후 | 적용 |
|---|---|---|---|
| BTC | +13.7% T72 MDD-12.7% | +20.0% T96 MDD-13.0% | 20(완화) ✅개선 |
| SOL | +70.5% T77 MDD-13.6% | +91.3% T91 MDD-12.1% | 20(완화) ✅수익↑MDD↓ |
| ETH | +42.3% T81 MDD-13.9% | +42.3% T81 MDD-13.9% | 25(유지) =무변동 |
| XRP | +0.6% T56 MDD-14.3% | +0.6% T56 MDD-14.3% | 25(유지) =무변동(보호) |
| DOGE | +54.5% T106 MDD-25.3% | +54.5% T106 MDD-25.3% | 25(유지) =무변동 |
- BTC·SOL 개선 유지 + ETH/XRP/DOGE는 변경전과 **완전 동일**(XRP -15% 악화 차단 확인). 순개선만 남고 악화 0. ✅ **라이브 반영 가능 상태.**

**P1-B (EMA200 게이트 DOGE 예외) — 정합 달성, 회귀 없음:**
- DOGE: BUY허용 26006/26006(100%) vs 순수규칙 11336(43.6%) → 예외 +56.4%p 정상 작동.
- BTC/ETH/SOL/XRP: 적용=면제 완전 동일 → 비-DOGE **무영향(회귀 없음)** 확인. ✅ **그대로 유지 권장.**

### 8. 🟢 운영 반영 & 관찰 중 (2026-06-01 ~, 2~3주)
> 코드 운영서버 빌드·반영 완료. 사용자가 5코인 소액 실거래 세션을 수동 생성·가동. H1 고정.

**가동 라인업:**
| 코인 | 전략 | 원금 | 이번 변경 발동 |
|---|---|---|---|
| KRW-BTC | COMPOSITE_BREAKOUT | 10만 | — (ROUTER 미사용) |
| KRW-ETH | COMPOSITE_MTF_BTC | 10만 | — |
| KRW-SOL | COMPOSITE_REGIME_ROUTER | 10만 | ✅ **P1-A 발동** (SOL 화이트리스트) |
| KRW-DOGE | COMPOSITE_MOMENTUM_ICHIMOKU_V2 | 5만 | ✅ **P1-B 발동** (EMA200 예외) |
| KRW-ADA | COMPOSITE_BREAKOUT | 10만 | — |
- XRP는 의도적 제외(P1-A 검증서 -15% 악화). DOGE는 MDD-30.8% 최악 → 원금 절반.

**3주 후 판단 체크포인트:**
- [ ] **P0 체결가 재발 점검** — 청산 PnL이 또 -4원(수수료만)이면 P0 수정 미반영. 청산 1건이라도 나오면 즉시 확인.
- [ ] **SOL P1-A 작동 증거** — 신호 로그 `[TRANSITIONAL]` BUY가 이전보다 발생하는지.
- [ ] **DOGE P1-B 작동 증거** — EMA200 아래 구간 BUY 진입 + MDD 추이.
- [ ] **백테스트↔실거래 괴리** — 승률·평균손익 (과거 13배 괴리 이력).
- 기준 충족 시 → 나머지(BTC/ETH/ADA) 확대. `docs/logs/` CSV 수집해두면 교차분석 가능.

**미커밋:** 이번 작업분(P1-A/P1-B/P2-A + 하니스 + 이 문서)은 운영 반영됐으나 **git 미커밋** 상태. 작업 브랜치 생성 후 커밋 권장.

**후속 과제 (이번 미적용):**
- P1-A 화이트리스트 ETH 추가 검토(수익↑/MDD악화 트레이드오프).
- P2-B StrategySelector↔CompositeRegimeRouter 레짐 앙상블 2중 구현 통합.
- `SignalEvaluationService` 데드코드(참조 0건) 정리.

### 9. 🔎 실전 이력 분석 + 주문 로그 조회 개선 (2026-06-15)

**분석 (docs/anal_data CSV 3종):**
- **KRW-ADA(포지션879, 세션153) 허위 미실현 손익**: 6/9 매도가 **계속 FAILED** → `reconcileClosingPositions`가 OPEN 롤백 → 무한 재시도. 포지션이 OPEN으로 남아 `updateSessionUnrealizedPnl` 시가평가가 멈추지 않음(세션 허위 +5.31%). **실제 FAILED 사유는 주문 `failedReason`/Upbit 응답 확인 필요** (유력: `resolveAskVolume` 잔고 잠김 / invalid_volume_ask).
- **손익 데이터 오염**: CLOSED 193건 중 143건 realizedPnl = -4원(매도수수료만) "가짜 본전", 44건 0원 → 성과지표 신뢰 불가. (P0 방어코드는 추가됐으나 과거 오염 레코드 미복구.)
- **신호 품질**: 561,800건 중 99.6% HOLD, LIVE 실체결 BUY 15·SELL 9건뿐. 4h/24h 사후수익률 컬럼 거의 null(백필 미동작).

**구현 — Upbit 주문 로그 화면(`settings/upbit-logs`) 조회 개선:**
- 날짜 **"직접 지정" 프리셋 + from~to 날짜 입력** (특정일 조회).
- 페이지네이션 **처음/끝 버튼 + 페이지 번호 직접 입력** 점프.
- **CSV(Excel) 내보내기** "엑셀로 받기" 버튼 — 현재 날짜·세션 필터 반영.
  - BE: `GET /api/v1/export/csv/live-trading/orders?sessionId&dateFrom&dateTo`, `CsvExportService.exportLiveTradingOrders`, `OrderRepository` non-paged 조회 3종. FE: `csvExportApi.liveTradingOrders`.
- web-api 컴파일 ✅ / 프론트 tsc(변경파일) ✅.

**후속:**
- [x] ADA FAILED 사유 확인 후 매도 실패 근본 원인 수정. → §10
- [ ] 상태/방향 필터 **서버측 쿼리화**(현재 클라이언트 측 = 현재 페이지 내에서만 필터).
- [ ] 오염된 CLOSED 손익 1회성 보정 스크립트.

### 10. 🛠 ADA 팬텀 포지션(체결을 취소로 오기록) 버그 수정 — 3중 방어 (2026-06-15)

**근본 원인 (주문 3803 기준 확정):** 시장가 손절 매도가 거래소에서 실제 체결됐는데, **주문 5분 타임아웃 자동취소**가 동작 → 이미 `done`이라 거래소 취소 API가 실패하지만 `cancelOrder`가 **로컬 상태를 무조건 CANCELLED로 박음** → `reconcileClosingPositions`가 포지션 OPEN 롤백 → DB는 보유 중인데 거래소엔 코인 없음 → 무한 재매도(잔고없음 FAILED) + 허위 미실현 손익.

**수정 (3중 방어):**
- **(A) 취소 직전 체결 재확인** — `OrderExecutionEngine.pollActiveOrders` 타임아웃 분기에서 취소 전 거래소 상태 재조회, `done`이면 취소 대신 `syncOrderState`로 체결 처리.
- **(B) 취소 실패 시 체결 의심** — `OrderExecutionEngine.cancelOrder` catch에서 거래소 재조회, FILLED/executed>0이면 CANCELLED 대신 체결 처리(체결을 취소로 오기록하는 경로 차단).
- **(C) 팬텀 포지션 안전망** — `LiveTradingService.reconcilePhantomPositions`(60초): OPEN인데 거래소 보유량(free+locked)이 DB 기대량의 5% 미만이면 팬텀으로 간주, **3회 연속(≈3분)·보유 10분↑** 확인 후 CLOSED 확정 + 세션 KRW 복원 + 텔레그램 경고. 추정 체결가 = 최근 FILLED 매도가 → 손절가 → 최신 캔들 종가 → 평균단가 순. **현재 멈춰있는 ADA 879도 가동 시 자동 정리됨.**
- **(C-역) 추적 안 되는 잔고 감지(경고만)** — `detectUntrackedBalances`: 거래소 보유량이 DB 추적량(OPEN size>0 + CLOSING)의 110% 초과 + **최근 24h FAILED/CANCELLED 매수 주문 존재**(dust·수동입금 구분)일 때, 3회 연속·6시간 쿨다운으로 텔레그램 경고. **매수 체결이 실패로 오기록된 거울 케이스 대응. 자동 청산/매도/포지션 생성은 안 함**(사용자 선택: 경고만).
- 진행 중 매도는 코인이 locked → 보유량>0 이라 (C)에서 자연 제외. 매수/매도 양방향 = A·B(주문 단위) 대칭 + C(잔고 대조)는 매도방향 자동청산·매수방향 경고. web-api 컴파일 ✅.

**후속:**
- [ ] 배포 후 ADA 879 자동 청산 로그/텔레그램 확인 (추정 손익 실제값 대조).
- [ ] 시장가 청산(SELL) 주문을 5분 타임아웃 자동취소 대상에서 제외하는 옵션 검토(추가 안전).
- [ ] (C-역) 경고 빈발 시 → 포지션 자동 복구 또는 자동 청산으로 승격 검토.

### 11. 📥 실전매매 세션/포지션 CSV — 세션별·다중 선택 다운로드 (2026-06-15)

분석용으로 **운영 여부 무관 과거 세션 포함** 세션별/다중 선택 export 지원.
- BE: `exportLiveTradingSessions(Collection<Long>)` / `exportLiveTradingPositions(Collection<Long>)` — `sessionIds` 지정 시 해당 세션만, 미지정 시 전체(기존 동작). 컨트롤러 `?sessionIds=1&sessionIds=2` 반복 파라미터. (세션 export는 원래도 전 상태 포함이었음 — 빠진 건 **선택 필터**였음.)
- FE: `trading/history` 테이블에 **체크박스 컬럼 + 전체선택** 추가. "세션 CSV (전체/N)" · "포지션 CSV (전체/N)" 버튼이 선택분만/전체 다운로드. `csvExportApi.liveTradingSessions/Positions(sessionIds?)`.
- **Upbit 주문 로그(`settings/upbit-logs`)도 다중 세션화**: 세션 필터를 단일 `<select>` → **체크박스 다중 선택 팝오버**로 교체. 선택분이 목록 조회·CSV 양쪽에 반영(미선택=전체). BE `getOrders`/`exportLiveTradingOrders`가 `sessionIds`(List) 수용, `OrderRepository`에 `...SessionIdIn...` 페이징/비페이징 쿼리 4종 추가. FE `tradingApi.getOrders(…, sessionIds[], …)`·`csvExportApi.liveTradingOrders(sessionIds[], …)`는 `sessionId=3&sessionId=5` 반복 파라미터로 직렬화.
- web-api 컴파일 ✅ / 변경 파일 tsc ✅ (그 외 tsc 에러는 기존 무관 파일).

### 12. 🗂 세션 soft-delete + 통합 세션 인덱스 + 전략로그 CSV/콤보박스 (2026-06-15)

**근본 원인:** `deleteSession`이 행을 hard-delete하고 **주문·포지션의 session_id를 NULL로** 만들어, 삭제된 세션이 이력·선택지에서 사라지고 주문이 미귀속됐음. (전략로그 session_id는 보존되고 있었음.)

- **soft-delete 전환** — `LiveTradingService.deleteSession`: `deleteById`+session_id NULL 처리 제거 → `status="DELETED"`로만 표시(링크 보존). 앞으로 삭제 세션도 이력·주문로그·전략로그에서 번호로 선택·조회·CSV 가능. 리컨실러는 RUNNING/CREATED/OPEN만 조회하므로 DELETED 무시(안전). **이미 hard-delete된 과거 세션은 주문이 NULL이라 주문로그엔 안 뜨지만, 전략로그는 session_id 보존돼 /logs·콤보박스에 DELETED로 노출됨.**
- **통합 세션 인덱스** — `LiveTradingService.getSessionIndex()` → `GET /api/v1/trading/sessions/index`. 라이브 세션 테이블(DELETED 포함) + `StrategyLogRepository.findDistinctSessionRefs()`(로그에만 있는 삭제/모의 세션) 병합, sessionId 내림차순. 항목: `{sessionId,strategyType,coinPair,status,sessionType}`.
- **upbit-logs**: 세션 팝오버 소스를 `listSessions` → `sessionIndex`(모의 제외)로 교체. `삭제됨` 배지 추가.
- **/logs(전략로그)**: 세션ID 텍스트 입력 → **콤보박스**(`#156 STRATEGY(COIN) 운영중` 형식, sessionIndex 기반, 구분 필터 연동) + **CSV 다운로드** 버튼 추가. BE `CsvExportService.exportStrategyLogs(sessionType, sessionId)` + `GET /api/v1/export/csv/strategy-logs`, `StrategyLogRepository` 비페이징 finder 3종.
- **trading/history**: `DELETED` 상태 라벨/스타일 추가, 삭제 세션은 재삭제 버튼 비활성. (soft-delete 후 종료 세션이 이력에 잔존.)
- web-api 컴파일 ✅ / 변경 파일 tsc ✅.

**한계/후속:**
- [ ] 이미 hard-delete된 과거 세션의 **주문 로그**는 session_id가 NULL이라 세션별 복구 불가(전략로그는 조회 가능). 필요 시 1회성 보정 검토.

### 13. 🔎 동적 멀티코인(DYNAMIC) 운영 로그 분석 — 매수 실행 0건 지속 (2026-07-22)

**운영 DB 조회 결과 (strategy_log session_type='DYNAMIC' 107,130건, 세션 38개 중 RUNNING 7개=id 32~38, M15, 자본 1만원):**
- 신호 분포: HOLD 97,531 / SELL 9,536 / **BUY 63 (0.06%)** — `was_executed`는 전량 false, **DYNAMIC 주문·포지션 0건**. 7/9 가동 이후 13일간 실거래 없음(available_krw=초기자본 그대로).
- **BUY 63건 전부 진입 게이트에서 차단**: BLACK_SWAN_GUARD 46 · EMA200(-3%) 15 · RANGE 레짐 2. 2026-07-15 2차 완화(weak 0.20, EMA 감쇠 0.7, EMA200 마진 3%) + 2026-07-21 감액 진입에도 결과 동일 — 병목이 점수 임계에서 **게이트로 이동**했을 뿐.
- **구조적 충돌**: 통과 가능한 BUY는 대부분 VWAP:BUY(100) 단독(=급락 후 이격) 패턴인데, 이 상황이 곧 BLACK_SWAN(1h -5%) 발동 조건이라 서로 상쇄. 사후수익률 검증: BLACK_SWAN 차단분 avg 4h -2.47% / 24h -5.60% (**차단이 대체로 옳았음**, 단 7/21 KRW-LA 8건은 +4~+6.5%로 기회손실). EMA200 차단분은 4h +0.50%/24h -0.32%로 중립.
- SELL 9,536건은 포지션 없는 SCANNING 중 발생한 실행 불능 신호(세션33 PULLBACK_MTF가 6,576건으로 로그의 63%) — 노이즈.
- 세션 38(MEANREV_BB, 7/20~)은 워치리스트가 KRW-XRP 1개뿐 + 781건 전량 '점수 미달' — 후보 필터(min_atr 0.5/spread 0.1)가 구형 기본값이라 후보 고갈 의심.

**조치 — BLACK_SWAN 3단계 진입 게이트 완화 (2026-07-22 구현):**
- `BlackSwanGuard.entryGate()` 신설 (하드 차단 `check()`는 보유 포지션 SL 강화 등 기존 경로용으로 유지):
  - 1시간 낙폭 > -5% → **정상 사이즈(1.0)**
  - **-8% < 낙폭 ≤ -5% → 감액 진입(0.5배)** ← 신규 완충 구간(기존엔 전량 차단)
  - 낙폭 ≤ -8% → **하드 차단** (나이프 캐칭·LUNA/FTX류 방어 유지)
  - 거래량 급증 조기경보(5배 + -2%)는 낙폭 무관 하드 차단 유지
- `DynamicTradingService` SCANNING 루프: 블랙스완 감액 배수를 EMA200 감액 배수와 **곱으로 중첩**(0.5×0.5=0.25), executeBuy의 최소주문액(5,000원) 보정이 소액 세션 진입을 살림.
- `EntryGate`는 기존 2단계 `check` 대비 **단조 완화**(−5~−8% 구간만 차단→감액). 회귀 테스트 6종 추가, `core-engine:test` + `web-api:compileJava` ✅.
- **주의**: 차단분 24h 사후수익률 -5.6%로 완충 구간도 손실 기대값 구간임 — 감액(0.5배)으로 노출을 절반 제한하되, 배포 후 실제 진입분의 손익을 관찰해 완충 폭(-8%)·감액 배수 재조정 필요.

**후속 검토:**
- [ ] 배포 후 완충 구간 감액 진입분의 실제 손익 관찰 → -8% 경계·0.5배 배수 튜닝.
- [ ] 진입 신호원이 사실상 VWAP 단독 — MACD/GRID가 M15에서 거의 0점인 원인(파라미터 스케일) 점검.
- [ ] 세션 38 워치리스트 1개 문제: min_atr/max_spread 파라미터 신·구 기본값 불일치 확인.

### 14. 🔎 실전매매(LIVE) 세션 운영 로그 분석 (2026-07-22)

**전체 현황 (live_trading_session 38개):** RUNNING 4 · EMERGENCY_STOPPED 12 · STOPPED 7 · DELETED 15.
- **RUNNING 4개 모두 정상 가동 중**(마지막 로그 0시간 전): 188(MTF_CONFIRMED/XRP), 190(MOMENTUM_ICHIMOKU/XRP), 192(MTF_BTC_STRICT/BTC), 193(MTF_BTC/ADA). 전부 자본 1만원.
- **최근 30일 실현손익 합계 -9,221원** (청산 62건, 승 19 / 패 43 = **승률 31%**, 평균 이익 +250 / 평균 손실 -333). 손실 우위 시스템.

**세부 관찰:**
- **DOGE 반복 손실**: 세션 177(MTF_MOMENTUM/DOGE) -3,807원(5연패, 각 -3.4~3.6%)이 단일 최대 손실. DOGE 누계 177+181(-1,740)+189(-591)+175(-20). CMI_V2/DOGE 세션 189는 EMA200 게이트 면제였다가 5연속 손절 후 -5.91% 비상정지(§Ema200RegimeGate 주석의 DOGE 예외 제거 근거와 일치). **DOGE는 화이트리스트 제외 검토 필요.**
- **비상정지 12건 중 다수는 실손실 아님**: 2026-07-06 `EXCHANGE_DOWN`(Upbit API 연속 3회 실패) 캐스케이드로 세션 184/185/187 등이 +0.00~-0.9%에서 일괄 비상정지됨. 실제 리스크로 멈춘 건 189(-5.91%)·191(-2.08%)·177(-12.69%) 등 소수.
- **세션 188·190 동시 중복 진입**: 두 세션이 **같은 초(2026-07-21 05:05:38)에 KRW-XRP를 동일가(1,655)·동일수량(4.83)** 매수. 서로 다른 전략이지만 같은 코인·타이밍이라 사실상 XRP 2배 노출. 현재 각 +0.91% 미실현. 다전략 세션이 같은 코인에 몰릴 때 **포트폴리오 상관 리스크** 관리 부재.
- **손실 청산이 대부분 SL(-1~-5%)**: 청산 상세에서 이익 청산은 소액(+250 평균)인데 손실은 SL 풀히트(-3~-5%)가 많음 — 익절이 손절보다 빨라 **손익비 역전**(평균손실 > 평균이익). 손절 -5%는 소액(1만원) 세션에 과대.
- SELL 신호 차단 사유 1위 "청산할 포지션 없음" 880건(노이즈), 2위 "본전 청산 차단"(pnl < +0.30%) — 손실 포지션을 SL까지 끌고 가는 정책이 평균손실을 키우는 구조와 연동됨.

**후속 검토 (사용자 결정 필요):**
- [ ] DOGE 화이트리스트 제외 (누적 최대 손실원, EMA200 면제 반증 이력).
- [ ] 다전략 세션의 동일코인 중복 진입 상한(포트폴리오 상관 리스크) — 코인당 총 노출 한도.
- [ ] 손익비 역전 개선: 손절 -5% 축소 또는 익절 목표 상향 / 트레일링 조정 (승률 31%에선 손익비 2:1+ 필요).
- [ ] `EXCHANGE_DOWN` 캐스케이드로 멈춘 정상 세션 자동 재개 정책 검토(현재 수동 재시작 필요).
