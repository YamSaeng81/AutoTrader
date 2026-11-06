# N06 인계 사전 준비(주말 지시 §6 W4) — 로컬 전용. 서버·cron·DB 를 건드리지 않는다.
# 사용: python -I n06_prepare.py <40자리 출처 SHA> <출력 폴더> <기대값 json>
#   출처 SHA 의 docs/LIMITED_LIVE_TEST_PLAN.md · docs/OPS_DAILY_CHECK.md 에서
#   ① F 15블록(색인 12~26) 해시를 기대값(현재 승인 핀)과 대조 ② F 변수 NEW·F1 IMGF1·이미지 기록 경로 확인
#   ③ F2-1·F5-3 블록 안의 awk 원문으로 §3-1 cron 전환 블록과 F3 블록을 꺼냄(손으로 옮기지 않는다) — 해시 대조, 옛 F3(30b5e729…) 거절
#   ④ F2-1·F5-3 실행 사본(첫 줄 SHA= 자리만 채움)과 종료 cron 사본 2개(3행 FROM/TO 만 바꿈) 생성 — 바뀐 줄 수·자리표시자·bash -n 확인
# 하나라도 어긋나면 실행 사본을 출력 폴더에 쓰지 않고 실패 보고(fail.json)만 남기고 종료 1. 인자 형식 오류는 종료 2.
# 🔴 이 도구의 출력은 리허설·인계 준비물이다. 최종 S(11-30)·F1 통과·운영자 확인·운영 예약을 뜻하지 않는다.
import hashlib, json, pathlib, re, shutil, subprocess, sys, tempfile

REPO = pathlib.Path(__file__).resolve().parents[3]
BASH = 'C:/Program Files/Git/bin/bash.exe'
PLACEHOLDER = 'SHA=<리뷰가 확인한 커밋 SHA>'
OLD_F3 = '30b5e729c2f857ae9e22e597bbcf96f6e1436b9a14e05eb6557b2339c3e94ca1'
CRON_FROM = 'FROM=CREATED; TO=RUNNING'
FINAL_STATES = ('STOPPED', 'EMERGENCY_STOPPED')


def H(b):
    return hashlib.sha256(b).hexdigest()


def git(*a, check=True):
    return subprocess.run(['git', '-C', str(REPO), *a], capture_output=True, check=check)


def blocks(text):
    return re.findall(r'^```bash\n(.*?)^```', text, re.M | re.S)


def awk_prog(block):
    m = re.search(r"\| awk '([^']*)' >", block)
    if not m:
        raise ValueError('블록에서 awk 원문을 찾지 못함')
    return m.group(1)


def exec_copy(orig, sha):
    """첫 줄의 자리표시자만 40자리로 채운다 — 정확히 한 번, 바뀐 줄 1개."""
    lines = orig.split('\n')
    if not lines[0].startswith(PLACEHOLDER) or orig.count(PLACEHOLDER) != 1:
        raise ValueError('첫 줄 SHA 자리표시자가 정확히 한 번 있지 않음')
    out = orig.replace(PLACEHOLDER, 'SHA=' + sha, 1)
    if '<리뷰가 확인한 커밋 SHA>' in out:
        raise ValueError('자리표시자가 남음')
    diff = [i for i, (a, b) in enumerate(zip(orig.split('\n'), out.split('\n'))) if a != b]
    if diff != [0] or len(orig.split('\n')) != len(out.split('\n')):
        raise ValueError('바뀐 줄이 첫 줄 하나가 아님: %s' % diff)
    return out


def cron_final(switch, to):
    lines = switch.split('\n')
    if len(lines) < 3 or not lines[2].startswith(CRON_FROM) or switch.count(CRON_FROM) != 1:
        raise ValueError('§3-1 3행이 FROM=CREATED; TO=RUNNING 으로 시작하지 않음')
    lines[2] = 'FROM=RUNNING; TO=' + to + lines[2][len(CRON_FROM):]
    out = '\n'.join(lines)
    diff = [i for i, (a, b) in enumerate(zip(switch.split('\n'), lines)) if a != b]
    if diff != [2]:
        raise ValueError('바뀐 줄이 3행 하나가 아님: %s' % diff)
    return out


def main():
    sys.stdout.reconfigure(encoding="utf-8")   # r0: Windows 콘솔 cp949 에서 출력 문자 오류로 멈춘 것을 고침
    if len(sys.argv) != 4:
        print('사용: n06_prepare.py <40자리 SHA> <출력 폴더> <기대값 json>'); sys.exit(2)
    sha, out, expf = sys.argv[1], pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        print('🔴 거절(종료 2): 출처는 40자리 소문자 SHA 여야 한다 — 받은 값 %r' % sha); sys.exit(2)
    if out.exists():
        print('🔴 거절(종료 2): 출력 폴더가 이미 있다 — 덮어쓰지 않는다 %s' % out); sys.exit(2)
    exp = json.loads(expf.read_text(encoding='utf-8'))
    rep = {'source': sha, 'expected_file': str(expf.name), 'checks': [], 'issued': False,
           'note': '리허설·인계 준비물. 최종 S(11-30)·F1 통과·운영자 확인·운영 예약이 아니다'}
    bad = []

    def chk(name, ok, detail=''):
        rep['checks'].append({'name': name, 'ok': bool(ok), 'detail': detail})
        print(('PASS ' if ok else 'FAIL ') + name + (' — ' + detail if detail else ''))
        if not ok:
            bad.append(name)

    if git('cat-file', '-e', sha + '^{commit}', check=False).returncode != 0:
        chk('출처 커밋이 저장소에 있다', False, sha)
        return finish(out, rep, bad, {})
    chk('출처 커밋이 저장소에 있다', True, sha)
    plan_b = git('show', sha + ':docs/LIMITED_LIVE_TEST_PLAN.md').stdout
    ops_b = git('show', sha + ':docs/OPS_DAILY_CHECK.md').stdout
    rep['documents'] = {'docs/LIMITED_LIVE_TEST_PLAN.md': {'sha256': H(plan_b), 'bytes': len(plan_b)},
                        'docs/OPS_DAILY_CHECK.md': {'sha256': H(ops_b), 'bytes': len(ops_b)}}
    plan = plan_b.decode('utf-8')
    B = blocks(plan)
    chk('계획 bash 블록 수 = %d' % exp['plan_bash_blocks'], len(B) == exp['plan_bash_blocks'], str(len(B)))
    for row in exp['f_blocks']:
        i = row['index_zero_based']
        h = H(B[i].encode('utf-8')) if i < len(B) else None
        chk('F 블록 %s(색인 %d) = 승인 해시 %s…' % (row['block'], i, row['new_sha256'][:8]), h == row['new_sha256'], (h or '없음')[:16])
    fvars = B[12] if len(B) > 12 else ''
    f1 = B[13] if len(B) > 13 else ''
    chk('F 변수 NEW = 운영 HEAD', re.search(r'^NEW=' + exp['operating_head'] + r'\b', fvars, re.M) is not None)
    chk('F1 IMGF1 = 운영 이미지', re.search(r'^IMGF1=' + re.escape(exp['operating_image']) + r'\b', f1, re.M) is not None)
    chk('F1 이미지 기록 경로 = %s' % exp['image_record'], exp['image_record'] in f1)
    for k, v in exp['install'].items():
        chk('F1 설치값 %s' % k, v in f1)

    files = {}
    try:
        f21 = B[exp['f21_index']]; f53 = B[exp['f53_index']]
        chk('F2-1 원문 해시', H(f21.encode()) == exp['f21_orig_sha256'], H(f21.encode())[:16])
        chk('F5-3 원문 해시', H(f53.encode()) == exp['f53_orig_sha256'], H(f53.encode())[:16])
        sw = subprocess.run([BASH, '-c', 'git -C "$0" show "$1":docs/OPS_DAILY_CHECK.md | awk "$2"', str(REPO), sha, awk_prog(f21)], capture_output=True)
        f3 = subprocess.run([BASH, '-c', 'git -C "$0" show "$1":docs/LIMITED_LIVE_TEST_PLAN.md | awk "$2"', str(REPO), sha, awk_prog(f53)], capture_output=True)
        chk('§3-1 cron 전환 추출 성공', sw.returncode == 0 and len(sw.stdout) > 0, 'rc=%d' % sw.returncode)
        chk('F3 추출 성공', f3.returncode == 0 and len(f3.stdout) > 0, 'rc=%d' % f3.returncode)
        chk('§3-1 = 승인 해시 %s…' % exp['ops_31_sha256'][:8], H(sw.stdout) == exp['ops_31_sha256'], H(sw.stdout)[:16])
        chk('F3 ≠ 옛 리허설 F3 30b5e729…', H(f3.stdout) != OLD_F3, H(f3.stdout)[:16])
        chk('F3 = 승인 해시 %s…' % exp['f3_sha256'][:8], H(f3.stdout) == exp['f3_sha256'], H(f3.stdout)[:16])
        chk('F3 추출본 = 계획 F3 블록(색인 18) 바이트', f3.stdout == B[18].encode('utf-8'))
        files['f21.orig.sh'] = f21.encode(); files['f53.orig.sh'] = f53.encode()
        files['f21.exec.sh'] = exec_copy(f21, sha).encode(); files['f53.exec.sh'] = exec_copy(f53, sha).encode()
        files['cron-switch.sh'] = sw.stdout; files['f3-block.sh'] = f3.stdout
        swt = sw.stdout.decode('utf-8')
        for st in FINAL_STATES:
            files['cron-final.%s.sh' % st] = cron_final(swt, st).encode()
        chk('실행 사본 2개 — 첫 줄만 40자리로, 자리표시자 0', True)
        chk('종료 cron 사본 2개 — 3행 FROM=RUNNING; TO=<최종 상태> 만', True)
    except Exception as e:
        chk('사본 생성', False, '%s: %s' % (type(e).__name__, e))
    tmp = pathlib.Path(tempfile.mkdtemp(prefix='n06prep-'))
    try:
        for n, b in files.items():
            (tmp / n).write_bytes(b)
            r = subprocess.run([BASH, '-n', str(tmp / n)], capture_output=True)
            if n.endswith('.orig.sh'):
                # r1: 원문은 자리표시자(<…>) 때문에 그대로는 문법 오류여야 한다 — 채우지 않은 원문이 실행되지 않는다는 안전 성질
                chk('bash -n %s — 원문은 자리표시자 때문에 그대로 실행 불가' % n, r.returncode != 0, 'rc=%d' % r.returncode)
            else:
                chk('bash -n %s' % n, r.returncode == 0, r.stderr.decode('utf-8', 'replace')[:200])
        return finish(out, rep, bad, files, tmp)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def finish(out, rep, bad, files, tmp=None):
    out.mkdir(parents=True)
    rep['failed'] = bad
    if bad:
        (out / 'fail.json').write_text(json.dumps(rep, ensure_ascii=False, indent=1), encoding='utf-8')
        print('🔴 실패 %d건 — 실행 사본을 발급하지 않았다(fail.json 만)' % len(bad)); sys.exit(1)
    rep['issued'] = True
    rep['artifacts'] = {n: {'sha256': H(b), 'bytes': len(b)} for n, b in sorted(files.items())}
    for n, b in files.items():
        (out / n).write_bytes(b)
    (out / 'manifest.json').write_text(json.dumps(rep, ensure_ascii=False, indent=1), encoding='utf-8')
    print('✅ 인계 준비물 %d개 — 출처 %s (리허설용 임시 출처, 최종 S 아님)' % (len(files), rep['source'])); sys.exit(0)


if __name__ == '__main__':
    main()
