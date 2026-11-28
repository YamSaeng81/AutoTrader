import difflib
import hashlib
import json
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).parent
COMMIT = "d76d9ab2643e2ed460797fa469610e2967439562"
PRODUCT = "1b719645ef907cb1aa45ffa01438fcfda7566758"
IMAGE = "sha256:66c4eba80eebdce70746661dd8ac95054d3b0c9c6fbf5b11a96b2f1753315a89"
SOURCE = subprocess.check_output(["git", "show", COMMIT + ":docs/LIMITED_LIVE_TEST_RUNBOOK.md"]).decode("utf-8")

def extract(pattern):
    marker = re.search(pattern, SOURCE)
    if not marker:
        raise ValueError("MARKER_NOT_FOUND")
    match = re.search(r"```bash\r?\n(.*?)\r?\n```", SOURCE[marker.start():], re.S)
    if not match:
        raise ValueError("BLOCK_NOT_FOUND")
    return match.group(1) + "\n"

def replace(s, before, after, expected=1):
    if s.count(before) != expected:
        raise ValueError("REPLACEMENT_COUNT")
    return s.replace(before, after)

def main():
    originals = {
        "F-variables-d81b.sh": extract(r"\*\*F [^\n]+\*\*\s*\u2014"),
        "F1-d81b.sh": extract(r"\*\*F1\."),
    }
    f = replace(originals["F-variables-d81b.sh"], "M=~/start-sid203;", "M=~/start-sid203-d81b;")
    f, count = re.subn(r"^NEW=.*$", "NEW=" + PRODUCT + "   # v1.254 accepted deployment", f, flags=re.M)
    if count != 1:
        raise ValueError("NEW_COUNT")
    f1 = replace(originals["F1-d81b.sh"], "0e1d2a02", "1b719645")
    f1 = replace(f1, "sha256:2c5d84276cdbf01f718077e3d2d0475f6af5982d7afb95082750fcebe7f5979f", IMAGE)
    f1 = replace(f1, "~/maint-v172/image-new.txt", "~/maint-d81b-20261127-exp1/image-new.txt", 2)
    f1 = replace(f1, "2c5d8427", "66c4eba8")
    f1 = replace(f1, "v1.176 V172D4", "v1.254 D81B")
    f1 = replace(f1, "V172D4", "D81B")
    revised = {"F-variables-d81b.sh": f, "F1-d81b.sh": f1}
    diff = ""
    for name, value in revised.items():
        (ROOT / name).write_text(value, encoding="utf-8", newline="")
        diff += "".join(difflib.unified_diff(originals[name].splitlines(True), value.splitlines(True), fromfile="original/" + name, tofile=name))
    (ROOT / "F-pins.diff").write_text(diff, encoding="utf-8", newline="")
    pins = {
        "version": "v1.255", "product_sha": PRODUCT, "backend_image": IMAGE,
        "backend_manifest": "sha256:625ba822f7ee8ea6936f0138b2335a54cd7b3473fbc0e2dd0b49b7bf4fe8e0c4",
        "backend_config": "sha256:8e32ebb8479075cd28cf5c8cf8b92cf8e8d1af9299676328e2851310c8fd4af8",
        "frontend_image": "sha256:9d7f123ad6afd75b46d954b7c7031267df5afb3bf6e99660d5a53a3c5d43798e",
        "flyway_version": 96, "flyway_checksum": -424836241,
        "jarm": "e8f425079d0fb77a9f224ba027ad16a18415e74238dac41edcbe2f9a2018a546", "jarn": 900,
        "source_runbook_commit": COMMIT,
        "source_runbook_sha256": hashlib.sha256(SOURCE.encode()).hexdigest(),
        "final_S": "not_confirmed", "F1_executed": False, "human_receipt": "unconfirmed",
        "files": {name: hashlib.sha256(value.encode()).hexdigest() for name, value in revised.items()},
    }
    (ROOT / "N06-pins.json").write_text(json.dumps(pins, indent=2) + "\n", encoding="utf-8", newline="")
    print("Generated two derived blocks; exact replacements checked; original RUNBOOK untouched")

if __name__ == "__main__":
    main()
