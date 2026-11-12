# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: paper-render.spec.ts >> ① 정상 보유 — 목록·상세에 총자산·수익률 숫자, 평가 불가 표시 없음
- Location: e2e\paper-render.spec.ts:102:5

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: getByText('20,230.4').first()
Expected: visible
Timeout: 5000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" with timeout 5000ms
  - waiting for getByText('20,230.4').first()

```

```yaml
- 'heading "Application error: a client-side exception has occurred while loading localhost (see the browser console for more information)." [level=2]'
```

# Test source

```ts
  5   | /**
  6   |  * 총괄 v1.153 G — PAPER 화면 실제 렌더링(Next dev + 실제 브라우저). 서버는 띄우지 않고 /api/proxy/** 를 가로채
  7   |  * PaperTradingController 의 응답 필드 이름(toSessionSummaryMap·toBalanceMap·toPositionMap·toPendingPositionMap·stop·stop-all)대로 답한다.
  8   |  * 값은 시험이 정한 대역이다(운영·실측 아님). 상태: ① 정상 보유 ② 청산 대기(CLOSING)·평가 null ③ 첫 시세 뒤 정산 ④ 정지 응답 대기 수.
  9   |  * 화면 사진은 OUT 폴더에 남긴다.
  10  |  */
  11  | const OUT = process.env.PAPER_RENDER_OUT ?? path.join(__dirname, '..', 'test-results', 'paper-render');
  12  | fs.mkdirSync(OUT, { recursive: true });
  13  | 
  14  | const SID = 9101;
  15  | const NOW = '2026-11-12T00:00:00Z';
  16  | 
  17  | type State = 'normal' | 'closing' | 'settled';
  18  | 
  19  | function session(state: State) {
  20  |   const pending = state === 'closing' ? 1 : 0;
  21  |   const base = {
  22  |     id: SID, strategyName: 'DAILY_SMA20', coinPair: 'KRW-BTC', timeframe: 'H1',
  23  |     status: state === 'settled' ? 'STOPPED' : 'RUNNING',
  24  |     availableKrw: state === 'settled' ? 20150.12 : 200, initialCapital: 20200,
  25  |     realizedPnl: state === 'settled' ? -49.88 : 0, totalFee: state === 'settled' ? 20 : 10,
  26  |     startedAt: '2026-11-11T00:00:00Z', stoppedAt: state === 'settled' ? NOW : null,
  27  |     strategyParams: { executionMode: 'LIVE_ALIGNED' },
  28  |     pendingCloseCount: pending,
  29  |     valuationStatus: pending > 0 ? 'PENDING_CLOSE' : 'VALUED',
  30  |   };
  31  |   if (state === 'closing') {
  32  |     return { ...base, totalAssetKrw: null, totalReturnPct: null, lastRecordedTotalKrw: 20188.5,
  33  |       lastRecordedNote: '마지막 기록 총자산 — 기록 시각 미상, 현재 평가 아님', realizedPnlNote: '완료 거래만 — 청산 대기 포지션은 아직 완료 거래가 아님' };
  34  |   }
  35  |   return { ...base, totalAssetKrw: state === 'settled' ? 20150.12 : 20230.4, totalReturnPct: state === 'settled' ? -0.25 : 0.15 };
  36  | }
  37  | 
  38  | function balance(state: State) {
  39  |   const s = session(state);
  40  |   const p = state === 'closing';
  41  |   return {
  42  |     ...s,
  43  |     positionValueKrw: p ? null : state === 'settled' ? 0 : 20030.4,
  44  |     costBasisKrw: state === 'settled' ? 0 : 20000,
  45  |     unrealizedPnl: p ? null : state === 'settled' ? 0 : 30.4,
  46  |     positionPnlPct: p ? null : state === 'settled' ? 0 : 0.15,
  47  |   };
  48  | }
  49  | 
  50  | function positions(state: State) {
  51  |   if (state === 'normal') {
  52  |     return [{ id: 1, coinPair: 'KRW-BTC', side: 'BUY', quantity: 0.0016, avgEntryPrice: 12500000, entryPrice: 12500000,
  53  |       unrealizedPnl: 30.4, unrealizedPnlPct: 0.15, realizedPnl: 0, status: 'OPEN', openedAt: '2026-11-11T23:00:00Z', closedAt: null }];
  54  |   }
  55  |   if (state === 'closing') {
  56  |     return [{ id: 1, coinPair: 'KRW-BTC', side: 'BUY', quantity: 0.0016, avgEntryPrice: 12500000, entryPrice: 12500000,
  57  |       unrealizedPnl: null, unrealizedPnlPct: null, valuationStatus: '평가 불가 — 청산 대기(유효 시세 없음)',
  58  |       lastRecordedUnrealizedPnl: 12.3, lastRecordedUnrealizedPnlNote: '청산 판단 이전 마지막 기록값 — 기록 시각 미상, 현재 평가 아님',
  59  |       pendingExitReason: 'FORCED_STOP', exitDecidedAt: NOW, realizedPnl: null, status: 'CLOSING', openedAt: '2026-11-11T23:00:00Z', closedAt: null }];
  60  |   }
  61  |   return [{ id: 1, coinPair: 'KRW-BTC', side: 'BUY', quantity: 0.0016, avgEntryPrice: 12500000, entryPrice: 12500000,
  62  |     unrealizedPnl: 0, unrealizedPnlPct: 0, realizedPnl: -49.88, status: 'CLOSED', openedAt: '2026-11-11T23:00:00Z', closedAt: NOW }];
  63  | }
  64  | 
  65  | const page0 = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };
  66  | 
  67  | async function serve(page: Page, get: () => State, stopBody?: () => unknown, stopAllBody?: () => unknown) {
  68  |   await page.route('**/api/proxy/**', async (route: Route) => {
  69  |     const url = new URL(route.request().url());
  70  |     const p = url.pathname.replace(/^\/api\/proxy/, '');
  71  |     const m = route.request().method();
  72  |     const ok = (data: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data, error: null }) });
  73  |     const st = get();
  74  |     if (m === 'GET' && p === '/api/v1/paper-trading/sessions') return ok([session(st)]);
  75  |     if (m === 'GET' && p === `/api/v1/paper-trading/sessions/${SID}`) return ok(balance(st));
  76  |     if (m === 'GET' && p === `/api/v1/paper-trading/sessions/${SID}/positions`) return ok(positions(st));
  77  |     if (m === 'GET' && p === `/api/v1/paper-trading/sessions/${SID}/orders`) return ok(page0);
  78  |     if (m === 'GET' && p === `/api/v1/paper-trading/sessions/${SID}/chart`) return ok({ candles: [], orders: [] });
  79  |     if (m === 'POST' && p === `/api/v1/paper-trading/sessions/${SID}/stop` && stopBody) return ok(stopBody());
  80  |     if (m === 'POST' && p === '/api/v1/paper-trading/sessions/stop-all' && stopAllBody) return ok(stopAllBody());
  81  |     if (p.startsWith('/api/v1/logs/')) return ok(page0);
  82  |     console.log('[unrouted]', m, p);
  83  |     return ok([]);   // 그 밖(전략 목록·코인 등)은 빈 목록
  84  |   });
  85  | }
  86  | 
  87  | test.beforeEach(async ({ page }) => {
  88  |   await page.setViewportSize({ width: 1440, height: 1000 });
  89  |   await page.addInitScript(() => {
  90  |     const hide = () => {
  91  |       const style = document.createElement('style');
  92  |       style.textContent = 'nextjs-portal { display: none !important; }';
  93  |       document.head.appendChild(style);
  94  |     };
  95  |     if (document.head) hide();
  96  |     else document.addEventListener('DOMContentLoaded', hide);
  97  |   });
  98  |   page.on('dialog', d => d.accept());   // 정지 확인 창
  99  |   page.on('pageerror', e => console.log('[pageerror]', e.message));
  100 | });
  101 | 
  102 | test('① 정상 보유 — 목록·상세에 총자산·수익률 숫자, 평가 불가 표시 없음', async ({ page }) => {
  103 |   await serve(page, () => 'normal');
  104 |   await page.goto('/paper-trading');
> 105 |   await expect(page.getByText('20,230.4').first()).toBeVisible();
      |                                                    ^ Error: expect(locator).toBeVisible() failed
  106 |   await expect(page.getByText('평가 불가 — 청산 대기')).toHaveCount(0);
  107 |   await page.screenshot({ path: path.join(OUT, '1-normal-list.png'), fullPage: true });
  108 |   await page.goto(`/paper-trading/${SID}`);
  109 |   await expect(page.getByText('20,230.4').first()).toBeVisible();
  110 |   await expect(page.getByText('청산 대기', { exact: false })).toHaveCount(0);
  111 |   await page.screenshot({ path: path.join(OUT, '1-normal-detail.png'), fullPage: true });
  112 | });
  113 | 
  114 | test('② 청산 대기(CLOSING)·평가 null — 0/0.00% 가 아니라 평가 불가, 마지막 기록값은 현재 평가 아님 표시와 함께, 보유 표에 미정산 보유', async ({ page }) => {
  115 |   await serve(page, () => 'closing');
  116 |   await page.goto('/paper-trading');
  117 |   await expect(page.getByText('평가 불가 — 청산 대기').first()).toBeVisible();
  118 |   await expect(page.getByText('0.00%')).toHaveCount(0);
  119 |   await page.screenshot({ path: path.join(OUT, '2-closing-list.png'), fullPage: true });
  120 |   await page.goto(`/paper-trading/${SID}`);
  121 |   await expect(page.getByText('평가 불가 — 청산 대기').first()).toBeVisible();
  122 |   await expect(page.getByText(/청산 대기 1건 — 유효 시세가 없어 현재 평가를 할 수 없습니다/)).toBeVisible();
  123 |   await expect(page.getByText(/마지막 기록 총자산 20,188.5 KRW/)).toBeVisible();
  124 |   await expect(page.getByText(/청산 대기 \(FORCED_STOP\) — 미정산 보유/)).toBeVisible();
  125 |   await page.screenshot({ path: path.join(OUT, '2-closing-detail.png'), fullPage: true });
  126 | });
  127 | 
  128 | test('③ 첫 유효 시세 뒤 정산 — 같은 화면이 다시 읽어 확정 총자산·청산 완료로 바뀐다', async ({ page }) => {
  129 |   let st: State = 'closing';
  130 |   await serve(page, () => st);
  131 |   await page.goto(`/paper-trading/${SID}`);
  132 |   await expect(page.getByText(/청산 대기 1건/)).toBeVisible();
  133 |   st = 'settled';
  134 |   await page.reload();
  135 |   await expect(page.getByText('20,150.12').first()).toBeVisible();
  136 |   await expect(page.getByText(/청산 대기 1건/)).toHaveCount(0);
  137 |   await expect(page.getByText('평가 불가 — 청산 대기')).toHaveCount(0);
  138 |   await page.screenshot({ path: path.join(OUT, '3-settled-detail.png'), fullPage: true });
  139 | });
  140 | 
  141 | test('④-가 단건 정지 응답 대기 1 — 상세에 머물러 대기 수·확정 아님을 보인다', async ({ page }) => {
  142 |   let st: State = 'normal';
  143 |   await serve(page, () => st, () => {
  144 |     st = 'closing';
  145 |     return { id: SID, status: 'STOPPED', finalAsset: null, lastRecordedTotalKrw: 20188.5,
  146 |       assetStatus: '확정 아님 — 청산 대기 포지션 미정산(마지막 기록 총자산, 현재 평가 아님)', stoppedAt: NOW,
  147 |       pendingCloseCount: 1, settlement: '청산 대기 — 첫 유효 시세에 정산' };
  148 |   });
  149 |   await page.goto(`/paper-trading/${SID}`);
  150 |   await page.getByRole('button', { name: '세션 중단' }).click();
  151 |   const notice = page.getByTestId('paper-stop-result');
  152 |   await expect(notice).toHaveText(`세션 #${SID} 정지 — 청산 대기 1건, 첫 유효 시세에 정산(정산 완료 아님) · 마지막 기록 총자산 20,188.5 KRW(확정 아님, 현재 평가 아님)`);
  153 |   await expect(page).toHaveURL(new RegExp(`/paper-trading/${SID}$`));
  154 |   await page.screenshot({ path: path.join(OUT, '4a-stop-single-pending.png'), fullPage: true });
  155 | });
  156 | 
  157 | test('④-나 단건 정지 응답 대기 0 — 종전대로 목록으로 이동', async ({ page }) => {
  158 |   let st: State = 'normal';
  159 |   await serve(page, () => st, () => {
  160 |     st = 'settled';
  161 |     return { id: SID, status: 'STOPPED', finalAsset: 20150.12, stoppedAt: NOW, pendingCloseCount: 0, settlement: '완료' };
  162 |   });
  163 |   await page.goto(`/paper-trading/${SID}`);
  164 |   await page.getByRole('button', { name: '세션 중단' }).click();
  165 |   await expect(page).toHaveURL(/\/paper-trading$/);
  166 | });
  167 | 
  168 | test('④-다 일괄 정지 응답 대기 2 — 목록 배너에 정지 수·대기 수, 정산 완료라고 쓰지 않음', async ({ page }) => {
  169 |   let st: State = 'normal';
  170 |   await serve(page, () => st, undefined, () => {
  171 |     st = 'closing';
  172 |     return { stoppedCount: 3, pendingCloseCount: 2, settlement: '청산 대기 2건 — 첫 유효 시세에 정산' };
  173 |   });
  174 |   await page.goto('/paper-trading');
  175 |   await page.getByRole('button', { name: '일괄 정지' }).click();
  176 |   const notice = page.getByTestId('paper-stop-result');
  177 |   await expect(notice).toHaveText('3개 세션 정지 — 청산 대기 2건, 첫 유효 시세에 정산(정산 완료 아님)');
  178 |   await page.screenshot({ path: path.join(OUT, '4c-stop-all-pending.png'), fullPage: true });
  179 | });
  180 | 
  181 | test('④-라 단건 정지(목록) 응답에 대기 수가 없으면 — 정산 완료로 보지 않는다', async ({ page }) => {
  182 |   await serve(page, () => 'normal', () => ({ id: SID, status: 'STOPPED', finalAsset: 20150.12, stoppedAt: NOW }));
  183 |   await page.goto('/paper-trading');
  184 |   await page.getByRole('button', { name: /중단|정지/ }).last().click();
  185 |   await expect(page.getByTestId('paper-stop-result')).toHaveText(`세션 #${SID} 정지 — 정산 상태 응답 없음(정산 완료로 보지 않음)`);
  186 |   await page.screenshot({ path: path.join(OUT, '4d-stop-single-unknown.png'), fullPage: true });
  187 | });
  188 | 
```