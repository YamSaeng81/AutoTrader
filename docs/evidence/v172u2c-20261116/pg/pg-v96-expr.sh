#!/bin/bash
# 총괄 v1.174 §2-6: U2확인의 V96 이력 SELECT 를 로컬 PostgreSQL 15(시험용 컨테이너 v152-pg, 운영 DB 아님)에서 그대로 실행해 문자열 형식을 대조한다.
# SQL 원문은 U2확인.sh 에서 꺼낸다(손으로 다시 쓰지 않는다). 같은 세션의 임시 표 flyway_schema_history(운영 표와 같은 열 형식: version varchar(50)·success boolean·checksum integer)가 이름을 가린다.
# 사용: bash pg-v96-expr.sh <U2확인.sh> — 출력은 사례 이름과 결과 한 줄씩
set -euo pipefail
SQL=$(PYTHONIOENCODING=utf-8 python -I -c 'import re,sys; s=open(sys.argv[1],encoding="utf-8").read(); m=re.findall(r"V96H=\$\(\$PSQL -At -c \"(SELECT string_agg[^\"]*)\"\)", s); assert len(m)==1; print(m[0])' "$1")
echo "SQL(U2확인 원문): $SQL"
P() { docker exec -i v152-pg psql -X -q -v ON_ERROR_STOP=1 -U trader -d s5 -At "$@"; }
echo "서버: $(P -c 'SHOW server_version')"
T="CREATE TEMP TABLE flyway_schema_history (installed_rank integer NOT NULL, version varchar(50), description varchar(200), success boolean NOT NULL, checksum integer)"
run() { local name=$1 rows=$2 r; r=$(P -c "$T" ${rows:+-c "INSERT INTO flyway_schema_history VALUES $rows"} -c "$SQL"); printf '%-22s [%s]\n' "$name" "$r"; }
run "정상(운영과 같은 행)" "(94,'94','sell quarantine release',true,-785319049),(95,'96','paper aligned runtime state',true,-424836241)"
run "실패 행(success=false)" "(94,'94','x',true,-785319049),(95,'96','x',false,-424836241)"
run "체크섬 다름" "(94,'94','x',true,-785319049),(95,'96','x',true,123456)"
run "0행(V95 이후 없음)" "(94,'94','x',true,-785319049)"
run "복수 행(95·96)" "(94,'95','x',true,111),(95,'96','x',true,-424836241)"
run "V96 두 번(실패·성공)" "(95,'96','x',false,-424836241),(96,'96','x',true,-424836241)"
run "체크섬 NULL" "(95,'96','x',true,NULL)"
echo "개별 boolean 열 표시(진단과 같은 -At): [$(P -c "$T" -c "INSERT INTO flyway_schema_history VALUES (95,'96','x',true,-424836241)" -c "SELECT success FROM flyway_schema_history")]"
echo "boolean || text 연결: [$(P -c "SELECT true || ':' , (true::text)")]"
