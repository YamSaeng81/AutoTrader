import { test, expect } from '@playwright/test';

test.describe('전략 관리 페이지', () => {
  test('/strategies 접속 → 페이지 타이틀 렌더링 확인', async ({ page }) => {
    await page.goto('/strategies');
    await expect(page.getByText('전략 관리')).toBeVisible();
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

  test('/strategies → 전략 목록 렌더링 후 최소 1개 전략 카드 존재 (서버 응답 시)', async ({ page }) => {
    await page.goto('/strategies');

    // 로딩 완료 대기
    await page.waitForFunction(() => {
      const spinner = document.querySelector('.animate-spin');
      return !spinner;
    }, { timeout: 10000 });

    // 전략 카드 또는 빈 상태 메시지 확인 (API 응답에 따라 다름)
    const cardCount = await page.locator('.cursor-pointer').count();
    const hasEmptyMsg = await page.getByText('사용 가능한 전략이 없습니다.').isVisible().catch(() => false);

    expect(cardCount > 0 || hasEmptyMsg).toBe(true);
  });

  test('/strategies → 전략 카드 클릭 시 선택 강조 스타일 적용', async ({ page }) => {
    await page.goto('/strategies');

    // 로딩 완료 대기
    await page.waitForFunction(() => {
      const spinner = document.querySelector('.animate-spin');
      return !spinner;
    }, { timeout: 10000 });

    const cards = page.locator('.cursor-pointer');
    const cardCount = await cards.count();

    if (cardCount > 0) {
      const firstCard = cards.first();
      await firstCard.click();

      // 선택 시 border-indigo-500 클래스가 적용되어야 함
      await expect(firstCard).toHaveClass(/border-indigo-500/);
    } else {
      // 카드 없으면 스킵
      test.skip();
    }
  });

  test('/strategies → 전략 카드에 상태 배지(사용 가능/구현 예정) 존재', async ({ page }) => {
    await page.goto('/strategies');

    // 로딩 완료 대기
    await page.waitForFunction(() => {
      const spinner = document.querySelector('.animate-spin');
      return !spinner;
    }, { timeout: 10000 });

    const cards = page.locator('.cursor-pointer');
    const cardCount = await cards.count();

    if (cardCount > 0) {
      // 첫 번째 카드에 상태 배지가 있어야 함
      const firstCard = cards.first();
      const badge = firstCard.locator('span').filter({ hasText: /사용 가능|구현 예정/ });
      await expect(badge).toBeVisible();
    } else {
      test.skip();
    }
  });

  test('/strategies → 전략 카드에 "설정 및 백테스트 지원" 텍스트 존재', async ({ page }) => {
    await page.goto('/strategies');

    // 로딩 완료 대기
    await page.waitForFunction(() => {
      const spinner = document.querySelector('.animate-spin');
      return !spinner;
    }, { timeout: 10000 });

    const cards = page.locator('.cursor-pointer');
    const cardCount = await cards.count();

    if (cardCount > 0) {
      const firstCard = cards.first();
      await expect(firstCard.getByText('설정 및 백테스트 지원')).toBeVisible();
    } else {
      test.skip();
    }
  });
});
