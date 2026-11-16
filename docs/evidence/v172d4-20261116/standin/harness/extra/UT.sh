(
# V172D4 함수 단위 시험(대역 전용 블록, 운영 실행 블록 아님) — U변수의 same3·tagkeep·namecls 반환값을 확인한다.
# rchain·tagq 만 이 블록 안에서 함수로 바꿔 끼운다(나머지는 U변수 원문 정의 그대로). 외부 명령을 부르지 않는다.
set -u; set +e
ok=0; bad=0
t() { if [ "$2" = "$3" ]; then ok=$((ok+1)); echo "UTPASS $1 = $2"; else bad=$((bad+1)); echo "UTFAIL $1 = $2 (기대 $3)"; fi; }
printf 'sha256:aaaaaaaaaaaaaaaaaaaa MA CA -\n' > "$M/ut-a.txt"; printf 'sha256:bbbbbbbbbbbbbbbbbbbb MB CB -\n' > "$M/ut-b.txt"
rchain() { case "$1" in ra) echo "sha256:aaaaaaaaaaaaaaaaaaaa MA CA x" ;; rb) echo "sha256:bbbbbbbbbbbbbbbbbbbb MB CB x" ;; rc) echo "sha256:cccccccccccccccccccc MC CC x" ;; *) return 7 ;; esac; }
tagq() { case "$1" in ra|rb|rc|rf) return 0 ;; rn) return 1 ;; *) return 2 ;; esac; }
same3 ra "$M/ut-a.txt" 2>/dev/null; t "same3 같음" $? 0
same3 rb "$M/ut-a.txt" 2>/dev/null; t "same3 대응 다름" $? 1
same3 rf "$M/ut-a.txt" 2>/dev/null; t "same3 조회 실패" $? 2
same3 ra "$M/ut-none.txt" 2>/dev/null; t "same3 기록 없음" $? 2
tagkeep ra "$M/ut-a.txt" 2>/dev/null; t "tagkeep 있음·같음" $? 0
tagkeep rn "$M/ut-a.txt" 2>/dev/null; t "tagkeep 명시적 부재" $? 1
tagkeep rb "$M/ut-a.txt" 2>/dev/null; t "tagkeep 다른 대상" $? 2
tagkeep rf "$M/ut-a.txt" 2>/dev/null; t "tagkeep 있음인데 대응 조회 실패" $? 3
tagkeep rx "$M/ut-a.txt" 2>/dev/null; t "tagkeep 태그 조회 불명" $? 3
r=$(namecls ra "$M/ut-a.txt" "$M/ut-b.txt" 2>/dev/null); t "namecls 원판" "$?:$r" "0:원판"
r=$(namecls rb "$M/ut-a.txt" "$M/ut-b.txt" 2>/dev/null); t "namecls 새판" "$?:$r" "0:새판"
r=$(namecls rc "$M/ut-a.txt" "$M/ut-b.txt" 2>/dev/null); t "namecls 제3" "$?:$r" "0:제3"
r=$(namecls rb "$M/ut-a.txt" "$M/ut-none.txt" 2>/dev/null); t "namecls 이번 빌드 기록 없음 → 제3" "$?:$r" "0:제3"
r=$(namecls rf "$M/ut-a.txt" "$M/ut-b.txt" 2>/dev/null); t "namecls 조회 불명" "$?:$r" "2:"
r=$(namecls ra "$M/ut-none.txt" "$M/ut-b.txt" 2>/dev/null); t "namecls 원판 기록 없음" "$?:$r" "2:"
echo "UT 결과 $ok/$((ok+bad))"; [ "$bad" = 0 ]
)
