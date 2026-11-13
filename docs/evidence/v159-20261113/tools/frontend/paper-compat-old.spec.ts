import { test, expect, type Page, type Route } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

/**
 * 총괄 v1.159 §5 — backend 먼저·frontend 나중 순서의 사이 구간: **구 프런트(b5cd1d0) + 새 API(4a4a04a3)** 표시·조회 확인.
 * 서버는 띄우지 않고 /api/proxy/** 를 가로채 새 PaperTradingController 의 응답 모양(toSessionSummaryMap·toBalanceMap·
 * toPositionMap·toPendingPositionMap·stop)으로 답한다. 값은 시험 대역이다(운영·실측 아님).
 *   A·B: 키 없는 종전 세션(사전 등록 66 과 같은 형태) — 새 API 는 같은 숫자에 pendingCloseCount·valuationStatus 를 덧붙일 뿐이다.
 *        구 화면이 숫자를 그대로 보이고 오류 없이 동작해야 한다(단언).
 *   C  : 새 모드(LIVE_ALIGNED) CLOSING·평가 null — 사이 구간에 새 모드 세션을 만들면 구 화면이 무엇을 보이는지 **관측만** 한다.
 * 이 파일은 b5cd1d0 작업트리에 잠시 넣어 실행하고 제품 트리에는 넣지 않는다.
 */
const OUT = process.env.PAPER_RENDER_OUT ?? path.join(__dirname, '..', 'test-results', 'paper-compat-old');
fs.mkdirSync(OUT, { recursive: true });

const SID = 9201;
const NOW = '2026-11-12T00:00:00Z';

function keylessSession() {   // 새 API, 키 없는 세션: 숫자는 종전과 같고 두 필드가 덧붙는다
  return { id: SID, strategyName: 'MEANREV_BB', coinPair: 'KRW-BTC', timeframe: 'M15', status: 'RUNNING',
    totalAssetKrw: 101234.5, availableKrw: 80000, initialCapital: 100000, totalReturnPct: 1.2345,
    realizedPnl: 321, totalFee: 12, startedAt: '2026-11-01T00:00:00Z', stoppedAt: null, strategyParams: {},
    pendingCloseCount: 0, valuationStatus: 'VALUED' };
}
function keylessBalance() {
  return { ...keylessSession(), positionValueKrw: 21234.5, costBasisKrw: 20000, unrealizedPnl: 1234.5, positionPnlPct: 6.1725 };
}
const keylessPositions = [{ id: 1, coinPair: 'KRW-BTC', side: 'BUY', quantity: 0.0016, avgEntryPrice: 12500000, entryPrice: 12500000,
  unrealizedPnl: 1234.5, unrealizedPnlPct: 6.17, realizedPnl: 0, status: 'OPEN', openedAt: '2026-11-11T23:00:00Z', closedAt: null }];

function closingSession() {   // 새 API, 새 모드 세션에 청산 대기 1
  return { id: SID, strategyName: 'DAILY_SMA20', coinPair: 'KRW-BTC', timeframe: 'H1', status: 'RUNNING',
    totalAssetKrw: null, availableKrw: 200, initialCapital: 20200, totalReturnPct: null, realizedPnl: 0, totalFee: 10,
    startedAt: '2026-11-11T00:00:00Z', stoppedAt: null, strategyParams: { paperExecutionMode: 'LIVE_ALIGNED' },
    pendingCloseCount: 1, valuationStatus: 'PENDING_CLOSE', lastRecordedTotalKrw: 20188.5,
    lastRecordedNote: '마지막 기록 총자산 — 기록 시각 미상, 현재 평가 아님' };
}
function closingBalance() {
  return { ...closingSession(), positionValueKrw: null, costBasisKrw: 20000, unrealizedPnl: null, positionPnlPct: null };
}
const closingPositions = [{ id: 1, coinPair: 'KRW-BTC', side: 'BUY', quantity: 0.0016, avgEntryPrice: 12500000, entryPrice: 12500000,
  unrealizedPnl: null, unrealizedPnlPct: null, valuationStatus: '평가 불가 — 청산 대기(유효 시세 없음)', lastRecordedUnrealizedPnl: 12.3,
  pendingExitReason: 'FORCED_STOP', exitDecidedAt: NOW, realizedPnl: null, status: 'CLOSING', openedAt: '2026-11-11T23:00:00Z', closedAt: null }];

const page0 = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };

async function serve(page: Page, kind: 'keyless' | 'closing', stopBody?: () => unknown) {
  await page.route('**/api/proxy/**', async (route: Route) => {
    const p = new URL(route.request().url()).pathname.replace(/^\/api\/proxy/, '');
    const m = route.request().method();
    const ok = (data: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data, error: null }) });
    const k = kind === 'keyless';
    if (m === 'GET' && p === '/api/v1/paper-trading/sessions') return ok([k ? keylessSession() : closingSession()]);
    if (m === 'GET' && p === `/api/v1/paper-trading/sessions/${SID}`) return ok(k ? keylessBalance() : closingBalance());
    if (m === 'GET' && p === `/api/v1/paper-trading/sessions/${SID}/positions`) return ok(k ? keylessPositions : closingPositions);
    if (m === 'GET' && p === `/api/v1/paper-trading/sessions/${SID}/orders`) return ok(page0);
    if (m === 'GET' && p === `/api/v1/paper-trading/sessions/${SID}/chart`) return ok({ candles: [], orders: [] });
    if (m === 'POST' && p === `/api/v1/paper-trading/sessions/${SID}/stop` && stopBody) return ok(stopBody());
    if (p.startsWith('/api/v1/logs/')) return ok(page0);
    return ok([]);
  });
}

const errors: string[] = [];
test.beforeEach(async ({ page }) => {
  errors.length = 0;
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.addInitScript(() => {
    const hide = () => { const s = document.createElement('style'); s.textContent = 'nextjs-portal { display: none !important; }'; document.head.appendChild(s); };
    if (document.head) hide(); else document.addEventListener('DOMContentLoaded', hide);
  });
  page.on('dialog', d => d.accept());
  page.on('pageerror', e => { errors.push(e.message); console.log('[pageerror]', e.message); });
});

test('A 구 화면 + 새 API(키 없는 세션) — 목록·상세가 같은 숫자, 화면 오류 0', async ({ page }) => {
  await serve(page, 'keyless');
  await page.goto('/paper-trading');
  await expect(page.getByText('101,234.5').first()).toBeVisible();
  await page.screenshot({ path: path.join(OUT, 'A-keyless-list.png'), fullPage: true });
  await page.goto(`/paper-trading/${SID}`);
  await expect(page.getByText('101,234.5').first()).toBeVisible();
  await expect(page.getByText('Application error')).toHaveCount(0);
  await page.screenshot({ path: path.join(OUT, 'A-keyless-detail.png'), fullPage: true });
  expect(errors, errors.join(' | ')).toEqual([]);
});

test('B 구 화면 + 새 API 정지 응답(키 없는 세션, 대기 0) — 종전대로 목록으로 이동, 화면 오류 0', async ({ page }) => {
  await serve(page, 'keyless', () => ({ id: SID, status: 'STOPPED', finalAsset: 101234.5, stoppedAt: NOW, pendingCloseCount: 0, settlement: '완료' }));
  await page.goto(`/paper-trading/${SID}`);
  await page.getByRole('button', { name: '세션 중단' }).click();
  await expect(page).toHaveURL(/\/paper-trading$/);
  expect(errors, errors.join(' | ')).toEqual([]);
});

test('C (관측) 구 화면 + 새 모드 CLOSING·평가 null — 구 화면이 보이는 것을 기록만 한다', async ({ page }) => {
  await serve(page, 'closing', () => ({ id: SID, status: 'STOPPED', finalAsset: null, lastRecordedTotalKrw: 20188.5,
    assetStatus: '확정 아님 — 청산 대기 포지션 미정산', stoppedAt: NOW, pendingCloseCount: 1, settlement: '청산 대기 — 첫 유효 시세에 정산' }));
  const obs: Record<string, unknown> = {};
  await page.goto('/paper-trading');
  await page.waitForTimeout(1500);
  const listText = await page.locator('body').innerText();
  await page.screenshot({ path: path.join(OUT, 'C-closing-list.png'), fullPage: true });
  await page.goto(`/paper-trading/${SID}`);
  await page.waitForTimeout(1500);
  const detailText = await page.locator('body').innerText();
  await page.screenshot({ path: path.join(OUT, 'C-closing-detail.png'), fullPage: true });
  let stopUrl = '';
  const btn = page.getByRole('button', { name: '세션 중단' });
  if (await btn.count()) { await btn.click(); await page.waitForTimeout(1500); stopUrl = page.url(); }
  const afterStop = await page.locator('body').innerText();
  for (const [name, t] of [['list', listText], ['detail', detailText], ['afterStop', afterStop]] as const) {
    obs[name] = { appError: t.includes('Application error'), hasNaN: /NaN/.test(t), zeroPct: /(^|[^0-9.])0\.00%/.test(t),
      evalUnavailable: t.includes('평가 불가'), closingWord: t.includes('CLOSING') || t.includes('청산 대기'),
      shows20188: t.includes('20,188.5'), excerpt: t.replace(/\s+/g, ' ').slice(0, 600) };
  }
  obs.pageErrors = [...errors];
  obs.stopUrl = stopUrl;
  fs.writeFileSync(path.join(OUT, 'C-observation.json'), JSON.stringify(obs, null, 1));
  console.log('PAPERCOMPAT C ' + JSON.stringify(obs));
});
