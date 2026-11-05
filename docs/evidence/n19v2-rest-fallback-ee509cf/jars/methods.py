import re, sys, subprocess
def methods(cls):
    out = subprocess.run(["javap", "-c", "-p", cls], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    out = re.sub(r"#\d+(,\s*\d+)?", "#", out)
    blocks, cur, name = {}, [], None
    for line in out.splitlines():
        if re.match(r"^  \S.*\);?$|^  \S.*\{\};$|^  static \{\};$", line) and not line.startswith("    "):
            if name: blocks[name] = "\n".join(cur)
            name, cur = line.strip(), []
        else:
            cur.append(line)
    if name: blocks[name] = "\n".join(cur)
    return blocks
a, b = methods(sys.argv[1]), methods(sys.argv[2])
diff = [k for k in sorted(set(a) | set(b)) if a.get(k) != b.get(k)]
print("메서드 수", len(a), len(b), "· 다른 메서드", len(diff))
for k in diff: print(("추가 " if k not in a else "삭제 " if k not in b else "변경 ") + k)
