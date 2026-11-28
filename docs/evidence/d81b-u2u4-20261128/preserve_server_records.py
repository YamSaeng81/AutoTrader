"""Read only the named deployment evidence files; no DB or deployment commands."""
import base64
import hashlib
import json
import pathlib
import subprocess

DEST = pathlib.Path(__file__).parent / "server-records"
REMOTE = r'''
import base64, hashlib, json, pathlib
home = pathlib.Path.home()
r = home / "work-batches/d81b-20261127-exp1/runs/d81b-u0u1-20261125T051448Z-2283028"
m = home / "maint-d81b-20261127-exp1"
paths = {
    "batch-final.txt": r / "final.txt",
    "batch-exit-codes.txt": r / "exit-codes.txt",
    "batch-U0.txt": r / "out-U0-deploy.txt",
    "batch-U1.txt": r / "out-U1-deploy.txt",
    "n03-verdict.txt": r / "n03/verdict.txt",
    "image-new.txt": m / "image-new.txt",
    "image-new-chain.txt": m / "image-new-chain.txt",
    "jar-diff.txt": m / "jar-diff.txt",
    "jar-manifest-new.txt": m / "jar-manifest-new.txt",
    "t-deploy.utc": m / "t-deploy.utc",
    "svc-deploy.txt": m / "svc-deploy.txt",
}
result = {}
for name, path in paths.items():
    data = path.read_bytes()
    result[name] = {"sha256": hashlib.sha256(data).hexdigest(), "b64": base64.b64encode(data).decode("ascii")}
print(json.dumps(result, sort_keys=True))
'''

if __name__ == "__main__":
    if DEST.exists():
        raise SystemExit("Existing output directory; not repeated")
    encoded = base64.b64encode(REMOTE.encode()).decode("ascii")
    command = f"printf '%s' '{encoded}' | base64 -d | python3 -"
    result = subprocess.run([
        r"C:\Windows\System32\OpenSSH\ssh.exe", "-F",
        r"C:\Users\SangJoon_Lee\.ssh\config", "-o", "BatchMode=yes",
        "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=15",
        "-T", "cat-prod", command,
    ], capture_output=True, check=False)
    if result.returncode or result.stderr:
        raise SystemExit(f"Read failed rc={result.returncode} stderr_bytes={len(result.stderr)}; no retry")
    records = json.loads(result.stdout)
    decoded = {}
    for name, record in records.items():
        if pathlib.Path(name).name != name:
            raise SystemExit("Invalid name")
        data = base64.b64decode(record["b64"], validate=True)
        if hashlib.sha256(data).hexdigest() != record["sha256"]:
            raise SystemExit("Hash mismatch")
        decoded[name] = data
    DEST.mkdir()
    for name, data in decoded.items():
        (DEST / name).write_bytes(data)
    (DEST / "remote-hashes.json").write_text(json.dumps({k: v["sha256"] for k, v in records.items()}, indent=2) + "\n", encoding="utf-8")
    print(f"Preserved {len(decoded)} files; all remote/local hashes match")
