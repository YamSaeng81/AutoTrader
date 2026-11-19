#!/usr/bin/env bash
cd "/d/Claude Code/projects/crypto-auto-trader/docs/evidence" || exit 1
export PATH="/c/Users/SANGJO~1/AppData/Local/Temp/claude/e30ef13592d895eb/c6f8fbe8-9704-4e4c-bf55-abd7b5a96074/scratchpad/sshshim2:$PATH"
command -v ssh > n03-1119-1000/ssh-path.txt
bash n03-1119-1000/read.sh 20261119-1000 n03-1119-1000 20261119 2> n03-1119-1000/read-stderr.txt
echo "read rc=$?" >> n03-1119-1000/read-stderr.txt
