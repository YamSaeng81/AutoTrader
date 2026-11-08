# O1·O3 배포 초안 D1(UB=O13D1, o1o3-candidate-1899b49/w6/blocks-d1) → D2(UB=O13D2). 총괄 v1.135 §2 H2·§5-3.
# 🔴 미승인·서버 실행 금지 초안. D1 은 덮어쓰지 않는다(출력 폴더는 없어야 한다).
# 바꾸는 것: 판 표지·후보 SHA·diff 경로 목록(최종 SHA 기준)·U1 의 jar 비교기(v1 → v2, 중복 명시 거절)와 그 종료값 확인. 그 밖의 로직은 D1 그대로.
# 바꾸는 곳은 모두 지정한 횟수만큼 정확히 맞아야 한다(아니면 멈춘다).
# 사용: python -I -X utf8 mkdraft2.py <blocks-d1> <출력 폴더> <최종 NEW 40자리> <diff 경로 파일(git diff --name-only OLD NEW 출력)> <jarcmp_v2.py>
import pathlib, re, sys
SRC, DST = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
NEW = sys.argv[3]
DIFF = [l for l in pathlib.Path(sys.argv[4]).read_text(encoding='utf-8').splitlines() if l]
CMP2 = pathlib.Path(sys.argv[5]).read_text(encoding='utf-8')
assert re.fullmatch(r'[0-9a-f]{40}', NEW)
N1 = '0d142aa213bb7049fa942e73aa4b652a35e2ed90'
n7 = NEW[:7]


def rep(s, a, b, n=1):
    c = s.count(a)
    if c != n:
        sys.exit('맞는 수 %d ≠ %d: %r' % (c, n, a[:90]))
    return s.replace(a, b)


UBOLD = '[ "${UB:-}" = O13D1 ] || stop "U 변수가 O1·O3 초안 D1 판이 아님 — F → G → O1·O3 초안 U 변수(초안은 실행하지 않는다)"'
UBNEW = '[ "${UB:-}" = O13D2 ] || stop "U 변수가 O1·O3 초안 D2 판이 아님 — F → G → O1·O3 초안 D2 U 변수(초안은 실행하지 않는다)"'
out = {}

s = (SRC / 'U변수.sh').read_text(encoding='utf-8')
s = rep(s, 'UB=O13D1   # O1·O3 다음 배포 초안 D1(미승인·실행 금지) 판 표지 — U 블록 전부가 확인한다. 후보 0d142aa(출처 candidate/o1o3-log-1899b49).',
        'UB=O13D2   # O1·O3 다음 배포 초안 D2(미승인·실행 금지, D1 대체 — jar 비교기 v2·최종 SHA) 판 표지 — U 블록 전부가 확인한다. 후보 %s(출처 candidate/o1o3-log-1899b49).' % n7)
s = rep(s, 'NEW=' + N1, 'NEW=' + NEW)
d1diff = re.search(r'DIFFN="\$\(printf \'%s\\n\' (.*?)\)"\n', s, re.S)
assert d1diff, 'D1 DIFFN 없음'
body = ' \\\n  '.join(DIFF)
s = s[:d1diff.start()] + 'DIFFN="$(printf \'%s\\n\' ' + body + ')"\n' + s[d1diff.end():]
s = rep(s, '# 실제 로컬 bootJar 대조(O1·O3 후보 0d142aa) — 1899b49·0d142aa jar 에서 새로 셈($PriceSnapshot 은 줄 번호만 다름)',
        '# 실제 로컬 bootJar 대조(O1·O3 최종 %s) — 1899b49 보존 jar·%s 로컬 bootJar 에서 jar 비교기 v2 로 새로 셈($PriceSnapshot 은 줄 번호만 다름)' % (n7, n7))
out['U변수.sh'] = s

s = (SRC / 'U0.sh').read_text(encoding='utf-8')
s = rep(s, '[ "${UB:-}" = O13D1 ] && [ "$NEW" = ' + N1 + ' ]', '[ "${UB:-}" = O13D2 ] && [ "$NEW" = ' + NEW + ' ]')
s = rep(s, 'stop "변수 순서 — F 변수 → G 변수 → O1·O3 초안 U 변수"', 'stop "변수 순서 — F 변수 → G 변수 → O1·O3 초안 D2 U 변수"')
s = rep(s, 'stop "후보 0d142aa 없음"', 'stop "후보 %s 없음"' % n7)
s = rep(s, 'stop "diff 가 정확히 O1·O3 5개 파일이 아님(마이그레이션·설정 포함 금지)"',
        'stop "diff 가 정확히 O1·O3 %d개 파일이 아님(마이그레이션·설정 포함 금지)"' % len(DIFF))
out['U0.sh'] = s

s = rep((SRC / 'U1.sh').read_text(encoding='utf-8'), UBOLD, UBNEW)
i = s.index('python3 - "$M/app-old.jar" "$M/app-new.jar" <<\'EOF\' | tee "$M/jar-diff.txt"\n')
j = s.index('\nEOF\n', i) + len('\nEOF\n')
new_cmp = ('python3 - "$M/app-old.jar" "$M/app-new.jar" > "$M/jar-diff.txt" 2>&1 <<\'EOF\' && JC=0 || JC=$?\n' + CMP2.rstrip('\n') + '\nEOF\n'
           'cat "$M/jar-diff.txt"\n'
           '[ "$JC" = 0 ] || stop "jar 비교기 v2 거절(종료 $JC) — 중복 항목·형식 이상. jar-diff.txt 를 보고(재기동 전). U1-되돌림"\n')
s = s[:i] + new_cmp + s[j:]
out['U1.sh'] = s

for n in ('U2', 'U3', 'U4', 'U되돌림', 'U뒤관측'):
    out[n + '.sh'] = rep((SRC / (n + '.sh')).read_text(encoding='utf-8'), UBOLD, UBNEW)

DST.mkdir(exist_ok=False)
for k, v in out.items():
    for bad in ('O13D1', N1, '0d142aa', '초안 D1 판'):
        if bad in v:
            sys.exit('남은 D1 표기 %r: %s' % (bad, k))
    (DST / k).write_bytes(v.encode('utf-8'))
print('완료', len(out), '블록 →', DST)
