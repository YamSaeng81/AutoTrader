# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: paper-render.spec.ts >> ② 청산 대기(CLOSING)·평가 null — 0/0.00% 가 아니라 평가 불가, 마지막 기록값은 현재 평가 아님 표시와 함께, 보유 표에 미정산 보유
- Location: e2e\paper-render.spec.ts:112:5

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: getByText('평가 불가 — 청산 대기').first()
Expected: visible
Timeout: 5000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" with timeout 5000ms
  - waiting for getByText('평가 불가 — 청산 대기').first()

```

```yaml
- 'heading "Application error: a client-side exception has occurred while loading localhost (see the browser console for more information)." [level=2]'
```

# Test source

```ts
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
  82  |     return ok([]);   // 그 밖(전략 목록·코인 등)은 빈 목록
  83  |   });
  84  | }
  85  | 
  86  | test.beforeEach(async ({ page }) => {
  87  |   await page.setViewportSize({ width: 1440, height: 1000 });
  88  |   await page.addInitScript(() => {
  89  |     const hide = () => {
  90  |       const style = document.createElement('style');
  91  |       style.textContent = 'nextjs-portal { display: none !important; }';
  92  |       document.head.appendChild(style);
  93  |     };
  94  |     if (document.head) hide();
  95  |     else document.addEventListener('DOMContentLoaded', hide);
  96  |   });
  97  |   page.on('dialog', d => d.accept());   // 정지 확인 창
  98  | });
  99  | 
  100 | test('① 정상 보유 — 목록·상세에 총자산·수익률 숫자, 평가 불가 표시 없음', async ({ page }) => {
  101 |   await serve(page, () => 'normal');
  102 |   await page.goto('/paper-trading');
  103 |   await expect(page.getByText('20,230.4').first()).toBeVisible();
  104 |   await expect(page.getByText('평가 불가 — 청산 대기')).toHaveCount(0);
  105 |   await page.screenshot({ path: path.join(OUT, '1-normal-list.png'), fullPage: true });
  106 |   await page.goto(`/paper-trading/${SID}`);
  107 |   await expect(page.getByText('20,230.4').first()).toBeVisible();
  108 |   await expect(page.getByText('청산 대기', { exact: false })).toHaveCount(0);
  109 |   await page.screenshot({ path: path.join(OUT, '1-normal-detail.png'), fullPage: true });
  110 | });
  111 | 
  112 | test('② 청산 대기(CLOSING)·평가 null — 0/0.00% 가 아니라 평가 불가, 마지막 기록값은 현재 평가 아님 표시와 함께, 보유 표에 미정산 보유', async ({ page }) => {
  113 |   await serve(page, () => 'closing');
  114 |   await page.goto('/paper-trading');
> 115 |   await expect(page.getByText('평가 불가 — 청산 대기').first()).toBeVisible();
      |                                                         ^ Error: expect(locator).toBeVisible() failed
  116 |   await expect(page.getByText('0.00%')).toHaveCount(0);
  117 |   await page.screenshot({ path: path.join(OUT, '2-closing-list.png'), fullPage: true });
  118 |   await page.goto(`/paper-trading/${SID}`);
  119 |   await expect(page.getByText('평가 불가 — 청산 대기').first()).toBeVisible();
  120 |   await expect(page.getByText(/청산 대기 1건 — 유효 시세가 없어 현재 평가를 할 수 없습니다/)).toBeVisible();
  121 |   await expect(page.getByText(/마지막 기록 총자산 20,188.5 KRW/)).toBeVisible();
  122 |   await expect(page.getByText(/청산 대기 \(FORCED_STOP\) — 미정산 보유/)).toBeVisible();
  123 |   await page.screenshot({ path: path.join(OUT, '2-closing-detail.png'), fullPage: true });
  124 | });
  125 | 
  126 | test('③ 첫 유효 시세 뒤 정산 — 같은 화면이 다시 읽어 확정 총자산·청산 완료로 바뀐다', async ({ page }) => {
  127 |   let st: State = 'closing';
  128 |   await serve(page, () => st);
  129 |   await page.goto(`/paper-trading/${SID}`);
  130 |   await expect(page.getByText(/청산 대기 1건/)).toBeVisible();
  131 |   st = 'settled';
  132 |   await page.reload();
  133 |   await expect(page.getByText('20,150.12').first()).toBeVisible();
  134 |   await expect(page.getByText(/청산 대기 1건/)).toHaveCount(0);
  135 |   await expect(page.getByText('평가 불가 — 청산 대기')).toHaveCount(0);
  136 |   await page.screenshot({ path: path.join(OUT, '3-settled-detail.png'), fullPage: true });
  137 | });
  138 | 
  139 | test('④-가 단건 정지 응답 대기 1 — 상세에 머물러 대기 수·확정 아님을 보인다', async ({ page }) => {
  140 |   let st: State = 'normal';
  141 |   await serve(page, () => st, () => {
  142 |     st = 'closing';
  143 |     return { id: SID, status: 'STOPPED', finalAsset: null, lastRecordedTotalKrw: 20188.5,
  144 |       assetStatus: '확정 아님 — 청산 대기 포지션 미정산(마지막 기록 총자산, 현재 평가 아님)', stoppedAt: NOW,
  145 |       pendingCloseCount: 1, settlement: '청산 대기 — 첫 유효 시세에 정산' };
  146 |   });
  147 |   await page.goto(`/paper-trading/${SID}`);
  148 |   await page.getByRole('button', { name: '세션 중단' }).click();
  149 |   const notice = page.getByTestId('paper-stop-result');
  150 |   await expect(notice).toHaveText(`세션 #${SID} 정지 — 청산 대기 1건, 첫 유효 시세에 정산(정산 완료 아님) · 마지막 기록 총자산 20,188.5 KRW(확정 아님, 현재 평가 아님)`);
  151 |   await expect(page).toHaveURL(new RegExp(`/paper-trading/${SID}$`));
  152 |   await page.screenshot({ path: path.join(OUT, '4a-stop-single-pending.png'), fullPage: true });
  153 | });
  154 | 
  155 | test('④-나 단건 정지 응답 대기 0 — 종전대로 목록으로 이동', async ({ page }) => {
  156 |   let st: State = 'normal';
  157 |   await serve(page, () => st, () => {
  158 |     st = 'settled';
  159 |     return { id: SID, status: 'STOPPED', finalAsset: 20150.12, stoppedAt: NOW, pendingCloseCount: 0, settlement: '완료' };
  160 |   });
  161 |   await page.goto(`/paper-trading/${SID}`);
  162 |   await page.getByRole('button', { name: '세션 중단' }).click();
  163 |   await expect(page).toHaveURL(/\/paper-trading$/);
  164 | });
  165 | 
  166 | test('④-다 일괄 정지 응답 대기 2 — 목록 배너에 정지 수·대기 수, 정산 완료라고 쓰지 않음', async ({ page }) => {
  167 |   let st: State = 'normal';
  168 |   await serve(page, () => st, undefined, () => {
  169 |     st = 'closing';
  170 |     return { stoppedCount: 3, pendingCloseCount: 2, settlement: '청산 대기 2건 — 첫 유효 시세에 정산' };
  171 |   });
  172 |   await page.goto('/paper-trading');
  173 |   await page.getByRole('button', { name: '일괄 정지' }).click();
  174 |   const notice = page.getByTestId('paper-stop-result');
  175 |   await expect(notice).toHaveText('3개 세션 정지 — 청산 대기 2건, 첫 유효 시세에 정산(정산 완료 아님)');
  176 |   await page.screenshot({ path: path.join(OUT, '4c-stop-all-pending.png'), fullPage: true });
  177 | });
  178 | 
  179 | test('④-라 단건 정지(목록) 응답에 대기 수가 없으면 — 정산 완료로 보지 않는다', async ({ page }) => {
  180 |   await serve(page, () => 'normal', () => ({ id: SID, status: 'STOPPED', finalAsset: 20150.12, stoppedAt: NOW }));
  181 |   await page.goto('/paper-trading');
  182 |   await page.getByRole('button', { name: /중단|정지/ }).last().click();
  183 |   await expect(page.getByTestId('paper-stop-result')).toHaveText(`세션 #${SID} 정지 — 정산 상태 응답 없음(정산 완료로 보지 않음)`);
  184 |   await page.screenshot({ path: path.join(OUT, '4d-stop-single-unknown.png'), fullPage: true });
  185 | });
  186 | 
```