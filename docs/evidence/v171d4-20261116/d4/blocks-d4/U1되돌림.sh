# U1-되돌림(V171D4) — U1 이 '… U1-되돌림' 으로 멈췄거나, U1 통과 뒤 운영자가 U2 를 하지 않기로 했을 때만 쓴다(자동 실행 없음). 재기동하지 않는다.
# 대상은 HEAD=NEW 뿐이다(이미 OLD 이면 체크아웃 없이 이미지 이름·원문만 맞춘다). 그 밖 HEAD·추적 변경·실행 컨테이너 대응 불일치·cron 불일치·프로세스/이미지 조회 실패는 바꾸지 않고 멈춘다.
# V171D4: 이미지 이름은 U1 의 frontend 단계 기록(fe-stage.txt)과 대응 확인한 보존 태그로 되돌린다. 컨테이너 .Image(가변 latest 의 옛 값)를 복구 출처로 쓰지 않는다.
#   없음/보존태그 = frontend 빌드 전(이미지 이름이 바뀌었으면 제3의 이미지 — 멈춤) · 빌드시작 = 빌드 중/뒤 실패(바뀐 이름은 보존 태그로) · 빌드끝 = 새 이미지 기록됨(이름이 그 새 이미지일 때만 되돌림)
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${UB:-}" = V171D4 ] || stop "U 변수가 v1.171 통합 후보 초안 D4 판이 아님 — F → G → v1.171 통합 후보 초안 D4 U 변수(초안은 실행하지 않는다)"
[ ! -e "$M/ok-U2" ] && [ ! -e "$M/t-deploy.utc" ] || stop "U2(재기동)를 시작했다 — U1-되돌림이 아니라 U되돌림"
IMG0=$(cat "$M/image-before.txt"); [ "$IMG0" = "$IMGOLD" ] || stop "image-before 가 #27 이미지(IMGOLD)가 아님"
[ -s "$M/image-before-chain.txt" ] && [ "$(ok3 "$M/image-before-chain.txt")" = "$IMGOLD $IMGOLDM $IMGOLDC" ] || stop "U0 backend 대응 기록이 없거나 고정값과 다름 — 바꾸지 않고 보고"
[ -s "$M/image-fe-before-chain.txt" ] && [ "$(ok3 "$M/image-fe-before-chain.txt")" = "$FETOLD $FEMOLD $FECOLD" ] && [ "$(cat "$M/fe-ref-before.txt")" = autotrader-frontend ] || stop "U0 frontend 대응 기록이 없거나 고정값과 다름 — 바꾸지 않고 보고"
[ -e "$M/start_66.py.before" ] || stop "U0 보존본이 없다 — 보고"
date '+%F %T %Z' | tee "$M/t-u1rollback.txt"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-pre-u1rollback.txt"
H=$(head -1 "$M/git-pre-u1rollback.txt")
[ "$H" = "$NEW" ] || [ "$H" = "$OLD" ] || stop "HEAD 가 후보·b5cd1d0 어느 것도 아님($H) — 다른 작업 상태일 수 있다. 바꾸지 않고 보고"
[ "$(sed -n 2p "$M/git-pre-u1rollback.txt")" = "(끝)" ] || stop "추적 파일 변경이 있다 — 덮어쓰지 않고 보고"
FS=$(cat "$M/fe-stage.txt" 2>/dev/null || echo 없음); echo "frontend 단계 기록: $FS" | tee "$M/fe-stage-u1rollback.txt"
case "$FS" in 없음|보존태그|빌드시작|빌드끝) ;; *) stop "frontend 단계 기록이 U1 단계가 아님($FS) — 바꾸지 않고 보고" ;; esac
echo "== 바꾸기 전 대응 확인 — 실행 컨테이너 두 개·이미지 이름·보존 태그"
isimg backend "$IMGOLD" $IMGOLD $IMGOLDM $IMGOLDC || stop "실행 중 backend 가 #27 이미지 대응이 아님 또는 조회 실패 — 재기동이 있었을 수 있다. 바꾸지 않고 보고"
isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "실행 중 frontend 가 U0 대응이 아님 또는 조회 실패 — 바꾸지 않고 보고"
BL=0; same3 $REPO:latest "$M/image-before-chain.txt" 2>> "$M/u1rb-probe.txt" || BL=1
if [ "$BL" = 1 ]; then
  L=$(docker image inspect --format '{{.Id}}' $REPO:latest) || stop "backend latest 조회 실패 — 바꾸지 않고 보고"
  [ -s "$M/image-new.txt" ] && [ "$L" = "$(cat "$M/image-new.txt")" ] || stop "backend latest 가 U0 이미지·U1 새 이미지 어느 것도 아님($L) — 바꾸지 않고 보고"
  same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "backend 보존 태그가 U0 대응과 다름 또는 조회 실패 — 바꾸지 않고 보고"
fi
FL=0; same3 autotrader-frontend "$M/image-fe-before-chain.txt" 2>> "$M/u1rb-probe.txt" || FL=1
if [ "$FL" = 1 ]; then
  case "$FS" in 빌드시작|빌드끝) ;; *) stop "frontend 이미지 이름이 U0 와 다른데 U1 이 frontend 빌드 전 단계($FS) — 제3의 이미지일 수 있다. 바꾸지 않고 보고" ;; esac
  FL1=$(docker image inspect --format '{{.Id}}' autotrader-frontend) || stop "frontend 이미지 이름 조회 실패 — 바꾸지 않고 보고"
  [ "$FS" != 빌드끝 ] || { [ -s "$M/image-fe-new.txt" ] && [ "$FL1" = "$(cat "$M/image-fe-new.txt")" ]; } || stop "frontend 이미지 이름이 U1 새 이미지가 아님($FL1, 제3의 이미지) — 바꾸지 않고 보고"
  echo "$FL1" | tee "$M/fe-latest-pre-u1rollback.txt"
  same3 autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || stop "frontend 보존 태그가 U0 대응과 다름 또는 조회 실패 — 바꾸지 않고 보고"
fi
echo "되돌릴 이미지 이름: backend latest $([ "$BL" = 1 ] && echo 보존 태그로 || echo 그대로) · frontend $([ "$FL" = 1 ] && echo 보존 태그로 || echo 그대로)" | tee "$M/u1rb-plan.txt"
crontab -l > "$M/crontab-u1rollback.txt" || stop "crontab 조회 실패 — 바꾸지 않고 보고"
[ "$(h256 "$M/crontab-u1rollback.txt")" = "$CRON" ] || stop "crontab 이 f2dce280… 과 다름 — 바꾸지 않고 보고"
FI=0; fresh_idle u1rb || FI=$?
[ "$FI" = 0 ] || { [ "$FI" = 1 ] && stop "freshness 회차가 돌고 있다(아직 바꾼 것 없음) — 끝난 뒤 다시(끊지 않는다)"; stop "프로세스 조회 실패(아직 바꾼 것 없음) — 회차 없음으로 보지 않는다. pgrep-u1rb.* 를 보고"; }
if [ "$H" = "$NEW" ]; then git checkout --quiet $OLD; else echo "HEAD 가 이미 b5cd1d0 — 체크아웃하지 않는다"; fi
if [ "$BL" = 1 ]; then docker tag "$IMGOLD" $REPO:latest; fi
same3 $REPO:latest "$M/image-before-chain.txt" || stop "backend latest 원복 확인 실패 — 보고"
if [ "$FL" = 1 ]; then docker tag "$FETOLD" autotrader-frontend; fi
same3 autotrader-frontend "$M/image-fe-before-chain.txt" || stop "frontend 이미지 이름 원복 확인 실패 — 보고"
isimg backend "$IMGOLD" $IMGOLD $IMGOLDM $IMGOLDC || stop "실행 중 backend 가 바뀌었다 또는 조회 실패 — 보고"
isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "실행 중 frontend 가 바뀌었다 또는 조회 실패 — 보고"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; echo "backend latest $(ok3 "$M/image-before-chain.txt")"; echo "frontend latest $(ok3 "$M/image-fe-before-chain.txt")"; } | tee "$M/git-u1rollback.txt"
[ "$(head -1 "$M/git-u1rollback.txt")" = "$OLD" ] && [ "$(sed -n 2p "$M/git-u1rollback.txt")" = "(끝)" ] || stop "되돌림 결과가 b5cd1d0 가 아님 — 보고"
[ "$(h256 "$NF")" = "$NHOLD" ] && cmp -s "$NF" "$M/start_66.py.before" && [ -e "$NT" ] || stop "start_66.py 가 U0 보존본과 다르거나 시험 파일이 없다 — 보고"
rm -f "$M/ok-U1"; echo "↩ U1-되돌림 끝 — HEAD b5cd1d0·backend latest = #27(52e12428)·frontend 이미지 이름 = U0 대상(837db515), 실행 컨테이너 두 개 대응 그대로, 재기동 없음. 상태 파일(streak·snap·log)은 되감지 않았다"
)
