# §87 T 블록(TB=87 — #24 실제 실행판, 출처 a1e38e7) → N19 개입 #25 U 블록(UB=90) 초안.
# 사용: python make_u.py <t-blocks.md> <t-hashes.json> <vals.json> <n19obs.sh> <jar-diff.txt> <out.md>
# 값·이름 바꾸기와 n19obs 추가만 한다. 나머지 줄은 §87 과 바이트가 같다(근거 t-to-u.diff).
# 전제: #24(7a94472)가 운영에 올라가 총괄이 수용한 뒤에만 실행할 수 있다. 수용 전에는 이 초안의 OLD/IMGOLD 전제가 성립하지 않는다.
import hashlib, json, pathlib, re, sys

s = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
th = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding='utf-8'))
val = json.loads(pathlib.Path(sys.argv[3]).read_text(encoding='utf-8'))
nobs = pathlib.Path(sys.argv[4]).read_text(encoding='utf-8').rstrip('\n')
jar = [l.rstrip('\r') for l in pathlib.Path(sys.argv[5]).read_text(encoding='utf-8').split('\n') if l.strip()]
assert jar[-1] == val['END'], jar[-1]
RARX = jar[:-1]


def bash_blocks(text):
    out, lines, i = [], text.split('\n'), 0
    while i < len(lines):
        if lines[i].strip() == '```bash':
            j = i + 1
            while lines[j].strip() != '```':
                j += 1
            out.append('\n'.join(lines[i + 1:j]) + '\n')
            i = j
        i += 1
    return out


# 입력 T 블록이 고정 출처 a1e38e7 §87 원문과 바이트가 같은지 먼저 본다
names = ['T변수', 'T0', 'T1', 'T2', 'T3', 'T4', 'T뒤관측', 'T되돌림']
got = [hashlib.sha256(b.encode()).hexdigest() for b in bash_blocks(s)]
assert got == [th[n]['sha256'] for n in names], '입력 T 블록이 a1e38e7 §87 과 다름'


def rep(a, b, n=1):
    global s
    c = s.count(a)
    if c != n:
        raise SystemExit('count %d != %d: %r' % (c, n, a[:120]))
    s = s.replace(a, b)


NEW, NEW7, OLD, OLD7 = val['NEW'], val['NEW'][:7], val['OLD'], val['OLD'][:7]
UB, SEC, VER = val['UB'], val['SEC'], val['VER']
IMG24 = val['IMG24']
IMG23 = 'sha256:9818254ca137a79245d1b30f07c0e193ca4d9a1ac028e0b26a16af6853616b62'

# ── 변수 블록 ──
rep('**T 변수** — 🔴 새 셸마다', '**U 변수** — 🔴 새 셸마다')
rep('H~R 변수는 넣지 않는다.', 'H~T 변수는 넣지 않는다.')
rep('M=~/maint-n17; mkdir -p "$M"; OPS=~/ops-scripts; TB=87   # v1.119(§5-9 87 블록) 판 표지 — T 블록 전부가 확인한다. 후보 7a94472(출처 candidate/n17-rest-price-1103f48). #23 기록 폴더 ~/maint-n15n16 은 읽기만 하고 덮어쓰지 않는다',
    'M=~/maint-n19; mkdir -p "$M"; OPS=~/ops-scripts; UB=%s   # v%s(§5-9 %s 블록) 판 표지 — U 블록 전부가 확인한다. 후보 %s(출처 %s). #24 기록 폴더 ~/maint-n17·#23 기록 폴더 ~/maint-n15n16 은 읽기만 하고 덮어쓰지 않는다' % (UB, VER, SEC, NEW7, val['SRC']))
rep('OLD=1103f48505a1457029f6555547a87aa986535738; NEW=7a944723c2959898226c2ad80f6b079ab5678296', 'OLD=%s; NEW=%s' % (OLD, NEW))
rep('IMGOLD=%s   # #23 배포 이미지(지금 실행 중, ~/maint-n15n16/image-new.txt)' % IMG23,
    'IMGOLD=%s   # #24 배포 이미지(지금 실행 중, ~/maint-n17/image-new.txt) — #24 결과 수용이 전제' % IMG24)
rep('(#23 R1 이 만듦, 보존, 바꾸지 않는다)',
    '(#23 R1 이 만듦, 보존, 바꾸지 않는다)\nIMGPREV5=%s   # #23 이미지 — 이전 복구 태그 autotrader-backend:1103f48 가 가리킨다(@@STEP24@@, 보존, 바꾸지 않는다)' % IMG23)
m = re.search(r"DIFFN=\"\$\(printf '%s\\n' web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService\.java \\\n(?:  .*\\\n)*  .*\)\"", s)
assert m, 'DIFFN'
s = s[:m.start()] + 'DIFFN="$(printf \'%%s\\n\' %s)"' % ' \\\n  '.join(val['DIFF']) + s[m.end():]
m = re.search(r"RARX=\"\$\(printf '%s\\n' '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/DynamicTradingService\.class' \\\n(?:  .*\\\n)*  .*\)\"   # 실제 bootJar 대조\(v1\.119\) — #23 기대값을 옮기지 않고 1103f48·7a94472 jar 에서 새로 셈", s)
assert m, 'RARX'
s = s[:m.start()] + 'RARX="$(printf \'%%s\\n\' %s)"   # 실제 bootJar 대조(v%s) — #24 기대값을 옮기지 않고 %s·%s jar 에서 새로 셈' % (
    ' \\\n  '.join("'%s'" % x for x in RARX), VER, OLD7, NEW7) + s[m.end():]
# n19obs 를 n17obs 함수 뒤(변수 블록 끝)에 더한다
rep('    print("N17: 미관측 — 이 구간에 자연 발생한 REST 강제 갱신 가격 이상 없음(유도하지 않는다)")\nsys.exit(0)\nPYEOF\n}\n```',
    '    print("N17: 미관측 — 이 구간에 자연 발생한 REST 강제 갱신 가격 이상 없음(유도하지 않는다)")\nsys.exit(0)\nPYEOF\n}\n' + nobs + '\n```')

# ── 옛 단계 이름·심각도 보호 ──
PROT = [('#22 P1 이 만듦', '@@P22@@'), ('#23 R1 이 만듦', '@@R23@@'), ('🔴 P0 — ', '\x00SEV1\x00'), ('🔴 P0: ', '\x00SEV2\x00')]
for a, b in PROT:
    s = s.replace(a, b)

# ── 판 표지 ──
rep('[ "$M" = "$HOME/maint-n17" ] && [ "${TB:-}" = 87 ] && [ "$NEW" = 7a944723c2959898226c2ad80f6b079ab5678296 ] && [ -n "${BTCIN:-}" ] && type sid203 > /dev/null 2>&1 && type newsobs > /dev/null 2>&1 && type httpobs > /dev/null 2>&1 && type holdobs > /dev/null 2>&1 && type entryobs > /dev/null 2>&1 && type n17obs > /dev/null 2>&1 || stop "변수 순서 — F 변수 → G 변수 → §5-9 87 T 변수"',
    '[ "$M" = "$HOME/maint-n19" ] && [ "${UB:-}" = %s ] && [ "$NEW" = %s ] && [ -n "${BTCIN:-}" ] && type sid203 > /dev/null 2>&1 && type newsobs > /dev/null 2>&1 && type httpobs > /dev/null 2>&1 && type holdobs > /dev/null 2>&1 && type entryobs > /dev/null 2>&1 && type n17obs > /dev/null 2>&1 && type n19obs > /dev/null 2>&1 || stop "변수 순서 — F 변수 → G 변수 → §5-9 %s U 변수"' % (UB, NEW, SEC))
rep('[ "${TB:-}" = 87 ] || stop "T 변수가 v1.119(§5-9 87 블록) 판이 아님 — F → G → §5-9 87 T 변수"',
    '[ "${UB:-}" = %s ] || stop "U 변수가 v%s(§5-9 %s 블록) 판이 아님 — F → G → §5-9 %s U 변수"' % (UB, VER, SEC, SEC), 6)
s = re.sub(r'ok-T([0-4])', r'ok-U\1', s)
s = s.replace('T1-되돌림', 'U1-되돌림')
rep('**T2. 재기동 (개입 #24) — T1 통과 뒤**', '**U2. 재기동 (개입 #25) — U1 통과 뒤**')
rep('**T3. 기동·뉴스·HTTP 줄·HOLD 질의·N15/N16·N17 줄 확인 — T2 5~10분 뒤**', '**U3. 기동·뉴스·HTTP 줄·HOLD 질의·N15/N16·N17·N19 줄 확인 — U2 5~10분 뒤**')
rep('**T 뒤 관측(뉴스·HTTP 줄·HOLD 질의·N15/N16·N17 줄) — 읽기 전용, 반복 가능** (T4 통과 뒤', '**U 뒤 관측(뉴스·HTTP 줄·HOLD 질의·N15/N16·N17·N19 줄) — 읽기 전용, 반복 가능** (U4 통과 뒤')
rep('**T 되돌림 — backend (T2~T4 의 "되돌림 필요" 때, 또는 총괄 지시 뒤)** (DB 마이그레이션·장부 변경이 없으므로 데이터 작업은 없다. 1103f48 로 돌아가면 N17 보완(REST 강제 갱신 0·음수의 INVALID_PRICE 분류·미발행·가격 이상 문구)이 다시 없어진다. N15·N16 방어는 1103f48 에도 있으므로 유지된다)',
    '**U 되돌림 — backend (U2~U4 의 "되돌림 필요" 때, 또는 총괄 지시 뒤)** (DB 마이그레이션·장부 변경이 없으므로 데이터 작업은 없다. 7a94472 로 돌아가면 N19 보완(REST 전역 폴백의 0·음수·형식 이상 항목만 건너뛰기)이 다시 없어진다. N15·N16·N17 방어는 7a94472 에도 있으므로 유지된다)')
rep('T 되돌림 블록 준비됨', 'U 되돌림 블록 준비됨', 2)
s = re.sub(r'(?<![A-Za-z0-9_])T([0-4])(?![0-9])', r'U\1', s)
for a, b in PROT:
    s = s.replace(b, a)
s = s.replace('@@STEP24@@', '#24 T1 이 만듦')

# ── U0 값 ──
rep('stop "HEAD 가 1103f48 가 아니거나 추적 파일 변경 있음"', 'stop "HEAD 가 %s 가 아니거나 추적 파일 변경 있음"' % OLD7)
rep('stop "후보 7a94472 없음"', 'stop "후보 %s 없음"' % NEW7)
rep('stop "1103f48 가 후보의 조상이 아님"', 'stop "%s 가 후보의 조상이 아님"' % OLD7)
rep('stop "diff 가 정확히 N17 4개 파일이 아님(마이그레이션·설정 포함 금지)"',
    'stop "diff 가 정확히 N19 %d개 파일이 아님(마이그레이션·설정 포함 금지)"' % len(val['DIFF']))
rep('[ "$IMG0" = "$IMGOLD" ] && [ "$IMGOLD" = "$(cat ~/maint-n15n16/image-new.txt)" ] || stop "실행 이미지가 #23 배포 이미지(9818254c…)와 다름"',
    '[ "$IMG0" = "$IMGOLD" ] && [ "$IMGOLD" = "$(cat ~/maint-n17/image-new.txt)" ] || stop "실행 이미지가 #24 배포 이미지(%s…)와 다름"' % IMG24[7:15])
rep('[ "$(docker image inspect --format \'{{.Id}}\' $REPO:fe01207)" = "$IMGPREV4" ] || stop "이전 복구 태그 $REPO:fe01207 이 #22 이미지(9cb5d84f…)가 아님 — 보고"',
    '[ "$(docker image inspect --format \'{{.Id}}\' $REPO:fe01207)" = "$IMGPREV4" ] || stop "이전 복구 태그 $REPO:fe01207 이 #22 이미지(9cb5d84f…)가 아님 — 보고"\n'
    '[ "$(docker image inspect --format \'{{.Id}}\' $REPO:1103f48)" = "$IMGPREV5" ] || stop "이전 복구 태그 $REPO:1103f48 이 #23 이미지(9818254c…)가 아님 — 보고"')
rep('echo "== 배포 전 기준선: N15·N16 줄(entryobs 종료 $ED — 1103f48 실행 중 로그, 기록만)"; cat "$M/entry-before.txt"',
    'echo "== 배포 전 기준선: N15·N16 줄(entryobs 종료 $ED — %s 실행 중 로그, 기록만)"; cat "$M/entry-before.txt"' % OLD7)
rep('echo "== 배포 전 기준선: N17 줄(1103f48 에는 이 줄이 없으므로 미관측이 기대, 기록만)"; cat "$M/n17-before.txt"',
    'echo "== 배포 전 기준선: N17 줄(%s 실행 중 로그, 기록만)"; cat "$M/n17-before.txt"\n'
    'n19obs "$M/backend-before.log" > "$M/n19-before.txt" || stop "n19obs 인자 오류 — n19-before.txt 를 보고"\n'
    'echo "== 배포 전 기준선: N19 줄(%s 에는 이 줄이 없으므로 미관측이 기대, 기록만)"; cat "$M/n19-before.txt"' % (OLD7, OLD7))
# ── U1 ──
rep('if docker image inspect $REPO:1103f48 > /dev/null 2>&1; then [ "$(docker image inspect --format \'{{.Id}}\' $REPO:1103f48)" = "$IMG0" ] || stop "$REPO:1103f48 태그가 이미 다른 이미지를 가리킨다(HEAD·latest 그대로, U1-되돌림 하지 않는다)"; fi',
    'if docker image inspect $REPO:%s > /dev/null 2>&1; then [ "$(docker image inspect --format \'{{.Id}}\' $REPO:%s)" = "$IMG0" ] || stop "$REPO:%s 태그가 이미 다른 이미지를 가리킨다(HEAD·latest 그대로, U1-되돌림 하지 않는다)"; fi' % (OLD7, OLD7, OLD7))
rep('docker tag "$IMG0" $REPO:1103f48', 'docker tag "$IMG0" $REPO:%s' % OLD7)
rep('[ "$(docker image inspect --format \'{{.Id}}\' $REPO:1103f48)" = "$IMG0" ] || stop "기존 이미지 보존 태그 실패(HEAD·latest 그대로, U1-되돌림 하지 않는다)"',
    '[ "$(docker image inspect --format \'{{.Id}}\' $REPO:%s)" = "$IMG0" ] || stop "기존 이미지 보존 태그 실패(HEAD·latest 그대로, U1-되돌림 하지 않는다)"' % OLD7)
rep('stop "jar 차이가 N17 6줄이 아님 — jar-diff.txt 를 보고(재기동 전). U1-되돌림"',
    'stop "jar 차이가 N19 %d줄이 아님 — jar-diff.txt 를 보고(재기동 전). U1-되돌림"' % len(RARX))
rep("grep -qx '(끝) 881 881' \"$M/jar-diff.txt\" || stop \"항목 수가 881 → 881 가 아님 — jar-diff.txt 를 보고(재기동 전). U1-되돌림\"",
    "grep -qx '%s' \"$M/jar-diff.txt\" || stop \"항목 수가 %s 가 아님 — jar-diff.txt 를 보고(재기동 전). U1-되돌림\"" % (val['END'], val['END_TEXT']))
rep('→ `1103f485…`·`image-before.txt` 와 같은 ID. 재기동하지 않는다. 그 밖의 U1 멈춤은 출력만 보고한다. 보존 태그 `autotrader-backend:1103f48` 는 지금 이미지를 가리키므로 남겨 둔다.',
    '→ `%s…`·`image-before.txt` 와 같은 ID. 재기동하지 않는다. 그 밖의 U1 멈춤은 출력만 보고한다. 보존 태그 `autotrader-backend:%s` 는 지금 이미지를 가리키므로 남겨 둔다.' % (OLD[:8], OLD7))
# ── U3 ──
rep('n17obs "$M/app-$T.log" | tee "$M/n17obs-$T.txt" || stop "n17obs 인자 오류 — 위 출력을 보고"\n',
    'n17obs "$M/app-$T.log" | tee "$M/n17obs-$T.txt" || stop "n17obs 인자 오류 — 위 출력을 보고"\n'
    'n19obs "$M/app-$T.log" | tee "$M/n19obs-$T.txt" || stop "n19obs 인자 오류 — 위 출력을 보고"\n')
rep('else echo "✅ U3(rollback) 통과 — 1103f48 복귀 확인. U4 는 하지 않고 출력을 보고"; fi',
    'else echo "✅ U3(rollback) 통과 — %s 복귀 확인. U4 는 하지 않고 출력을 보고"; fi' % OLD7)
# ── U4 ──
rep('n17obs "$M/app-after30.log" | tee "$M/n17obs-after30.txt" || stop "n17obs 인자 오류 — 위 출력을 보고"\n',
    'n17obs "$M/app-after30.log" | tee "$M/n17obs-after30.txt" || stop "n17obs 인자 오류 — 위 출력을 보고"\n'
    'echo "== N19 줄(기동 뒤 30분 로그 · 자연 발생만)"\n'
    'n19obs "$M/app-after30.log" | tee "$M/n19obs-after30.txt" || stop "n19obs 인자 오류 — 위 출력을 보고"\n')
rep('grep -q "^N17: 미관측" "$M/n17obs-after30.txt" && echo "⚪ N17 REST 강제 갱신 가격 이상 미관측 — 운영 반영은 HEAD·이미지·jar 로만 확인, 동작 관측은 미관측으로 보고(INVALID_PRICE 를 주입하지 않는다)" || true',
    'grep -q "^N17: 미관측" "$M/n17obs-after30.txt" && echo "⚪ N17 REST 강제 갱신 가격 이상 미관측 — 동작 관측은 미관측으로 보고(INVALID_PRICE 를 주입하지 않는다)" || true\n'
    'grep -q "^N19: 미관측" "$M/n19obs-after30.txt" && echo "⚪ N19 REST 전역 폴백 항목 건너뜀 미관측 — 운영 반영은 HEAD·이미지·jar 로만 확인, 동작 관측은 미관측으로 보고(WS 를 끊거나 이상 응답을 주입하지 않는다)" || true')
# ── U 뒤 관측 ──
rep('n17obs "$M/app-news-$TS.log" | tee "$M/n17obs-$TS.txt" || echo "⚪ n17obs 인자 오류 — 위 출력을 보고"',
    'n17obs "$M/app-news-$TS.log" | tee "$M/n17obs-$TS.txt" || echo "⚪ n17obs 인자 오류 — 위 출력을 보고"\n'
    'echo "== 기동 뒤 N19 줄(자연 발생만)"\n'
    'n19obs "$M/app-news-$TS.log" | tee "$M/n19obs-$TS.txt" || echo "⚪ n19obs 인자 오류 — 위 출력을 보고"')
# ── U 되돌림 ──
rep('stop "image-before 가 #23 이미지(9818254c…)가 아님"', 'stop "image-before 가 #24 이미지(%s…)가 아님"' % IMG24[7:15])
rep('echo "✅ 1103f48 (9818254c…) 로 복귀 — 5분 뒤 U3 를 다시 실행(t-rollback.utc 기준으로 자동 전환, BTC·뉴스·HTTP 줄·HOLD 질의·N15/N16·N17 줄은 기록만)"',
    'echo "✅ %s (%s…) 로 복귀 — 5분 뒤 U3 를 다시 실행(t-rollback.utc 기준으로 자동 전환, BTC·뉴스·HTTP 줄·HOLD 질의·N15/N16·N17·N19 줄은 기록만)"' % (OLD7, IMG24[7:15]))

# 남은 옛 값 검사(설명·보존 태그 확인 줄의 허용 개수 고정)
pathlib.Path(sys.argv[6] + ".dbg").write_text(s, encoding='utf-8', newline='\n')   # 검사 전 사본(검사 실패 때 원인 확인용)
assert s.count('v1.119') == 1 and '# N17 줄 관측(v1.119)' in s, 'v1.119 는 n17obs 원래 주석 1곳만'
for bad in ['TB=', '${TB', 'TB:-', 'ok-T', '§5-9 87','maint-n15n16/image-new', '**T 변수**', '§5-9 87 T', 'T 되돌림', 'T 뒤 관측', '개입 #24']:
    if bad in s.replace('#24 T1 이 만듦', ''):
        raise SystemExit('남은 옛 값: %r' % bad)
assert s.count('#24 T1 이 만듦') == 1
left = re.findall(r'(?<![A-Za-z0-9_])T[0-4](?![0-9])', s.replace('#24 T1 이 만듦', ''))
assert not left, '남은 옛 단계 이름 %r' % left
pathlib.Path(sys.argv[6]).write_text(s, encoding='utf-8', newline='\n')
print('ok', len(s.split('\n')))
