# 시나리오 스크립트를 머리(함수 정의) + '== ' 절 묶음으로 나눠 병렬 실행용 파일 4개를 만든다(사례·단언 그대로, 순서만 묶음).
import pathlib, re, sys
s = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
head_end = s.index('\necho "== x01')
tail_start = s.index('\nF=0; for d in runs/$P-*')
head, body, tail = s[:head_end + 1], s[head_end + 1:tail_start + 1], s[tail_start + 1:]
secs = re.split(r'(?m)^(?=echo "== )', body)
secs = [x for x in secs if x.strip()]
groups = {'a': ['x01', 'x02', 'x03'], 'b': ['x04', 'x05', 'x06', '판 표지', 'i01'], 'c': ['f01', 't01', 'c01'], 'd': ['r01', 'p01', 'o01']}
used = []
for g, keys in groups.items():
    pick = [x for x in secs if any(x.startswith('echo "== ' + k) for k in keys)]
    used += pick
    pathlib.Path(sys.argv[2] + '-' + g + '.sh').write_text(head + ''.join(pick) + tail, encoding='utf-8', newline='\n')
    print(g, len(pick), [x.split('\n')[0][9:30] for x in pick])
assert len(used) == len(secs) and len(set(map(id, used))) == len(secs), (len(used), len(secs))
