import { defineConfig } from '@playwright/test';
import { AUTH_PASSWORD, AUTH_SECRET, STORAGE_STATE } from './e2e/auth-fixtures';

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  use: {
    baseURL: 'http://localhost:3000',
    headless: true,
    // proxy.ts 인증 우회를 위해 globalSetup에서 받아둔 세션 쿠키를 재사용
    storageState: STORAGE_STATE,
  },
  webServer: {
    // env로 주입하므로 셸 프리픽스(VAR=x cmd)를 쓰지 않는다 — Windows에서도 동작
    command: 'npm run dev',
    env: {
      NEXT_PUBLIC_USE_MOCK: 'true',
      AUTH_PASSWORD,
      AUTH_SECRET,
    },
    url: 'http://localhost:3000',
    reuseExistingServer: !process.env.CI,
    timeout: 120000,
  },
});
