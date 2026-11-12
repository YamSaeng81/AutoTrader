#!/bin/bash
# 사용: probe.sh <작업사본> <출력폴더> <--tests 패턴...>
W=$1; R=$2; shift 2
mkdir -p $R; cd $W
echo "start $(date '+%T') $(git rev-parse --short HEAD) args: $*" > $R/time.txt
rm -rf web-api/build/test-results/test
A=""; for p in "$@"; do A="$A --tests $p"; done
./gradlew :web-api:test --rerun --continue --console=plain $A > $R/gradle.log 2>&1
echo "rc=$? end $(date '+%T')" >> $R/time.txt
mkdir -p $R/xml; cp web-api/build/test-results/test/*.xml $R/xml/
cd $R/xml; t=0; f=0
for x in *.xml; do a=$(grep -o 'tests="[0-9]*"' $x|head -1|tr -dc 0-9); b=$(grep -o 'failures="[0-9]*"' $x|head -1|tr -dc 0-9); c=$(grep -o 'errors="[0-9]*"' $x|head -1|tr -dc 0-9); t=$((t+a)); f=$((f+b+c)); [ $((b+c)) -gt 0 ] && echo "FAIL $((b+c)) ${x#TEST-com.cryptoautotrader.api.}"; done > $R/fails.txt
echo "classes=$(ls|wc -l) tests=$t fail=$f" >> $R/fails.txt
cat $R/time.txt $R/fails.txt
