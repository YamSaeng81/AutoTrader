#!/bin/bash
# r3(총괄 v1.174 후속 메모 tail-record §2): U2확인 V95 이후 이력 SELECT 를 로컬 PG 15(v152-pg, 운영 DB 아님)에서 전체 입력 목록으로 실행한다. 블록마다 그 블록의 SQL 원문과 기대값으로 판정한다.
# 사용: bash pg-v96-r3.sh <U2확인.sh> — 사례마다 결과와 블록 기대값 일치 여부(통과/멈춤)를 한 줄씩. 통과는 '정상' 사례 하나뿐이어야 한다.
set -euo pipefail
read -r SQL EXPT < <(PYTHONIOENCODING=utf-8 python -I -c '
import re,sys
s=open(sys.argv[1],encoding="utf-8").read()
m=re.findall(r"V96H=\$\(\$PSQL -At -c \"(SELECT [^\"]*)\"\)", s); e=re.findall(r"\[ \"\$V96H\" = \"([^\"]*)\" \]", s)
assert len(m)==1 and len(e)==1; print(m[0].replace(" ","\x1f"), e[0].replace("$V96SUM","-424836241"))' "$1" | tr -d '\r')   # Windows python print 의 \r 제거(1차 판정 줄 결함 원인)
SQL=${SQL//$'\x1f'/ }
echo "블록 $(sha256sum < "$1" | cut -c1-16) · SQL: $SQL"; echo "블록 기대값(V96SUM=-424836241): $EXPT"
P() { docker exec -i v152-pg psql -X -q -v ON_ERROR_STOP=1 -U trader -d s5 -At "$@"; }
echo "서버: $(P -c 'SHOW server_version')"
T="CREATE TEMP TABLE flyway_schema_history (installed_rank integer NOT NULL, version varchar(50), description varchar(200), success boolean NOT NULL, checksum integer)"
run() { local r; r=$(P -c "$T" ${2:+-c "INSERT INTO flyway_schema_history VALUES $2"} -c "$SQL"); printf '%-30s [%s] %s\n' "$1" "$r" "$([ "$r" = "$EXPT" ] && echo 통과 || echo 멈춤)"; }
run "정상(운영과 같은 행)" "(94,'94','x',true,-785319049),(95,'96','x',true,-424836241)"
run "실패 행(success=false)" "(95,'96','x',false,-424836241)"
run "체크섬 다름" "(95,'96','x',true,123456)"
run "0행(V95 이후 없음)" "(94,'94','x',true,-785319049)"
run "복수 행(95·96)" "(94,'95','x',true,111),(95,'96','x',true,-424836241)"
run "V96 두 번(실패·성공)" "(95,'96','x',false,-424836241),(96,'96','x',true,-424836241)"
run "단독 체크섬 NULL V96" "(95,'96','x',true,NULL)"
run "정상 V96 + NULL 체크섬 V95" "(94,'95','x',true,NULL),(95,'96','x',true,-424836241)"
run "정상 V96 + NULL 체크섬 V96 추가" "(95,'96','x',true,-424836241),(96,'96','x',true,NULL)"
run "정상 V96 + NULL 체크섬 V97" "(95,'96','x',true,-424836241),(96,'97','x',true,NULL)"
run "정상 V96 + 실패·NULL V95" "(94,'95','x',false,NULL),(95,'96','x',true,-424836241)"
run "표 비었음" ""
