# U 블록 추출·bash -n. 사용: python bashn.py u-blocks.md (n17 근거의 bashn.py 와 같은 내용, 머리 주석만 다름)
import re, subprocess, pathlib, sys, tempfile, os
s = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
blocks = re.findall(r'(?:^|\n)(\*\*[^\n]*?\*\*)[^\n]*\n```bash\n(.*?)\n```', s, re.S)
out = []
for i, (h, b) in enumerate(blocks):
    fd, p = tempfile.mkstemp(suffix='.sh'); os.close(fd)
    pathlib.Path(p).write_text(b + '\n', encoding='utf-8', newline='\n')
    r = subprocess.run(['bash', '-n', p], capture_output=True, text=True, encoding='utf-8')
    out.append('%d %s rc=%d %s' % (i, h[:24], r.returncode, r.stderr.strip()[:200])); os.unlink(p)
out.append('blocks %d' % len(blocks))
pathlib.Path('bash-n.txt').write_text('\n'.join(out) + '\n', encoding='utf-8', newline='\n')
