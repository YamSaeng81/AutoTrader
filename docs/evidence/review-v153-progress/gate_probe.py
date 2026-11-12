from pathlib import Path
import subprocess,shutil,json,hashlib
O=Path(__file__).resolve().parent;R=O.parents[2];H=R/'docs/evidence/v153-20261112/h-rollback';B='C:/Program Files/Git/bin/bash.exe'
D=O/'gate';D.mkdir(exist_ok=False)
for n in ['rollback_gate.sh','gate.sql']:shutil.copy2(H/n,D/n)
wrapper=D/'probe.sh'
wrapper.write_text('''#!/usr/bin/env bash
root_fake_psql() {
  local input
  input="$(cat)"
  case "$input" in
    *"AS gate"*) printf '%s\\n' 'GATE|v96=1|created=1|running=0|odd_session=0|open=0|closing=0|odd_position=0|stopped_with_open=0' 'DECIDED|closing_without_decided_at=0' ;;
    *) printf '%s\\n' 'id,status' '1,STOPPED' ;;
  esac
}
sha256sum() { printf 'ROOT deliberate checksum failure\\n' >&2; return 17; }
export -f root_fake_psql sha256sum
export PSQL=root_fake_psql
bash "$1" "$2"
''',encoding='utf8',newline='\n')
def posix(p):s=str(p.resolve()).replace('\\','/');return '/'+s[0].lower()+s[2:]
p=subprocess.run([B,'--noprofile','--norc',posix(wrapper),posix(D/'rollback_gate.sh'),posix(D/'ledger')],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
(D/'stdout.txt').write_bytes(p.stdout)
result={'rc':p.returncode,'output':p.stdout.decode('utf8'),'checksum_failure_injected':17,'manifest_bytes':(D/'ledger/SHA256SUMS').stat().st_size,'source_sha':hashlib.sha256((D/'rollback_gate.sh').read_bytes()).hexdigest(),'sql_sha':hashlib.sha256((D/'gate.sql').read_bytes()).hexdigest(),'psql_calls':'fake only; no DB/SSH/Docker/network'}
assert p.returncode==0 and b'POST_CREATION_OK' in p.stdout and result['manifest_bytes']==0
(D/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf8');print(json.dumps(result,ensure_ascii=False,indent=2))
