"""v2 변이 시험 — 임시 폴더에 사본을 만들고 한 줄씩 바꿔 시험을 돌린다. 각 변이에서 시험이 하나 이상 실패해야 한다."""
import shutil, subprocess, sys, tempfile
from pathlib import Path
HERE = Path(__file__).resolve().parent.parent
MUTS = {
    "m1_exit_cost_0": ("EXIT_COST = FEE + SLIP", "EXIT_COST = Decimal(0)"),
    "m2_drop_undecidable": ("primary = None if undecidable else", "primary = None if (undecidable and not diffs) else"),
    "m3_skip_protocol": ("    check_against_protocol(arms_rows, protocol)\n", "    pass\n"),
    "m4_skip_all_same_sha": ('    if len({r.get("code_sha") for r in arms_rows}) != 1:', '    if False:'),
    "m5_skip_fingerprint": ('        if e.get("ruleset_hash") != r.get("ruleset_hash"):', '        if False:'),
    "m6_skip_t0_trades": ("    if early:\n", "    if False:\n"),
}
out = []
for name, (old, new) in MUTS.items():
    d = Path(tempfile.mkdtemp(prefix="abv2-" + name + "-"))
    for f in ("ab_aggregate.py", "test_ab_aggregate.py", "protocol.json"):
        shutil.copy(HERE / f, d / f)
    src = (d / "ab_aggregate.py").read_text(encoding="utf-8")
    assert src.count(old) == 1, (name, src.count(old))
    (d / "ab_aggregate.py").write_text(src.replace(old, new), encoding="utf-8")
    if name == "m2_drop_undecidable":
        s = (d / "ab_aggregate.py").read_text(encoding="utf-8")
        s = s.replace("primary = None if (undecidable and not diffs) else str((sum(diffs) / len(diffs))",
                      "primary = None if (undecidable and not diffs) else str((sum(diffs) / len(diffs))")
        (d / "ab_aggregate.py").write_text(s, encoding="utf-8")
    r = subprocess.run([sys.executable, "-X", "utf8", "-m", "unittest", "test_ab_aggregate"], cwd=d, capture_output=True, text=True, encoding="utf-8")
    tail = (r.stderr or "").strip().splitlines()[-1] if r.stderr else ""
    fails = [l for l in (r.stderr or "").splitlines() if l.startswith(("FAIL:", "ERROR:"))]
    out.append(f"{name}: exit={r.returncode} {tail} :: {'; '.join(fails)}")
    shutil.rmtree(d, ignore_errors=True)
print("\n".join(out))
