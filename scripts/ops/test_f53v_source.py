"""F5-3v(v1.159 398차) — 분리 이후 커밋의 RUNBOOK 에서 F3 를 꺼내는 새 출처 시험.

RUNBOOK 의 F5-3v 블록을 문서에서 그대로 꺼내, git·psql·curl·python3 만 대역으로 바꿔 돌린다.
운영 서버·DB·API 에 접속하지 않는다. 확인하는 것:
  - 정상: 같은 커밋의 RUNBOOK 에서 F3 를 꺼내 기대 해시(bb86fb39…)와 맞으면 F3·완료 조회를 실행한다.
  - fetch 실패 / RUNBOOK 읽기 실패 / F3 없음(0바이트) / F3 한 글자 변경(해시 불일치) / 이전 실행의 표지·추출본이
    남은 채 실패 — 어느 경우에도 F3 와 완료 조회를 실행하지 않고 🔴 멈춤을 낸다.
  - 문서 대조: F5-3v 의 추출식·F3 실행 줄·완료 조회문이 기존 F5-3 과 같은 글자이고, 기존 15개 bash 블록은
    분리 커밋(b12f19f8)의 바이트 그대로다. 기존 F5-3 추출식을 현재 계획에 적용하면 0바이트(보완 이유)다.

실행: python scripts/ops/test_f53v_source.py   (종료값 0 = 모두 통과)
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOC = ROOT / "docs/LIMITED_LIVE_TEST_RUNBOOK.md"
PLAN = ROOT / "docs/LIMITED_LIVE_TEST_PLAN.md"
SPLIT_SHA = "b12f19f8"
F3H = "bb86fb397da00523f564a8f02a1960c98bb1ac03887629fe74372990929fa2bb"
BASH = os.environ.get("BASH_EXE") or (
    r"C:\Program Files\Git\bin\bash.exe" if os.name == "nt" else shutil.which("bash")
)
AWK = "awk '/^\\*\\*F3\\. /{f=1; next} f && /^```bash$/{g=1; next} g && /^```$/{exit} g'"
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


def awk_f3(text):
    lines, f, g, out = text.split("\n"), False, False, []
    for ln in lines:
        if g:
            if ln == "```":
                break
            out.append(ln)
        elif f and ln == "```bash":
            g = True
        elif ln.startswith("**F3. "):
            f = True
    return "\n".join(out) + ("\n" if out else "")


doc = DOC.read_text(encoding="utf-8")
F53 = block(doc, "F5-3")
F53V = block(doc, "F5-3v")
QUERY_START = "$PSQL -At -F ' | ' -c \"SELECT *, CASE WHEN status IN"
QUERY_END = ' x" | tee "$E/stop-check.txt"'


def query(b):
    i = b.index(QUERY_START)
    return b[i:b.index(QUERY_END, i) + len(QUERY_END)]


# ── 문서 대조 ──
check("F5-3v 완료 조회문 = F5-3 완료 조회문(같은 글자)", query(F53V) == query(F53))
check("F5-3v 추출식 = F5-3 추출식(같은 awk), 출처만 RUNBOOK",
      AWK in F53 and AWK in F53V and 'git show "$SHA":docs/LIMITED_LIVE_TEST_RUNBOOK.md' in F53V
      and "LIMITED_LIVE_TEST_PLAN.md" not in F53V)
check("F5-3v F3 실행 줄 = F5-3 F3 실행 줄", '( M=$E; . "$E/f3-block.sh" )' in F53 and '( M=$E; . "$E/f3-block.sh" )' in F53V)
check("F5-3v 기대 해시 = bb86fb39…", ("F3H=" + F3H) in F53V)
old_doc = subprocess.check_output(["git", "show", SPLIT_SHA + ":docs/LIMITED_LIVE_TEST_RUNBOOK.md"], cwd=ROOT).decode("utf-8")
bo = re.findall(r"```bash\n(.*?)\n```", old_doc, re.S)
bn = re.findall(r"```bash\n(.*?)\n```", doc, re.S)
check("기존 15개 bash 블록이 분리 커밋 %s 의 바이트·순서 그대로이고 새 블록은 F5-3v 하나" % SPLIT_SHA,
      len(bo) == 15 and len(bn) == 16 and [b for b in bn if b != F53V] == bo, (len(bo), len(bn)))
cur_f3 = awk_f3(doc)
check("현재 RUNBOOK 의 F3 추출 SHA256 = 기대 해시", hashlib.sha256(cur_f3.encode("utf-8")).hexdigest() == F3H,
      hashlib.sha256(cur_f3.encode("utf-8")).hexdigest())
check("보완 이유: 기존 F5-3 추출식을 분리 뒤 현재 계획에 적용하면 0바이트", awk_f3(PLAN.read_text(encoding="utf-8")) == "")

# ── 실행 대역 ──
STUBS = r'''
E="$WORK/e"; mkdir -p "$E"
SID=203; PSQL=psqlstub
psqlstub() { echo "PSQL-CALL" >> "$WORK/calls.log"; echo "STUB-ROW"; }
curl() { echo "CURL-CALL" >> "$WORK/calls.log"; }
python3() { cat > /dev/null; echo "PY-CALL" >> "$WORK/calls.log"; }
git() {
  case "$1" in
    fetch) echo "fetch" >> "$WORK/git.log"; [ -z "${FAIL_FETCH:-}" ] ;;
    show) echo "show $2" >> "$WORK/git.log"; [ -n "${FAIL_SHOW:-}" ] && { echo "fatal: stub" >&2; return 128; }; cat "$SRC" ;;
    *) return 99 ;;
  esac
}
'''


def run(src_text, env_extra=None, stale=False):
    work = Path(tempfile.mkdtemp(prefix="f53v-")).resolve()
    try:
        (work / "e").mkdir()
        src = work / "src.md"
        src.write_bytes(src_text.encode("utf-8"))
        if stale:
            (work / "e" / "f3-src-ok").write_text("", encoding="utf-8")
            (work / "e" / "f3-block.sh").write_bytes(cur_f3.encode("utf-8"))
        code = F53V.replace("SHA=<리뷰가 확인한 커밋 SHA>", "SHA=TESTSHA")
        env = dict(os.environ, WORK=posix(work), SRC=posix(src), BASH_ENV="", ENV="", PYTHONIOENCODING="utf-8")
        env.update(env_extra or {})
        r = subprocess.run([BASH, "--noprofile", "--norc"], input=(STUBS + code + "\n").encode("utf-8"),
                           env=env, capture_output=True)
        out = (r.stdout + r.stderr).decode("utf-8", "replace")
        calls = (work / "calls.log").read_text(encoding="utf-8") if (work / "calls.log").exists() else ""
        gitlog = (work / "git.log").read_text(encoding="utf-8") if (work / "git.log").exists() else ""
        files = {p.name for p in (work / "e").iterdir()}
        return out, calls, gitlog, files
    finally:
        shutil.rmtree(work, ignore_errors=True)


out, calls, gitlog, files = run(doc)
check("정상: F3 추출 해시 줄 → F3 실행(psql·curl·python3 대역 호출) → 완료 조회 stop-check.txt",
      ("sha256 " + F3H) in out and "🔴" not in out and "PSQL-CALL" in calls and "CURL-CALL" in calls
      and "stop-check.txt" in files and "f3-src-ok" in files, (out, calls, files))
check("정상: git show 대상이 '<SHA>:docs/LIMITED_LIVE_TEST_RUNBOOK.md'",
      "show TESTSHA:docs/LIMITED_LIVE_TEST_RUNBOOK.md" in gitlog and gitlog.startswith("fetch"), gitlog)
check("정상: 추출 원문 f3-src-runbook.md 보존", "f3-src-runbook.md" in files, files)


def refused(name, res, reason):
    out, calls, gitlog, files = res
    check(name + " → 🔴 멈춤(" + reason + ")·F3/완료 조회 미실행·표지 없음",
          ("🔴 멈춤: " in out and reason in out and "🔴 F3 출처 확인 실패" in out and calls == ""
           and "stop-check.txt" not in files and "f3-src-ok" not in files), (out, calls, files))


refused("fetch 실패", run(doc, {"FAIL_FETCH": "1"}), "git fetch 실패")
refused("RUNBOOK 읽기 실패(커밋에 없음)", run(doc, {"FAIL_SHOW": "1"}), "RUNBOOK 을 읽지 못함")
refused("F3 없음 — 분리 뒤 현재 계획을 대신 준 경우", run(PLAN.read_text(encoding="utf-8")), "0바이트")
i = doc.index("**F3. ")
j = doc.index("```bash\n", i) + len("```bash\n")
mutated = doc[:j] + doc[j:].replace("S=$($PSQL", "S=$( $PSQL", 1)
refused("F3 한 글자 변경", run(mutated), "F3 해시 불일치")
refused("이전 실행의 표지·추출본이 남은 채 읽기 실패", run(doc, {"FAIL_SHOW": "1"}, stale=True), "RUNBOOK 을 읽지 못함")

print("%d/%d 통과" % (sum(results), len(results)))
sys.exit(0 if all(results) else 1)
