import path from 'node:path';

/** 테스트 전용 자격증명 — 운영 값과 무관. playwright.config.ts와 global-setup.ts가 공유한다. */
export const AUTH_PASSWORD = 'e2e-test-password';
export const AUTH_SECRET = 'e2e-test-secret';

export const STORAGE_STATE = path.join(__dirname, '.auth', 'state.json');
