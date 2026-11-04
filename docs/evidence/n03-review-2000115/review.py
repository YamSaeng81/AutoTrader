"""Read-only audit of committed N03 evidence and the deployed news source code.

Run from any directory. Uses git reads and the Python standard library only.
This is an artifact/code review, not an application test or a server check.
"""
from collections import Counter
from datetime import datetime, timedelta
from hashlib import sha256
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]
SUBMISSION = "20001155d07e5ecde2c34623c5cdaf4c6e5435c4"
PRODUCTION = "ea4490099f0fa6b614fe71102534b0ba84c0eb7f"
EVIDENCE = "docs/evidence/n03-1104-1000"
JAVA = "web-api/src/main/java/com/cryptoautotrader/api/"


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def blob(ref, path):
    return git("show", ref + ":" + path)


def text(path):
    return blob(SUBMISSION, EVIDENCE + "/" + path).decode("utf-8")


def main():
    manifest = []
    for line in text("SHA256SUMS").splitlines():
        digest, name = line.split(" ", 1)
        name = name.lstrip(" *")
        path = EVIDENCE + "/" + name
        manifest.append({
            "file": name,
            "expected": digest,
            "working_copy_matches": sha256((ROOT / path).read_bytes()).hexdigest() == digest,
            "committed_blob_matches": sha256(blob(SUBMISSION, path)).hexdigest() == digest,
        })

    report = text("20261104-1000.txt")
    msg = text("20261104-1000.txt.msg")
    last_send = text("last_send")
    raw = text("20261104-1000.txt.raw")
    raw_lines = raw.splitlines()
    db_lines = raw_lines[raw_lines.index("BEGIN") + 1:raw_lines.index("ROLLBACK")]
    db_lines = [line for line in db_lines if "|" in line]
    counts = Counter(line.split("|", 1)[0] for line in db_lines)
    values = dict(line.split("|", 1) for line in db_lines)
    errors = text("error_lines.txt").splitlines()
    state = text("install_state.txt")
    observed = datetime.strptime("2026-" + values["btc_h1_at"], "%Y-%m-%d %H:%M:%S")
    required = (observed - timedelta(minutes=2)).replace(minute=0, second=0) - timedelta(hours=1)
    finished = datetime.strptime("2026-" + values["btc_h1_done"], "%Y-%m-%d %H:%M")
    source_paths = [
        "news/NewsSource.java",
        "news/NewsAggregatorService.java",
        "news/source/RssNewsSource.java",
        "news/source/UpbitNoticeSource.java",
        "news/source/CryptoPanicSource.java",
    ]
    sources = []
    for path in source_paths:
        data = blob(PRODUCTION, JAVA + path)
        sources.append({
            "path": JAVA + path,
            "sha256": sha256(data).hexdigest(),
            "same_in_submission": data == blob(SUBMISSION, JAVA + path),
        })

    unclassified = [line for line in errors if "news.source." in line]
    previous = blob(SUBMISSION, "docs/evidence/n03-1103-1000/error_lines.txt").decode("utf-8")
    previous_news = [line for line in previous.splitlines() if "news.source." in line]
    source_hashes = {}
    for name in ("daily_check.sh", "daily_checks.sql"):
        digest = sha256(blob("d491fd1609b700d195ecc4d0ccea6be2987bf2fe", "scripts/ops/" + name)).hexdigest()
        source_hashes[name] = {"sha256": digest, "recorded_in_install_state": digest in state}

    result = {
        "review_date_kst": "2026-11-04",
        "submission": SUBMISSION,
        "production_source": PRODUCTION,
        "scope": "Local committed artifacts and source inspection only; no SSH, DB, HTTP, app tests or report execution.",
        "manifest": manifest,
        "report_and_msg_match_ignoring_final_newline": "\n".join(report.splitlines()[:-1]).rstrip() == msg.rstrip(),
        "report_footer_matches_last_send": report.splitlines()[-1] == last_send.strip(),
        "db_key_count": len(db_lines),
        "db_two_nonempty_fields": all(len(line.split("|")) == 2 and all(line.split("|")) for line in db_lines),
        "duplicate_keys": [key for key, count in counts.items() if count != 1],
        "btc_observed_kst": observed.isoformat(),
        "btc_required_recalculated_kst": required.isoformat(),
        "btc_required_matches": values["btc_h1_need"] == required.strftime("%m-%d %H:%M"),
        "btc_finished_covers_required": finished >= required,
        "btc_b1": values["btc_h1_ok"],
        "btc_b3": values["btc_h1_daily"],
        "error_line_count": len(errors),
        "current_news_errors": unclassified,
        "previous_news_errors": previous_news,
        "timeout_request_count": sum("request timed out" in line for line in errors),
        "timeout_sync_failure_count": sum("시장 데이터 동기화 실패" in line for line in errors),
        "timeout_count_interpretation": "Three coin requests produced six ERROR lines; the +4 category count is six timeout lines replacing the previous window's two timeout lines, not six additional requests.",
        "installed_source_hashes": source_hashes,
        "recorded_cron_hash_occurrences": state.count("f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b"),
        "error_lines_remote_hash_recorded": sha256(blob(SUBMISSION, EVIDENCE + "/error_lines.txt")).hexdigest() in state,
        "remote_original_five_hash_limit": "Developer reports server-original hashes 5/5. The five remote digest values are not separately retained in this evidence set; reviewer verified local files and committed blobs 9/9, not an independent server-side five-file comparison.",
        "phone_receipt": "Unconfirmed; server-send success is distinct.",
        "source_files": sources,
        "static_finding": "NewsSource.fetch returns a list only (failure may return an empty or partial list). NewsAggregatorService unconditionally updates lastFetchedAt and logs collection complete after fetch. This cannot distinguish a successful empty result from a source-caught failure. This is a code inspection, not an executed failure test or proof of a particular post-error production completion log.",
        "cause_limit": "Repeated sources and similar times do not establish DNS, TLS, pool, provider, scheduler or network root cause, nor source recovery.",
    }
    assert all(row["working_copy_matches"] and row["committed_blob_matches"] for row in manifest)
    assert result["report_and_msg_match_ignoring_final_newline"] and result["report_footer_matches_last_send"]
    assert result["db_key_count"] == 32 and result["db_two_nonempty_fields"] and not result["duplicate_keys"]
    assert result["btc_required_matches"] and result["btc_finished_covers_required"]
    assert len(errors) == 17 and len(unclassified) == len(previous_news) == 3
    assert all(row["same_in_submission"] for row in sources)
    assert all(row["recorded_in_install_state"] for row in source_hashes.values())
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
