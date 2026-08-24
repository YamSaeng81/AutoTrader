import { test, expect } from '@playwright/test';

/** 전략 카드 셀렉터 — 목록 카드에만 붙는 클래스 조합 */
const CARD = '.cursor-pointer';

/** /strategies 로 이동 후 로딩 스피너가 사라질 때까지 대기 */
async function gotoStrategies(page: import('@playwright/test').Page) {
  await page.goto('/strategies');
  await page.waitForFunction(() => !document.querySelector('.animate-spin'), { timeout: 10000 });
}

test.describe('전략 관리 페이지', () => {
  test('/strategies 접속 → 페이지 타이틀 렌더링 확인', async ({ page }) => {
    await page.goto('/strategies');
    // 같은 문구가 사이드바 링크·모바일 앱바·라우트 어나운서에도 있으므로 h1으로 좁힌다
    await expect(page.getByRole('heading', { name: '전략 관리' })).toBeVisible();
    await expect(page.getByText('사용 가능한 트레이딩 전략을 조회하고 파라미터를 설정합니다.')).toBeVisible();
  });

  test('/strategies → 전략 파라미터 패널 렌더링 확인', async ({ page }) => {
    await page.goto('/strategies');

    // 우측 설정 패널 헤더 확인
    await expect(page.getByText('전략 파라미터')).toBeVisible();
  });

  test('/strategies → 초기 상태에서 전략 선택 안내 메시지 확인', async ({ page }) => {
    await page.goto('/strategies');

    // 로딩 완료 대기
    await page.waitForFunction(() => {
      const spinner = document.querySelector('.animate-spin');
      return !spinner;
    }, { timeout: 10000 });

    // 선택 안내 메시지 또는 전략 카드 중 하나가 있어야 함
    const hasHint = await page.getByText('좌측에서 전략을 선택해주세요.').isVisible().catch(() => false);
    const hasCards = (await page.locator('.grid .cursor-pointer').count()) > 0;

    // 전략 카드가 없으면(빈 상태) 또는 카드가 있으면 테스트 통과
    expect(hasHint || hasCards).toBe(true);
  });

  test('/strategies → 전략 목록 렌더링 후 최소 1개 전략 카드 존재', async ({ page }) => {
    await gotoStrategies(page);

    // e2e 는 항상 mock 모드(NEXT_PUBLIC_USE_MOCK=true)로 돌고 MSW 가 목록을 채워주므로
    // 카드가 0개면 그건 정상 상태가 아니라 회귀다. "빈 상태 메시지도 통과" 로 두면
    // 목이 죽어도 초록불이 뜬다 — 08-06 MSW 사고가 그렇게 넘어갔다.
    await expect(page.locator(CARD)).not.toHaveCount(0);
  });

  test('/strategies → 전략 카드 클릭 시 선택 강조 스타일 적용', async ({ page }) => {
    await gotoStrategies(page);

    const firstCard = page.locator(CARD).first();
    await firstCard.click();

    // 선택 시 border-indigo-500 클래스가 적용되어야 함
    await expect(firstCard).toHaveClass(/border-indigo-500/);
  });

  test('/strategies → 전략 카드에 상태 배지(사용 가능/구현 예정) 존재', async ({ page }) => {
    await gotoStrategies(page);

    const badge = page.locator(CARD).first().locator('span').filter({ hasText: /사용 가능|구현 예정/ });
    await expect(badge).toBeVisible();
  });

  test('/strategies → 전략 카드에 "설정 및 백테스트 지원" 텍스트 존재', async ({ page }) => {
    await gotoStrategies(page);

    await expect(page.locator(CARD).first().getByText('설정 및 백테스트 지원')).toBeVisible();
  });

  test('/strategies → "복합 전략" 탭 전환 시 복합 전략 카드가 나온다', async ({ page }) => {
    await gotoStrategies(page);

    await page.getByRole('button', { name: '복합 전략' }).click();
    await expect(page.locator(CARD)).not.toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'COMPOSITE_BREAKOUT', exact: true })).toBeVisible();
  });
});
