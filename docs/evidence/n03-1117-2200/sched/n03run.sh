#!/usr/bin/env bash
cd "/d/Claude Code/projects/crypto-auto-trader/docs/evidence" || exit 1
export PATH="/c/Users/SANGJO~1/AppData/Local/Temp/claude/e30ef13592d895eb/4db72c23-c6b3-4349-8d0b-eac28a06d925/scratchpad/sshshim2:$PATH"
command -v ssh > n03-1117-2200/ssh-path.txt
bash n03-1117-2200/read.sh 20261117-2200 n03-1117-2200 2> n03-1117-2200/read-stderr.txt
echo "read rc=$?" >> n03-1117-2200/read-stderr.txt
