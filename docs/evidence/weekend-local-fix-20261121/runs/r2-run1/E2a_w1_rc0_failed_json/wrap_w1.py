# 시험용 감싸기(사례 E2a_w1_rc0_failed_json) — 원본 registry_f1.py 를 그대로 실행한 뒤 json_fail, 종료값 0
import json, os, subprocess, sys
sys.dont_write_bytecode = True
REAL = 'D:\\Claude Code\\projects\\crypto-auto-trader\\docs\\evidence\\weekend-local-20261120\\candidate\\registry_f1.py'
p = subprocess.run([sys.executable, '-I', REAL, *sys.argv[1:]])
out = sys.argv[sys.argv.index('--out') + 1]
mode = 'json_fail'
if mode == 'json_fail':
    o = json.load(open(out, encoding='utf-8'))
    o['verdict'], o['exit'] = 'MISMATCH', 20
    json.dump(o, open(out, 'w', encoding='utf-8', newline='\n'), ensure_ascii=False, indent=1)
elif mode == 'json_remove':
    os.remove(out)
sys.exit(0)
