"""Validate the four authored Markdown files and seal this review directory."""
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import unquote

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
paths = [ROOT / 'docs/LIMITED_LIVE_TEST_PLAN.md', ROOT / 'docs/CURRENT_HANDOFF.md',
         ROOT / 'docs/PROGRESS.md', HERE / 'README.md']
bad_links, script_chars, lines, link_count = [], [], {}, 0
for path in paths:
    text = path.read_text(encoding='utf-8')
    rel = path.relative_to(ROOT).as_posix()
    lines[rel] = len(text.splitlines())
    for n, line in enumerate(text.splitlines(), 1):
        for ch in re.findall(r'[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\u3001\u3002]', line):
            script_chars.append({'file': rel, 'line': n, 'character': ch})
        for target in re.findall(r'\[[^\]]+\]\(([^)]+)\)', line):
            target = target.strip().strip('<>')
            if re.match(r'^(https?://|mailto:|#)', target):
                continue
            link_count += 1
            p = path.parent / unquote(target.split('#', 1)[0])
            if not p.exists():
                bad_links.append({'file': rel, 'line': n, 'target': target})
result = {'markdown_lines': lines, 'local_links': link_count, 'broken_links': bad_links,
          'foreign_script_characters': script_chars}
(HERE / 'document-check.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
if bad_links or script_chars:
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(1)
rows = []
for p in sorted(HERE.rglob('*')):
    if p.is_file() and p.name != 'SHA256SUMS':
        rows.append(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.relative_to(HERE).as_posix())
(HERE / 'SHA256SUMS').write_text('\n'.join(rows) + '\n', encoding='utf-8')
verified = all(hashlib.sha256((HERE / row[66:]).read_bytes()).hexdigest() == row[:64] for row in rows)
print(json.dumps({'lines': lines, 'links': link_count, 'broken': 0, 'foreign_script': 0,
                  'review_manifest_entries': len(rows), 'manifest_verified': verified}, ensure_ascii=False))
raise SystemExit(0 if verified else 1)
