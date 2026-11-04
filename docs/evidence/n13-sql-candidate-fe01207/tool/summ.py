# N13 측정 요약 — bench.py 가 남긴 JSON 을 표로. 사용: python -I -X utf8 summ.py <runs_dir> > summary.txt
import json, pathlib, sys

d = pathlib.Path(sys.argv[1])


def walk(n, depth=0, out=None):
    out = [] if out is None else out
    out.append((depth, n))
    for c in n.get('Plans', []):
        walk(c, depth + 1, out)
    return out


def shape(plan):
    parts = []
    for depth, n in walk(plan):
        t = n['Node Type']
        if t in ('Hash Join', 'Merge Join', 'Nested Loop'):
            t = '%s(%s)' % (t, n.get('Join Type', ''))
        rel = n.get('Relation Name') or n.get('Index Name') or ''
        parts.append('%s%s%s' % ('  ' * depth, t, (' ' + rel) if rel else ''))
    return parts


for f in sorted(d.glob('*.json')):
    log = json.loads(f.read_text(encoding='utf-8'))
    gen = next(x for x in log if x.get('event') == 'generate')
    st = gen['stats']
    print('=' * 100)
    print('%s  생성 %.1fs  rows=%s pages=%s size=%s hold_priced=%s done4=%s done24=%s l4=%s l24=%s md5=%s' % (
        f.stem, gen['wall_s'], st.get('rows'), st.get('pages'), st.get('size_bytes'), st.get('hold_priced'),
        st.get('done4_rows'), st.get('done24_rows'), st.get('l4_rows'), st.get('l24_rows'), st.get('content_md5')))
    ex = [x for x in log if 'run' in x]
    for x in ex:
        if x['rc'] == 0:
            p = x['plan'][0]['Plan']
            sh = p.get('Shared Hit Blocks', 0); sr = p.get('Shared Read Blocks', 0)
            tw = p.get('Temp Written Blocks', 0)
            print('  %-4s %-3s run%d  exec=%9.1fms plan=%6.1fms wall=%6.2fs rows=%-4s buffers hit=%d read=%d temp_w=%d  at=%s' % (
                x['variant'], x['k'], x['run'], x['execution_ms'], x['planning_ms'], x['wall_s'], p.get('Actual Rows'),
                sh, sr, tw, x['at']))
        else:
            print('  %-4s %-3s run%d  %s wall=%6.2fs  at=%s  stderr=%s' % (
                x['variant'], x['k'], x['run'], '30초 초과(statement_timeout)' if x.get('timeout') else '오류', x['wall_s'], x['at'],
                x['stderr'].splitlines()[-1] if x['stderr'] else ''))
    # 첫 성공 실행의 계획 모양과 주요 노드 실제 행
    shown = set()
    for x in ex:
        key = (x['variant'], x['k'])
        if x['rc'] != 0 or key in shown:
            continue
        shown.add(key)
        print('  -- 계획 %s %s (run%d)' % (x['variant'], x['k'], x['run']))
        for depth, n in walk(x['plan'][0]['Plan']):
            t = n['Node Type']
            if 'Join Type' in n:
                t += '(%s)' % n['Join Type']
            extra = []
            for kk in ('Relation Name', 'Index Name', 'Hash Cond', 'Merge Cond', 'Join Filter'):
                if kk in n:
                    extra.append('%s=%s' % (kk.split()[0], n[kk]))
            if 'Rows Removed by Filter' in n:
                extra.append('removed_filter=%s' % n['Rows Removed by Filter'])
            if 'Rows Removed by Join Filter' in n:
                extra.append('removed_join_filter=%s' % n['Rows Removed by Join Filter'])
            if 'Workers Launched' in n:
                extra.append('workers=%s/%s' % (n['Workers Launched'], n.get('Workers Planned')))
            print('     %s%s rows=%s loops=%s %s' % ('  ' * depth, t, n.get('Actual Rows'), n.get('Actual Loops'), ' '.join(extra)))
    res = [x for x in log if x.get('event') == 'result']
    print('  -- 결과 대조(같은 자료·같은 cutoff·limit, 전체 열 해시)')
    by = {}
    for x in res:
        by.setdefault((x['k'], x['limit']), []).append(x)
    for (k, lim), xs in sorted(by.items()):
        cells = []
        for x in xs:
            if x['rc'] == 0:
                cells.append('%s=%s행/%s' % (x['variant'], x['rows'], x['sha256'][:12]))
            else:
                cells.append('%s=%s' % (x['variant'], '30초 초과' if 'statement timeout' in x['stderr'] else '오류'))
        ok = [x['sha256'] for x in xs if x['rc'] == 0]
        verdict = ('같음' if len(set(ok)) == 1 else '다름') if len(ok) == len(xs) else ('완료한 판끼리 같음' if len(set(ok)) <= 1 else '다름')
        print('     %s limit=%d: %s → %s' % (k, lim, ' · '.join(cells), verdict))
