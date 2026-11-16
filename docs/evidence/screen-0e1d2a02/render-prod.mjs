// 운영 frontend 9d7f123a 브라우저 표시 확인(총괄 v1.176 §4-3) — 읽기만, 비열람 범위 보호.
// 사용: printf '%s\n' '<로그인 비밀번호>' | node render-prod.mjs <기준 URL(SSH 터널)> <출력 폴더> <종이 세션 ID(522~587 밖)>
//  - 비밀번호는 표준 입력 첫 줄에서만 읽고, 로그인 입력란에 넣은 뒤 변수에서 지운다. 파일·출력에 쓰지 않는다.
//  - 브라우저의 모든 /api/ 요청을 가로챈다. 로그인 POST 1건과 아래 허용 목록의 GET 만 서버로 보낸다. 나머지는 브라우저 안에서 중단(abort)하고 기록한다.
//    → 사전 등록 종이 세션 522~587 의 성과·포지션·신호를 담을 수 있는 요청(종이 목록, 범위 안 ID, 세션 지정 없는 로그 등)은 서버에 닿지 않는다.
//  - 클릭은 로그인 버튼과 LIVE·DYNAMIC 목록의 '전체 세션 보기'(화면 안 필터 상태만 바꾸는 버튼, 요청 없음)뿐이다.
//    정지·시작·삭제 버튼은 누르지 않고, 비GET 은 로그인 외 모두 중단한다.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
// Playwright 는 프런트 저장소의 node_modules 에서 읽는다(PW_FROM=그 폴더 안 아무 파일 경로).
const { chromium } = createRequire(process.env.PW_FROM)('@playwright/test');

const [BASE, OUT, PID] = process.argv.slice(2);
if (!BASE || !OUT || !/^\d+$/.test(PID ?? '')) { console.error('인자: <기준 URL> <출력 폴더> <종이 세션 ID>'); process.exit(2); }
const pid = Number(PID);
if (pid >= 522 && pid <= 587) { console.error(`종이 세션 ${pid} 는 사전 등록 범위 522~587 — 거절`); process.exit(2); }
if (fs.existsSync(OUT)) { console.error(`출력 폴더가 이미 있다 — 덮어쓰지 않는다: ${OUT}`); process.exit(2); }
fs.mkdirSync(OUT, { recursive: true });

let pw = fs.readFileSync(0, 'utf8').split(/\r?\n/)[0];
if (!pw) { console.error('표준 입력 첫 줄에 비밀번호가 없다'); process.exit(2); }

const LIVE = 203, DYN = 93;
const ALLOW = [
  /^\/api\/v1\/trading\/sessions$/,                                         // LIVE 목록(사전 등록 종이 세션 없음)
  new RegExp(`^/api/v1/trading/sessions/${LIVE}(/(positions|orders|chart))?$`),
  /^\/api\/v1\/dynamic-sessions$/,                                           // DYNAMIC 목록(사전 등록 종이 세션 없음)
  new RegExp(`^/api/v1/dynamic-sessions/${DYN}(/positions)?$`),
  new RegExp(`^/api/v1/paper-trading/sessions/${pid}(/(positions|orders|chart))?$`),
];
const LOGS_OK = { LIVE: LIVE, DYNAMIC: DYN, PAPER: pid };   // 전략 로그는 유형과 세션 ID 가 둘 다 지정되고 허용 세션일 때만

const log = [];
const t = () => new Date().toISOString();
function decide(req) {
  const u = new URL(req.url());
  if (!u.pathname.startsWith('/api/')) return 'pass';
  if (u.pathname === '/api/auth/login') return req.method() === 'POST' ? 'allow' : 'block';
  if (req.method() !== 'GET') return 'block';
  if (!u.pathname.startsWith('/api/proxy/')) return 'block';
  const p = u.pathname.slice('/api/proxy'.length);
  if (ALLOW.some(r => r.test(p))) return 'allow';
  if (p === '/api/v1/logs/strategy') {
    const ty = u.searchParams.get('sessionType'), id = Number(u.searchParams.get('sessionId'));
    if (ty in LOGS_OK && LOGS_OK[ty] === id) return 'allow';
  }
  return 'block';
}

if (process.env.DECIDE_SELFTEST === '1') {   // 운영 접속 전 판정 시험: 기대와 다른 판정이 하나라도 있으면 rc1
  const R = (method, url) => ({ method: () => method, url: () => `http://x${url}` });
  const cases = [
    ['POST', '/api/auth/login', 'allow'], ['GET', '/api/auth/login', 'block'],
    ['GET', '/api/proxy/api/v1/paper-trading/sessions', 'block'], ['GET', '/api/proxy/api/v1/paper-trading/sessions?page=0', 'block'],
    ['GET', '/api/proxy/api/v1/paper-trading/sessions/587', 'block'], ['GET', '/api/proxy/api/v1/paper-trading/sessions/522/positions', 'block'],
    ['GET', `/api/proxy/api/v1/paper-trading/sessions/${pid}`, 'allow'], ['GET', `/api/proxy/api/v1/paper-trading/sessions/${pid}/chart`, 'allow'],
    ['GET', `/api/proxy/api/v1/paper-trading/sessions/${pid}5`, 'block'], ['POST', `/api/proxy/api/v1/paper-trading/sessions/${pid}/stop`, 'block'],
    ['GET', '/api/proxy/api/v1/trading/sessions', 'allow'], ['GET', '/api/proxy/api/v1/trading/sessions/203/orders', 'allow'],
    ['GET', '/api/proxy/api/v1/trading/sessions/204', 'block'], ['POST', '/api/proxy/api/v1/trading/sessions/203/start', 'block'],
    ['GET', '/api/proxy/api/v1/dynamic-sessions', 'allow'], ['GET', '/api/proxy/api/v1/dynamic-sessions/93/positions', 'allow'],
    ['POST', '/api/proxy/api/v1/dynamic-sessions/93/stop', 'block'], ['DELETE', '/api/proxy/api/v1/dynamic-sessions/93', 'block'],
    ['GET', '/api/proxy/api/v1/logs/strategy?page=0&size=20&sessionType=LIVE&sessionId=203', 'allow'],
    ['GET', `/api/proxy/api/v1/logs/strategy?page=0&size=20&sessionType=PAPER&sessionId=${pid}`, 'allow'],
    ['GET', '/api/proxy/api/v1/logs/strategy?page=0&size=20&sessionType=PAPER&sessionId=587', 'block'],
    ['GET', '/api/proxy/api/v1/logs/strategy?page=0&size=20', 'block'], ['GET', '/api/proxy/api/v1/logs/strategy?sessionType=PAPER', 'block'],
    ['GET', '/api/proxy/api/v1/account/summary', 'block'], ['GET', '/api/proxy/api/v1/paper-trading/summary', 'block'],
    ['GET', '/api/other', 'block'], ['GET', '/trading/203', 'pass'], ['GET', '/_next/static/x.js', 'pass'],
  ];
  let bad = 0;
  for (const [m, u, exp] of cases) { const got = decide(R(m, u)); if (got !== exp) bad++; console.log(`${got === exp ? 'OK ' : 'BAD'} ${m} ${u} → ${got} (기대 ${exp})`); }
  console.log(`판정 시험 ${cases.length - bad}/${cases.length}`);
  fs.rmdirSync(OUT);
  process.exit(bad ? 1 : 0);
}

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'ko-KR', timezoneId: 'Asia/Seoul' });
await ctx.route('**/*', async route => {
  const req = route.request();
  const d = decide(req);
  if (d === 'pass') return route.continue();
  const u = new URL(req.url());
  log.push({ at: t(), decision: d, method: req.method(), path: u.pathname, query: u.search });
  return d === 'allow' ? route.continue() : route.abort('blockedbyclient');
});
const page = await ctx.newPage();
const responses = [];
page.on('response', r => { const u = new URL(r.url()); if (u.pathname.startsWith('/api/')) responses.push({ at: t(), status: r.status(), path: u.pathname }); });
const pageErrors = [];
page.on('pageerror', e => pageErrors.push({ at: t(), message: String(e.message).slice(0, 300) }));

// 1) 로그인(정상 화면 경로)
await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
await page.locator('input[type=password]').fill(pw);
pw = null;
await Promise.all([page.waitForURL(u => !u.pathname.startsWith('/login'), { timeout: 20000 }).catch(() => null), page.locator('button[type=submit]').click()]);
const loggedIn = !new URL(page.url()).pathname.startsWith('/login');
const summary = { started: t(), base: BASE, paperSession: pid, loggedIn, shots: [] };
if (!loggedIn) { summary.error = '로그인 뒤에도 /login 에 머묾 — 중단'; }

// 2) 대상 화면
const targets = [
  ['live-list', '/trading'], ['live-203', `/trading/${LIVE}`],
  ['dyn-list', '/trading/dynamic'], ['dyn-93', `/trading/dynamic/${DYN}`],
  [`paper-${pid}`, `/paper-trading/${pid}`],
];
if (loggedIn) for (const [name, p] of targets) {
  const r = await page.goto(`${BASE}${p}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(6000);   // 클라이언트 조회(5초 주기) 1회 이상
  const file = `${name}.png`;
  await page.screenshot({ path: path.join(OUT, file), fullPage: true });
  const text = (await page.locator('body').innerText()).replace(/\s+\n/g, '\n');
  fs.writeFileSync(path.join(OUT, `${name}.txt`), text);
  summary.shots.push({ name, path: p, status: r?.status() ?? null, url: page.url(), file,
    errorText: /Application error|Unhandled Runtime Error|Internal Server Error/i.test(text) });
  if (name.endsWith('-list')) {   // '운영 중만' 기본 필터 → 전체(화면 안 상태 전환)
    const b = page.getByRole('button', { name: /^전체 세션 보기/ });
    if (await b.count() === 1) {
      await b.click(); await page.waitForTimeout(1500);
      const f2 = `${name}-all.png`;
      await page.screenshot({ path: path.join(OUT, f2), fullPage: true });
      const t2 = (await page.locator('body').innerText()).replace(/\s+\n/g, '\n');
      fs.writeFileSync(path.join(OUT, `${name}-all.txt`), t2);
      summary.shots.push({ name: `${name}-all`, path: p, status: null, url: page.url(), file: f2,
        errorText: /Application error|Unhandled Runtime Error|Internal Server Error/i.test(t2) });
    } else summary.shots.push({ name: `${name}-all`, path: p, note: `전체 보기 버튼 ${await b.count()}개 — 누르지 않음` });
  }
}
await browser.close();
summary.finished = t();
summary.requests = { allowed: log.filter(x => x.decision === 'allow').length, blocked: log.filter(x => x.decision === 'block').length };
summary.pageErrors = pageErrors;
fs.writeFileSync(path.join(OUT, 'requests.json'), JSON.stringify(log, null, 1));
fs.writeFileSync(path.join(OUT, 'responses.json'), JSON.stringify(responses, null, 1));
fs.writeFileSync(path.join(OUT, 'summary.json'), JSON.stringify(summary, null, 1));
console.log(JSON.stringify({ ...summary, pageErrors: pageErrors.length }, null, 1));
process.exit(loggedIn ? 0 : 1);
