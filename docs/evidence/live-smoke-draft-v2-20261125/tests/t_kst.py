# K·R·F01~F03: kst.py 순수 판정 검증. 사용: python -I t_kst.py <kst.py 경로>  (TZ 변형 하위 실행 포함)
import datetime as d, importlib.util, json, os, subprocess, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
KP = os.path.abspath(sys.argv[1])
def load(p):
    s = importlib.util.spec_from_file_location('kst_v2', p); m = importlib.util.module_from_spec(s); s.loader.exec_module(m); return m
def ep(iso): return int(d.datetime.strptime(iso, '%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=d.timezone.utc).timestamp())
K = [('K01','2026-11-30T14:59:59Z','gate','2026-11-30','1000','1030'), ('K02','2026-11-30T15:00:00Z','gate','2026-11-30','1000','1030'),
     ('K03','2026-12-01T00:59:59Z','create','2026-12-01','1000','1030'), ('K04','2026-12-01T01:00:00Z','create','2026-12-01','1000','1030'),
     ('K05','2026-12-01T01:30:00Z','start','2026-12-01','1000','1030'), ('K06','2026-12-01T01:30:59Z','start','2026-12-01','1000','1030'),
     ('K07','2026-12-01T01:31:00Z','start','2026-12-01','1000','1030'), ('K08','2026-12-01T01:00:00Z','p0','2026-11-30','1000','1030'),
     ('K09','2026-11-30T15:00:00Z','verify','2026-11-30','0000','2359'), ('K10s','2026-12-01T05:00:00Z','stop','2026-12-01','1000','1030'),
     ('K10g','2026-12-01T05:00:00Z','gate','2026-12-01','1000','1030'), ('K10w','2026-12-01T05:00:00Z','watch','2026-12-01','1000','1030'),
     ('K11','2026-12-01T05:00:00Z','create','2026-12-01','1000','1030')]
X = '2026-12-01T01:10:00Z'
R = [('R01','create','2026-02-30','1000','1030'),('R02','create','2026-13-01','1000','1030'),('R03','create','2026-1-01','1000','1030'),
     ('R04','create','','1000','1030'),('R05','create','2026-12-01','1000','2460'),('R06','create','2026-12-01','2400','2400'),
     ('R07','create','2026-12-01','1260','1300'),('R08','create','2026-12-01','999','1030'),('R09','create','2026-12-01','',''),
     ('R10','create','2026-12-01','1100','1000'),('R11','foo','2026-12-01','1000','1030'),('R12','stop','2026-02-30','1000','1030')]
EXP = {'K01':(0,'2026-11-30 2359'),'K02':(0,'2026-12-01 0000'),'K03':(3,None),'K04':(0,'2026-12-01 1000'),'K05':(0,'2026-12-01 1030'),
       'K06':(0,'2026-12-01 1030'),'K07':(3,None),'K08':(3,None),'K09':(3,None),'K10s':(0,'2026-12-01 1400'),'K10g':(0,'2026-12-01 1400'),
       'K10w':(0,'2026-12-01 1400'),'K11':(3,None)}
def run_all(m):
    out = {}
    for c, iso, *a in K:
        rc, line = m.judge(ep(iso), *a); out[c] = [rc, line]
    for c, *a in R:
        rc, line = m.judge(ep(X), *a); out[c] = [rc, line]
    return out
if len(sys.argv) > 2 and sys.argv[2] == '--dump':   # 하위 실행: 이 TZ 환경에서 결과만 JSON
    print(json.dumps(run_all(load(KP)), ensure_ascii=False)); sys.exit(0)
m = load(KP); base = run_all(m); ok = bad = 0
def rep(c, good, msg):
    global ok, bad
    ok += good; bad += (not good); print(('ok ' if good else 'NG ') + c + ' ' + msg)
for c, (erc, dh) in EXP.items():
    rc, line = base[c]; good = rc == erc and (dh is None or ' '.join(line.split()[1:3]) == dh)
    rep(c, good, 'rc=%d %s' % (rc, line))
for c, *_ in R:
    rc, line = base[c]; rep(c, rc == 3, 'rc=%d %s' % (rc, line))
# -c 원문 실행(서버 방식)과 같은 함수인지: 원문 문자열을 exec 해 같은 결과
ns = {'__name__': 'kst_c'}; exec(open(KP, encoding='utf-8').read(), ns)
class _M: pass
mc = _M(); mc.judge = ns['judge']; rep('C01', run_all(mc) == base, '-c 원문 exec 결과 동일')
for tz in ['UTC', 'America/Los_Angeles', 'Asia/Seoul', 'Pacific/Kiritimati', 'EST+5']:
    env = dict(os.environ, TZ=tz)
    p = subprocess.run([sys.executable, '-I', os.path.abspath(__file__), KP, '--dump'], env=env, capture_output=True, text=True, encoding='utf-8')
    rep('TZ:' + tz, p.returncode == 0 and json.loads(p.stdout) == base, 'rc=%d 동일=%s' % (p.returncode, p.returncode == 0 and json.loads(p.stdout) == base))
try:
    m.judge('1795742040', 'create', '2026-12-01', '1000', '1030'); rep('F01', False, '예외 없음')
except TypeError as e:
    rep('F01', True, 'TypeError %s' % e)
p = subprocess.run([sys.executable, '-I', KP, 'window', 'create'], capture_output=True, text=True, encoding='utf-8')
rep('F02', p.returncode == 2 and p.stdout == '', 'rc=%d 출력=%r' % (p.returncode, p.stdout))
p = subprocess.run([sys.executable, '-I', KP, 'window', 'create', '2000-01-01', '1000', '1030'], capture_output=True, text=True, encoding='utf-8')
rep('F03', p.returncode == 3, 'rc=%d %s' % (p.returncode, p.stdout.strip()))
print('t_kst ok %d / %d' % (ok, ok + bad)); sys.exit(0 if bad == 0 else 1)
