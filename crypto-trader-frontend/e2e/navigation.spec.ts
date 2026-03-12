import { test, expect } from '@playwright/test';

test.describe('네비게이션 기본 동작', () => {
  test('앱 진입 시 대시보드(/) 로드 확인', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL('/');
    await expect(page.getByText('대시보드')).toBeVisible();
  });

  test('Sidebar에 주요 메뉴 항목이 렌더링된다', async ({ page }) => {
    await page.goto('/');
    // 사이드바 내 네비게이션 링크 존재 확인
    await expect(page.getByRole('link', { name: '백테스트 이력' })).toBeVisible();
    await expect(page.getByRole('link', { name: '새 백테스트' })).toBeVisible();
    await expect(page.getByRole('link', { name: '전략 비교' })).toBeVisible();
    await expect(page.getByRole('link', { name: '데이터 수집' })).toBeVisible();
    await expect(page.getByRole('link', { name: '로그' })).toBeVisible();
    await expect(page.getByRole('link', { name: '전략 관리' })).toBeVisible();
  });

  test('백테스트 이력 메뉴 클릭 → /backtest 이동', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('link', { name: '백테스트 이력' }).click();
    await expect(page).toHaveURL('/backtest');
    await expect(page.getByText('백테스트 이력')).toBeVisible();
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
    await expect(page.getByText('전략 비교 분석')).toBeVisible();
  });

  test('데이터 수집 메뉴 클릭 → /data 이동', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('link', { name: '데이터 수집' }).click();
    await expect(page).toHaveURL('/data');
  });

  test('로그 메뉴 클릭 → /logs 이동', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('link', { name: '로그' }).click();
    await expect(page).toHaveURL('/logs');
  });

  test('전략 관리 메뉴 클릭 → /strategies 이동', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('link', { name: '전략 관리' }).click();
    await expect(page).toHaveURL('/strategies');
  });

  test('Sidebar 접기 버튼 클릭 → w-16 상태로 전환 (sidebarCollapsed)', async ({ page }) => {
    await page.goto('/');

    // 사이드바가 펼쳐진 상태(w-64) 확인
    const sidebar = page.locator('div.bg-slate-900.border-r').first();
    await expect(sidebar).toHaveClass(/w-64/);

    // 접기 버튼 클릭 (ChevronLeft 아이콘이 있는 버튼)
    const collapseBtn = page.getByTitle('사이드바 접기');
    await collapseBtn.click();

    // w-16 상태로 전환 확인
    await expect(sidebar).toHaveClass(/w-16/);
  });

  test('Sidebar 접기 후 펼치기 버튼 클릭 → w-64 상태로 복귀', async ({ page }) => {
    await page.goto('/');

    const sidebar = page.locator('div.bg-slate-900.border-r').first();

    // 접기
    await page.getByTitle('사이드바 접기').click();
    await expect(sidebar).toHaveClass(/w-16/);

    // 펼치기
    await page.getByTitle('사이드바 펼치기').click();
    await expect(sidebar).toHaveClass(/w-64/);
  });
});
