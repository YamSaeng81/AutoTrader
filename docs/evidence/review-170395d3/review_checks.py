"""Local blob audit and focused in-memory contract probes; no network/DB/test runner."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import posixpath
import re
import subprocess
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
EV = ROOT / 'docs/evidence'
SHA = '170395d3ffefe253294bc8a10a6911d068fc669b'
checks, probes = [], []

def git(*args):
    return subprocess.check_output(['git', '-c', 'core.quotepath=false', *args], cwd=ROOT)

def digest(data):
    return hashlib.sha256(data).hexdigest()

def check(name, ok, detail=None):
    checks.append({'name': name, 'ok': bool(ok), 'detail': detail})

def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module

def read(path):
    return json.loads(path.read_text(encoding='utf-8'))

def summarize_card(card):
    return {k: card.get(k) for k in ('id', 'start_equity', 'end_equity', 'realized_in_period',
        'realized_known_part', 'round_trips_closed', 'round_trips_closed_known_part',
        'wins', 'losses', 'wins_known_part', 'losses_known_part')}

def main():
    check('HEAD and saved origin/main', git('rev-parse', 'HEAD').decode().strip() == SHA
          and git('rev-parse', 'origin/main').decode().strip() == SHA,
          'Saved references only; no remote query')
    changes = git('diff', '--name-only', '43d729db', SHA).decode().splitlines()
    check('Submission paths only management docs and evidence', all(p.startswith('docs/evidence/') or p in
          ('docs/CURRENT_HANDOFF.md', 'docs/LIMITED_LIVE_TEST_PLAN.md', 'docs/PROGRESS.md') for p in changes),
          {'paths': len(changes), 'settings_included': '.claude/settings.local.json' in changes})
    folders = ['dec-read-gate-v3-20261120', 'dec-read-gate-v2-20261120', 'review-43d729db',
               'review-read-gate-v2-20261120', 'review-read-gate-v3-20261120',
               'dec-scorecard-source-v4-20261120', 'dec-registry-p7v8-20261120', 'dec-read-source-20261120',
               'dec-scorecard-source-v3-20261120', 'dec-registry-p7v7-20261120']
    for folder in folders:
        base = 'docs/evidence/' + folder + '/'
        manifest = git('show', SHA + ':' + base + 'SHA256SUMS')
        bad, count = [], 0
        for line in manifest.decode('utf-8-sig').splitlines():
            if not line.strip():
                continue
            match = re.fullmatch(r'([a-fA-F0-9]{64})\s+\*?(.+)', line)
            if not match:
                bad.append({'format': line})
                continue
            h, rel = match.groups()
            rel = rel.removeprefix('./')
            p = posixpath.normpath(base + rel)
            data = git('show', SHA + ':' + p)
            count += 1
            if digest(data) != h.lower() or digest((ROOT / p).read_bytes()) != h.lower():
                bad.append(p)
        check('Manifest ' + folder, not bad, {'entries': count, 'bad': bad})
    pins = {
        'dec-read-gate-v3-20261120/read.sql': '9818db5452f66f058c97322ac9aaf73097036997777d10a0af9f6dde05bdae03',
        'dec-read-gate-v3-20261120/parse.py': '0f6fe827c9da513445ce9c52300b8688f8f4f162a99026b90d05943c10341908'}
    for path, h in pins.items():
        check('Accepted pin ' + path, digest(git('show', SHA + ':docs/evidence/' + path)) == h)
    attr = git('show', SHA + ':docs/evidence/dec-read-gate-v3-20261120/.gitattributes')
    check('R3 raw preservation attribute', attr.strip() == b'* -text')
    for folder, final_line in [('dec-scorecard-source-v4-20261120', '8'), ('dec-registry-p7v8-20261120', '15')]:
        rel = 'run1/cases.out.txt' if final_line == '8' else 'p7v8/run-v8/cases.out.txt'
        last = (EV / folder / rel).read_text(encoding='utf-8').splitlines()[-1]
        numbers = re.findall(r'\d+', last)
        check('Preserved direct cases ' + folder, numbers == [final_line, final_line, '0'], {'last_line': last})
    old = EV / 'dec-scorecard-source-v3-20261120'
    new = EV / 'dec-scorecard-source-v4-20261120'
    c3, c4 = load(old / 'convert.py', 'review_c3'), load(new / 'convert.py', 'review_c4')
    s4, s5 = load(old / 'scorecard/scorecard.py', 'review_s4'), load(new / 'scorecard/scorecard.py', 'review_s5')
    source = read(old / 'run1/file/source.json')
    def cards(conv, scorer, data):
        inp, rep = conv.convert(copy.deepcopy(data))
        return inp, rep, scorer.build(copy.deepcopy(inp))
    s1 = copy.deepcopy(source)
    s1['clock'] = {'same_source': []}
    s1['period']['axis'] = 'db'
    s1['sessions'][0]['created_at'] = s1['period']['from']
    s1['sessions'][0]['boundary']['start'] = None
    s1['sessions'][0]['orders'] = []
    s1['registry_events'] = []
    i0, r0, o0 = cards(c3, s4, s1)
    i1, r1, o1 = cards(c4, s5, s1)
    check('S1 old zero opening vs new unknown', o0['sessions'][0]['start_equity'] == '0'
          and o1['sessions'][0]['start_equity'] is None and 'created_at' not in i1['sessions'][0])
    probes.append({'name': 'S1', 'old': summarize_card(o0['sessions'][0]), 'new': summarize_card(o1['sessions'][0]),
                   'creation': r1['sessions'][0].get('creation')})
    # A second session is evaluated alone and alongside the affected session.
    other = copy.deepcopy(source['sessions'][0])
    other['id'] = 204
    both = copy.deepcopy(s1)
    both['sessions'].append(other)
    single = copy.deepcopy(both)
    single['sessions'] = [other]
    _, _, two = cards(c4, s5, both)
    _, _, one = cards(c4, s5, single)
    check('S1 second session preserved independently', len(two['sessions']) == 2
          and two['sessions'][1] == one['sessions'][0] and one['sessions'][0]['end_equity'] is not None)
    probes.append({'name': 'S1 second-session isolation', 'two_cards': [summarize_card(x) for x in two['sessions']],
                   'same_as_alone': two['sessions'][1] == one['sessions'][0]})
    period = (s5.tstamp('2026-10-01T00:00:00+00:00', 'f'), s5.tstamp('2026-11-01T00:00:00+00:00', 't'))
    def trade(sale_time, proceeds):
        return {'buy': {'qty': '1', 'funds': '100', 'fee': '0', 'value_status': '\ud655\uc815',
                        'settled_at': '2026-10-02T00:00:00+00:00'},
                'sells': [{'qty': '1', 'funds': proceeds, 'fee': '0', 'value_status': '\ud655\uc815', 'settled_at': sale_time}]}
    trades = [trade('2026-10-03T00:00:00+00:00', '90'), trade(None, '110')]
    m0, m1 = s4.trade_metrics(copy.deepcopy(trades), period, 'review'), s5.trade_metrics(copy.deepcopy(trades), period, 'review')
    check('S2 unknown month with confirmed loss partial preserved', m0['round_trips_closed'] == 1
          and m1['round_trips_closed'] is None and m1['wins'] is None and m1['losses'] is None
          and m1['round_trips_closed_known_part'] == 1 and m1['losses_known_part'] == 1
          and m1['realized_known_part'] == -10 and m1['realized_in_period'] is None)
    probes.append({'name': 'S2 mixed confirmed and unknown month', 'old': m0, 'new': m1})
    pc = load(EV / 'dec-registry-p7v8-20261120/p7v8/cases_v8.py', 'review_p8_cases')
    base = pc.ev(pc.V8, pc.P0)
    neg_old, neg_new = pc.ev(pc.V7, pc.p1()), pc.ev(pc.V8, pc.p1())
    check('P7 confirmed negative coexists with reservation hold', 'ACCOUNT_SUM_NEGATIVE_UNALLOCATED' not in pc.codes(neg_old)
          and 'ACCOUNT_SUM_NEGATIVE_UNALLOCATED' in pc.codes(neg_new)
          and 'ACCOUNT_SUM_PARTIAL_RESERVATION' in pc.codes(neg_new, 'holds') and neg_new['overall'] == 'ANOMALY')
    unknown = pc.ev(pc.V8, pc.p1_time_unknown())
    check('P7 unallocated boundary unknown is not confirmed negative', 'ACCOUNT_SUM_BASE_BOUNDARY' in pc.codes(unknown, 'holds')
          and 'ACCOUNT_SUM_NEGATIVE_UNALLOCATED' not in pc.codes(unknown))
    check('P7 normal output unchanged and F3 accepts', base == pc.ev(pc.V7, pc.P0) and pc.F8.judge(base)[0])
    probes.append({'name': 'P7 scoped holds', 'old_anomalies': pc.codes(neg_old), 'new_anomalies': pc.codes(neg_new),
                   'new_holds': pc.codes(neg_new, 'holds'), 'boundary_unknown_holds': pc.codes(unknown, 'holds')})
    malformed = []
    for field, value in [('code', ['x']), ('exchange_coverage', ['x']), ('account', ['x']), ('account_sum', ['x']),
                         ('anomalies', ['x']), ('holds', ['x']), ('observation', []), ('observation_stable', 'true')]:
        obj = copy.deepcopy(base)
        obj[field] = value
        try:
            ok, reasons = pc.F8.judge(obj)
            result = {'kind': 'returned', 'accepted': ok, 'reasons': reasons}
        except Exception as exc:
            result = {'kind': 'exception', 'accepted': False, 'error': type(exc).__name__, 'message': str(exc)}
        malformed.append({'field': field, 'value': value, 'result': result})
    obj = copy.deepcopy(base)
    obj['exchange_coverage']['window'] = ['x']
    try:
        ok, reasons = pc.F8.judge(obj)
        result = {'kind': 'returned', 'accepted': ok, 'reasons': reasons}
    except Exception as exc:
        result = {'kind': 'exception', 'accepted': False, 'error': type(exc).__name__, 'message': str(exc)}
    malformed.append({'field': 'exchange_coverage.window', 'value': ['x'], 'result': result})
    check('Malformed F3 never returns acceptance', not any(x['result']['accepted'] for x in malformed))
    probes.append({'name': 'F3 malformed required-field diagnostics', 'cases': malformed,
                   'note': 'Exception is fail-closed, but is not a structured input verdict.'})
    # One direct contradiction returns the required rejection rather than relying on the OK string.
    obj = copy.deepcopy(base)
    obj['anomalies'] = [{'code': 'ACCOUNT_SUM_NEGATIVE_UNALLOCATED'}]
    check('F3 rejects contradictory OK and nonempty anomaly list', pc.F8.judge(obj)[0] is False)
    pins_out = {p: digest((EV / p).read_bytes()) for p in (
        'dec-scorecard-source-v4-20261120/convert.py', 'dec-scorecard-source-v4-20261120/scorecard/scorecard.py',
        'dec-registry-p7v8-20261120/p7v8/reconcile.py', 'dec-registry-p7v8-20261120/p7v8/f3.py')}
    output = {'commit': SHA, 'scope': 'Local committed blobs, saved output, in-memory file functions only',
              'pass': sum(c['ok'] for c in checks), 'fail': sum(not c['ok'] for c in checks), 'pins': pins_out, 'checks': checks}
    (HERE / 'checks.json').write_text(json.dumps(output, ensure_ascii=False, indent=2, default=str) + '\n', encoding='utf-8')
    (HERE / 'contract-probes.json').write_text(json.dumps({'probes': probes}, ensure_ascii=False, indent=2, default=str) + '\n', encoding='utf-8')
    print(json.dumps({'checks_pass': output['pass'], 'checks_fail': output['fail'],
                      'f3_malformed_exceptions': sum(x['result']['kind'] == 'exception' for x in malformed)}, ensure_ascii=False))
    return bool(output['fail'])

if __name__ == '__main__':
    sys.exit(main())
