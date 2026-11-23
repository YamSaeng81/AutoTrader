# U1-되돌림(D81D4 — V172D4 원문에서 frontend 단계를 뺐다) — U1 이 '… U1-되돌림' 으로 멈췄거나, U1 통과 뒤 운영자가 U2 를 하지 않기로 했을 때만 쓴다(자동 실행 없음). 재기동하지 않는다.
# 대상은 HEAD=NEW 뿐이다(이미 OLD 이면 체크아웃 없이 backend latest·원문만 맞춘다). 그 밖 HEAD·추적 변경·실행 컨테이너 대응 불일치·cron 불일치·프로세스/이미지 조회 실패는 바꾸지 않고 멈춘다.
# frontend 는 이 판에서 바꾸지 않는다 — 이미지 이름·실행 컨테이너가 U0 대응과 다르거나 조회 불명이면 아무것도 바꾸지 않고 멈춘다. 재기동 뒤 복구(U되돌림)와는 전제·출처 판정이 다르다.
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${UB:-}" = D81D4 ] || stop "U 변수가 v1.198 backend 전용 D81D4 판이 아님 — F → G → D81D4 U 변수(초안은 실행하지 않는다)"
[ ! -e "$M/ok-U2" ] && [ ! -e "$M/t-deploy.utc" ] || stop "U2(재기동)를 시작했다 — U1-되돌림이 아니라 U되돌림"
IMG0=$(cat "$M/image-before.txt"); [ "$IMG0" = "$IMGOLD" ] || stop "image-before 가 운영 2c5d8427 이미지(IMGOLD)가 아님"
[ -s "$M/image-before-chain.txt" ] && [ "$(ok3 "$M/image-before-chain.txt")" = "$IMGOLD $IMGOLDM $IMGOLDC" ] || stop "U0 backend 대응 기록이 없거나 고정값과 다름 — 바꾸지 않고 보고"
[ -s "$M/image-fe-before-chain.txt" ] && [ "$(ok3 "$M/image-fe-before-chain.txt")" = "$FETOLD $FEMOLD $FECOLD" ] && [ "$(cat "$M/fe-ref-before.txt")" = autotrader-frontend ] || stop "U0 frontend 대응 기록이 없거나 고정값과 다름 — 바꾸지 않고 보고"
[ -e "$M/start_66.py.before" ] || stop "U0 보존본이 없다 — 보고"
date '+%F %T %Z' | tee "$M/t-u1rollback.txt"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-pre-u1rollback.txt"
H=$(head -1 "$M/git-pre-u1rollback.txt")
[ "$H" = "$NEW" ] || [ "$H" = "$OLD" ] || stop "HEAD 가 후보·0e1d2a02 어느 것도 아님($H) — 다른 작업 상태일 수 있다. 바꾸지 않고 보고"
[ "$(sed -n 2p "$M/git-pre-u1rollback.txt")" = "(끝)" ] || stop "추적 파일 변경이 있다 — 덮어쓰지 않고 보고"
echo "== 바꾸기 전 대응 확인 — 실행 컨테이너 두 개·이미지 이름·보존 태그"
isimg backend "$IMGOLD" $IMGOLD $IMGOLDM $IMGOLDC || stop "실행 중 backend 가 운영 2c5d8427 대응이 아님 또는 조회 실패 — 재기동이 있었을 수 있다. 바꾸지 않고 보고"
isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "실행 중 frontend 가 U0 대응이 아님 또는 조회 실패 — 바꾸지 않고 보고"
echo "== V172D4 R2 — backend latest 출처 판정(원판·이번 빌드판·제3·조회 불명)과 frontend 이미지 이름 무변경 확인. 판정을 끝내기 전에는 HEAD·태그를 바꾸지 않는다"
BN=$(namecls $REPO:latest "$M/image-before-chain.txt" "$M/image-new-chain.txt" 2>> "$M/u1rb-probe.txt") || { tail -3 "$M/u1rb-probe.txt"; stop "backend latest 대응 조회 불명 — 원판·새판으로 추정하지 않는다. 바꾸지 않고 보고"; }
FC=0; same3 autotrader-frontend "$M/image-fe-before-chain.txt" 2>> "$M/u1rb-probe.txt" || FC=$?
echo "이름 출처 판정 backend latest=$BN · frontend 이미지 이름 U0 대응 판정 $FC(0 같음 / 1 다름 / 2 조회 불명)" | tee "$M/u1rb-names.txt"
[ "$BN" != 제3 ] || { tail -3 "$M/u1rb-probe.txt"; stop "backend latest 가 U0 원판·U1 이번 빌드 기록(image-new-chain) 어느 것도 아님 — 출처 미확인, 바꾸지 않고 보고"; }
case "$FC" in 0) ;; 1) tail -3 "$M/u1rb-probe.txt"; stop "frontend 이미지 이름이 U0 대응과 다름 — 이 판은 frontend 를 바꾸지 않았다(출처 미확인). 바꾸지 않고 보고" ;; *) tail -3 "$M/u1rb-probe.txt"; stop "frontend 이미지 이름 대응 조회 불명 — 같음으로 추정하지 않는다. 바꾸지 않고 보고" ;; esac
BL=0; [ "$BN" = 원판 ] || BL=1
if [ "$BL" = 1 ]; then same3 $REPO:0e1d2a0 "$M/image-before-chain.txt" || stop "backend 보존 태그가 U0 대응과 다름 또는 조회 실패 — 바꾸지 않고 보고"; fi
echo "되돌릴 이미지 이름: backend latest $([ "$BL" = 1 ] && echo 보존 태그 대상으로 || echo 그대로) · frontend 그대로(무교체)" | tee "$M/u1rb-plan.txt"
crontab -l > "$M/crontab-u1rollback.txt" || stop "crontab 조회 실패 — 바꾸지 않고 보고"
[ "$(h256 "$M/crontab-u1rollback.txt")" = "$CRON" ] || stop "crontab 이 f2dce280… 과 다름 — 바꾸지 않고 보고"
FI=0; fresh_idle u1rb || FI=$?
[ "$FI" = 0 ] || { [ "$FI" = 1 ] && stop "freshness 회차가 돌고 있다(아직 바꾼 것 없음) — 끝난 뒤 다시(끊지 않는다)"; stop "프로세스 조회 실패(아직 바꾼 것 없음) — 회차 없음으로 보지 않는다. pgrep-u1rb.* 를 보고"; }
if [ "$H" = "$NEW" ]; then git checkout --quiet $OLD; else echo "HEAD 가 이미 0e1d2a02 — 체크아웃하지 않는다"; fi
if [ "$BL" = 1 ]; then docker tag "$IMGOLD" $REPO:latest; fi
same3 $REPO:latest "$M/image-before-chain.txt" || stop "backend latest 원복 확인 실패 — 보고"
same3 autotrader-frontend "$M/image-fe-before-chain.txt" || stop "frontend 이미지 이름이 바뀌었다 또는 조회 실패 — 보고"
isimg backend "$IMGOLD" $IMGOLD $IMGOLDM $IMGOLDC || stop "실행 중 backend 가 바뀌었다 또는 조회 실패 — 보고"
isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "실행 중 frontend 가 바뀌었다 또는 조회 실패 — 보고"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; echo "backend latest $(ok3 "$M/image-before-chain.txt")"; echo "frontend latest $(ok3 "$M/image-fe-before-chain.txt")"; } | tee "$M/git-u1rollback.txt"
[ "$(head -1 "$M/git-u1rollback.txt")" = "$OLD" ] && [ "$(sed -n 2p "$M/git-u1rollback.txt")" = "(끝)" ] || stop "되돌림 결과가 0e1d2a02 가 아님 — 보고"
[ "$(h256 "$NF")" = "$NHOLD" ] && cmp -s "$NF" "$M/start_66.py.before" && [ -e "$NT" ] || stop "start_66.py 가 U0 보존본과 다르거나 시험 파일이 없다 — 보고"
rm -f "$M/ok-U1"; echo "↩ U1-되돌림 끝 — HEAD 0e1d2a02·backend latest = 운영 2c5d8427·frontend 무변경(9d7f123a), 실행 컨테이너 두 개 대응 그대로, 재기동 없음. 상태 파일(streak·snap·log)은 되감지 않았다"
)
