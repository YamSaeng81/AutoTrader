#!/bin/bash
# 총괄 v1.174 후속 메모(tail-record) §2: 정상 V96 + checksum NULL 인 다른 V95 이후 행 — string_agg 가 NULL 입력을 빼서 추가 행을 놓치는지 로컬 PG 15(v152-pg, 운영 DB 아님)에서 확인한다.
# 사용: bash pg-v96-null.sh <U2확인.sh> — SQL 은 그 블록에서 꺼낸다(pg-v96-expr.sh 와 같은 방식, 별도 실행·별도 출력)
set -euo pipefail
SQL=$(PYTHONIOENCODING=utf-8 python -I -c 'import re,sys; s=open(sys.argv[1],encoding="utf-8").read(); m=re.findall(r"V96H=\$\(\$PSQL -At -c \"(SELECT [^\"]*)\"\)", s); assert len(m)==1; print(m[0])' "$1")
echo "SQL(블록 원문): $SQL"
P() { docker exec -i v152-pg psql -X -q -v ON_ERROR_STOP=1 -U trader -d s5 -At "$@"; }
echo "서버: $(P -c 'SHOW server_version')"
T="CREATE TEMP TABLE flyway_schema_history (installed_rank integer NOT NULL, version varchar(50), description varchar(200), success boolean NOT NULL, checksum integer)"
run() { local r; r=$(P -c "$T" -c "INSERT INTO flyway_schema_history VALUES $2" -c "$SQL"); printf '%-34s [%s]\n' "$1" "$r"; }
run "정상(운영과 같은 행)" "(94,'94','x',true,-785319049),(95,'96','x',true,-424836241)"
run "정상 V96 + NULL 체크섬 V95 행" "(94,'95','x',true,NULL),(95,'96','x',true,-424836241)"
run "정상 V96 + NULL 체크섬 V96 추가 행" "(95,'96','x',true,-424836241),(96,'96','x',true,NULL)"
run "정상 V96 + NULL 체크섬 V97 행" "(95,'96','x',true,-424836241),(96,'97','x',true,NULL)"
run "정상 V96 + 실패·NULL V95 행" "(94,'95','x',false,NULL),(95,'96','x',true,-424836241)"
run "단독 NULL 체크섬 V96" "(95,'96','x',true,NULL)"
