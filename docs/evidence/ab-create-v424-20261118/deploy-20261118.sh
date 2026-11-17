#!/usr/bin/env bash
# 개발 PC → 서버 배치(총괄 v1.181 §4). 🔴 실제 생성 승인 뒤 운영자 확인 1회로 한 번만. 서버에는 새 폴더 하나만 만든다.
#   bash docs/evidence/ab-create-v424-20261118/deploy-20261118.sh <커밋 SHA 40자>
# 로컬 git archive·로컬 검증·원격 ssh/tar·원격 검증의 종료값을 각각 남기고, 하나라도 0 이 아니면 그 자리에서 멈춘다(파이프로 가리지 않는다).
# 종료값: 0 성공 · 2 입력 이상 · 3 로컬 git archive 실패 · 4 로컬 묶음/파일 해시 불일치 · 5 원격 폴더 이미 있음 · 6 원격 만들기/풀기 실패
#        · 7 원격 검증 불일치 · 255 ssh 접속 실패(재시도하지 않음 — 원격 상태를 읽기로 먼저 확인)
set -u
export LC_ALL=C
P=docs/evidence/ab-create-v424-20261118
RN=ab-run-20261118
BUNDLE_LIST_SHA=e28b1293bca3dbabbb885be44ce57ff03e81b7fd21af8b5d0a2e7a5e476c3880
STEPS_SHA=bdf0a76448f1fd40f3561c0166acd9129003d69b6fbfa62dec8c5465b4acdff0   # steps-20261118.sh
C=${1:-}
[[ $C =~ ^[0-9a-f]{40}$ ]] || { echo "STOP 2: 커밋 SHA 40자 필요"; exit 2; }
T=$(mktemp -d) || exit 2; trap 'rm -rf "$T"' EXIT
cd "$(git rev-parse --show-toplevel)" || exit 2
LOGF=$P/deploy-20261118-run.log; [ ! -e "$LOGF" ] || { echo "STOP 2: $LOGF 이미 있음 — 덮어쓰지 않음"; exit 2; }
log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" | tee -a "$LOGF" >&2; }   # 기록은 이 폴더의 deploy-20261118-run.log 에 남는다(근거로 보존)

git archive --format=tar "$C" "$P" > "$T/b.tar"; ga=$?; log "로컬 git archive rc=$ga"
[ $ga -eq 0 ] && [ -s "$T/b.tar" ] || { log "STOP 3"; exit 3; }
mkdir "$T/x" && tar xf "$T/b.tar" -C "$T/x"; tx=$?; log "로컬 풀기 rc=$tx"; [ $tx -eq 0 ] || { log "STOP 3"; exit 3; }
(cd "$T/x/$P" && sha256sum --quiet --strict -c bundle/SHA256SUMS.bundle); lv=$?; log "로컬 묶음 16개 해시 rc=$lv"
lh=$(sha256sum < "$T/x/$P/bundle/SHA256SUMS.bundle" | cut -d' ' -f1); sh=$(sha256sum < "$T/x/$P/steps-20261118.sh" | cut -d' ' -f1)
log "목록 해시 $lh · 단계 파일 해시 $sh"
[ $lv -eq 0 ] && [ "$lh" = "$BUNDLE_LIST_SHA" ] && [ "$sh" = "$STEPS_SHA" ] || { log "STOP 4: 로컬 해시 불일치"; exit 4; }

ssh -o BatchMode=yes cat-prod "if [ -e ~/$RN ]; then exit 10; fi; mkdir ~/$RN || exit 11; tar x --strip-components=3 -C ~/$RN || exit 12" < "$T/b.tar"; sr=$?
log "원격 만들기·풀기 ssh rc=$sr (10 이미 있음 · 11 mkdir 실패 · 12 tar 실패 · 255 접속)"
case $sr in 0) ;; 10) log "STOP 5"; exit 5;; 255) log "STOP 255: 접속 실패 — 원격 폴더 유무를 읽기로 확인 전 재실행 금지"; exit 255;; *) log "STOP 6"; exit 6;; esac

ssh -o BatchMode=yes cat-prod "cd ~/$RN && sha256sum --quiet --strict -c bundle/SHA256SUMS.bundle && sha256sum < bundle/SHA256SUMS.bundle && sha256sum < steps-20261118.sh" < /dev/null > "$T/rv.txt"; rv=$?
log "원격 검증 ssh rc=$rv"; cat "$T/rv.txt" >&2
[ $rv -eq 0 ] && grep -q "^$BUNDLE_LIST_SHA " "$T/rv.txt" && grep -q "^$STEPS_SHA " "$T/rv.txt" || { log "STOP 7: 원격 검증 불일치"; exit 7; }
log "배치 완료 — 서버 ~/$RN, 다음은 steps-20261118.sh 0"; exit 0
