# §81 R 블록(RV=81 — #23 실제 실행판, 총괄 v1.116 수용) → N17 개입 #24 T 블록(TB=87) 초안.
# 사용: python make_t.py <r-blocks.md> <vals.json> <n17obs.sh> <jar-diff.txt> <out.md>
# 값·이름 바꾸기와 n17obs 추가만 한다. 나머지 줄은 §81 과 바이트가 같다(근거 r-to-t.diff).
# 'S' 는 최종 원본 S 와 헷갈리므로 쓰지 않고 다음 글자 T 를 쓴다. 판 표지 변수는 옛 I 블록의 경로 변수 TV 와 겹치지 않게 TB 다.
import json, pathlib, re, sys

s = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
val = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding='utf-8'))
nobs = pathlib.Path(sys.argv[3]).read_text(encoding='utf-8').rstrip('\n')
jar = [l.rstrip('\r') for l in pathlib.Path(sys.argv[4]).read_text(encoding='utf-8').split('\n') if l.strip()]
assert jar[-1] == val['END'], jar[-1]
RARX = jar[:-1]


def rep(a, b, n=1):
    global s
    c = s.count(a)
    if c != n:
        raise SystemExit('count %d != %d: %r' % (c, n, a[:120]))
    s = s.replace(a, b)


NEW, NEW7, OLD = val['NEW'], val['NEW'][:7], val['OLD']
TB, SEC, VER = val['TB'], val['SEC'], val['VER']
IMG23 = 'sha256:9818254ca137a79245d1b30f07c0e193ca4d9a1ac028e0b26a16af6853616b62'
IMG22 = 'sha256:9cb5d84f0bfde5208f24f591ad291dacabdfa691a0b2e1f057c4f8c3e1720fb7'

# ── 변수 블록 ──
rep('**R 변수** — 🔴 새 셸마다', '**T 변수** — 🔴 새 셸마다')
rep('H~P 변수는 넣지 않는다.', 'H~R 변수는 넣지 않는다.')
rep('M=~/maint-n15n16; mkdir -p "$M"; OPS=~/ops-scripts; RV=81   # v1.113(§5-9 81 블록) 판 표지 — R 블록 전부가 확인한다. 후보 1103f48(출처 candidate/n15-n16-integration-fe01207 의 시험 격리 커밋). 옛 §5-9 79 초안(RV=79)은 실행하지 않는다',
    'M=~/maint-n17; mkdir -p "$M"; OPS=~/ops-scripts; TB=%s   # v%s(§5-9 %s 블록) 판 표지 — T 블록 전부가 확인한다. 후보 %s(출처 %s). #23 기록 폴더 ~/maint-n15n16 은 읽기만 하고 덮어쓰지 않는다' % (TB, VER, SEC, NEW7, val['SRC']))
rep('OLD=fe01207f3f60e97e308925157f84a1bb2da04388; NEW=1103f48505a1457029f6555547a87aa986535738',
    'OLD=%s; NEW=%s' % (OLD, NEW))
rep('IMGOLD=%s   # #22 배포 이미지(지금 실행 중, ~/maint-n13/image-new.txt)' % IMG22,
    'IMGOLD=%s   # #23 배포 이미지(지금 실행 중, ~/maint-n15n16/image-new.txt)' % IMG23)
rep('IMGPREV3=sha256:67294e80ada31d346e36caeb01a2c259b6b5514357c804977ba1d20fba6aec07   # #21 이미지 — 이전 복구 태그 autotrader-backend:6995c52 가 가리킨다(#22 P1 이 만듦, 보존, 바꾸지 않는다)',
    'IMGPREV3=sha256:67294e80ada31d346e36caeb01a2c259b6b5514357c804977ba1d20fba6aec07   # #21 이미지 — 이전 복구 태그 autotrader-backend:6995c52 가 가리킨다(#22 P1 이 만듦, 보존, 바꾸지 않는다)\n'
    'IMGPREV4=%s   # #22 이미지 — 이전 복구 태그 autotrader-backend:fe01207 가 가리킨다(@@STEP23@@, 보존, 바꾸지 않는다)' % IMG22)
m = re.search(r"DIFFN=\"\$\(printf '%s\\n' web-api/src/main/java/com/cryptoautotrader/api/dto/OrderRequest\.java \\\n(?:  .*\\\n)*  .*\)\"", s)
assert m, 'DIFFN'
s = s[:m.start()] + 'DIFFN="$(printf \'%%s\\n\' %s)"' % ' \\\n  '.join(val['DIFF']) + s[m.end():]
m = re.search(r"RARX=\"\$\(printf '%s\\n' '변경 BOOT-INF/classes/com/cryptoautotrader/api/dto/OrderRequest\.class' \\\n(?:  .*\\\n)*  .*\)\"   # 실제 bootJar 대조\(v1\.113\) — #22 기대값을 옮기지 않고 fe01207·1103f48 jar 에서 새로 셈", s)
assert m, 'RARX'
s = s[:m.start()] + 'RARX="$(printf \'%%s\\n\' %s)"   # 실제 bootJar 대조(v%s) — #23 기대값을 옮기지 않고 1103f48·%s jar 에서 새로 셈' % (
    ' \\\n  '.join("'%s'" % x for x in RARX), VER, NEW7) + s[m.end():]
# n17obs 를 entryobs 함수 뒤(변수 블록 끝)에 더한다
rep('sys.exit(1 if seen["만료 사유 기록 실패"] else 0)\nPYEOF\n}\n```', 'sys.exit(1 if seen["만료 사유 기록 실패"] else 0)\nPYEOF\n}\n' + nobs + '\n```')

# ── 심각도 표기·옛 단계 이름 보호 ──
SEV = [('#22 P1 이 만듦', '@@STEP22@@'), ('🔴 P0 — ', '\x00SEV1\x00'), ('🔴 P0: ', '\x00SEV2\x00')]
for a, b in SEV:
    s = s.replace(a, b)

# ── 판 표지 ──
rep('[ "$M" = "$HOME/maint-n15n16" ] && [ "${RV:-}" = 81 ] && [ "$NEW" = 1103f48505a1457029f6555547a87aa986535738 ] && [ -n "${BTCIN:-}" ] && type sid203 > /dev/null 2>&1 && type newsobs > /dev/null 2>&1 && type httpobs > /dev/null 2>&1 && type holdobs > /dev/null 2>&1 && type entryobs > /dev/null 2>&1 || stop "변수 순서 — F 변수 → G 변수 → §5-9 81 R 변수"',
    '[ "$M" = "$HOME/maint-n17" ] && [ "${TB:-}" = %s ] && [ "$NEW" = %s ] && [ -n "${BTCIN:-}" ] && type sid203 > /dev/null 2>&1 && type newsobs > /dev/null 2>&1 && type httpobs > /dev/null 2>&1 && type holdobs > /dev/null 2>&1 && type entryobs > /dev/null 2>&1 && type n17obs > /dev/null 2>&1 || stop "변수 순서 — F 변수 → G 변수 → §5-9 %s T 변수"' % (TB, NEW, SEC))
rep('[ "${RV:-}" = 81 ] || stop "R 변수가 v1.113(§5-9 81 블록) 판이 아님 — F → G → §5-9 81 R 변수"',
    '[ "${TB:-}" = %s ] || stop "T 변수가 v%s(§5-9 %s 블록) 판이 아님 — F → G → §5-9 %s T 변수"' % (TB, VER, SEC, SEC), 6)
s = re.sub(r'ok-R([0-4])', r'ok-T\1', s)
s = s.replace('R1-되돌림', 'T1-되돌림')
rep('**R2. 재기동 (개입 #23) — R1 통과 뒤**', '**T2. 재기동 (개입 #24) — T1 통과 뒤**')
rep('**R3. 기동·뉴스·HTTP 줄·HOLD 질의·N15/N16 줄 확인 — R2 5~10분 뒤**', '**T3. 기동·뉴스·HTTP 줄·HOLD 질의·N15/N16·N17 줄 확인 — T2 5~10분 뒤**')
rep('**R 뒤 관측(뉴스·HTTP 줄·HOLD 질의·N15/N16 줄) — 읽기 전용, 반복 가능** (R4 통과 뒤', '**T 뒤 관측(뉴스·HTTP 줄·HOLD 질의·N15/N16·N17 줄) — 읽기 전용, 반복 가능** (T4 통과 뒤')
rep('**R 되돌림 — backend (R2~R4 의 "되돌림 필요" 때, 또는 총괄 지시 뒤)** (DB 마이그레이션·장부 변경이 없으므로 데이터 작업은 없다. 구판으로 돌아가면 N15 승인 실행 만료와 N16 가격·관측 시각 방어가 다시 없어진다. 이미 커밋된 의도·주문은 기존 추적·정산 경로가 처리한다)',
    '**T 되돌림 — backend (T2~T4 의 "되돌림 필요" 때, 또는 총괄 지시 뒤)** (DB 마이그레이션·장부 변경이 없으므로 데이터 작업은 없다. 1103f48 로 돌아가면 N17 보완(REST 강제 갱신 0·음수의 INVALID_PRICE 분류·미발행·가격 이상 문구)이 다시 없어진다. N15·N16 방어는 1103f48 에도 있으므로 유지된다)')
rep('R 되돌림 블록 준비됨', 'T 되돌림 블록 준비됨', 2)
s = re.sub(r'(?<![A-Za-z0-9_])R([0-4])(?![0-9])', r'T\1', s)
for a, b in SEV:
    s = s.replace(b, a)
s = s.replace('@@STEP23@@', '#23 R1 이 만듦')

# ── T0 값 ──
rep('stop "HEAD 가 fe01207 가 아니거나 추적 파일 변경 있음"', 'stop "HEAD 가 1103f48 가 아니거나 추적 파일 변경 있음"')
rep('stop "후보 1103f48 없음"', 'stop "후보 %s 없음"' % NEW7)
rep('stop "fe01207 가 후보의 조상이 아님"', 'stop "1103f48 가 후보의 조상이 아님"')
rep('stop "diff 가 정확히 N15·N16 12개 파일이 아님(마이그레이션·설정 포함 금지)"',
    'stop "diff 가 정확히 N17 %d개 파일이 아님(마이그레이션·설정 포함 금지)"' % len(val['DIFF']))
rep('[ "$IMG0" = "$IMGOLD" ] && [ "$IMGOLD" = "$(cat ~/maint-n13/image-new.txt)" ] || stop "실행 이미지가 #22 배포 이미지(9cb5d84f…)와 다름"',
    '[ "$IMG0" = "$IMGOLD" ] && [ "$IMGOLD" = "$(cat ~/maint-n15n16/image-new.txt)" ] || stop "실행 이미지가 #23 배포 이미지(9818254c…)와 다름"')
rep('[ "$(docker image inspect --format \'{{.Id}}\' $REPO:6995c52)" = "$IMGPREV3" ] || stop "이전 복구 태그 $REPO:6995c52 이 #21 이미지(67294e80…)가 아님 — 보고"',
    '[ "$(docker image inspect --format \'{{.Id}}\' $REPO:6995c52)" = "$IMGPREV3" ] || stop "이전 복구 태그 $REPO:6995c52 이 #21 이미지(67294e80…)가 아님 — 보고"\n'
    '[ "$(docker image inspect --format \'{{.Id}}\' $REPO:fe01207)" = "$IMGPREV4" ] || stop "이전 복구 태그 $REPO:fe01207 이 #22 이미지(9cb5d84f…)가 아님 — 보고"')
rep('echo "== 배포 전 기준선: N15·N16 줄(entryobs 종료 $ED — fe01207 에는 이 줄들이 없으므로 미관측이 기대, 기록만)"; cat "$M/entry-before.txt"',
    'echo "== 배포 전 기준선: N15·N16 줄(entryobs 종료 $ED — 1103f48 실행 중 로그, 기록만)"; cat "$M/entry-before.txt"\n'
    'n17obs "$M/backend-before.log" > "$M/n17-before.txt" || stop "n17obs 인자 오류 — n17-before.txt 를 보고"\n'
    'echo "== 배포 전 기준선: N17 줄(1103f48 에는 이 줄이 없으므로 미관측이 기대, 기록만)"; cat "$M/n17-before.txt"')
# ── T1 ──
rep('if docker image inspect $REPO:fe01207 > /dev/null 2>&1; then [ "$(docker image inspect --format \'{{.Id}}\' $REPO:fe01207)" = "$IMG0" ] || stop "$REPO:fe01207 태그가 이미 다른 이미지를 가리킨다(HEAD·latest 그대로, T1-되돌림 하지 않는다)"; fi',
    'if docker image inspect $REPO:1103f48 > /dev/null 2>&1; then [ "$(docker image inspect --format \'{{.Id}}\' $REPO:1103f48)" = "$IMG0" ] || stop "$REPO:1103f48 태그가 이미 다른 이미지를 가리킨다(HEAD·latest 그대로, T1-되돌림 하지 않는다)"; fi')
rep('docker tag "$IMG0" $REPO:fe01207', 'docker tag "$IMG0" $REPO:1103f48')
rep('[ "$(docker image inspect --format \'{{.Id}}\' $REPO:fe01207)" = "$IMG0" ] || stop "기존 이미지 보존 태그 실패(HEAD·latest 그대로, T1-되돌림 하지 않는다)"',
    '[ "$(docker image inspect --format \'{{.Id}}\' $REPO:1103f48)" = "$IMG0" ] || stop "기존 이미지 보존 태그 실패(HEAD·latest 그대로, T1-되돌림 하지 않는다)"')
rep('stop "jar 차이가 N15·N16 29줄이 아님 — jar-diff.txt 를 보고(재기동 전). T1-되돌림"',
    'stop "jar 차이가 N17 %d줄이 아님 — jar-diff.txt 를 보고(재기동 전). T1-되돌림"' % len(RARX))
rep("grep -qx '(끝) 879 881' \"$M/jar-diff.txt\" || stop \"항목 수가 879 → 881 가 아님 — jar-diff.txt 를 보고(재기동 전). T1-되돌림\"",
    "grep -qx '%s' \"$M/jar-diff.txt\" || stop \"항목 수가 %s 가 아님 — jar-diff.txt 를 보고(재기동 전). T1-되돌림\"" % (val['END'], val['END_TEXT']))
rep('→ `fe01207f…`·`image-before.txt` 와 같은 ID. 재기동하지 않는다. 그 밖의 T1 멈춤은 출력만 보고한다. 보존 태그 `autotrader-backend:fe01207` 는 지금 이미지를 가리키므로 남겨 둔다.',
    '→ `1103f485…`·`image-before.txt` 와 같은 ID. 재기동하지 않는다. 그 밖의 T1 멈춤은 출력만 보고한다. 보존 태그 `autotrader-backend:1103f48` 는 지금 이미지를 가리키므로 남겨 둔다.')
# ── T3 ──
rep('[ "$EQ" = 0 ] || [ "$T" = rollback ] || stop "🔴 N15 만료 사유 기록 실패 줄(종료 $EQ) — 바로 되돌리지 말고 위 출력과 원문 줄을 보고"\ntouch "$M/ok-T3-$T"',
    '[ "$EQ" = 0 ] || [ "$T" = rollback ] || stop "🔴 N15 만료 사유 기록 실패 줄(종료 $EQ) — 바로 되돌리지 말고 위 출력과 원문 줄을 보고"\n'
    'n17obs "$M/app-$T.log" | tee "$M/n17obs-$T.txt" || stop "n17obs 인자 오류 — 위 출력을 보고"\n'
    'touch "$M/ok-T3-$T"')
rep('else echo "✅ T3(rollback) 통과 — fe01207 복귀 확인. T4 는 하지 않고 출력을 보고"; fi',
    'else echo "✅ T3(rollback) 통과 — 1103f48 복귀 확인. T4 는 하지 않고 출력을 보고"; fi')
# ── T4 ──
rep('[ "$EQ" = 0 ] || stop "🔴 N15 만료 사유 기록 실패 줄(종료 $EQ) — 바로 되돌리지 말고 위 출력과 원문 줄을 보고"\npython3',
    '[ "$EQ" = 0 ] || stop "🔴 N15 만료 사유 기록 실패 줄(종료 $EQ) — 바로 되돌리지 말고 위 출력과 원문 줄을 보고"\n'
    'echo "== N17 줄(기동 뒤 30분 로그 · 자연 발생만)"\n'
    'n17obs "$M/app-after30.log" | tee "$M/n17obs-after30.txt" || stop "n17obs 인자 오류 — 위 출력을 보고"\n'
    'python3')
rep('grep -q "^N16: 미관측" "$M/entryobs-after30.txt" && echo "⚪ N16 가격 아닌 시세 미관측 — 동작 관측은 미관측으로 보고(유도하지 않는다). 미래 관측 시각 보정은 로그 줄이 없어 이 함수로 보지 않는다" || true',
    'grep -q "^N16: 미관측" "$M/entryobs-after30.txt" && echo "⚪ N16 가격 아닌 시세 미관측 — 동작 관측은 미관측으로 보고(유도하지 않는다). 미래 관측 시각 보정은 로그 줄이 없어 이 함수로 보지 않는다" || true\n'
    'grep -q "^N17: 미관측" "$M/n17obs-after30.txt" && echo "⚪ N17 REST 강제 갱신 가격 이상 미관측 — 운영 반영은 HEAD·이미지·jar 로만 확인, 동작 관측은 미관측으로 보고(INVALID_PRICE 를 주입하지 않는다)" || true')
rep('touch "$M/ok-T4"; echo "✅ T4 통과 — T0~T4 출력을 보고(F 변수·F1 은 총괄 판단 뒤 개발 에이전트가 갱신)"',
    'touch "$M/ok-T4"; echo "✅ T4 통과 — T0~T4 출력을 보고(F 변수·F1·N06 핀은 총괄 판단 뒤 개발 에이전트가 갱신)"')
# ── T 뒤 관측 ──
rep('case "$EQ" in 0) ;; *) echo "🔴 N15 만료 사유 기록 실패 줄(종료 $EQ) — 위 출력과 원문 줄을 보고" ;; esac',
    'case "$EQ" in 0) ;; *) echo "🔴 N15 만료 사유 기록 실패 줄(종료 $EQ) — 위 출력과 원문 줄을 보고" ;; esac\n'
    'echo "== 기동 뒤 N17 줄(자연 발생만)"\n'
    'n17obs "$M/app-news-$TS.log" | tee "$M/n17obs-$TS.txt" || echo "⚪ n17obs 인자 오류 — 위 출력을 보고"')
# ── T 되돌림 ──
rep('stop "image-before 가 #22 이미지(9cb5d84f…)가 아님"', 'stop "image-before 가 #23 이미지(9818254c…)가 아님"')
rep('echo "✅ fe01207 (9cb5d84f…) 로 복귀 — 5분 뒤 T3 를 다시 실행(t-rollback.utc 기준으로 자동 전환, BTC·뉴스·HTTP 줄·HOLD 질의·N15/N16 줄은 기록만)"',
    'echo "✅ 1103f48 (9818254c…) 로 복귀 — 5분 뒤 T3 를 다시 실행(t-rollback.utc 기준으로 자동 전환, BTC·뉴스·HTTP 줄·HOLD 질의·N15/N16·N17 줄은 기록만)"')

# 남은 옛 값이 없어야 한다(설명 문구 포함). 허용: IMGPREV4·그 태그 확인의 fe01207/9cb5d84f, IMGOLD 출처의 maint-n15n16
assert '#22 P1 이 만듦' in s and '#23 R1 이 만듦' in s and '#22 T1' not in s and '#23 T1' not in s
allowed = {'fe01207': 3, '9cb5d84f': 2, 'maint-n15n16': 3}
for k, n in allowed.items():
    if s.count(k) != n:
        raise SystemExit('옛 값 %r 개수 %d != %d' % (k, s.count(k), n))
for bad in ['RV=', '${RV', 'RV:-', 'ok-R', '§5-9 81', 'v1.113', '#23) —', 'maint-n13', '879', 'R 변수가', '**R 변수**', '§5-9 81 R', 'R0', 'R1', 'R2', 'R3', 'R4']:
    if bad in s.replace('#23 R1 이 만듦', ''):
        raise SystemExit('남은 옛 값: %r' % bad)
pathlib.Path(sys.argv[5]).write_text(s, encoding='utf-8', newline='\n')
print('ok', len(s.split('\n')))
