# v1.250 §2 서버 호출 3: 호출 2 가 끝난 뒤 분리 실행 사례 결과 확인 + 합성 검증 결과 회수. 쓰기는 전용 폴더 selftest/ 안의 체크섬 파일뿐.
set -u
D=$HOME/work-batches/d81b-20261127-exp1
echo "[호출3 시작] $(date -u '+%F %T')Z $(date -u +%s)"
bash "$D/batch/selftest.sh" detach-check "$D/selftest/detach1"; echo "detach-check rc=$?"
cat "$D/selftest/detach1/nohup.txt"
echo "== d01 단계"; cat "$D"/selftest/detach1/case-d01-detached/runs/*/steps.txt | head -3; tail -2 "$D"/selftest/detach1/case-d01-detached/runs/*/steps.txt; cat "$D"/selftest/detach1/case-d01-detached/runs/*/final.txt
( cd "$D/selftest" && find . -type f | sort | xargs -d '\n' sha256sum > ../selftest.sha256 ) && echo "selftest.sha256 $(sha256sum < "$D/selftest.sha256" | cut -d' ' -f1) $(wc -l < "$D/selftest.sha256")파일"
echo "== result r1"; cat "$D/selftest/r1/result.txt"
echo "[호출3 끝] $(date -u '+%F %T')Z"
