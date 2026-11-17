// 로컬 실행용(커밋 안 함, e2e/.auth 는 Git 무시) — 포트 3000 이 Windows 예약 범위(2954~3053)라 같은 설정을 3311 에서 돈다. 그 밖은 playwright.config.ts 와 같다.
import { defineConfig } from '@playwright/test';
import path from 'node:path';
import { AUTH_PASSWORD, AUTH_SECRET, STORAGE_STATE } from '../auth-fixtures';

export default defineConfig({
  testDir: path.join(__dirname, '..'),
  globalSetup: path.join(__dirname, '..', 'global-setup.ts'),
  retries: 0,
  reporter: 'list',
  use: { baseURL: 'http://localhost:3311', headless: true, storageState: STORAGE_STATE },
  webServer: {
    command: 'npx next dev -p 3311',
    cwd: path.join(__dirname, '..', '..'),
    // 개발 목(MSW)은 끈다 — 켜면 요청이 서비스 워커를 거쳐 page.route 가로채기를 지나친다(1회차 '세션을 찾을 수 없습니다').
    env: { AUTH_PASSWORD, AUTH_SECRET },
    url: 'http://localhost:3311',
    reuseExistingServer: false,
    timeout: 180000,
  },
});
