import { defineConfig } from '@playwright/test';
import { AUTH_PASSWORD, AUTH_SECRET, STORAGE_STATE } from './e2e/auth-fixtures';

/**
 * 총괄 v1.159 §5 — 구 프런트(b5cd1d0)+새 API 호환 관측 전용 설정(v1.153 G 설정에서 파일·포트만 바꿈). MSW(목 모드)를 켜지 않고, 시험이 브라우저의 /api/proxy/** 요청을 직접 가로채
 * 서버 응답 모양(PaperTradingController 의 필드 이름)대로 답한다. 백엔드·운영 접속 없음. 기본 e2e 설정과 포트를 나눈다.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'paper-compat-old.spec.ts',
  globalSetup: './e2e/global-setup.ts',
  retries: 0,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:3211',
    headless: true,
    storageState: STORAGE_STATE,
  },
  webServer: {
    command: 'npx next dev -p 3211',
    env: { AUTH_PASSWORD, AUTH_SECRET },
    url: 'http://localhost:3211',
    reuseExistingServer: false,
    timeout: 180000,
  },
});
