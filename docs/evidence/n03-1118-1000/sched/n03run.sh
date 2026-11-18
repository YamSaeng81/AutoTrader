#!/usr/bin/env bash
cd "/d/Claude Code/projects/crypto-auto-trader/docs/evidence" || exit 1
export PATH="/c/Users/SANGJO~1/AppData/Local/Temp/claude/e30ef13592d895eb/5393789e-187f-4488-b92c-86d906e7c798/scratchpad/sshshim2:$PATH"
command -v ssh > n03-1118-1000/ssh-path.txt
bash n03-1118-1000/read.sh 20261118-1000 n03-1118-1000 20261118 2> n03-1118-1000/read-stderr.txt
echo "read rc=$?" >> n03-1118-1000/read-stderr.txt
