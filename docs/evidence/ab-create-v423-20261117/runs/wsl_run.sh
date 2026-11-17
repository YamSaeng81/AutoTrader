#!/bin/bash
# WSL Ubuntu(일반 사용자)에서 부른다: 묶음을 Linux 고유 파일 시스템(/tmp)에 복사해 시험하고, 보존 결과만 원래 폴더 runs/ 로 돌려준다.
set -u
SRC="$1"; exec > "$SRC/runs/linux-wsl-test.txt" 2>&1   # 기록은 WSL 안 한 fd 로(Windows 쪽 stdout·stderr 합치기에서 첫 줄이 덮인 1차 시도 뒤 변경)
D=$(mktemp -d /tmp/v423.XXXX) || exit 2
cp -r "$SRC/bundle" "$SRC/create" "$SRC/sma20" "$SRC/tool" "$SRC/preflight" "$D/" || exit 2
cd "$D" || exit 2
echo "workdir=$D uname=$(uname -sr) user_uid=$(id -u) bash=$BASH_VERSION $(python3 --version) $(grep -V | head -1) $(stat --version | head -1)"
sha256sum -c bundle/SHA256SUMS.bundle | grep -c ': OK$' | sed 's/^/bundle_manifest_ok=/'
sha256sum bundle/test_run_bundle.sh
mkdir -p "$D/keep"; date -u "+start_utc %F %T"
KEEP_DIR="$D/keep" bash bundle/test_run_bundle.sh; r=$?
echo "test_rc=$r"; date -u "+end_utc %F %T"
[ -e "$D/run-live" ] && echo "run-live 생김" || echo "run-live 없음"
rm -rf "$SRC/runs/linux-keep"; cp -r "$D/keep" "$SRC/runs/linux-keep"
rm -rf "$D"
exit $r
