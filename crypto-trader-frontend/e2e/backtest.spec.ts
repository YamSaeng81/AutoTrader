import { test, expect } from '@playwright/test';

test.describe('백테스트 워크플로우', () => {
  test('/backtest 페이지 접속 → 페이지 타이틀 렌더링 확인', async ({ page }) => {
    await page.goto('/backtest');
    await expect(page.getByText('백테스트 이력')).toBeVisible();
    await expect(page.getByText('과거 시뮬레이션 결과 목록입니다.')).toBeVisible();
  });

  test('/backtest 페이지 → 새 백테스트 버튼 존재 확인', async ({ page }) => {
    await page.goto('/backtest');
    const newBtn = page.getByRole('link', { name: '새 백테스트' });
    await expect(newBtn).toBeVisible();
    await expect(newBtn).toHaveAttribute('href', '/backtest/new');
  });

  test('/backtest 페이지 → 목록 테이블 또는 빈 상태 메시지 렌더링 확인', async ({ page }) => {
    await page.goto('/backtest');

    // 로딩 완료 대기 (스피너가 사라지거나 콘텐츠가 나타날 때까지)
    await page.waitForFunction(() => {
      const spinner = document.querySelector('.animate-spin');
      return !spinner;
    }, { timeout: 10000 });

    // 테이블이 있거나 빈 상태 메시지 중 하나가 있어야 함
    const hasTable = await page.locator('table').isVisible().catch(() => false);
    const hasEmptyMsg = await page.getByText('기록이 없습니다. 첫 백테스트를 실행해보세요.').isVisible().catch(() => false);

    expect(hasTable || hasEmptyMsg).toBe(true);
  });

  test('/backtest/new 접속 → 폼 필드 존재 확인', async ({ page }) => {
    await page.goto('/backtest/new');

    // 페이지 제목
    await expect(page.getByText('새 백테스트 설정')).toBeVisible();

    // 전략 유형 셀렉트
    const strategySelect = page.locator('select').first();
    await expect(strategySelect).toBeVisible();

    // 날짜 입력 필드
    const dateInputs = page.locator('input[type="date"]');
    await expect(dateInputs).toHaveCount(2);

    // 백테스트 실행 버튼
    await expect(page.getByRole('button', { name: /백테스트 실행/ })).toBeVisible();
  });

  test('/backtest/new → 타임프레임 셀렉트 옵션 확인', async ({ page }) => {
    await page.goto('/backtest/new');

    // 타임프레임 select (3번째 select)
    const timeframeSelect = page.locator('select').nth(2);
    await expect(timeframeSelect).toBeVisible();

    // 옵션 확인
    const options = await timeframeSelect.locator('option').allTextContents();
    expect(options).toContain('1분 (M1)');
    expect(options).toContain('1시간 (H1)');
    expect(options).toContain('1일 (D1)');
  });

  test('/backtest/new → 초기 자금 입력 필드 존재 확인', async ({ page }) => {
    await page.goto('/backtest/new');

    const capitalInput = page.locator('input[type="number"]');
    await expect(capitalInput).toBeVisible();

    // 기본값 확인
    await expect(capitalInput).toHaveValue('10000000');
  });

  test('/backtest/compare 접속 → 비교 UI 렌더링 확인', async ({ page }) => {
    await page.goto('/backtest/compare');

    // 페이지 제목
    await expect(page.getByText('전략 비교 분석')).toBeVisible();
    await expect(page.getByText('2~6개 백테스트 결과를 선택하여 성과를 비교합니다.')).toBeVisible();
  });

  test('/backtest/compare → 백테스트 선택 UI 컨테이너 확인', async ({ page }) => {
    await page.goto('/backtest/compare');

    // 선택 섹션 헤더 확인
    const selectionHeader = page.getByText(/백테스트 선택/);
    await expect(selectionHeader).toBeVisible();
  });

  test('/backtest/compare → 데이터 없을 때 안내 메시지 확인', async ({ page }) => {
    await page.goto('/backtest/compare');

    // 로딩 완료 대기
    await page.waitForFunction(() => {
      const spinner = document.querySelector('.animate-spin');
      return !spinner;
    }, { timeout: 10000 });

    // 데이터 없음 메시지 또는 선택 버튼 중 하나가 있어야 함
    const hasNoDataMsg = await page.getByText('비교할 백테스트 결과가 없습니다. 먼저 백테스트를 실행하세요.').isVisible().catch(() => false);
    const hasItems = await page.locator('button').count() > 0;

    expect(hasNoDataMsg || hasItems).toBe(true);
  });

  test('/backtest/compare → 2개 미만 선택 시 안내 문구 없음 (초기 상태)', async ({ page }) => {
    await page.goto('/backtest/compare');

    // 초기 상태에서는 "비교하려면 최소 2개 이상" 메시지가 없어야 함
    const minSelectMsg = page.getByText('비교하려면 최소 2개 이상 선택해 주세요.');
    await expect(minSelectMsg).not.toBeVisible();
  });
});
