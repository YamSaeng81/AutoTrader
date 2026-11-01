"""F1 → F2 시작 연결 국소 시험 — 문서 원문과 명령 대역만 사용한다.

운영 API·DB·네트워크에는 접근하지 않는다. Git Bash의 PATH를 비우고 문서에서
부르는 외부 명령을 모두 함수 대역으로 제한한다. 파일 대역도 workspace build
아래의 임시 폴더만 읽고 쓴다. 전체 앱 시험이나 리허설을 실행하지 않는다.
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
DOC = ROOT / "docs/LIMITED_LIVE_TEST_PLAN.md"
BUILD = ROOT / "core-engine/build"
BASH = os.environ.get("BASH_EXE") or (
    r"C:\Program Files\Git\bin\bash.exe" if os.name == "nt" else shutil.which("bash")
)
LEGACY_SHA = "b44cecb"
sys.stdout.reconfigure(encoding="utf-8")
results = []


def check(name, ok, detail=""):
    results.append(bool(ok))
    print(("PASS " if ok else "FAIL ") + name)
    if not ok:
        print(detail)


def block(doc, section):
    tail = doc[doc.index("**" + section + ". "):]
    return re.search(r"```bash\n(.*?)\n```", tail, re.S).group(1)


def posix(path):
    value = str(path).replace("\\", "/")
    return "/" + value[0].lower() + value[2:] if os.name == "nt" else value


FILEOPS = r'''import os, pathlib, shutil, sys
root = pathlib.Path.cwd().resolve()
op, *args = sys.argv[1:]
def checked(s):
    p = pathlib.Path(s).resolve()
    if p != root and root not in p.parents:
        raise SystemExit("대역 파일 경로가 시험 폴더 밖이다: " + s)
    return p
if op == "mkdir":
    for s in args:
        if s != "-p": checked(s).mkdir(parents=True, exist_ok=True)
elif op == "rm":
    for s in args:
        if s != "-f": checked(s).unlink(missing_ok=True)
elif op == "cp":
    source, dest = [s for s in args if s != "-p"]
    shutil.copy2(checked(source), checked(dest))
elif op == "touch":
    for s in args: checked(s).touch()
elif op == "stat":
    print(int(checked(args[-1]).stat().st_mtime))
else:
    raise SystemExit("알 수 없는 파일 대역")
'''

STUBS = r'''
export PATH=""
export LC_ALL=C
PSQL=stubpsql
EG=EG_QUERY; EGS=EXPECTED_EG; LIVE1=LIVE_QUERY; SID=203; START_DATE=2026-12-01
NEW=expected-head; M=evidence
date() {
  case "$*" in
    +%s) printf '%s\n' "$FAKE_NOW" ;;
    +%F) printf '2026-12-01\n' ;;
    +%H%M) printf '0850\n' ;;
    '-u +%Y%m%dT%H%M%SZ') printf '20261201T084500Z\n' ;;
    '-u +%Y-%m-%dT%H:%M:%SZ') printf '2026-12-01T08:45:00Z\n' ;;
    '+%F %T %Z') printf '2026-12-01 08:50:00 KST\n' ;;
    *) printf '차단: 알 수 없는 date 대역 %s\n' "$*" >&2; return 97 ;;
  esac
}
stat() { "$SAFE_PY" fileops.py stat "$@"; }
mkdir() { "$SAFE_PY" fileops.py mkdir "$@"; }
rm() { "$SAFE_PY" fileops.py rm "$@"; }
cp() { "$SAFE_PY" fileops.py cp "$@"; }
touch() { "$SAFE_PY" fileops.py touch "$@"; }
tee() {
  local append=0 line target
  if [ "${1:-}" = -a ]; then append=1; shift; fi
  target=$1
  case "$target" in evidence/*) ;; *) printf '차단: tee 경로 %s\n' "$target" >&2; return 97 ;; esac
  [ "$append" = 1 ] || : > "$target"
  while IFS= read -r line || [ -n "$line" ]; do printf '%s\n' "$line" >> "$target"; printf '%s\n' "$line"; done
}
grep() {
  case "$*" in
    '-m1 ^API_AUTH_TOKEN= .env') printf 'API_AUTH_TOKEN=fake-local-token\n' ;;
    *)
      if [ "${1:-}" = -q ] && { [ "${2:-}" = '<' ] || [ "${2:-}" = '다른 것 있음' ]; }; then
        local line
        while IFS= read -r line || [ -n "$line" ]; do case "$line" in *"$2"*) return 0 ;; esac; done < "$3"
        return 1
      fi
      printf '차단: 알 수 없는 grep 대역 %s\n' "$*" >&2; return 97 ;;
  esac
}
cut() { local line; [ "$*" = '-d= -f2-' ] || return 97; while IFS= read -r line; do printf '%s\n' "${line#*=}"; done; }
stubpsql() {
  case "$*" in
    '-At -c EG_QUERY') printf '%s\n' "$EGS" ;;
    '-At -c LIVE_QUERY') printf '203:CREATED\n' ;;
    *) printf '차단: 알 수 없는 PSQL 대역 %s\n' "$*" >&2; return 97 ;;
  esac
}
curl() {
  case "$*" in
    '-s -X POST -H Authorization: Bearer fake-local-token http://localhost:8080/api/v1/trading/sessions/203/start')
      printf 'POST STUB\n' >> post.log; printf '{"data":{"status":"RUNNING"}}' ;;
    *) printf '차단: 허용하지 않은 curl 대역 %s\n' "$*" >&2; return 97 ;;
  esac
}
git() {
  case "$*" in
    'rev-parse HEAD') printf 'wrong-head\n' ;;
    'status --porcelain --untracked-files=no') return 0 ;;
    *) printf '차단: 알 수 없는 git 대역\n' >&2; return 97 ;;
  esac
}
head() { IFS= read -r line < "${@: -1}"; printf '%s\n' "$line"; }
sed() { printf '차단: 실행되면 안 되는 sed\n' >&2; return 97; }
docker() { printf '차단: 실행되면 안 되는 docker\n' >&2; return 97; }
python3() { printf '차단: 실행되면 안 되는 python3\n' >&2; return 97; }
awk() { printf '차단: 실행되면 안 되는 awk\n' >&2; return 97; }
cat() { printf '차단: 실행되면 안 되는 cat\n' >&2; return 97; }
sort() { printf '차단: 실행되면 안 되는 sort\n' >&2; return 97; }
sha256sum() { printf '차단: 실행되면 안 되는 sha256sum\n' >&2; return 97; }
svc() { printf '차단: 실행되면 안 되는 svc\n' >&2; return 97; }
sc() { printf '차단: 실행되면 안 되는 sc\n' >&2; return 97; }
for name in date stat mkdir rm cp touch tee grep cut stubpsql curl git head sed docker python3 awk cat sort sha256sum svc sc; do
  [ "$(type -t "$name")" = function ] || { printf '대역 검증 실패 %s\n' "$name"; exit 97; }
done
[ -z "$PATH" ] || exit 97
printf '명령 대역 확인: 빈 PATH · 운영/API/DB 호출 없음\n'
'''


BUILD.mkdir(parents=True, exist_ok=True)
work = Path(tempfile.mkdtemp(prefix="start-gate-", dir=BUILD)).resolve()
assert BUILD.resolve() in work.parents
try:
    (work / "fileops.py").write_text(FILEOPS, encoding="utf-8")
    (work / "evidence").mkdir()
    doc = DOC.read_text(encoding="utf-8")
    legacy = subprocess.check_output(["git", "show", LEGACY_SHA + ":docs/LIMITED_LIVE_TEST_PLAN.md"], cwd=ROOT).decode("utf-8")
    old_f2, new_f1, new_f2 = block(legacy, "F2"), block(doc, "F1"), block(doc, "F2")
    now = 1_800_000_000

    def seed(latest_failed=False):
        evidence = work / "evidence"
        for p in evidence.iterdir():
            if p.is_file():
                p.unlink()
        (work / "post.log").unlink(missing_ok=True)
        items = {"t-f1.txt": (now - (300 if latest_failed else 1250), "F1 attempt\n"),
                 "ok-F1": (now - 1200, ""),
                 "operator-confirm.txt": (now - 900, "경보 네 갈래: 기존 반복만\n휴대폰 수신 시각: 08:35\n")}
        for name, (stamp, text) in items.items():
            p = evidence / name
            p.write_text(text, encoding="utf-8")
            os.utime(p, (stamp, stamp))

    def run(code):
        env = dict(os.environ, SAFE_PY=posix(sys.executable), FAKE_NOW=str(now), PYTHONIOENCODING="utf-8", BASH_ENV="", ENV="")
        # 긴 F1 원문은 Windows 명령행 길이 제한을 피하고 표준입력으로 넘긴다.
        result = subprocess.run([BASH, "--noprofile", "--norc"], input=(STUBS + code + "\n").encode("utf-8"), cwd=work, env=env, capture_output=True)
        out = (result.stdout + result.stderr).decode("utf-8", "replace")
        calls = (work / "post.log").read_text(encoding="utf-8").count("POST STUB") if (work / "post.log").exists() else 0
        return result.returncode, out, calls

    seed(latest_failed=True)
    rc, out, calls = run(old_f2)
    check("구버전 재현 — 최신 F1 실패 시도 뒤 옛 통과·확인으로 POST 대역 1회", rc == 0 and calls == 1, (rc, out, calls))

    seed(latest_failed=True)
    rc, out, calls = run(new_f2)
    check("수정본 — 최신 시도보다 오래된 통과 표지는 요청 0회", rc != 0 and calls == 0, (rc, out, calls))

    seed()
    rc, out, calls = run(new_f2)
    check("수정본 — 최신 시도·통과·확인이면 POST 대역 1회", rc == 0 and calls == 1, (rc, out, calls))
    rc, out, calls = run(new_f2)
    check("수정본 — 시작 요청 반복은 추가 요청 0회(누적 1회)", rc != 0 and calls == 1, (rc, out, calls))

    seed()
    (work / "evidence/f1-failed-2026-12-01").write_text("F1 failed\n", encoding="utf-8")
    rc, out, calls = run(new_f2)
    check("수정본 — 당일 실패 기록이 있으면 옛 통과가 있어도 요청 0회", rc != 0 and calls == 0, (rc, out, calls))

    seed()
    rc, out, calls = run(new_f1)
    previous = list((work / "evidence/f1-attempts").glob("*/ok-F1"))
    check("F1 실패 — 옛 증거 보존·통과/확인 무효화·당일 실패 표지", rc != 0 and bool(previous)
          and not (work / "evidence/ok-F1").exists() and not (work / "evidence/operator-confirm.txt").exists()
          and (work / "evidence/f1-failed-2026-12-01").exists() and calls == 0, (rc, out, previous, calls))
    rc, out, calls = run(new_f2)
    check("실제 실패한 F1 뒤 F2 — 요청 0회", rc != 0 and calls == 0, (rc, out, calls))
finally:
    # Windows: 재귀 삭제 전에 절대 경로가 지정한 workspace build 안인지 확인한다.
    assert BUILD.resolve() in work.resolve().parents
    shutil.rmtree(work)

print("%d/%d 통과" % (sum(results), len(results)))
sys.exit(0 if all(results) else 1)
