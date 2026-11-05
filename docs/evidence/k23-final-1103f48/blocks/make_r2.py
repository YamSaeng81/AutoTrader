# §69 P 블록(문서 원문, PV=70 — #22 실제 실행판) → N15·N16 통합 개입 #23 R 블록.
# v1.113 판(make_r2.py): k23-prep-51dcee5/blocks/make_r.py 에서 세 군데만 바꿨다 — ① 판 표지 줄 주석에 후보·출처와 옛 §79 초안 대체를 적는다,
# ② IMGPREV3 주석의 실제 단계명 '#22 P1' 이 P→R 일괄 바꾸기에 걸려 '#22 R1' 이 되던 것을 막는다(총괄 v1.112 지적), ③ 남은 옛 값 검사에 51dcee5 를 더한다.
# 사용: python make_r.py <plan.md> <values.json> <entryobs.sh> <out.md>
# 값·이름 바꾸기와 entryobs 추가만 한다. 나머지 줄은 §69 와 바이트가 같다(근거 p-to-r.diff). N14 설치 블록은 가져오지 않는다.
import json, pathlib, re, sys

plan = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8').split('\n')
val = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding='utf-8'))
eobs = pathlib.Path(sys.argv[3]).read_text(encoding='utf-8').rstrip('\n')

start = plan.index('**P 변수** — 🔴 새 셸마다 §5-8 **F 변수** → §5-9 의 **G 변수** → 이 블록 순서로 실행한다. F 변수가 `BTCIN`·`PSQL`·`DC`·`sc`·`svc` 를, G 변수가 `qc`·`zero5`·`alertsok`·`sid203`·`REPO`·`QS`·`POOLX`·`NOT404` 를 준다. 이 블록이 `M`·`OLD`·`NEW` 를 덮어쓴다. H~L 변수는 넣지 않는다.')
end = plan.index('**N14 설치 — 정기 점검 셸 교체 (⏸ 총괄 승인 뒤, 1회. 이 커밋에서는 실행하지 않는다)**', start)
s = '\n'.join(plan[start:end]).rstrip('\n') + '\n'


def rep(a, b, n=1):
    global s
    c = s.count(a)
    if c != n:
        raise SystemExit('count %d != %d: %r' % (c, n, a[:120]))
    s = s.replace(a, b)


NEW, NEW7 = val['NEW'], val['NEW'][:7]
RV, SEC = val['RV'], val['SEC']
STOPV = 'stop "R 변수가 v%s(§5-9 %s 블록) 판이 아님 — F → G → §5-9 %s R 변수"' % (val['VER'], SEC, SEC)

# ── 변수 블록 ──
rep('**P 변수** — 🔴 새 셸마다', '**R 변수** — 🔴 새 셸마다')
rep('H~L 변수는 넣지 않는다.', 'H~P 변수는 넣지 않는다.')
rep('M=~/maint-n13; mkdir -p "$M"; OPS=~/ops-scripts; PV=70   # v1.102(§5-9 69 블록·§70 판정) 판 표지 — P 블록 전부가 확인한다',
    'M=~/maint-n15n16; mkdir -p "$M"; OPS=~/ops-scripts; RV=%s   # v%s(§5-9 %s 블록) 판 표지 — R 블록 전부가 확인한다. 후보 %s(출처 %s). 옛 §5-9 79 초안(RV=79)은 실행하지 않는다' % (RV, val['VER'], SEC, NEW7, val['SRC']))
rep('OLD=6995c52259e3de4c7c922a32f9fb2aa9ddd49f00; NEW=fe01207f3f60e97e308925157f84a1bb2da04388',
    'OLD=fe01207f3f60e97e308925157f84a1bb2da04388; NEW=%s' % NEW)
rep('IMGOLD=sha256:67294e80ada31d346e36caeb01a2c259b6b5514357c804977ba1d20fba6aec07   # #21 배포 이미지(지금 실행 중, ~/maint-n12/image-new.txt)',
    'IMGOLD=sha256:9cb5d84f0bfde5208f24f591ad291dacabdfa691a0b2e1f057c4f8c3e1720fb7   # #22 배포 이미지(지금 실행 중, ~/maint-n13/image-new.txt)')
rep('IMGPREV2=sha256:fd3ac351bcf508d465b9c2f6024ba978273603729f3e6cecba40a639a2f4dcb6   # #20 이미지 — 이전 복구 태그 autotrader-backend:b1d7664 가 가리킨다(보존, 바꾸지 않는다)',
    'IMGPREV2=sha256:fd3ac351bcf508d465b9c2f6024ba978273603729f3e6cecba40a639a2f4dcb6   # #20 이미지 — 이전 복구 태그 autotrader-backend:b1d7664 가 가리킨다(보존, 바꾸지 않는다)\n'
    'IMGPREV3=sha256:67294e80ada31d346e36caeb01a2c259b6b5514357c804977ba1d20fba6aec07   # #21 이미지 — 이전 복구 태그 autotrader-backend:6995c52 가 가리킨다(#22 P1 이 만듦, 보존, 바꾸지 않는다)')
old_diff = ('DIFFN="$(printf \'%s\\n\' web-api/src/main/java/com/cryptoautotrader/api/repository/StrategyLogRepository.java \\\n'
            '  web-api/src/test/java/com/cryptoautotrader/api/n13/HoldBaselineLoopPgTest.java \\\n'
            '  web-api/src/test/java/com/cryptoautotrader/api/n13/HoldBaselinePgSupport.java \\\n'
            '  web-api/src/test/java/com/cryptoautotrader/api/n13/HoldBaselineSqlEquivalencePgTest.java)"')
rep(old_diff, 'DIFFN="$(printf \'%%s\\n\' %s)"' % ' \\\n  '.join(val['DIFF']))
rep('PARX="$(printf \'%s\\n\' \'변경 BOOT-INF/classes/com/cryptoautotrader/api/repository/StrategyLogRepository.class\')"   # 실제 bootJar 대조(v1.101) — #21 기대값을 옮기지 않고 6995c52·fe01207 jar 에서 새로 셈',
    'RARX="$(printf \'%%s\\n\' %s)"   # 실제 bootJar 대조(v%s) — #22 기대값을 옮기지 않고 fe01207·%s jar 에서 새로 셈' % (
        ' \\\n  '.join("'%s'" % x for x in val['RARX']), val['VER'], NEW7))
# entryobs 를 holdobs 함수 뒤(변수 블록 끝)에 더한다
rep('sys.exit(1 if to else (3 if leaks else 0))\nPYEOF\n}\n```', 'sys.exit(1 if to else (3 if leaks else 0))\nPYEOF\n}\n' + eobs + '\n```')

# ── 심각도 표기 보호(단계 이름이 아니다) ──
SEV = [('#22 P1 이 만듦', '@@STEP22@@'), ('🔴 P0 — ', '\x00SEV1\x00'), ('🔴 P0: ', '\x00SEV2\x00')]
for a, b in SEV:
    s = s.replace(a, b)

# ── 이름·판 표지 ──
rep('[ "$M" = "$HOME/maint-n13" ] && [ "${PV:-}" = 70 ] && [ "$NEW" = fe01207f3f60e97e308925157f84a1bb2da04388 ] && [ -n "${BTCIN:-}" ] && type sid203 > /dev/null 2>&1 && type newsobs > /dev/null 2>&1 && type httpobs > /dev/null 2>&1 && type holdobs > /dev/null 2>&1 || stop "변수 순서 — F 변수 → G 변수 → §5-9 69 P 변수"',
    '[ "$M" = "$HOME/maint-n15n16" ] && [ "${RV:-}" = %s ] && [ "$NEW" = %s ] && [ -n "${BTCIN:-}" ] && type sid203 > /dev/null 2>&1 && type newsobs > /dev/null 2>&1 && type httpobs > /dev/null 2>&1 && type holdobs > /dev/null 2>&1 && type entryobs > /dev/null 2>&1 || stop "변수 순서 — F 변수 → G 변수 → §5-9 %s R 변수"' % (RV, NEW, SEC))
rep('[ "${PV:-}" = 70 ] || stop "P 변수가 v1.102(§5-9 69 블록·§70 판정) 판이 아님 — F → G → §5-9 69 P 변수"',
    '[ "${RV:-}" = %s ] || %s' % (RV, STOPV), 6)
s = re.sub(r'ok-P([0-4])', r'ok-R\1', s)
s = s.replace('P1-되돌림', 'R1-되돌림')
for a, b in [('**P0. ⏸', '**R0. ⏸'), ('**P1. 새', '**R1. 새'), ('**P2. 재기동 (개입 #22) — P1 통과 뒤**', '**R2. 재기동 (개입 #23) — R1 통과 뒤**'),
             ('**P3. 기동·뉴스·HTTP 줄·HOLD 질의 확인 — P2 5~10분 뒤**', '**R3. 기동·뉴스·HTTP 줄·HOLD 질의·N15/N16 줄 확인 — R2 5~10분 뒤**'),
             ('**P4. 30분 재점검', '**R4. 30분 재점검'),
             ('**P 뒤 관측(뉴스·HTTP 줄·HOLD 질의) — 읽기 전용, 반복 가능** (P4 통과 뒤', '**R 뒤 관측(뉴스·HTTP 줄·HOLD 질의·N15/N16 줄) — 읽기 전용, 반복 가능** (R4 통과 뒤'),
             ('**P 되돌림 — backend (P2~P4 의 "되돌림 필요" 때, 또는 총괄 지시 뒤)** (DB 마이그레이션·장부 변경이 없으므로 데이터 작업은 없다. 구판으로 돌아가면 HOLD 질의 장기 대여는 다시 생길 수 있다)',
              '**R 되돌림 — backend (R2~R4 의 "되돌림 필요" 때, 또는 총괄 지시 뒤)** (DB 마이그레이션·장부 변경이 없으므로 데이터 작업은 없다. 구판으로 돌아가면 N15 승인 실행 만료와 N16 가격·관측 시각 방어가 다시 없어진다. 이미 커밋된 의도·주문은 기존 추적·정산 경로가 처리한다)')]:
    rep(a, b)
rep('P 되돌림 블록 준비됨', 'R 되돌림 블록 준비됨', 2)
# 멈춤·안내 문구의 P0~P4 → R0~R4 (위에서 머리는 이미 바꿨다. 심각도 표기는 보호됨)
s = re.sub(r'(?<![A-Za-z0-9_])P([0-4])(?![0-9])', r'R\1', s)
for a, b in SEV:
    s = s.replace(b, a)

# ── R0 값 ──
rep('stop "HEAD 가 6995c52 가 아니거나 추적 파일 변경 있음"', 'stop "HEAD 가 fe01207 가 아니거나 추적 파일 변경 있음"')
rep('stop "후보 fe01207 없음"', 'stop "후보 %s 없음"' % NEW7)
rep('stop "6995c52 가 후보의 조상이 아님"', 'stop "fe01207 가 후보의 조상이 아님"')
rep('[ "$(git diff --name-only $OLD $NEW)" = "$DIFFN" ] || stop "diff 가 정확히 N13 4개 파일이 아님(마이그레이션 포함 금지)"',
    '[ "$(git diff --name-only $OLD $NEW)" = "$DIFFN" ] || stop "diff 가 정확히 N15·N16 %d개 파일이 아님(마이그레이션·설정 포함 금지)"' % len(val['DIFF']))
rep('[ "$IMG0" = "$IMGOLD" ] && [ "$IMGOLD" = "$(cat ~/maint-n12/image-new.txt)" ] || stop "실행 이미지가 #21 배포 이미지(67294e80…)와 다름"',
    '[ "$IMG0" = "$IMGOLD" ] && [ "$IMGOLD" = "$(cat ~/maint-n13/image-new.txt)" ] || stop "실행 이미지가 #22 배포 이미지(9cb5d84f…)와 다름"')
rep('[ "$(docker image inspect --format \'{{.Id}}\' $REPO:b1d7664)" = "$IMGPREV2" ] || stop "이전 복구 태그 $REPO:b1d7664 이 #20 이미지(fd3ac351…)가 아님 — 보고"',
    '[ "$(docker image inspect --format \'{{.Id}}\' $REPO:b1d7664)" = "$IMGPREV2" ] || stop "이전 복구 태그 $REPO:b1d7664 이 #20 이미지(fd3ac351…)가 아님 — 보고"\n'
    '[ "$(docker image inspect --format \'{{.Id}}\' $REPO:6995c52)" = "$IMGPREV3" ] || stop "이전 복구 태그 $REPO:6995c52 이 #21 이미지(67294e80…)가 아님 — 보고"')
rep('|| stop "정기 점검 설치본이 c669ccd 판이 아님(N14 먼저 설치)"', '|| stop "정기 점검 설치본이 c669ccd 판이 아님"')
rep('echo "== 배포 전 기준선: HOLD 질의 장기 대여(holdobs 종료 $HD — 6995c52 는 HOLD 경고가 있으므로 3 이어도 멈추지 않는다, 기록만)"; cat "$M/hold-before.txt"',
    'echo "== 배포 전 기준선: HOLD 질의 장기 대여(holdobs 종료 $HD — 3 이어도 멈추지 않는다, 기록만)"; cat "$M/hold-before.txt"\n'
    'entryobs "$M/backend-before.log" > "$M/entry-before.txt" && ED=0 || ED=$?\n'
    'echo "== 배포 전 기준선: N15·N16 줄(entryobs 종료 $ED — fe01207 에는 이 줄들이 없으므로 미관측이 기대, 기록만)"; cat "$M/entry-before.txt"')
rep('touch "$M/ok-R0"; echo "✅ R0 통과 — 30분 안에 R1"', 'touch "$M/ok-R0"; echo "✅ R0 통과 — 30분 안에 R1"')
# ── R1 ──
rep('if docker image inspect $REPO:6995c52 > /dev/null 2>&1; then [ "$(docker image inspect --format \'{{.Id}}\' $REPO:6995c52)" = "$IMG0" ] || stop "$REPO:6995c52 태그가 이미 다른 이미지를 가리킨다(HEAD·latest 그대로, R1-되돌림 하지 않는다)"; fi',
    'if docker image inspect $REPO:fe01207 > /dev/null 2>&1; then [ "$(docker image inspect --format \'{{.Id}}\' $REPO:fe01207)" = "$IMG0" ] || stop "$REPO:fe01207 태그가 이미 다른 이미지를 가리킨다(HEAD·latest 그대로, R1-되돌림 하지 않는다)"; fi')
rep('docker tag "$IMG0" $REPO:6995c52', 'docker tag "$IMG0" $REPO:fe01207')
rep('[ "$(docker image inspect --format \'{{.Id}}\' $REPO:6995c52)" = "$IMG0" ] || stop "기존 이미지 보존 태그 실패(HEAD·latest 그대로, R1-되돌림 하지 않는다)"',
    '[ "$(docker image inspect --format \'{{.Id}}\' $REPO:fe01207)" = "$IMG0" ] || stop "기존 이미지 보존 태그 실패(HEAD·latest 그대로, R1-되돌림 하지 않는다)"')
rep('[ "$(grep -v \'^(끝)\' "$M/jar-diff.txt")" = "$PARX" ] || stop "jar 차이가 N13 1줄이 아님 — jar-diff.txt 를 보고(재기동 전). R1-되돌림"',
    '[ "$(grep -v \'^(끝)\' "$M/jar-diff.txt")" = "$RARX" ] || stop "jar 차이가 N15·N16 %d줄이 아님 — jar-diff.txt 를 보고(재기동 전). R1-되돌림"' % len(val['RARX']))
rep("grep -qx '(끝) 879 879' \"$M/jar-diff.txt\" || stop \"항목 수가 879 → 879 가 아님 — jar-diff.txt 를 보고(재기동 전). R1-되돌림\"",
    "grep -qx '%s' \"$M/jar-diff.txt\" || stop \"항목 수가 %s 가 아님 — jar-diff.txt 를 보고(재기동 전). R1-되돌림\"" % (val['END'], val['END_TEXT']))
rep('- **R1-되돌림**: 멈춤 문구에 "R1-되돌림" 이 있을 때만 한다(`git checkout` 뒤). `git checkout --quiet $OLD; docker tag "$(cat "$M/image-before.txt")" $REPO:latest; git rev-parse HEAD; docker image inspect --format \'{{.Id}}\' $REPO:latest` → `6995c522…`·`image-before.txt` 와 같은 ID. 재기동하지 않는다. 그 밖의 R1 멈춤은 출력만 보고한다. 보존 태그 `autotrader-backend:6995c52` 는 지금 이미지를 가리키므로 남겨 둔다.',
    '- **R1-되돌림**: 멈춤 문구에 "R1-되돌림" 이 있을 때만 한다(`git checkout` 뒤). `git checkout --quiet $OLD; docker tag "$(cat "$M/image-before.txt")" $REPO:latest; git rev-parse HEAD; docker image inspect --format \'{{.Id}}\' $REPO:latest` → `fe01207f…`·`image-before.txt` 와 같은 ID. 재기동하지 않는다. 그 밖의 R1 멈춤은 출력만 보고한다. 보존 태그 `autotrader-backend:fe01207` 는 지금 이미지를 가리키므로 남겨 둔다.')
# ── R3 ──
rep('if [ "$HQ" = 3 ]; then cp "$M/holdobs-$T.txt" "$M/hold-check-$T.txt"; echo "⚪ 장기 대여 경고가 있다 — 개선 미확인. 되돌리지 않는다. 위 줄을 보고"; fi\ntouch "$M/ok-R3-$T"',
    'if [ "$HQ" = 3 ]; then cp "$M/holdobs-$T.txt" "$M/hold-check-$T.txt"; echo "⚪ 장기 대여 경고가 있다 — 개선 미확인. 되돌리지 않는다. 위 줄을 보고"; fi\n'
    'echo "== N15·N16 줄(기동 뒤 로그 · 자연 발생만, 미관측이면 그대로 기록)"\n'
    'entryobs "$M/app-$T.log" | tee "$M/entryobs-$T.txt" && EQ=0 || EQ=$?\n'
    '[ "$EQ" = 0 ] || [ "$T" = rollback ] || stop "🔴 N15 만료 사유 기록 실패 줄(종료 $EQ) — 바로 되돌리지 말고 위 출력과 원문 줄을 보고"\n'
    'touch "$M/ok-R3-$T"')
rep('else echo "✅ R3(rollback) 통과 — 6995c52 복귀 확인. R4 는 하지 않고 출력을 보고"; fi',
    'else echo "✅ R3(rollback) 통과 — fe01207 복귀 확인. R4 는 하지 않고 출력을 보고"; fi')
# ── R4 ──
rep('if [ "$HQ" = 3 ]; then cp "$M/holdobs-after30.txt" "$M/hold-check-after30.txt"; echo "⚪ 장기 대여 경고가 있다 — 개선 미확인. 되돌리지 않는다. 위 줄을 보고"; fi\npython3',
    'if [ "$HQ" = 3 ]; then cp "$M/holdobs-after30.txt" "$M/hold-check-after30.txt"; echo "⚪ 장기 대여 경고가 있다 — 개선 미확인. 되돌리지 않는다. 위 줄을 보고"; fi\n'
    'echo "== N15·N16 줄(기동 뒤 30분 로그 · 자연 발생만)"\n'
    'entryobs "$M/app-after30.log" | tee "$M/entryobs-after30.txt" && EQ=0 || EQ=$?\n'
    '[ "$EQ" = 0 ] || stop "🔴 N15 만료 사유 기록 실패 줄(종료 $EQ) — 바로 되돌리지 말고 위 출력과 원문 줄을 보고"\n'
    'python3')
rep('[ ! -e "$M/hold-check-after30.txt" ] || echo "⚪ 단, 장기 대여 경고가 남았다 — N13 개선 확인으로 보고하지 않는다(hold-check-after30.txt)"',
    '[ ! -e "$M/hold-check-after30.txt" ] || echo "⚪ 단, 장기 대여 경고가 남았다 — N13 개선 확인으로 보고하지 않는다(hold-check-after30.txt)"\n'
    'grep -q "^N15: 미관측" "$M/entryobs-after30.txt" && echo "⚪ N15 만료 미관측 — 운영 반영은 HEAD·이미지·jar 로만 확인, 동작 관측은 미관측으로 보고(유도하지 않는다)" || true\n'
    'grep -q "^N16: 미관측" "$M/entryobs-after30.txt" && echo "⚪ N16 가격 아닌 시세 미관측 — 동작 관측은 미관측으로 보고(유도하지 않는다). 미래 관측 시각 보정은 로그 줄이 없어 이 함수로 보지 않는다" || true')
# ── R 뒤 관측 ──
rep('case "$HQ" in 0) ;; 3) echo "⚪ 장기 대여 경고 있음 — 개선 미확인, 위 줄을 보고" ;; *) echo "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고" ;; esac',
    'case "$HQ" in 0) ;; 3) echo "⚪ 장기 대여 경고 있음 — 개선 미확인, 위 줄을 보고" ;; *) echo "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고" ;; esac\n'
    'echo "== 기동 뒤 N15·N16 줄(자연 발생만)"\n'
    'entryobs "$M/app-news-$TS.log" | tee "$M/entryobs-$TS.txt" && EQ=0 || EQ=$?\n'
    'case "$EQ" in 0) ;; *) echo "🔴 N15 만료 사유 기록 실패 줄(종료 $EQ) — 위 출력과 원문 줄을 보고" ;; esac')
# ── R 되돌림 ──
rep('IMG0=$(cat "$M/image-before.txt"); [ "$IMG0" = "$IMGOLD" ] || stop "image-before 가 #21 이미지(67294e80…)가 아님"',
    'IMG0=$(cat "$M/image-before.txt"); [ "$IMG0" = "$IMGOLD" ] || stop "image-before 가 #22 이미지(9cb5d84f…)가 아님"')
rep('echo "✅ 6995c52 (67294e80…) 로 복귀 — 5분 뒤 R3 를 다시 실행(t-rollback.utc 기준으로 자동 전환, BTC·뉴스·HTTP 줄·HOLD 질의는 기록만)"',
    'echo "✅ fe01207 (9cb5d84f…) 로 복귀 — 5분 뒤 R3 를 다시 실행(t-rollback.utc 기준으로 자동 전환, BTC·뉴스·HTTP 줄·HOLD 질의·N15/N16 줄은 기록만)"')

# 남은 옛 값이 없어야 한다(설명 문구 포함)
assert '#22 P1 이 만듦' in s and '#22 R1' not in s
for bad in ['PV=', '${PV', 'maint-n12', 'PARX', 'ok-P', '§5-9 69', '#22) —', 'v1.102', '51dcee5']:
    if bad in s:
        raise SystemExit('남은 옛 값: %r' % bad)
pathlib.Path(sys.argv[4]).write_text(s, encoding='utf-8', newline='\n')
print('ok', len(s.split('\n')))
