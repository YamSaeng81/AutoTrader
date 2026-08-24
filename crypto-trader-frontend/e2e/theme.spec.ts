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

  // 미사용 컴포넌트였던 Header.tsx 는 2026-08-20 삭제 — 해당 테스트도 함께 제거했다.

  test('라이트 모드로 재방문해도 다크 화면이 한 순간도 보이지 않는다', async ({ page }) => {
    // 로드가 끝난 뒤의 상태만 보면 이 회귀는 안 잡힌다 — 옛 구현도 최종적으로는
    // 라이트로 수렴했고, 문제는 그 사이에 dark 가 한 프레임 보이는 것이었다.
    // 그래서 <html> 의 class 변화를 처음부터 감시해 dark 가 "한 번이라도" 붙었는지 본다.
    await page.addInitScript(() => {
      localStorage.setItem('theme', 'light');
      const w = window as unknown as { __darkSeen?: boolean };
      w.__darkSeen = false;
      const check = () => {
        if (document.documentElement.classList.contains('dark')) w.__darkSeen = true;
      };
      new MutationObserver(check).observe(document.documentElement, {
        attributes: true,
        attributeFilter: ['class'],
      });
      check();
    });

    await page.goto('/');
    await expect(page.locator('html')).not.toHaveClass(/dark/);

    const darkSeen = await page.evaluate(
      () => (window as unknown as { __darkSeen?: boolean }).__darkSeen
    );
    expect(darkSeen).toBe(false);
  });

  test('다크 모드는 React 하이드레이션 전에 이미 적용돼 있다', async ({ page }) => {
    // 기본값이 dark 인데 SSR HTML 에는 dark 클래스가 없다. React 가 마운트된 뒤에
    // 클래스를 붙이면 그 사이에 흰 배경(bg-slate-50)이 먼저 칠해진다.
    // app/layout.tsx 의 블로킹 인라인 스크립트가 그걸 막는다 —
    // 스크립트가 빠지면 DOMContentLoaded 시점에 dark 가 없어 이 테스트가 깨진다.
    await page.addInitScript(() => {
      localStorage.setItem('theme', 'dark');
      const w = window as unknown as { __darkAtDcl?: boolean };
      document.addEventListener('DOMContentLoaded', () => {
        w.__darkAtDcl = document.documentElement.classList.contains('dark');
      });
    });

    await page.goto('/', { waitUntil: 'domcontentloaded' });

    // dev 서버가 진입 직후 한 번 더 내비게이션할 수 있으므로 값이 기록될 때까지 기다린 뒤 읽는다
    await page.waitForFunction(
      () => (window as unknown as { __darkAtDcl?: boolean }).__darkAtDcl !== undefined
    );
    const darkAtDcl = await page.evaluate(
      () => (window as unknown as { __darkAtDcl?: boolean }).__darkAtDcl
    );
    expect(darkAtDcl).toBe(true);
  });
});
