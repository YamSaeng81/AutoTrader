import { test, expect, type Page, type Route } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

/**
 * 총괄 v1.153 G — PAPER 화면 실제 렌더링(Next dev + 실제 브라우저). 서버는 띄우지 않고 /api/proxy/** 를 가로채
 * PaperTradingController 의 응답 필드 이름(toSessionSummaryMap·toBalanceMap·toPositionMap·toPendingPositionMap·stop·stop-all)대로 답한다.
 * 값은 시험이 정한 대역이다(운영·실측 아님). 상태: ① 정상 보유 ② 청산 대기(CLOSING)·평가 null ③ 첫 시세 뒤 정산 ④ 정지 응답 대기 수.
 * 화면 사진은 OUT 폴더에 남긴다.
 */
const OUT = process.env.PAPER_RENDER_OUT ?? path.join(__dirname, '..', 'test-results', 'paper-render');
fs.mkdirSync(OUT, { recursive: true });

const SID = 9101;
const NOW = '2026-11-12T00:00:00Z';

type State = 'normal' | 'closing' | 'settled';

function session(state: State) {
  const pending = state === 'closing' ? 1 : 0;
  const base = {
    id: SID, strategyName: 'DAILY_SMA20', coinPair: 'KRW-BTC', timeframe: 'H1',
    status: state === 'settled' ? 'STOPPED' : 'RUNNING',
    availableKrw: state === 'settled' ? 20150.12 : 200, initialCapital: 20200,
    realizedPnl: state === 'settled' ? -49.88 : 0, totalFee: state === 'settled' ? 20 : 10,
    startedAt: '2026-11-11T00:00:00Z', stoppedAt: state === 'settled' ? NOW : null,
    strategyParams: { executionMode: 'LIVE_ALIGNED' },
    pendingCloseCount: pending,
    valuationStatus: pending > 0 ? 'PENDING_CLOSE' : 'VALUED',
  };
  if (state === 'closing') {
    return { ...base, totalAssetKrw: null, totalReturnPct: null, lastRecordedTotalKrw: 20188.5,
      lastRecordedNote: '마지막 기록 총자산 — 기록 시각 미상, 현재 평가 아님', realizedPnlNote: '완료 거래만 — 청산 대기 포지션은 아직 완료 거래가 아님' };
  }
  return { ...base, totalAssetKrw: state === 'settled' ? 20150.12 : 20230.4, totalReturnPct: state === 'settled' ? -0.25 : 0.15 };
}

function balance(state: State) {
  const s = session(state);
  const p = state === 'closing';
  return {
    ...s,
    positionValueKrw: p ? null : state === 'settled' ? 0 : 20030.4,
    costBasisKrw: state === 'settled' ? 0 : 20000,
    unrealizedPnl: p ? null : state === 'settled' ? 0 : 30.4,
    positionPnlPct: p ? null : state === 'settled' ? 0 : 0.15,
  };
}

function positions(state: State) {
  if (state === 'normal') {
    return [{ id: 1, coinPair: 'KRW-BTC', side: 'BUY', quantity: 0.0016, avgEntryPrice: 12500000, entryPrice: 12500000,
      unrealizedPnl: 30.4, unrealizedPnlPct: 0.15, realizedPnl: 0, status: 'OPEN', openedAt: '2026-11-11T23:00:00Z', closedAt: null }];
  }
  if (state === 'closing') {
    return [{ id: 1, coinPair: 'KRW-BTC', side: 'BUY', quantity: 0.0016, avgEntryPrice: 12500000, entryPrice: 12500000,
      unrealizedPnl: null, unrealizedPnlPct: null, valuationStatus: '평가 불가 — 청산 대기(유효 시세 없음)',
      lastRecordedUnrealizedPnl: 12.3, lastRecordedUnrealizedPnlNote: '청산 판단 이전 마지막 기록값 — 기록 시각 미상, 현재 평가 아님',
      pendingExitReason: 'FORCED_STOP', exitDecidedAt: NOW, realizedPnl: null, status: 'CLOSING', openedAt: '2026-11-11T23:00:00Z', closedAt: null }];
  }
  return [{ id: 1, coinPair: 'KRW-BTC', side: 'BUY', quantity: 0.0016, avgEntryPrice: 12500000, entryPrice: 12500000,
    unrealizedPnl: 0, unrealizedPnlPct: 0, realizedPnl: -49.88, status: 'CLOSED', openedAt: '2026-11-11T23:00:00Z', closedAt: NOW }];
}

const page0 = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };

async function serve(page: Page, get: () => State, stopBody?: () => unknown, stopAllBody?: () => unknown) {
  await page.route('**/api/proxy/**', async (route: Route) => {
    const url = new URL(route.request().url());
    const p = url.pathname.replace(/^\/api\/proxy/, '');
    const m = route.request().method();
    const ok = (data: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data, error: null }) });
    const st = get();
    if (m === 'GET' && p === '/api/v1/paper-trading/sessions') return ok([session(st)]);
    if (m === 'GET' && p === `/api/v1/paper-trading/sessions/${SID}`) return ok(balance(st));
    if (m === 'GET' && p === `/api/v1/paper-trading/sessions/${SID}/positions`) return ok(positions(st));
    if (m === 'GET' && p === `/api/v1/paper-trading/sessions/${SID}/orders`) return ok(page0);
    if (m === 'GET' && p === `/api/v1/paper-trading/sessions/${SID}/chart`) return ok({ candles: [], orders: [] });
    if (m === 'POST' && p === `/api/v1/paper-trading/sessions/${SID}/stop` && stopBody) return ok(stopBody());
    if (m === 'POST' && p === '/api/v1/paper-trading/sessions/stop-all' && stopAllBody) return ok(stopAllBody());
    if (p.startsWith('/api/v1/logs/')) return ok(page0);
    console.log('[unrouted]', m, p);
    return ok([]);   // 그 밖(전략 목록·코인 등)은 빈 목록
  });
}

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.addInitScript(() => {
    const hide = () => {
      const style = document.createElement('style');
      style.textContent = 'nextjs-portal { display: none !important; }';
      document.head.appendChild(style);
    };
    if (document.head) hide();
    else document.addEventListener('DOMContentLoaded', hide);
  });
  page.on('dialog', d => d.accept());   // 정지 확인 창
  page.on('pageerror', e => console.log('[pageerror]', e.message));
});

test('① 정상 보유 — 목록·상세에 총자산·수익률 숫자, 평가 불가 표시 없음', async ({ page }) => {
  await serve(page, () => 'normal');
  await page.goto('/paper-trading');
  await expect(page.getByText('20,230.4').first()).toBeVisible();
  await expect(page.getByText('평가 불가 — 청산 대기')).toHaveCount(0);
  await page.screenshot({ path: path.join(OUT, '1-normal-list.png'), fullPage: true });
  await page.goto(`/paper-trading/${SID}`);
  await expect(page.getByText('20,230.4').first()).toBeVisible();
  await expect(page.getByText('청산 대기', { exact: false })).toHaveCount(0);
  await page.screenshot({ path: path.join(OUT, '1-normal-detail.png'), fullPage: true });
});

test('② 청산 대기(CLOSING)·평가 null — 0/0.00% 가 아니라 평가 불가, 마지막 기록값은 현재 평가 아님 표시와 함께, 보유 표에 미정산 보유', async ({ page }) => {
  await serve(page, () => 'closing');
  await page.goto('/paper-trading');
  await expect(page.getByText('평가 불가 — 청산 대기').first()).toBeVisible();
  await expect(page.getByText('0.00%')).toHaveCount(0);
  await page.screenshot({ path: path.join(OUT, '2-closing-list.png'), fullPage: true });
  await page.goto(`/paper-trading/${SID}`);
  await expect(page.getByText('평가 불가 — 청산 대기').first()).toBeVisible();
  await expect(page.getByText(/청산 대기 1건 — 유효 시세가 없어 현재 평가를 할 수 없습니다/)).toBeVisible();
  await expect(page.getByText(/마지막 기록 총자산 20,188.5 KRW/)).toBeVisible();
  await expect(page.getByText(/청산 대기 \(FORCED_STOP\) — 미정산 보유/)).toBeVisible();
  await page.screenshot({ path: path.join(OUT, '2-closing-detail.png'), fullPage: true });
});

test('③ 첫 유효 시세 뒤 정산 — 같은 화면이 다시 읽어 확정 총자산·청산 완료로 바뀐다', async ({ page }) => {
  let st: State = 'closing';
  await serve(page, () => st);
  await page.goto(`/paper-trading/${SID}`);
  await expect(page.getByText(/청산 대기 1건/)).toBeVisible();
  st = 'settled';
  await page.reload();
  await expect(page.getByText('20,150.12').first()).toBeVisible();
  await expect(page.getByText(/청산 대기 1건/)).toHaveCount(0);
  await expect(page.getByText('평가 불가 — 청산 대기')).toHaveCount(0);
  await page.screenshot({ path: path.join(OUT, '3-settled-detail.png'), fullPage: true });
});

test('④-가 단건 정지 응답 대기 1 — 상세에 머물러 대기 수·확정 아님을 보인다', async ({ page }) => {
  let st: State = 'normal';
  await serve(page, () => st, () => {
    st = 'closing';
    return { id: SID, status: 'STOPPED', finalAsset: null, lastRecordedTotalKrw: 20188.5,
      assetStatus: '확정 아님 — 청산 대기 포지션 미정산(마지막 기록 총자산, 현재 평가 아님)', stoppedAt: NOW,
      pendingCloseCount: 1, settlement: '청산 대기 — 첫 유효 시세에 정산' };
  });
  await page.goto(`/paper-trading/${SID}`);
  await page.getByRole('button', { name: '세션 중단' }).click();
  const notice = page.getByTestId('paper-stop-result');
  await expect(notice).toHaveText(`세션 #${SID} 정지 — 청산 대기 1건, 첫 유효 시세에 정산(정산 완료 아님) · 마지막 기록 총자산 20,188.5 KRW(확정 아님, 현재 평가 아님)`);
  await expect(page).toHaveURL(new RegExp(`/paper-trading/${SID}$`));
  await page.screenshot({ path: path.join(OUT, '4a-stop-single-pending.png'), fullPage: true });
});

test('④-나 단건 정지 응답 대기 0 — 종전대로 목록으로 이동', async ({ page }) => {
  let st: State = 'normal';
  await serve(page, () => st, () => {
    st = 'settled';
    return { id: SID, status: 'STOPPED', finalAsset: 20150.12, stoppedAt: NOW, pendingCloseCount: 0, settlement: '완료' };
  });
  await page.goto(`/paper-trading/${SID}`);
  await page.getByRole('button', { name: '세션 중단' }).click();
  await expect(page).toHaveURL(/\/paper-trading$/);
});

test('④-다 일괄 정지 응답 대기 2 — 목록 배너에 정지 수·대기 수, 정산 완료라고 쓰지 않음', async ({ page }) => {
  let st: State = 'normal';
  await serve(page, () => st, undefined, () => {
    st = 'closing';
    return { stoppedCount: 3, pendingCloseCount: 2, settlement: '청산 대기 2건 — 첫 유효 시세에 정산' };
  });
  await page.goto('/paper-trading');
  await page.getByRole('button', { name: '일괄 정지' }).click();
  const notice = page.getByTestId('paper-stop-result');
  await expect(notice).toHaveText('3개 세션 정지 — 청산 대기 2건, 첫 유효 시세에 정산(정산 완료 아님)');
  await page.screenshot({ path: path.join(OUT, '4c-stop-all-pending.png'), fullPage: true });
});

test('④-라 단건 정지(목록) 응답에 대기 수가 없으면 — 정산 완료로 보지 않는다', async ({ page }) => {
  await serve(page, () => 'normal', () => ({ id: SID, status: 'STOPPED', finalAsset: 20150.12, stoppedAt: NOW }));
  await page.goto('/paper-trading');
  await page.getByRole('button', { name: /중단|정지/ }).last().click();
  await expect(page.getByTestId('paper-stop-result')).toHaveText(`세션 #${SID} 정지 — 정산 상태 응답 없음(정산 완료로 보지 않음)`);
  await page.screenshot({ path: path.join(OUT, '4d-stop-single-unknown.png'), fullPage: true });
});
