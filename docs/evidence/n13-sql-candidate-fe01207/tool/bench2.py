# N13 보강 측정 — limit 1·37 과 준비문 generic plan(각 1회). 사용: bench2.py <out_dir> <name> <hours> <scen> <seed>
# 판 c1·c2 만(구판은 r3 에서 limit 100 기준으로 측정됨). 결과 해시는 구판과 대조(30초 제한, 초과는 초과로 기록).
import json, sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import bench as B


def generic(variant, k, limit):
    body = (B.HERE / 'sql' / f'{variant}_{k}.sql').read_text(encoding='utf-8').rstrip().rstrip(';')
    body = body.replace(':cutoff', '$1').replace(':limit', '$2')
    cut = B.CUTOFF[k]
    sql = ("SET statement_timeout = '30s';\nSET plan_cache_mode = force_generic_plan;\n"
           f"PREPARE p(timestamptz, int) AS\n{body};\n"
           f"EXPLAIN (ANALYZE, BUFFERS, TIMING OFF, FORMAT JSON) EXECUTE p({cut}, {limit});\n")
    rc, out, err, wall = B.psql(sql)
    rec = {'variant': variant, 'k': k, 'limit': limit, 'mode': 'generic', 'rc': rc, 'wall_s': round(wall, 3), 'stderr': err.strip()}
    if rc == 0:
        plan = json.loads(out)
        rec.update(execution_ms=plan[0]['Execution Time'], planning_ms=plan[0]['Planning Time'], plan=plan)
    else:
        rec['timeout'] = 'statement timeout' in err
    return rec


def main():
    out_dir = pathlib.Path(sys.argv[1]); out_dir.mkdir(parents=True, exist_ok=True)
    name, hours, scen, seed = sys.argv[2], int(sys.argv[3]), sys.argv[4], sys.argv[5]
    log = [{'at': B.now(), 'event': 'start', 'dataset': name, 'hours': hours, 'scen': scen, 'seed': seed}]
    gen = (B.HERE / 'sql' / 'gen.sql').read_text(encoding='utf-8')
    rc, out, err, wall = B.psql(gen, ['-v', f'hours={hours}', '-v', f'scen={scen}', '-v', f'seed={seed}'])
    log.append({'at': B.now(), 'event': 'generate', 'rc': rc, 'wall_s': round(wall, 1), 'stderr': err.strip(),
                'stats': dict(l.split('|', 1) for l in out.split('\n') if '|' in l)})
    if rc != 0:
        raise SystemExit(err)
    # 생성 직후 autovacuum 과 겹치지 않도록 먼저 끝낸다(r3 의 250만 S1 에서 겹침이 있었다 — 기록)
    rc, out, err, wall = B.psql('VACUUM (ANALYZE) strategy_log;')
    log.append({'at': B.now(), 'event': 'vacuum_analyze', 'rc': rc, 'wall_s': round(wall, 1), 'stderr': err.strip()})
    B.restart(log)
    for v in ('c1', 'c2'):
        for k in ('4h', '24h'):
            for lim in (1, 37):
                rec = B.explain(v, k, lim); rec['run'] = 1; rec['mode'] = 'custom'; rec['at'] = B.now(); log.append(rec)
                print(name, v, k, 'limit', lim, rec.get('execution_ms'), rec.get('timeout'), flush=True)
            for lim in (100, 1):
                rec = generic(v, k, lim); rec['run'] = 1; rec['at'] = B.now(); log.append(rec)
                print(name, v, k, 'generic', lim, rec.get('execution_ms'), rec.get('timeout'), flush=True)
    for k in ('4h', '24h'):
        for lim in (1, 37):
            for v in ('orig', 'c2'):
                rec = B.result(v, k, lim); rec['event'] = 'result'; rec['at'] = B.now(); log.append(rec)
    log.append({'at': B.now(), 'event': 'end'})
    (out_dir / f'{name}.json').write_text(json.dumps(log, ensure_ascii=False, indent=1), encoding='utf-8')


if __name__ == '__main__':
    main()
