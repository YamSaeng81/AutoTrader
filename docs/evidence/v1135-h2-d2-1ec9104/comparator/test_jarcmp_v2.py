# jar 비교기 v1/v2 합성 대조(총괄 v1.135 H2). 사용: python -I -X utf8 test_jarcmp_v2.py <작업 폴더(없어야 함)>
# 합성 jar 는 운영 jar 를 대표하지 않는다. 실제 보존 jar 두 개에는 v1·v2 출력이 같아야 한다.
import hashlib, io, os, pathlib, subprocess, sys, warnings, zipfile
HERE = pathlib.Path(__file__).resolve().parent
V1 = pathlib.Path('D:/Claude Code/projects/crypto-auto-trader/docs/evidence/k23-prep-51dcee5/jars/jarcmp.py')
V2 = HERE / 'jarcmp_v2.py'
FX = pathlib.Path('D:/Claude Code/projects/crypto-auto-trader/docs/evidence/weekend-review-a762b8cb/fixtures')
W = pathlib.Path(sys.argv[1]); W.mkdir(parents=True, exist_ok=False)
sys.stdout.reconfigure(encoding='utf-8')
R = []


def inner(entries, comp=zipfile.ZIP_DEFLATED, date=(2026, 11, 7, 10, 0, 0)):
    b = io.BytesIO()
    with warnings.catch_warnings():
        warnings.simplefilter('ignore', UserWarning)
        with zipfile.ZipFile(b, 'w') as z:
            for n, data in entries:
                zi = zipfile.ZipInfo(n, date_time=date); zi.compress_type = comp
                z.writestr(zi, data)
    return b.getvalue()


def jar(name, outer, comp=zipfile.ZIP_DEFLATED, date=(2026, 11, 7, 10, 0, 0)):
    p = W / name
    with warnings.catch_warnings():
        warnings.simplefilter('ignore', UserWarning)
        with zipfile.ZipFile(p, 'w') as z:
            for n, data in outer:
                zi = zipfile.ZipInfo(n, date_time=date); zi.compress_type = comp if not n.endswith('.jar') else zipfile.ZIP_STORED
                z.writestr(zi, data)
    return p


CORE = [('p/', b''), ('p/A.class', b'A-1'), ('p/B.class', b'B-1')]
BASE = [('META-INF/MANIFEST.MF', b'Main-Class: L\n'), ('org/springframework/boot/loader/L.class', b'L-1'),
        ('BOOT-INF/', b''), ('BOOT-INF/classes/application.yml', b'a: 1\n'),
        ('BOOT-INF/classes/db/migration/V94__x.sql', b'-- v94\n'), ('BOOT-INF/classes/c/S.class', b'S-1'),
        ('BOOT-INF/lib/foo-1.0.jar', b'foo-1.0-bytes'), ('BOOT-INF/lib/core-engine-1.0-SNAPSHOT.jar', inner(CORE))]


def var(changes=(), drop=(), add=(), core=None, comp=zipfile.ZIP_DEFLATED, date=(2026, 11, 7, 10, 0, 0), name=None):
    out = []
    for n, d in BASE:
        if n in drop:
            continue
        if n == 'BOOT-INF/lib/core-engine-1.0-SNAPSHOT.jar' and core is not None:
            d = inner(core, comp, date)
        elif n == 'BOOT-INF/lib/core-engine-1.0-SNAPSHOT.jar':
            d = inner(CORE, comp, date)
        out.append((n, dict(changes).get(n, d)))
    out += list(add)
    return jar(name, out, comp, date)


def run(v, a, b):
    r = subprocess.run([sys.executable, '-I', '-X', 'utf8', str(v), str(a), str(b)], capture_output=True)
    return r.returncode, r.stdout.decode('utf-8').replace('\r\n', '\n').strip()


def case(name, a, b, want_rc, want_lines=None, v1_note=None):
    rc1, o1 = run(V1, a, b)
    rc2, o2 = run(V2, a, b)
    lines2 = [l for l in o2.split('\n') if not l.startswith('(끝)')] if rc2 == 0 else None
    ok = rc2 == want_rc and (want_lines is None or lines2 == want_lines)
    R.append(ok)
    print('%s %s\n   v2: 종료 %d · %s\n   v1: 종료 %d · %s%s' % ('PASS' if ok else 'FAIL', name, rc2, o2.replace('\n', ' ⏎ '), rc1,
          o1.replace('\n', ' ⏎ ')[:300], ('\n   v1 비고: ' + v1_note) if v1_note else ''))


base = var(name='base.jar')
same = var(name='same.jar')
case('대조군: 같은 내용 → 차이 0', base, same, 0, [])
repack = var(name='repack.jar', comp=zipfile.ZIP_STORED, date=(2025, 1, 1, 0, 0, 0))
case('시각·압축 방식만 다른 재포장 → 차이 0(정상)', base, repack, 0, [])
INNER_DUP_DIFF = [('p/', b''), ('p/A.class', b'earlier-different'), ('p/A.class', b'A-1'), ('p/B.class', b'B-1')]
INNER_DUP_SAME = [('p/', b''), ('p/A.class', b'A-1'), ('p/A.class', b'A-1'), ('p/B.class', b'B-1')]
idd = var(name='inner-dup-diff.jar', core=INNER_DUP_DIFF)
ids = var(name='inner-dup-same.jar', core=INNER_DUP_SAME)
case('자체 모듈 내부 중복(다른 내용, 새 쪽) → 거절', base, idd, 3, v1_note='v1 은 차이 0 으로 놓친다(총괄 재현과 같음)')
case('자체 모듈 내부 중복(같은 바이트, 새 쪽) → 거절', base, ids, 3)
case('자체 모듈 내부 중복(옛 쪽만) → 거절', idd, base, 3)
case('자체 모듈 내부 중복(양쪽 같은 중복) → 상쇄하지 않고 거절', idd, var(name='inner-dup-diff-2.jar', core=INNER_DUP_DIFF), 3)
odd = jar('outer-dup-diff.jar', [('BOOT-INF/classes/application.yml', b'earlier')] + BASE[:3] + [(n, d) for n, d in BASE[3:]])
ods = jar('outer-dup-same.jar', BASE + [('BOOT-INF/classes/c/S.class', b'S-1')])
case('바깥 중복(다른 내용, 새 쪽) → 거절', base, odd, 3, v1_note='v1 은 "(중복 항목 이름 있음)" 차이 줄로 간접 표시')
case('바깥 중복(같은 바이트, 새 쪽) → 거절', base, ods, 3)
case('바깥 중복(옛 쪽만) → 거절', odd, base, 3)
case('바깥 중복(양쪽) → 거절', odd, jar('outer-dup-diff-2.jar', [('BOOT-INF/classes/application.yml', b'earlier')] + BASE), 3)
case('바깥 디렉터리 항목 중복 → 거절', base, jar('outer-dup-dir.jar', BASE + [('BOOT-INF/', b'')]), 3)
case('자체 모듈 jar 두 판이 같은 이름으로 겹침 → 거절', base,
     var(name='own-collide.jar', add=[('BOOT-INF/lib/core-engine-1.1.jar', inner([('p/A.class', b'A-9')]))]), 3,
     v1_note='v1 은 뒤의 판이 앞을 덮어써 일부 항목만 비교한다')
case('자체 모듈 판 번호만 바뀜(내용 같음) → 차이 0', base,
     var(name='own-bump.jar', drop=('BOOT-INF/lib/core-engine-1.0-SNAPSHOT.jar',), add=[('BOOT-INF/lib/core-engine-1.1-SNAPSHOT.jar', inner(CORE))]), 0, [])
case('자체 모듈 내부 클래스 변경 → 변경 줄', base, var(name='core-change.jar', core=[('p/', b''), ('p/A.class', b'A-2'), ('p/B.class', b'B-1')]), 0,
     ['변경 BOOT-INF/lib/core-engine.jar!p/A.class'])
case('자체 모듈 내부 리소스 누락 → 삭제 줄', base, var(name='core-missing.jar', core=[('p/', b''), ('p/A.class', b'A-1')]), 0,
     ['삭제 BOOT-INF/lib/core-engine.jar!p/B.class'])
case('리소스 추가 → 추가 줄', base, var(name='res-add.jar', add=[('BOOT-INF/classes/c/T.class', b'T-1')]), 0, ['추가 BOOT-INF/classes/c/T.class'])
case('리소스 누락 → 삭제 줄', base, var(name='res-drop.jar', drop=('BOOT-INF/classes/c/S.class',)), 0, ['삭제 BOOT-INF/classes/c/S.class'])
case('설정 변경 → 변경 줄', base, var(name='cfg.jar', changes=[('BOOT-INF/classes/application.yml', b'a: 2\n')]), 0, ['변경 BOOT-INF/classes/application.yml'])
case('마이그레이션 추가 → 추가 줄', base, var(name='mig.jar', add=[('BOOT-INF/classes/db/migration/V95__y.sql', b'-- v95\n')]), 0,
     ['추가 BOOT-INF/classes/db/migration/V95__y.sql'])
case('외부 라이브러리 판 변경 → 삭제·추가 줄', base,
     var(name='lib.jar', drop=('BOOT-INF/lib/foo-1.0.jar',), add=[('BOOT-INF/lib/foo-1.1.jar', b'foo-1.1-bytes')]), 0,
     ['삭제 BOOT-INF/lib/foo-1.0.jar', '추가 BOOT-INF/lib/foo-1.1.jar'])   # 정렬 순서(r1 기대값 순서 오류 정정)
case('로더·META-INF 변경 → 변경 줄', base, var(name='loader.jar', changes=[('META-INF/MANIFEST.MF', b'Main-Class: L2\n'),
     ('org/springframework/boot/loader/L.class', b'L-2')]), 0,
     ['변경 META-INF/MANIFEST.MF', '변경 org/springframework/boot/loader/L.class'])
bad = W / 'broken.jar'; bad.write_bytes(b'not a zip')
case('jar 형식 아님 → 거절', base, bad, 3)
# 총괄 합성 자료 세 개(weekend-review-a762b8cb/fixtures)
case('총괄 fixture base ↔ base', FX / 'base.jar.bin', FX / 'base.jar.bin', 0, [])
case('총괄 fixture base ↔ inner-duplicate → 거절', FX / 'base.jar.bin', FX / 'inner-duplicate.jar.bin', 3, v1_note='v1 은 차이 0')
case('총괄 fixture base ↔ outer-duplicate → 거절', FX / 'base.jar.bin', FX / 'outer-duplicate.jar.bin', 3)
# 실제 보존 jar — v1·v2 출력이 같고 3항목·881→881
J1, J2 = pathlib.Path('D:/tmp/o1o3/jars/1899b49.jar'), pathlib.Path('D:/tmp/o1o3/jars/0d142aa.jar')
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
print('실제 jar sha256: 1899b49 %s · 0d142aa %s' % (sha(J1), sha(J2)))
rc1, o1 = run(V1, J1, J2); rc2, o2 = run(V2, J1, J2)
ok = rc1 == rc2 == 0 and o1 == o2 and o2.split('\n')[-1] == '(끝) 881 881' and len(o2.split('\n')) == 4
R.append(ok)
print('%s 실제 보존 jar 1899b49 → 0d142aa: v1 과 v2 출력 같음·3항목·(끝) 881 881\n%s' % ('PASS' if ok else 'FAIL', o2))
print('결과 %d/%d' % (sum(R), len(R)))
sys.exit(0 if all(R) else 1)
