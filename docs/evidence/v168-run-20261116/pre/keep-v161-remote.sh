# v1.170 §2 — 원격 보존 구간(keep-v161.sh 가 ssh 로 보낸다). 기존 ~/maint-v161 전체를 ~/maint-v161-pre-v168-20261116 에 보존하고 sha256 목록을 대조한다.
# 실패 즉시 중단(set -euo pipefail) + 목록 생성·복사·비교마다 명시 종료값 확인. 임시 목록은 이번 호출 전용 mktemp -d 폴더에만 두고 종료 시 그 폴더만 지운다.
# 기존 보존본·maint-v167/v168 이 있으면 바꾸지 않고 종료3. 복사/대조 실패로 사본이 남아도 삭제·덮어쓰기·재시도하지 않는다(종료4/5, 경로 보고).
set -euo pipefail
export LC_ALL=C
O="$HOME/maint-v161"; K="$HOME/maint-v161-pre-v168-20261116"
echo "서버 $(date '+%F %T %Z')"
echo "== 보존 전 목록(확인용 — 판정과 무관)"; ls -ld "$HOME"/maint-v16* 2>&1 || true
for d in "$HOME/maint-v167" "$HOME/maint-v168"; do
  if [ -e "$d" ]; then echo "🔴 멈춤: $d 가 이미 있다 — 덮어쓰거나 임의 재개하지 않는다"; exit 3; fi
done
if [ ! -d "$O" ]; then echo "🔴 멈춤: $O 없음"; exit 3; fi
if [ -e "$K" ]; then echo "🔴 멈춤: $K 가 이미 있다"; exit 3; fi
echo "== 원본 숨김·하위 폴더 확인(확인용)"; { ls -la "$O" | head -50; } || true
T=$(mktemp -d "${TMPDIR:-/tmp}/keep-v161.XXXXXXXX")
trap 'rm -rf -- "$T"' EXIT
mklist() { ( cd -- "$1" && find . -type f -print0 | sort -z | xargs -0 -r sha256sum ) > "$2"; }
rc=0; mklist "$O" "$T/orig.sha" || rc=$?
if [ "$rc" -ne 0 ]; then echo "🔴 원본 목록 생성 실패 rc=$rc — 복사하지 않았다"; exit 4; fi
n=$(wc -l < "$T/orig.sha")
if [ "$n" -eq 0 ]; then echo "🔴 원본 목록 0개 — 복사하지 않았다"; exit 4; fi
rc=0; cp -a -- "$O" "$K" || rc=$?
if [ "$rc" -ne 0 ]; then echo "🔴 복사 실패 rc=$rc — 사본 $K 가 남았을 수 있다(삭제·재시도 안 함)"; exit 4; fi
rc=0; mklist "$K" "$T/keep.sha" || rc=$?
if [ "$rc" -ne 0 ]; then echo "🔴 사본 목록 생성 실패 rc=$rc — 사본 $K 남김(삭제·재시도 안 함)"; exit 4; fi
m=$(wc -l < "$T/keep.sha")
echo "== 원본 파일 ${n}개 · 사본 ${m}개"
rc=0; cmp -s "$T/orig.sha" "$T/keep.sha" || rc=$?
if [ "$rc" -ne 0 ]; then echo "🔴 원본·사본 sha256 목록 다름 rc=$rc — 사본 $K 남김(삭제·재시도 안 함)"; diff "$T/orig.sha" "$T/keep.sha" || true; exit 5; fi
echo "== 원본 sha256 목록"; cat "$T/orig.sha"
echo "== 보존 후 목록(확인용)"; ls -ld "$HOME"/maint-v16* 2>&1 || true
echo "✅ 보존 완료: 원본·사본 sha256 목록 같음 (${n}개) → $K"
