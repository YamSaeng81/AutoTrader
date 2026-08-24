import { test, expect } from '@playwright/test';

const DESKTOP = { width: 1440, height: 900 };
const MOBILE = { width: 390, height: 844 };

/**
 * Next dev 서버의 개발자 오버레이(<nextjs-portal>)가 좌하단에 고정으로 떠서
 * 접힌 사이드바(w-16)의 하단 버튼을 덮어 클릭을 가로챈다. 운영 빌드에는 없는
 * 개발 전용 요소이므로 e2e에서만 숨긴다.
 */
test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    const hide = () => {
      const style = document.createElement('style');
      style.textContent = 'nextjs-portal { display: none !important; }';
      document.head.appendChild(style);
    };
    if (document.head) hide();
    else document.addEventListener('DOMContentLoaded', hide);
  });
});

test.describe('데스크톱 네비게이션', () => {
  test.use({ viewport: DESKTOP });

  test('앱 진입 시 대시보드(/) 로드 확인', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL('/');
    await expect(page.getByRole('link', { name: '대시보드' })).toBeVisible();
  });

  test('첫 그룹(백테스트·모의투자)이 기본으로 펼쳐진다', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('link', { name: '백테스트 이력' })).toBeVisible();
    await expect(page.getByRole('link', { name: '새 백테스트' })).toBeVisible();
    await expect(page.getByRole('link', { name: '전략 비교' })).toBeVisible();
    await expect(page.getByRole('link', { name: '데이터 수집' })).toBeVisible();
  });

  test('5개 대분류 그룹 헤더가 모두 보인다', async ({ page }) => {
    await page.goto('/');
    for (const label of ['백테스트 · 모의투자', '실전매매', '전략관리', '분석', '설정']) {
      await expect(page.getByRole('button', { name: new RegExp(label) })).toBeVisible();
    }
  });

  test('그룹 헤더 클릭 → 접힌 그룹이 펼쳐진다', async ({ page }) => {
    await page.goto('/');
    // '전략관리'는 기본 접힘 상태
    await expect(page.getByRole('link', { name: '전략 관리' })).toBeHidden();

    await page.getByRole('button', { name: /^전략관리/ }).click();
    await expect(page.getByRole('link', { name: '전략 관리' })).toBeVisible();
  });

  test('백테스트 이력 메뉴 클릭 → /backtest 이동', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('link', { name: '백테스트 이력' }).click();
    await expect(page).toHaveURL('/backtest');
  });

  test('새 백테스트 메뉴 클릭 → /backtest/new 이동', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('link', { name: '새 백테스트' }).click();
    await expect(page).toHaveURL('/backtest/new');
  });

  test('전략 비교 메뉴 클릭 → /backtest/compare 이동', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('link', { name: '전략 비교' }).click();
    await expect(page).toHaveURL('/backtest/compare');
    // Next의 라우트 어나운서(#__next-route-announcer__)에도 같은 문구가 들어가므로 h1으로 좁힌다
    await expect(page.getByRole('heading', { name: '전략 비교 분석' })).toBeVisible();
  });

  test('데이터 수집 메뉴 클릭 → /data 이동', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('link', { name: '데이터 수집' }).click();
    await expect(page).toHaveURL('/data');
  });

  test('전략 로그는 "분석" 그룹에 있다 → /logs 이동', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: /^분석/ }).click();
    await page.getByRole('link', { name: '전략 로그' }).click();
    await expect(page).toHaveURL('/logs');
  });

  test('현재 경로가 속한 그룹은 자동으로 펼쳐진 채 진입한다', async ({ page }) => {
    await page.goto('/trading/risk');
    await expect(page.getByRole('link', { name: '리스크 설정' })).toBeVisible();
  });

  test('Sidebar 접기 버튼 클릭 → w-16 상태로 전환 (sidebarCollapsed)', async ({ page }) => {
    await page.goto('/');

    const sidebar = page.locator('div.bg-slate-900.border-r').first();
    await expect(sidebar).toHaveClass(/w-64/);

    await page.getByTitle('사이드바 접기').click();
    await expect(sidebar).toHaveClass(/w-16/);
  });

  test('Sidebar 접기 후 펼치기 버튼 클릭 → w-64 상태로 복귀', async ({ page }) => {
    await page.goto('/');

    const sidebar = page.locator('div.bg-slate-900.border-r').first();

    await page.getByTitle('사이드바 접기').click();
    await expect(sidebar).toHaveClass(/w-16/);

    await page.getByTitle('사이드바 펼치기').click();
    await expect(sidebar).toHaveClass(/w-64/);
  });

  test('모바일 전용 UI는 데스크톱에서 보이지 않는다', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('button', { name: '메뉴 열기' })).toBeHidden();
  });
});

test.describe('모바일 네비게이션', () => {
  test.use({ viewport: MOBILE });

  test('사이드바 대신 상단바 + 하단 탭바가 보인다', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('button', { name: '메뉴 열기' })).toBeVisible();
    // 하단 탭 5개 대분류 + 홈
    const tabs = page.getByRole('navigation', { name: '빠른 메뉴' });
    for (const label of ['홈', '검증', '실전', '전략', '분석', '설정']) {
      await expect(tabs.getByText(label, { exact: true })).toBeVisible();
    }
    // 데스크톱 고정 사이드바는 숨김
    await expect(page.getByTitle('사이드바 접기')).toBeHidden();
  });

  test('본문이 상단바에 가리지 않는다 (main 상단 여백)', async ({ page }) => {
    await page.goto('/');
    const box = await page.locator('main').boundingBox();
    expect(box).not.toBeNull();
    // main 콘텐츠는 h-14(56px) 앱바 아래에서 시작해야 한다
    expect((await page.locator('main').evaluate(el => parseFloat(getComputedStyle(el).paddingTop)))).toBeGreaterThanOrEqual(56);
  });

  test('하단 탭 "실전" 탭 → 하위 메뉴 시트가 열리고 이동한다', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('navigation', { name: '빠른 메뉴' }).getByRole('button', { name: '실전' }).click();

    await expect(page.getByRole('dialog', { name: /실전매매 메뉴/ })).toBeVisible();
    await page.getByRole('link', { name: '동적 멀티코인' }).click();
    await expect(page).toHaveURL('/trading/dynamic');

    // 이동 후 시트는 닫힌다
    await expect(page.getByRole('dialog', { name: /실전매매 메뉴/ })).toBeHidden();
  });

  test('햄버거 → 전체 메뉴 드로어가 열리고 백드롭 클릭으로 닫힌다', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: '메뉴 열기' }).click();

    const drawer = page.getByRole('dialog', { name: '전체 메뉴' });
    await expect(drawer).toBeVisible();
    await expect(drawer.getByRole('link', { name: '대시보드' })).toBeVisible();

    await page.getByRole('button', { name: '메뉴 닫기' }).click();
    await expect(drawer.getByRole('link', { name: '대시보드' })).toBeHidden();
  });

  test('드로어에서 그룹 펼쳐 이동 → /strategies', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: '메뉴 열기' }).click();

    const drawer = page.getByRole('dialog', { name: '전체 메뉴' });
    await drawer.getByRole('button', { name: /^전략관리/ }).click();
    await drawer.getByRole('link', { name: '전략 관리' }).click();

    await expect(page).toHaveURL('/strategies');
  });

  test('페이지가 가로로 스크롤되지 않는다', async ({ page }) => {
    await page.goto('/');
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth
    );
    expect(overflow).toBeLessThanOrEqual(1);
  });
});
