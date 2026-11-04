# N13 로컬 측정 실행기 — 격리 컨테이너 n13-pg 전용. 운영 접속 없음.
# 사용: python -I -X utf8 bench.py <out_dir> <dataset_name> <hours> <scen> <seed> [variants]
import json, subprocess, sys, time, hashlib, pathlib, datetime

CONTAINER = 'n13-pg'
DB = 'crypto_auto_trader'
HERE = pathlib.Path(__file__).resolve().parent
T0 = '2026-11-04 12:00:00+00'
CUTOFF = {'4h': "timestamptz '2026-11-04 08:00:00+00'", '24h': "timestamptz '2026-11-03 12:00:00+00'"}


def now():
    return datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).isoformat(timespec='milliseconds')


def psql(sql, extra=()):
    t = time.perf_counter()
    p = subprocess.run(['docker', 'exec', '-i', CONTAINER, 'psql', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1',
                        '-U', 'trader', '-d', DB, *extra], input=sql.encode('utf-8'), capture_output=True)
    return p.returncode, p.stdout.decode('utf-8'), p.stderr.decode('utf-8'), time.perf_counter() - t


def wait_ready():
    for _ in range(120):
        p = subprocess.run(['docker', 'exec', CONTAINER, 'pg_isready', '-U', 'trader', '-d', DB], capture_output=True)
        if p.returncode == 0:
            rc, out, err, _ = psql('SELECT 1;')
            if rc == 0:
                return
        time.sleep(0.5)
    raise SystemExit('DB not ready')


def restart(log):
    subprocess.run(['docker', 'restart', CONTAINER], capture_output=True, check=True)
    wait_ready()
    log.append({'at': now(), 'event': 'container_restart'})


def query(variant, k, limit):
    body = (HERE / 'sql' / f'{variant}_{k}.sql').read_text(encoding='utf-8')
    assert body.count(':cutoff') == 1 and body.count(':limit') == 1
    return body.replace(':cutoff', CUTOFF[k]).replace(':limit', str(limit))


def explain(variant, k, limit):
    sql = ("SET statement_timeout = '30s';\n"
           "EXPLAIN (ANALYZE, BUFFERS, TIMING OFF, FORMAT JSON)\n" + query(variant, k, limit).rstrip().rstrip(';') + ';\n')
    rc, out, err, wall = psql(sql)
    rec = {'variant': variant, 'k': k, 'limit': limit, 'rc': rc, 'wall_s': round(wall, 3), 'stderr': err.strip()}
    if rc == 0:
        plan = json.loads(out)
        rec['execution_ms'] = plan[0]['Execution Time']
        rec['planning_ms'] = plan[0]['Planning Time']
        rec['plan'] = plan
    else:
        rec['timeout'] = 'statement timeout' in err
    return rec


def result(variant, k, limit):
    sql = "SET statement_timeout = '30s';\n" + query(variant, k, limit).rstrip().rstrip(';') + ';\n'
    rc, out, err, wall = psql(sql)
    rec = {'variant': variant, 'k': k, 'limit': limit, 'rc': rc, 'wall_s': round(wall, 3), 'stderr': err.strip()}
    if rc == 0:
        rows = [r for r in out.split('\n') if r]
        rec['rows'] = len(rows)
        rec['sha256'] = hashlib.sha256(out.encode('utf-8')).hexdigest()
        rec['first_ids'] = [r.split('|')[0] for r in rows[:5]]
        rec['last_ids'] = [r.split('|')[0] for r in rows[-3:]]
    return rec


def main():
    out_dir = pathlib.Path(sys.argv[1]); out_dir.mkdir(parents=True, exist_ok=True)
    name, hours, scen, seed = sys.argv[2], int(sys.argv[3]), sys.argv[4], sys.argv[5]
    variants = sys.argv[6].split(',') if len(sys.argv) > 6 else ['orig', 'c1', 'c2']
    log = [{'at': now(), 'event': 'start', 'dataset': name, 'hours': hours, 'scen': scen, 'seed': seed, 'variants': variants}]
    gen = (HERE / 'sql' / 'gen.sql').read_text(encoding='utf-8')
    rc, out, err, wall = psql(gen, ['-v', f'hours={hours}', '-v', f'scen={scen}', '-v', f'seed={seed}'])
    log.append({'at': now(), 'event': 'generate', 'rc': rc, 'wall_s': round(wall, 1), 'stderr': err.strip(),
                'stats': dict(l.split('|', 1) for l in out.split('\n') if '|' in l)})
    if rc != 0:
        (out_dir / f'{name}.json').write_text(json.dumps(log, ensure_ascii=False, indent=1), encoding='utf-8')
        raise SystemExit(err)
    for v in variants:
        restart(log)
        for k in ('4h', '24h'):
            for run in (1, 2, 3):
                rec = explain(v, k, 100); rec['run'] = run; rec['at'] = now()
                log.append(rec)
                print(name, v, k, run, rec.get('execution_ms'), rec.get('timeout'), flush=True)
                if rec['rc'] != 0:
                    break
    for v in variants:
        for k in ('4h', '24h'):
            for lim in (100, 1):
                rec = result(v, k, lim); rec['event'] = 'result'; rec['at'] = now()
                log.append(rec)
    log.append({'at': now(), 'event': 'end'})
    (out_dir / f'{name}.json').write_text(json.dumps(log, ensure_ascii=False, indent=1), encoding='utf-8')


if __name__ == '__main__':
    main()
