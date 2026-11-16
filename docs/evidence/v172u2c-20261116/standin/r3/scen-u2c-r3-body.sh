
# r3 시나리오 쪽 수정: c31·c32 주입값에 따옴표(r2 는 따옴표 없이 env.sh 에 써서 | 가 파이프로 해석돼 주입이 되지 않았다)
# ── r3(후속 메모 tail-record §2: 행수·내용 한 SELECT, 블록 1c9eab35) — r2 본문에서 V96 이력 사례만 새 응답 형식(V96C_OUT)으로 바꾸고 NULL 혼합·단독 NULL·옛 형식 사례를 더했다
# ── v1.174 U2확인(총괄 v1.174 §2-6) — 가짜 DB 의 V95 이후 이력 응답을 실제 PG 형식 96:true: 로 고친 장치로, 11-16 14:16 실제 상태(U2 rc1)를 만든 뒤 U2확인을 돌린다 ──
# SQL 출력 형식의 근거는 로컬 PG 15.13 대조(pg-v96-expr-out.txt)이고, 이 대역은 제어 흐름(멈춤 위치·ok-U2 미기록·변경 명령 0)의 근거다.
pre() { ini $1; u0u1 $1; chk $1 U2 "" 1 "되돌림 필요: V95 이후 이력이 V96 한 줄(성공·체크섬 -424836241)이 아님" 1; }
mh() { /usr/bin/sha256sum runs/$P-$1/home/$MV/t-deploy.txt runs/$P-$1/home/$MV/t-deploy.utc | cut -c1-16 | tr '\n' ' '; }
nook() { is $1 "ok-U2·svc-deploy·git-deploy 없음·변경 명령 늘지 않음·배포 기록 그대로" "$(ex $1 ok-U2) $(ex $1 svc-deploy.txt) $(ex $1 git-deploy.txt) $(chg $1) $(mh $1)" "없음 $([ "$1" = c44 ] && echo 있음 || echo 없음) 없음 $2 $3"; }   # c44 는 svc-deploy 를 미리 둔 사례
fail() { local c=$1 w=$2 n0 h0; n0=$(chg $c); h0=$(mh $c); chk $c U2확인 "" 1 "$w" 1; nook $c $n0 "$h0"; }   # 실패 사례 공통: 멈춤 문구·ok-U2·변경 명령·t-deploy 해시
if [ "$SEC" = u2c ]; then
echo "== 장치 확인: 고친 가짜 DB 로 원래 U2(ce6df2fa)가 실제와 같은 줄에서 rc1 — 11-16 14:16 재현"
pre c01
is c01 "원래 U2 멈춤 뒤: 새 backend 실행·V96·ok-U2 없음·svc-deploy 없음" "$(cut -d' ' -f2 runs/$P-c01/state/ctr/backend | cut -c1-15) $(ex c01 ok-U2) $(ex c01 svc-deploy.txt)" "$(cut -d' ' -f1 runs/$P-c01/home/$MV/image-new-chain.txt | cut -c1-15) 없음 없음"
echo "== c01 정상: U2확인 통과 → ok-U2(마지막) → U3 → UF2 → U4 → U뒤관측"
n0=$(chg c01); h0=$(mh c01); adv c01 600
chk c01 U2확인 "" 0 "✅ U2확인 통과 — ok-U2 기록" 1
is c01 "변경 명령 늘지 않음·배포 기록 그대로" "$(chg c01) $(mh c01)" "$n0 $h0"
is c01 "ok-U2 내용" "$(mf c01 ok-U2 | cut -d' ' -f1-3)" "V172D4 U2확인 $(TZ=KST-9 /usr/bin/date -d @"$(cat runs/$P-c01/state/clock)" '+%Y%m%d-%H%M%S')"
is c01 "출력 순서: P0 → V96 이력 → ✅(마지막 줄)" "$(grep -nE '^== U2확인|연결 획득|^V95 이후 이력\(행수\|내용\): 1\|96:true:-424836241$|✅ U2확인' runs/$P-c01/out/out-U2확인.txt | cut -d: -f1 | tr '\n' ' ' | awk '{print ($1<$2 && $2<$3 && $3<$4) ? "순서 맞음" : "어긋남"}')" "순서 맞음"
is c01 "새 기록(svc·git·v96·holdobs·backend-start·app) 각 1개, U2 꼬리 이름 = 새 기록과 같은 바이트" "$(for f in svc-u2c git-u2c v96-u2c holdobs-u2c backend-start-u2c app-u2c; do ls runs/$P-c01/home/$MV/ | grep -c "^$f-"; done | tr '\n' ' ')$(cmp -s runs/$P-c01/home/$MV/svc-deploy.txt runs/$P-c01/home/$MV/svc-u2c-*.txt && cmp -s runs/$P-c01/home/$MV/git-deploy.txt runs/$P-c01/home/$MV/git-u2c-*.txt && echo 같음)" "1 1 1 1 1 1 같음"
chk c01 U2확인 2 1 "U2 를 이미 통과했다 — U2확인 대상 아님(U3)" 1
chk c01 U3 "" 0 "✅ U3(deploy) 통과" 1; chk c01 UF2 "" 0 "✅ UF2 통과" 1
adv c01 1500; chk c01 U4 "" 0 "✅ U4 통과" 1; chk c01 U뒤관측 "" 0 "" 1
is c01 "U3·U4·U뒤관측 기록" "$(ex c01 holdobs-deploy-p0.txt) $(ex c01 ok-U3-deploy) $(ex c01 ok-U4) $(ls runs/$P-c01/home/$MV/ | grep -c '^newsobs-2026')" "있음 있음 있음 1"

echo "== c02~c13n V96 이력(r3 행수·내용 한 SELECT): false·체크섬 다름·0행·복수 행·V96 두 번·조회 실패·옛 t 표기·NULL 혼합 2종·단독 NULL·행수 없는 옛 형식 → ok-U2 전에 멈춤"
while IFS=';' read -r c e w; do pre $c; env_ $c "$e"; fail $c "$w"; is $c "V96 이력 원문 기록 수(조회 실패면 0)" "$(ls runs/$P-$c/home/$MV/ | grep -c '^v96-u2c-')" "$([ "${e%%=*}" = V96H_FAIL ] && echo 0 || echo 1)"; done <<'T'
c02;V96C_OUT='1|96:false:-424836241';V95 이후 이력이 V96 한 줄(행수 1·성공 true·체크섬 -424836241)이 아님
c03;V96C_OUT='1|96:true:123456';V95 이후 이력이 V96 한 줄(행수 1·성공 true·체크섬 -424836241)이 아님
c04;V96C_OUT='0|-';V95 이후 이력이 V96 한 줄(행수 1·성공 true·체크섬 -424836241)이 아님
c05;V96C_OUT='2|95:true:111,96:true:-424836241';V95 이후 이력이 V96 한 줄(행수 1·성공 true·체크섬 -424836241)이 아님
c06;V96C_OUT='2|96:false:-424836241,96:true:-424836241';V95 이후 이력이 V96 한 줄(행수 1·성공 true·체크섬 -424836241)이 아님
c07;V96H_FAIL=1;V95 이후 이력 조회 실패 — 보고
c08;V96C_OUT='1|96:t:-424836241';V95 이후 이력이 V96 한 줄(행수 1·성공 true·체크섬 -424836241)이 아님
c09;V96C_OUT='2|95:true:NULL,96:true:-424836241';V95 이후 이력이 V96 한 줄(행수 1·성공 true·체크섬 -424836241)이 아님
c10;V96C_OUT='2|96:true:-424836241,96:true:NULL';V95 이후 이력이 V96 한 줄(행수 1·성공 true·체크섬 -424836241)이 아님
c12n;V96C_OUT='1|96:true:NULL';V95 이후 이력이 V96 한 줄(행수 1·성공 true·체크섬 -424836241)이 아님
c13n;V96C_OUT='96:true:-424836241';V95 이후 이력이 V96 한 줄(행수 1·성공 true·체크섬 -424836241)이 아님
T
is c04 "0행 기록 표시" "$(cat runs/$P-c04/home/$MV/v96-u2c-*)" "V95 이후 이력(행수|내용): 0|-"
is c09 "NULL 혼합 기록 표시" "$(cat runs/$P-c09/home/$MV/v96-u2c-*)" "V95 이후 이력(행수|내용): 2|95:true:NULL,96:true:-424836241"

echo "== c11~c14 P0 먼저: 로그 P0·로그 조회 실패·빈 로그·holdobs 판정 실패 → DB·이미지 확인 전에 멈춤"
pre c11; cp runs/$P-c11/fix/log-p0.log runs/$P-c11/state/backend.log; fail c11 "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고"
pre c12; env_ c12 LOGS_FAIL=1; fail c12 "🔴 P0 검사 미완료 — 기동 구간 로그를 확보하지 못함"
pre c13; env_ c13 LOGS_EMPTY=1; fail c13 "🔴 P0 검사 미완료 — 기동 구간 로그가 비었다"
pre c14; env_ c14 PY_HOLD_FAIL=app-u2c-; fail c14 "🔴 P0 검사 미완료 — holdobs 종료 2"
for c in c11 c12 c13 c14; do is $c "P0 단계 뒤 확인 미도달(git·이미지·DB 조회 0)" "$(awk -F'\t' -v s="$(grep -n 'logs --no-color' runs/$P-$c/calls.log | tail -1 | cut -d: -f1)" 'NR>s && ($2=="git" || $3 ~ /image inspect|exec -T db/)' runs/$P-$c/calls.log | wc -l) $(ls runs/$P-$c/home/$MV/ | grep -c '^v96-u2c-')" "0 0"; done

echo "== c21~c27 상태 불일치: 서비스·backend 이미지·frontend 이미지·latest·재시작·다시 기동·HEAD"
pre c21; sed -i "s/^\([^ ]*\) [^ ]*/\1 sha256:$(printf redis2 | /usr/bin/sha256sum | cut -c1-64)/" runs/$P-c21/state/ctr/redis; fail c21 "backend 외 서비스가 바뀜 — 보고"
pre c22; echo "743dfebac8c062011d7711bb59ebe727ee1e174724526b0160f6921afd5bfece $I26 $I26M" > runs/$P-c22/state/ctr/backend; fail c22 "실행 backend 가 U1 새 이미지 대응이 아님 또는 조회 실패 — 보고"
pre c23; C23=$(mkimg c23 frontend c23); read -r m _ < runs/$P-c23/state/man/${C23#sha256:}; echo "$(printf c23 | /usr/bin/sha256sum | cut -c1-64) $C23 $m" > runs/$P-c23/state/ctr/frontend; fail c23 "실행 frontend 가 U0 구판 대응이 아님 또는 조회 실패 — 보고"
pre c24; echo "$I26" > runs/$P-c24/state/tag/latest; fail c24 "backend latest 가 U1 새 이미지 대상·manifest·config 와 다름 또는 조회 실패 — 보고"
pre c25; env_ c25 RESTARTS=1; fail c25 "backend 재시작 1 · 상태 running — 보고"
pre c26; date -u -d "@$(( $(cat runs/$P-c26/state/clock) + 600 ))" '+%Y-%m-%dT%H:%M:%S.000000000Z' > runs/$P-c26/state/backend.started; fail c26 "뒤 2분 안이 아님"
pre c27; echo $OLDC > runs/$P-c27/state/HEAD; fail c27 "HEAD 가 NEW 가 아니거나 추적 파일 변경 있음 — 보고"

echo "== c31~c34 DB 상태: SID203·66세션·공유 풀·Flyway 최고 버전"
pre c31; env_ c31 "EG_OUT='1|0|1|false|-|92|0'"; fail c31 "SID 203·기준 행·EG·0 확인 중 다른 것 있음 — 보고"
pre c32; env_ c32 "SC_OUT='66 | 62 | 3 | 530:STOPPED'"; fail c32 "검증 66세션 상태 다름 — 보고"
pre c33; env_ c33 POOL_PER=30000; fail c33 "공유 풀 등록값 다름 — 보고"
pre c34; env_ c34 V_OUT=95; fail c34 "Flyway 최고 버전이 96 이 아님 또는 조회 실패 — 보고"

echo "== c41~c44 단계 전제: U2 재기동 전·복구 시작·frontend 교체 시작·원래 U2 꼬리 기록 있음"
ini c41; u0u1 c41; n0=$(chg c41); chk c41 U2확인 "" 1 "U2 배포 시각 기록 없음 — U2 재기동 전 상태" 1; is c41 "ok-U2 없음·변경 명령 0" "$(ex c41 ok-U2) $(chg c41)" "없음 $n0"
pre c42; : > runs/$P-c42/home/$MV/rb-probe.txt; fail c42 "복구를 시작한 기록 rb-probe.txt 이 있다"
pre c43; : > runs/$P-c43/home/$MV/t-fe.utc; fail c43 "frontend 교체를 시작한 기록 t-fe.utc 이 있다"
pre c44; : > runs/$P-c44/home/$MV/svc-deploy.txt; fail c44 "원래 U2 의 꼬리 기록(svc-deploy·git-deploy)이 있다"
pre c45; echo 교체시작 > runs/$P-c45/home/$MV/fe-stage.txt; fail c45 "U1 기록(frontend 단계 빌드끝·새 이미지 대응)이 U1 통과 상태가 아님"
fi
