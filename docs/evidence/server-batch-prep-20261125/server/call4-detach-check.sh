# v1.250 §2 서버 호출 4: 호출 3 이 이르러(분리 사례 진행 중) 같은 확인을 완료 뒤 1회 더. 쓰기는 전용 폴더의 selftest-final.sha256 뿐(호출 3 의 selftest.sha256 은 보존).
set -u
D=$HOME/work-batches/d81b-20261127-exp1
echo "[호출4 시작] $(date -u '+%F %T')Z $(date -u +%s)"
bash "$D/batch/selftest.sh" detach-check "$D/selftest/detach1"; echo "detach-check rc=$?"
echo "== d01"; R=$(ls -d "$D"/selftest/detach1/case-d01-detached/runs/*); ls "$R"; cat "$R/final.txt" "$R/exit-codes.txt"; tail -3 "$R/steps.txt"; cat "$D/selftest/detach1/case-d01-detached/calls.txt"
[ -e "$D/selftest-final.sha256" ] || ( cd "$D/selftest" && find . -type f | sort | xargs -d '\n' sha256sum > ../selftest-final.sha256 ) && echo "selftest-final.sha256 $(sha256sum < "$D/selftest-final.sha256" | cut -d' ' -f1) $(wc -l < "$D/selftest-final.sha256")파일"
[ -e "$HOME/maint-d81b-20261127-exp1" ] && echo "🔴 maint 경로 있음" || echo "maint-d81b 없음"; [ -e "$D/runs" ] && echo "🔴 runs 있음" || echo "runs 없음(배치 미등록)"; pgrep -af "work-batches" || echo "work-batches 프로세스 0"
echo "[호출4 끝] $(date -u '+%F %T')Z"
