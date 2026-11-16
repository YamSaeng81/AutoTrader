# V172D4 U2 보정 생성·대조기(총괄 v1.174 §2-2~5). 사용: python -I -X utf8 mku2c.py
# 1) ce6df2fa 의 blocks-d4/U2.sh(2feef9e1…)를 git 에서 꺼내 해시를 확인하고, 기대값 한 곳 "96:t:$V96SUM" → "96:true:$V96SUM" 만 바꾼 교정 사본 U2-교정.sh 를 쓴다(이번 기동에 실행하지 않는 참조 원문).
# 2) 손으로 쓴 U2확인.sh 를 대조한다: V96 이력 SQL 이 U2 원문의 표·조건·내용식에 행수(count)와 NULL 체크섬 표시를 더한 한 SELECT 인지(r3), 기대값이 "1|" + 교정 사본 형식인지, 금지 명령이 없는지, ok-U2 쓰기가 마지막 한 줄뿐이고
#    U2 꼬리 이름(svc-deploy·git-deploy, U4 원문이 읽음) 쓰기가 그 바로 앞 한 곳뿐인지, P0 검사가 DB 조회·이미지 확인보다 앞인지.
# 3) blocks-u2c.sha256 을 쓴다. 하나라도 어긋나면 종료 1(파일은 쓰지 않는다).
import hashlib, pathlib, re, subprocess, sys
REPO = 'D:/Claude Code/projects/crypto-auto-trader'
SRC = 'ce6df2fa2b1b4a0b089576bc700fb867a466686d'
D = pathlib.Path(__file__).resolve().parent
H = lambda b: hashlib.sha256(b).hexdigest()
bad = []
def need(c, m):
    if not c: bad.append(m)
u2 = subprocess.run(['git', '-C', REPO, 'show', SRC + ':docs/evidence/v172d4-20261116/d4/blocks-d4/U2.sh'], capture_output=True, check=True).stdout
need(H(u2) == '2feef9e129d07d2e9a1607c16f5cccb17f51eb3c53325dd313e5e7009a2b77e5', 'U2 원문 해시')
t = u2.decode('utf-8')
A, B = '= "96:t:$V96SUM" ]', '= "96:true:$V96SUM" ]'
need(t.count(A) == 1 and t.count(B) == 0, 'U2 원문의 기대값 자리 수')
fix = t.replace(A, B)
need(sum(1 for x, y in zip(t.splitlines(), fix.splitlines()) if x != y) == 1 and len(t.splitlines()) == len(fix.splitlines()), '교정 사본은 한 줄만 다름')
SQL = re.search(r'\$\(\$PSQL -At -c "(SELECT string_agg\(version[^"]*)"\)', t).group(1)
c = (D / 'U2확인.sh').read_bytes(); need(b'\r' not in c, 'U2확인 CR 없음'); s = c.decode('utf-8')
need(s.startswith('(\nset -euo pipefail\n') and s.endswith('\n)\n'), 'U2확인 블록 틀')
SQL2 = ("SELECT count(*) || '|' || coalesce(string_agg(version || ':' || success || ':' || coalesce(checksum::text, 'NULL'), ',' ORDER BY installed_rank), '-') "
        "FROM flyway_schema_history WHERE version::int >= 95")   # r3(후속 메모 §2): 행수·내용을 한 SELECT 로, 체크섬 NULL 도 글자로
need(SQL == "SELECT string_agg(version || ':' || success || ':' || checksum, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE version::int >= 95", 'U2 원문 SQL')
need(SQL.split(' FROM ', 1)[1] == SQL2.split(' FROM ', 1)[1] and "version || ':' || success || ':' || " in SQL2, 'U2확인 SQL 의 표·조건·내용식이 U2 원문과 같음')
need(s.count('V96H=$($PSQL -At -c "' + SQL2 + '")') == 1, 'U2확인 V96 이력 SQL = 행수·내용 한 SELECT')
code = '\n'.join(l for l in s.splitlines() if not l.lstrip().startswith('#'))
need(code.count('[ "$V96H" = "1|96:true:$V96SUM" ]') == 1 and '96:t:' not in code and code.count('96:true:') == 1, 'U2확인 기대값 = 행수 1 + 교정 사본 형식(96:true:), 실행 줄에 t 허용 없음')
for p in [r'\$DC (up|build|restart|stop|start|rm|down|pull|create)\b', r'docker (tag|rmi|run|restart|stop|start|rm|pull|build|image rm|compose)\b', r'git (checkout|reset|fetch|pull|merge|switch|clean|stash)\b',
          r'\b(INSERT|UPDATE|DELETE|ALTER|CREATE|DROP|TRUNCATE|GRANT|COPY)\b', r'\btouch\b', r'\brm -', r'\bsleep\b', r'docker image inspect']:
    need(not re.search(p, s), '금지 명령 없음: ' + p)
L = s.splitlines()
okw = [i for i, l in enumerate(L) if '"$M/ok-U2"' in l and ('>' in l.split('"$M/ok-U2"')[0][-3:])]
need(len(okw) == 1 and okw[0] == len(L) - 2, 'ok-U2 쓰기는 마지막 실행 줄 한 곳')
cpw = [i for i, l in enumerate(L) if l.startswith('cp ')]
need(len(cpw) == 1 and cpw[0] == len(L) - 4 and L[cpw[0]] == 'cp "$M/svc-u2c-$TS.txt" "$M/svc-deploy.txt"; cp "$M/git-u2c-$TS.txt" "$M/git-deploy.txt"', 'U2 꼬리 이름(svc-deploy·git-deploy) 쓰기는 모든 확인 뒤 ok-U2 바로 앞 한 곳')
need(sum(l.count('"$M/svc-deploy.txt"') for l in L) == 4 and sum(l.count('"$M/git-deploy.txt"') for l in L) == 4, 'svc-deploy·git-deploy 언급은 없음 확인 2·쓰기 1·대조 1')
first = lambda pat: next(i for i, l in enumerate(L) if pat in l)
p0 = first('holdobs "$M/app-u2c-$TS.log"')
for pat in ['same3 ', 'isimg ', 'sid203 ||', '$(sc)', 'v96 ||', 'V96H=$(', 'git rev-parse HEAD', 'docker inspect']:
    need(p0 < first(pat), 'P0 검사가 앞: ' + pat)
need(first('$DC logs --no-color --since "$S" backend') < p0, '로그 보존 뒤 검사')
if bad:
    print('어긋남:', *bad, sep='\n  '); sys.exit(1)
(D / 'U2-교정.sh').write_bytes(fix.encode('utf-8'))
out = {'U2확인.sh': H(c), 'U2-교정.sh': H(fix.encode('utf-8'))}
(D / 'blocks-u2c.sha256').write_text(''.join('%s *%s\n' % (h, n) for n, h in out.items()), encoding='utf-8', newline='\n')
print('U2 원문 2feef9e1 확인 · 교정 한 줄 · U2확인 SQL = 행수·내용 한 SELECT(표·조건·내용식 U2 원문과 같음) · 금지 명령 0 · ok-U2 마지막 한 줄 · P0 먼저')
for n, h in out.items(): print(h, n)
