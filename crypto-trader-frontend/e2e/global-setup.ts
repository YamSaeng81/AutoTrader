import { request, type FullConfig } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { AUTH_PASSWORD, STORAGE_STATE } from './auth-fixtures';

/**
 * proxy.ts가 auth_session 쿠키를 요구하므로, 테스트 전에 한 번 로그인해
 * storageState로 저장한다. 자격증명은 테스트 전용 값이다(운영 비밀번호 아님).
 */
export default async function globalSetup(config: FullConfig) {
    const baseURL = config.projects[0]?.use?.baseURL ?? 'http://localhost:3000';

    const ctx = await request.newContext({ baseURL });
    const res = await ctx.post('/api/auth/login', { data: { password: AUTH_PASSWORD } });
    if (!res.ok()) {
        throw new Error(
            `e2e 로그인 실패 (${res.status()}). playwright.config.ts webServer.env의 AUTH_PASSWORD/AUTH_SECRET을 확인하세요.`
        );
    }

    fs.mkdirSync(path.dirname(STORAGE_STATE), { recursive: true });
    await ctx.storageState({ path: STORAGE_STATE });
    await ctx.dispose();
}
