import { defineConfig } from '@playwright/test';
import { AUTH_PASSWORD, AUTH_SECRET, STORAGE_STATE } from './e2e/auth-fixtures';

/**
 * 총괄 v1.153 G — PAPER 화면 렌더링 확인 전용 설정. MSW(목 모드)를 켜지 않고, 시험이 브라우저의 /api/proxy/** 요청을 직접 가로채
 * 서버 응답 모양(PaperTradingController 의 필드 이름)대로 답한다. 백엔드·운영 접속 없음. 기본 e2e 설정과 포트를 나눈다.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'paper-render.spec.ts',
  globalSetup: './e2e/global-setup.ts',
  retries: 0,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:3210',
    headless: true,
    storageState: STORAGE_STATE,
  },
  webServer: {
    command: 'npx next dev -p 3210',
    env: { AUTH_PASSWORD, AUTH_SECRET },
    url: 'http://localhost:3210',
    reuseExistingServer: false,
    timeout: 180000,
  },
});
