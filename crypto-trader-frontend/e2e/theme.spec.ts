import { test, expect } from '@playwright/test';

test.describe('다크모드 테마 토글', () => {
  test.beforeEach(async ({ page }) => {
    // 테스트 시작 전 localStorage 초기화하여 다크모드 기본값 보장
    await page.goto('/');
    await page.evaluate(() => localStorage.removeItem('theme'));
    await page.reload();
  });

  test('초기 로드 시 dark 클래스가 <html>에 존재한다', async ({ page }) => {
    // ThemeProvider 기본값이 'dark'이므로 초기에 dark 클래스가 있어야 함
    await expect(page.locator('html')).toHaveClass(/dark/);
  });

  test('Sidebar 테마 토글 버튼 클릭 → dark 클래스 제거', async ({ page }) => {
    // dark 클래스 초기 확인
    await expect(page.locator('html')).toHaveClass(/dark/);

    // Sidebar 하단의 테마 토글 버튼 클릭 (title: '라이트 모드')
    const toggleBtn = page.getByTitle('라이트 모드');
    await toggleBtn.click();

    // dark 클래스 제거 확인
    await expect(page.locator('html')).not.toHaveClass(/dark/);
  });

  test('라이트 모드에서 재클릭 → dark 클래스 복귀', async ({ page }) => {
    // 라이트 모드로 전환
    await page.getByTitle('라이트 모드').click();
    await expect(page.locator('html')).not.toHaveClass(/dark/);

    // 다크 모드로 재전환
    const darkToggleBtn = page.getByTitle('다크 모드');
    await darkToggleBtn.click();
    await expect(page.locator('html')).toHaveClass(/dark/);
  });

  test('테마 상태가 localStorage에 저장된다', async ({ page }) => {
    // 라이트 모드로 전환
    await page.getByTitle('라이트 모드').click();
    await expect(page.locator('html')).not.toHaveClass(/dark/);

    // localStorage 확인
    const stored = await page.evaluate(() => localStorage.getItem('theme'));
    expect(stored).toBe('light');
  });

  test('localStorage의 테마 값이 페이지 새로고침 후에도 유지된다', async ({ page }) => {
    // 라이트 모드로 전환
    await page.getByTitle('라이트 모드').click();

    // 새로고침
    await page.reload();

    // 라이트 모드 유지 확인 (ThemeProvider가 localStorage에서 읽어 적용)
    // useEffect로 적용되므로 잠시 대기
    await page.waitForTimeout(200);
    await expect(page.locator('html')).not.toHaveClass(/dark/);
  });

  test('Header의 테마 토글 버튼도 동일하게 동작한다', async ({ page }) => {
    await page.goto('/');

    // dark 초기 상태 확인
    await expect(page.locator('html')).toHaveClass(/dark/);

    // Header의 테마 토글 버튼 (aria-label로 선택)
    const headerToggle = page.getByRole('button', { name: '라이트 모드로 전환' });
    await headerToggle.click();

    await expect(page.locator('html')).not.toHaveClass(/dark/);
  });
});
