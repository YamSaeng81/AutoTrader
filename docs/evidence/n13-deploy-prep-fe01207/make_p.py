# §63 L 블록(문서 원문) → N13 개입 #22 P 블록. 사용: python make_p.py <plan.md> <values.json> <holdobs.sh> <out.md>
# 값·이름 바꾸기와 holdobs 추가만 한다. 나머지 줄은 §63 과 바이트가 같다(근거 l-to-p.diff).
import json, pathlib, re, sys

plan = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8').split('\n')
val = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding='utf-8'))
hold = pathlib.Path(sys.argv[3]).read_text(encoding='utf-8').rstrip('\n')

sec63 = next(i for i, l in enumerate(plan) if l.startswith('**63. 개입 #21 (N12) 관측 함수'))
start = plan.index('**L 변수** — 🔴 새 셸마다 §5-8 **F 변수** → §5-9 의 **G 변수** → 이 블록 순서로 실행한다. F 변수가 `BTCIN`·`PSQL`·`DC`·`sc`·`svc` 를, G 변수가 `qc`·`zero5`·`alertsok`·`sid203`·`REPO`·`QS`·`POOLX`·`NOT404` 를 준다. 이 블록이 `M`·`OLD`·`NEW` 를 덮어쓴다. H~K 변수는 넣지 않는다.', sec63)
end = plan.index('**64. 총괄 #21 관측 함수 보완 수용·실행 승인 (2026-11-04, v1.96)**')
src = '\n'.join(plan[start:end]).rstrip('\n') + '\n'
s = src


def rep(a, b, n=1):
    global s
    c = s.count(a)
    if c != n:
        raise SystemExit('count %d != %d: %r' % (c, n, a[:120]))
    s = s.replace(a, b)


NEW, NEW7 = val['NEW'], val['NEW'][:7]
# ── 변수 블록 ──
rep('**L 변수** — 🔴 새 셸마다', '**P 변수** — 🔴 새 셸마다')
rep('H~K 변수는 넣지 않는다.', 'H~L 변수는 넣지 않는다.')
rep('M=~/maint-n12; mkdir -p "$M"; OPS=~/ops-scripts; LV=63   # v1.95(§5-9 63) 판 표지 — L 블록 전부가 확인한다',
    'M=~/maint-n13; mkdir -p "$M"; OPS=~/ops-scripts; PV=69   # v1.101(§5-9 69) 판 표지 — P 블록 전부가 확인한다')
rep('OLD=b1d7664db73222aec5d86f2e9137debef0ff4df3; NEW=6995c52259e3de4c7c922a32f9fb2aa9ddd49f00',
    'OLD=6995c52259e3de4c7c922a32f9fb2aa9ddd49f00; NEW=%s' % NEW)
rep('IMGOLD=sha256:fd3ac351bcf508d465b9c2f6024ba978273603729f3e6cecba40a639a2f4dcb6   # #20 배포 이미지(지금 실행 중, ~/maint-n11/image-new.txt)',
    'IMGOLD=sha256:67294e80ada31d346e36caeb01a2c259b6b5514357c804977ba1d20fba6aec07   # #21 배포 이미지(지금 실행 중, ~/maint-n12/image-new.txt)')
rep('IMGPREV=sha256:618046540764e49aa40efef7ec950f28cd5882e8bf7d6bdfdc3da0ed1f0112f9   # #19 이미지 — 이전 복구 태그 autotrader-backend:ea44900 가 가리킨다(보존, 바꾸지 않는다)',
    'IMGPREV=sha256:618046540764e49aa40efef7ec950f28cd5882e8bf7d6bdfdc3da0ed1f0112f9   # #19 이미지 — 이전 복구 태그 autotrader-backend:ea44900 가 가리킨다(보존, 바꾸지 않는다)\n'
    'IMGPREV2=sha256:fd3ac351bcf508d465b9c2f6024ba978273603729f3e6cecba40a639a2f4dcb6   # #20 이미지 — 이전 복구 태그 autotrader-backend:b1d7664 가 가리킨다(보존, 바꾸지 않는다)')
old_diff = ('DIFF3="$(printf \'%s\\n\' web-api/src/main/java/com/cryptoautotrader/api/news/source/CryptoPanicSource.java \\\n'
            '  web-api/src/main/java/com/cryptoautotrader/api/news/source/UpbitNoticeSource.java web-api/src/test/java/com/cryptoautotrader/api/news/NewsHttpErrorLogTest.java)"')
new_diff = 'DIFFN="$(printf \'%%s\\n\' %s)"' % ' \\\n  '.join(val['DIFF'])
rep(old_diff, new_diff)
old_larx = ('LARX="$(printf \'%s\\n\' \'BOOT-INF/classes/com/cryptoautotrader/api/news/source/CryptoPanicSource.class\' \\\n'
            '  \'BOOT-INF/classes/com/cryptoautotrader/api/news/source/UpbitNoticeSource.class\' | sed \'s/^/변경 /\')"   # 실제 bootJar 대조(v1.93) — 이전 9줄·877→879 는 쓰지 않는다')
new_larx = 'PARX="$(printf \'%%s\\n\' %s)"   # 실제 bootJar 대조(v1.101) — #21 기대값을 옮기지 않고 6995c52·fe01207 jar 에서 새로 셈' % ' \\\n  '.join("'%s'" % x for x in val['PARX'])
rep(old_larx, new_larx)
# holdobs 를 httpobs 함수 뒤(변수 블록 끝)에 더한다
rep('sys.exit(1 if bad else (3 if chk else 0))\nPYEOF\n}\n```', 'sys.exit(1 if bad else (3 if chk else 0))\nPYEOF\n}\n' + hold + '\n```')

# ── 이름·판 표지 ──
rep('[ "$M" = "$HOME/maint-n12" ] && [ "${LV:-}" = 63 ] && [ "$NEW" = 6995c52259e3de4c7c922a32f9fb2aa9ddd49f00 ] && [ -n "${BTCIN:-}" ] && type sid203 > /dev/null 2>&1 && type newsobs > /dev/null 2>&1 && type httpobs > /dev/null 2>&1 || stop "변수 순서 — F 변수 → G 변수 → §5-9 63 L 변수"',
    '[ "$M" = "$HOME/maint-n13" ] && [ "${PV:-}" = 69 ] && [ "$NEW" = %s ] && [ -n "${BTCIN:-}" ] && type sid203 > /dev/null 2>&1 && type newsobs > /dev/null 2>&1 && type httpobs > /dev/null 2>&1 && type holdobs > /dev/null 2>&1 || stop "변수 순서 — F 변수 → G 변수 → §5-9 69 P 변수"' % NEW)
rep('[ "${LV:-}" = 63 ] || stop "L 변수가 v1.95(§5-9 63) 판이 아님 — F → G → §5-9 63 L 변수"',
    '[ "${PV:-}" = 69 ] || stop "P 변수가 v1.101(§5-9 69) 판이 아님 — F → G → §5-9 69 P 변수"', 6)
s = re.sub(r'ok-L([0-4])', r'ok-P\1', s)
s = s.replace('L1-되돌림', 'P1-되돌림')
for a, b in [('**L0. ⏸', '**P0. ⏸'), ('**L1. 새', '**P1. 새'), ('**L2. 재기동 (개입 #21) — L1 통과 뒤**', '**P2. 재기동 (개입 #22) — P1 통과 뒤**'),
             ('**L3. 기동·뉴스·HTTP 줄 확인 — L2 5~10분 뒤**', '**P3. 기동·뉴스·HTTP 줄·HOLD 질의 확인 — P2 5~10분 뒤**'),
             ('**L4. 30분 재점검', '**P4. 30분 재점검'),
             ('**L 뒤 뉴스 관측 — 읽기 전용, 반복 가능** (L4 통과 뒤', '**P 뒤 관측(뉴스·HTTP 줄·HOLD 질의) — 읽기 전용, 반복 가능** (P4 통과 뒤'),
             ('**L 되돌림 — backend (L2~L4 의 "되돌림 필요" 때, 또는 총괄 지시 뒤)**', '**P 되돌림 — backend (P2~P4 의 "되돌림 필요" 때, 또는 총괄 지시 뒤)**')]:
    rep(a, b)
# 멈춤·안내 문구의 L0~L4 → P0~P4 (코드 블록 안 문자열만 — 위에서 머리는 이미 바꿨다)
s = re.sub(r'(?<![A-Za-z0-9_])L([0-4])(?![0-9])', r'P\1', s)
# ── P0 값 ──
rep('stop "HEAD 가 b1d7664 가 아니거나 추적 파일 변경 있음"', 'stop "HEAD 가 6995c52 가 아니거나 추적 파일 변경 있음"')
rep('stop "후보 6995c52 없음"', 'stop "후보 %s 없음"' % NEW7)
rep('stop "b1d7664 가 후보의 조상이 아님"', 'stop "6995c52 가 후보의 조상이 아님"')
rep('[ "$(git diff --name-only $OLD $NEW)" = "$DIFF3" ] || stop "diff 가 정확히 N12 3개 파일이 아님(마이그레이션 포함 금지)"',
    '[ "$(git diff --name-only $OLD $NEW)" = "$DIFFN" ] || stop "diff 가 정확히 N13 %d개 파일이 아님(마이그레이션 포함 금지)"' % len(val['DIFF']))
rep('[ "$IMG0" = "$IMGOLD" ] && [ "$IMGOLD" = "$(cat ~/maint-n11/image-new.txt)" ] || stop "실행 이미지가 #20 배포 이미지(fd3ac351…)와 다름"',
    '[ "$IMG0" = "$IMGOLD" ] && [ "$IMGOLD" = "$(cat ~/maint-n12/image-new.txt)" ] || stop "실행 이미지가 #21 배포 이미지(67294e80…)와 다름"')
rep('[ "$(docker image inspect --format \'{{.Id}}\' $REPO:ea44900)" = "$IMGPREV" ] || stop "이전 복구 태그 $REPO:ea44900 이 #19 이미지(61804654…)가 아님 — 보고"',
    '[ "$(docker image inspect --format \'{{.Id}}\' $REPO:ea44900)" = "$IMGPREV" ] || stop "이전 복구 태그 $REPO:ea44900 이 #19 이미지(61804654…)가 아님 — 보고"\n'
    '[ "$(docker image inspect --format \'{{.Id}}\' $REPO:b1d7664)" = "$IMGPREV2" ] || stop "이전 복구 태그 $REPO:b1d7664 이 #20 이미지(fd3ac351…)가 아님 — 보고"')
rep('echo "== 배포 전 기준선: 출처 HTTP 비200 줄(httpobs 종료 $HB — b1d7664 는 CryptoPanic·업비트 본문을 찍으므로 1 이어도 멈추지 않는다, 기록만)"; cat "$M/http-before.txt"',
    'echo "== 배포 전 기준선: 출처 HTTP 비200 줄(httpobs 종료 $HB — 기록만)"; cat "$M/http-before.txt"\n'
    'holdobs "$M/backend-before.log" > "$M/hold-before.txt" && HD=0 || HD=$?\n'
    '[ "$HD" != 1 ] || stop "🔴 P0 — 배포 전 로그에 연결 획득 시간 초과 줄이 있다. 배포하지 않고 hold-before.txt 를 즉시 보고"\n'
    'echo "== 배포 전 기준선: HOLD 질의 장기 대여(holdobs 종료 $HD — 6995c52 는 HOLD 경고가 있으므로 3 이어도 멈추지 않는다, 기록만)"; cat "$M/hold-before.txt"')
# ── P1 ──
rep('if docker image inspect $REPO:b1d7664 > /dev/null 2>&1; then [ "$(docker image inspect --format \'{{.Id}}\' $REPO:b1d7664)" = "$IMG0" ] || stop "$REPO:b1d7664 태그가 이미 다른 이미지를 가리킨다(HEAD·latest 그대로, P1-되돌림 하지 않는다)"; fi',
    'if docker image inspect $REPO:6995c52 > /dev/null 2>&1; then [ "$(docker image inspect --format \'{{.Id}}\' $REPO:6995c52)" = "$IMG0" ] || stop "$REPO:6995c52 태그가 이미 다른 이미지를 가리킨다(HEAD·latest 그대로, P1-되돌림 하지 않는다)"; fi')
rep('docker tag "$IMG0" $REPO:b1d7664\n[ "$(docker image inspect --format \'{{.Id}}\' $REPO:b1d7664)" = "$IMG0" ]',
    'docker tag "$IMG0" $REPO:6995c52\n[ "$(docker image inspect --format \'{{.Id}}\' $REPO:6995c52)" = "$IMG0" ]')
rep('[ "$(grep -v \'^(끝)\' "$M/jar-diff.txt")" = "$LARX" ] || stop "jar 차이가 N12 2줄이 아님 — jar-diff.txt 를 보고(재기동 전). P1-되돌림"',
    '[ "$(grep -v \'^(끝)\' "$M/jar-diff.txt")" = "$PARX" ] || stop "jar 차이가 N13 %d줄이 아님 — jar-diff.txt 를 보고(재기동 전). P1-되돌림"' % len(val['PARX']))
rep("grep -qx '(끝) 879 879' \"$M/jar-diff.txt\" || stop \"항목 수가 879 → 879 가 아님 — jar-diff.txt 를 보고(재기동 전). P1-되돌림\"",
    "grep -qx '%s' \"$M/jar-diff.txt\" || stop \"항목 수가 %s 가 아님 — jar-diff.txt 를 보고(재기동 전). P1-되돌림\"" % (val['END'], val['END_TEXT']))
rep('`git checkout --quiet $OLD; docker tag "$(cat "$M/image-before.txt")" $REPO:latest; git rev-parse HEAD; docker image inspect --format \'{{.Id}}\' $REPO:latest` → `b1d7664d…`·`image-before.txt` 와 같은 ID. 재기동하지 않는다. 그 밖의 P1 멈춤은 출력만 보고한다. 보존 태그 `autotrader-backend:b1d7664` 는 지금 이미지를 가리키므로 남겨 둔다.',
    '`git checkout --quiet $OLD; docker tag "$(cat "$M/image-before.txt")" $REPO:latest; git rev-parse HEAD; docker image inspect --format \'{{.Id}}\' $REPO:latest` → `6995c522…`·`image-before.txt` 와 같은 ID. 재기동하지 않는다. 그 밖의 P1 멈춤은 출력만 보고한다. 보존 태그 `autotrader-backend:6995c52` 는 지금 이미지를 가리키므로 남겨 둔다.')
# ── P3 ── HOLD 관측(되돌림 뒤에는 기록만)
rep('if [ "$HO" = 3 ]; then cp "$M/httpobs-$T.txt" "$M/http-check-$T.txt"; echo "⚪ HTTP 줄 확인 필요 — 되돌리지 않는다. 위 ⚪ 줄을 보고"; fi\ntouch "$M/ok-P3-$T"',
    'if [ "$HO" = 3 ]; then cp "$M/httpobs-$T.txt" "$M/http-check-$T.txt"; echo "⚪ HTTP 줄 확인 필요 — 되돌리지 않는다. 위 ⚪ 줄을 보고"; fi\n'
    'echo "== HOLD 질의 장기 대여·연결 획득(기동 뒤 로그 · 배포 전 기준선: $(head -1 "$M/hold-before.txt"))"\n'
    'holdobs "$M/app-$T.log" | tee "$M/holdobs-$T.txt" && HQ=0 || HQ=$?\n'
    '[ "$HQ" != 1 ] || stop "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고(되돌림 여부는 총괄 판단, P 되돌림 블록 준비됨)"\n'
    'if [ "$HQ" = 3 ]; then cp "$M/holdobs-$T.txt" "$M/hold-check-$T.txt"; echo "⚪ 장기 대여 경고가 있다 — 개선 미확인. 되돌리지 않는다. 위 줄을 보고"; fi\n'
    'touch "$M/ok-P3-$T"')
rep('else echo "✅ P3(rollback) 통과 — b1d7664 복귀 확인. P4 는 하지 않고 출력을 보고"; fi',
    'else echo "✅ P3(rollback) 통과 — 6995c52 복귀 확인. P4 는 하지 않고 출력을 보고"; fi')
# ── P4 ──
rep('if [ "$HO" = 3 ]; then cp "$M/httpobs-after30.txt" "$M/http-check-after30.txt"; echo "⚪ HTTP 줄 확인 필요 — 되돌리지 않는다. 위 ⚪ 줄을 보고"; fi\npython3 scripts/prospective/start_66.py alerts --since "$S"',
    'if [ "$HO" = 3 ]; then cp "$M/httpobs-after30.txt" "$M/http-check-after30.txt"; echo "⚪ HTTP 줄 확인 필요 — 되돌리지 않는다. 위 ⚪ 줄을 보고"; fi\n'
    'echo "== HOLD 질의 장기 대여·연결 획득(기동 뒤 30분 로그 — 시작 catchup 과 5분 뒤 첫 정기 회차를 포함 · 배포 전 기준선: $(head -1 "$M/hold-before.txt"))"\n'
    'holdobs "$M/app-after30.log" | tee "$M/holdobs-after30.txt" && HQ=0 || HQ=$?\n'
    '[ "$HQ" != 1 ] || stop "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고(되돌림 여부는 총괄 판단, P 되돌림 블록 준비됨)"\n'
    'grep -q "시작 Catchup 완료" "$M/app-after30.log" || echo "⚪ 시작 catchup 완료 줄이 아직 없다 — HOLD 질의 개선 관측 미완(되돌리지 않는다)"\n'
    'if [ "$HQ" = 3 ]; then cp "$M/holdobs-after30.txt" "$M/hold-check-after30.txt"; echo "⚪ 장기 대여 경고가 있다 — 개선 미확인. 되돌리지 않는다. 위 줄을 보고"; fi\n'
    'python3 scripts/prospective/start_66.py alerts --since "$S"')
rep('[ ! -e "$M/http-check-after30.txt" ] || echo "⚪ 단, HTTP 줄 확인 필요가 남았다 — N12 운영 관측 완료로 보고하지 않는다(http-check-after30.txt)"',
    '[ ! -e "$M/http-check-after30.txt" ] || echo "⚪ 단, HTTP 줄 확인 필요가 남았다 — N12 운영 관측 완료로 보고하지 않는다(http-check-after30.txt)"\n'
    '[ ! -e "$M/hold-check-after30.txt" ] || echo "⚪ 단, 장기 대여 경고가 남았다 — N13 개선 확인으로 보고하지 않는다(hold-check-after30.txt)"')
# ── P 뒤 관측 ──
rep('case "$HO" in 0) ;; 3) echo "⚪ HTTP 줄 확인 필요 — 위 ⚪ 줄을 보고" ;; *) echo "🔴 출처 HTTP 비200 줄이 고정 한 줄이 아님(종료 $HO) — 위 출력을 보고" ;; esac\n)',
    'case "$HO" in 0) ;; 3) echo "⚪ HTTP 줄 확인 필요 — 위 ⚪ 줄을 보고" ;; *) echo "🔴 출처 HTTP 비200 줄이 고정 한 줄이 아님(종료 $HO) — 위 출력을 보고" ;; esac\n'
    'echo "== 기동 뒤 HOLD 질의 장기 대여·연결 획득(N13 · 배포 전 기준선: $(head -1 "$M/hold-before.txt"))"\n'
    'holdobs "$M/app-news-$TS.log" | tee "$M/holdobs-$TS.txt" && HQ=0 || HQ=$?\n'
    'case "$HQ" in 0) ;; 3) echo "⚪ 장기 대여 경고 있음 — 개선 미확인, 위 줄을 보고" ;; *) echo "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고" ;; esac\n)')
# ── P 되돌림 ──
rep('IMG0=$(cat "$M/image-before.txt"); [ "$IMG0" = "$IMGOLD" ] || stop "image-before 가 #20 이미지(fd3ac351…)가 아님"',
    'IMG0=$(cat "$M/image-before.txt"); [ "$IMG0" = "$IMGOLD" ] || stop "image-before 가 #21 이미지(67294e80…)가 아님"')
rep('echo "✅ b1d7664 (fd3ac351…) 로 복귀 — 5분 뒤 P3 를 다시 실행(t-rollback.utc 기준으로 자동 전환, BTC·뉴스·HTTP 줄은 기록만)"',
    'echo "✅ 6995c52 (67294e80…) 로 복귀 — 5분 뒤 P3 를 다시 실행(t-rollback.utc 기준으로 자동 전환, BTC·뉴스·HTTP 줄·HOLD 질의는 기록만)"')
rep('**P 되돌림 — backend (P2~P4 의 "되돌림 필요" 때, 또는 총괄 지시 뒤)** (DB 마이그레이션·장부 변경이 없으므로 데이터 작업은 없다)',
    '**P 되돌림 — backend (P2~P4 의 "되돌림 필요" 때, 또는 총괄 지시 뒤)** (DB 마이그레이션·장부 변경이 없으므로 데이터 작업은 없다. 구판으로 돌아가면 HOLD 질의 장기 대여는 다시 생길 수 있다)')
# P3 되돌림 모드: 되돌림 뒤 holdobs 는 기록만 — 종료 3 은 위에서 이미 기록만, 종료 1(P0)은 되돌림 뒤에도 보고 대상이라 멈춘다.
for leftover in ('LV', 'ok-L', '63 L 변수', '**L 변수', 'maint-n12"', 'DIFF3', 'LARX'):
    if leftover in s:
        raise SystemExit('남은 L 표기: %s — %s' % (leftover, [l for l in s.split(chr(10)) if leftover in l][:3]))
pathlib.Path(sys.argv[4]).write_text(s, encoding='utf-8')
pathlib.Path(sys.argv[4] + '.src').write_text(src, encoding='utf-8')
print('blocks written', s.count('```bash'))
