#!/usr/bin/env python3
"""PKOS Final Testing — Pass/Fail record keeper.

The single source of truth is testing/final-testing-results.csv (one row per test case).

  python3 scripts/testplan.py check            # validate the records, print a summary (exit 1 if a record is invalid)
  python3 scripts/testplan.py gate             # release gate: exit 1 unless every case is PASS (or SKIPPED with a reason)
  python3 scripts/testplan.py render           # regenerate docs/final-testing-plan.md from the CSV
  python3 scripts/testplan.py list [--execution CI|OWNER_PC|DEVICE] [--accounts NONE|NOTION|...] [--risk ...] [--result ...]
  python3 scripts/testplan.py set ID RESULT --build B --device D --evidence E [--notes N] [--date YYYY-MM-DD]

Rules (so that nothing is shown as passed without proof):
  * RESULT is one of NOT_RUN, PASS, FAIL, BLOCKED, SKIPPED.
  * PASS needs date, build, device and evidence (a screenshot name, CI run link, video, log file …).
  * FAIL and BLOCKED need notes (what happened); SKIPPED needs notes (why it does not apply).
  * A DESTRUCTIVE case can only get a result when notes say the owner approved it at that moment ("owner-approved").
"""
import csv
import datetime
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CSV = os.path.join(ROOT, "testing", "final-testing-results.csv")
DOC = os.path.join(ROOT, "docs", "final-testing-plan.md")
RESULTS = ("NOT_RUN", "PASS", "FAIL", "BLOCKED", "SKIPPED")
EXECUTION = {
    "CI": "build machine or GitHub Actions — no phone, no account",
    "OWNER_PC": "the owner's own computer (release key, downloaded files)",
    "DEVICE": "a real Android phone with the release candidate installed",
}
ACCOUNTS = {
    "NONE": "no account needed",
    "DRIVE": "owner's Google account + release OAuth client",
    "NOTION": "owner's Notion integration token (typed only into the app)",
    "NOTION+DRIVE": "both Notion and Google Drive",
    "GEMINI": "owner's own free Gemini API key",
    "COMPAT_ENDPOINT": "owner's own OpenAI-compatible https endpoint (optional; SKIPPED if none — never buy one)",
}
RISK = {
    "SAFE": "reads or adds only test items",
    "CHANGES_TEST_DATA": "creates PKOS-TEST pages in Notion or files in Drive (owner removes them after testing; PKOS never deletes Notion pages)",
    "DESTRUCTIVE": "uninstall / restore / wipe — only after PR-SAFE and the owner's explicit OK at that moment",
}
AREAS = {
    "AUTO": "Automated tests and builds",
    "DEV": "Real device install and basics",
    "CAP": "Capture (Share, in-app, floating)",
    "OCR": "OCR (Bengali and English)",
    "OAUTH": "Google Drive OAuth",
    "BAK": "Backup and restore",
    "NOT": "Notion",
    "AI": "AI / Gemini / compatible provider",
    "OFF": "Offline",
    "SRCH": "Search",
    "EXP": "Export, import and recovery",
    "SEC": "Security and privacy",
    "A11Y": "Accessibility / TalkBack",
    "PERF": "Performance, memory, battery",
    "DATA": "Data integrity and crash recovery",
    "FEAT": "Knowledge features",
    "FLOW": "Full user workflows",
}


def load():
    with open(CSV, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def save(rows):
    with open(CSV, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)


def problems(rows):
    out, seen = [], set()
    for r in rows:
        i = r["id"]
        if i in seen:
            out.append(f"{i}: duplicate id")
        seen.add(i)
        res = r["result"]
        if res not in RESULTS:
            out.append(f"{i}: unknown result '{res}'")
        if r["area"] not in AREAS:
            out.append(f"{i}: unknown area '{r['area']}'")
        if res == "PASS":
            for k in ("date", "build", "device", "evidence"):
                if not r[k].strip():
                    out.append(f"{i}: PASS without {k}")
        if res in ("FAIL", "BLOCKED", "SKIPPED") and not r["notes"].strip():
            out.append(f"{i}: {res} without notes")
        if res == "NOT_RUN" and r["evidence"].strip():
            out.append(f"{i}: NOT_RUN but evidence filled (set the result)")
        if r.get("execution") not in EXECUTION:
            out.append(f"{i}: unknown execution '{r.get('execution')}'")
        if r.get("accounts") not in ACCOUNTS:
            out.append(f"{i}: unknown accounts '{r.get('accounts')}'")
        if r.get("risk") not in RISK:
            out.append(f"{i}: unknown risk '{r.get('risk')}'")
        if r.get("risk") == "DESTRUCTIVE" and res != "NOT_RUN" and "owner-approved" not in r["notes"]:
            out.append(f"{i}: DESTRUCTIVE case recorded without 'owner-approved' in notes")
        for k in ("prerequisites", "steps", "expected", "evidence_required"):
            if not r.get(k, "").strip():
                out.append(f"{i}: empty {k}")
    return out


def summary(rows):
    by = {}
    for r in rows:
        by.setdefault(r["area"], {k: 0 for k in RESULTS})[r["result"]] += 1
    total = {k: sum(a[k] for a in by.values()) for k in RESULTS}
    lines = ["| Area | Cases | PASS | FAIL | BLOCKED | SKIPPED | NOT_RUN |", "|---|---|---|---|---|---|---|"]
    for a in AREAS:
        if a in by:
            c = by[a]
            lines.append(f"| {a} — {AREAS[a]} | {sum(c.values())} | {c['PASS']} | {c['FAIL']} | {c['BLOCKED']} | {c['SKIPPED']} | {c['NOT_RUN']} |")
    lines.append(f"| **Total** | **{len(rows)}** | **{total['PASS']}** | **{total['FAIL']}** | **{total['BLOCKED']}** | **{total['SKIPPED']}** | **{total['NOT_RUN']}** |")
    return "\n".join(lines), total


def count_by(rows, key, values):
    return " · ".join(f"{v} {sum(1 for r in rows if r[key] == v)}" for v in values if any(r[key] == v for r in rows))


def render(rows):
    table, total = summary(rows)
    esc = lambda t: t.replace("\n", " ").strip()
    parts = [
        "# A-to-Z Final Testing Plan — PKOS 1.0.0",
        "",
        "> Generated by `python3 scripts/testplan.py render` from `testing/final-testing-results.csv` — edit the CSV, not this file.",
        "",
        "**Status: Final Testing has NOT started.** Every case stays `NOT_RUN` until it is actually performed on the final",
        "release candidate with evidence. Development-time test results are not counted. Phone cases start only after the",
        "owner's signed release APK and release OAuth client exist ([owner-prerequisites.md](owner-prerequisites.md)).",
        "",
        "## Where each case runs",
        f"- By execution: {count_by(rows, 'execution', EXECUTION)}",
        f"- By account: {count_by(rows, 'accounts', ACCOUNTS)}",
        f"- By risk: {count_by(rows, 'risk', RISK)}",
        "",
        "| Execution | Meaning |", "|---|---|",
        *[f"| {k} | {v} |" for k, v in EXECUTION.items()],
        "",
        "| Accounts | Meaning |", "|---|---|",
        *[f"| {k} | {v} |" for k, v in ACCOUNTS.items()],
        "",
        "| Risk | Meaning |", "|---|---|",
        *[f"| {k} | {v} |" for k, v in RISK.items()],
        "",
        "Destructive cases (each needs PR-SAFE and the owner's OK at that moment; record with notes `owner-approved`): "
        + ", ".join(r["id"] for r in rows if r["risk"] == "DESTRUCTIVE") + ".",
        "",
        "## Prerequisite codes",
        "| Code | Meaning |", "|---|---|",
        "| PR-PC | Owner's computer: repo at the release-candidate commit, JDK 21, Android SDK (platform 37.2, build-tools 37.0.0) |",
        "| PR-KEY | Owner's release keystore exists outside the repo (owner-prerequisites §1) |",
        "| PR-REL-APK | Signed release APK built by the owner (AUTO-03 passed) |",
        "| PR-PHONE | Test phone: Android 8+, ≥ 1 GB free, screen lock on, date/time automatic |",
        "| PR-OAUTH | Release Android OAuth client `com.pkos.app` + release SHA-1 created (owner-prerequisites §3) |",
        "| PR-NET / PR-OFF | Internet on / Airplane mode on |",
        "| PR-FIX | `testing/fixtures/*` copied to the phone's Download folder (check SHA256SUMS.txt) |",
        "| PR-SAFE | Right before the case: a verified Drive backup **and** an export ZIP copied off the phone; recovery key at hand |",
        "| PR-NOT | Notion integration connected to Master Database, token saved in PKOS, auto-sync on |",
        "| PR-NOTION-BASE | Before the first Notion sync: screenshots/CSV export of Master Database properties and the 15 legacy entries |",
        "| PR-AI | Owner's own free Gemini key saved in AI settings, cloud AI on |",
        "| PR-PAPER | Paper or a password manager for the recovery key (never a screenshot) |",
        "",
        "## Order (one pass)",
        "1. **Wave 0 — CI** (no phone): AUTO-01, AUTO-02, AUTO-05, AUTO-06, SEC-05.",
        "2. **Wave 1 — owner's computer**: AUTO-03, AUTO-04 (signed build).",
        "3. **Wave 2 — phone, no account**: DEV → CAP → OCR → OFF → SRCH → AI-01 → EXP-01…04 → DATA-01/02/05 → FEAT → A11Y → PERF → FLOW-03/04 → SEC-03.",
        "4. **Wave 3 — phone + accounts**: OAUTH-01…04 → BAK-01…05, 07…09 → NOT (PR-NOTION-BASE first) → AI-02…06 → SEC-02 → DATA-03 → FLOW-01 → NOT-03 (final schema check) → BAK-03, EXP-02 (computer).",
        "5. **Wave 4 — destructive, with explicit OK each**: BAK-06 → SEC-01 → EXP-05 → DATA-04 → FLOW-02 → OAUTH-05 → SEC-04 (last).",
        "6. Fix → new RC → re-run AUTO + affected cases. `python3 scripts/testplan.py gate` must pass before 1.0.0 is declared released.",
        "",
        "## How to record a result",
        "1. Save evidence as `testing/evidence/<ID>-<n>.<ext>` (no tokens, keys, recovery keys, passwords or private content; blur emails if you like).",
        "2. `python3 scripts/testplan.py set CAP-01 PASS --build 1.0.0 --device \"<model>, Android <n>\" --evidence testing/evidence/CAP-01-1.png`",
        "   (`FAIL`/`BLOCKED`/`SKIPPED` need `--notes`; destructive cases need `owner-approved` in notes).",
        "3. `python3 scripts/testplan.py check` validates; `list --result NOT_RUN` shows what is left.",
        "",
        "## Summary",
        table,
        "",
    ]
    for a in AREAS:
        rs = [r for r in rows if r["area"] == a]
        if not rs:
            continue
        parts += [f"## {a} — {AREAS[a]}", ""]
        for r in rs:
            parts += [
                f"### {r['id']} — {esc(r['title'])}",
                f"- **Runs on:** {r['execution']} · **Accounts:** {r['accounts']} · **Risk:** {r['risk']} · **Result:** `{r['result']}`",
                f"- **Prerequisites:** {esc(r['prerequisites'])}",
                f"- **Steps:** {esc(r['steps'])}",
                f"- **Expected:** {esc(r['expected'])}",
                f"- **Evidence:** {esc(r['evidence_required'])}",
                "",
            ]
    with open(DOC, "w", encoding="utf-8") as f:
        f.write("\n".join(parts))
    return total


def main(argv):
    if len(argv) < 2 or argv[1] not in ("check", "gate", "render", "set", "list"):
        print(__doc__)
        return 2
    rows = load()
    cmd = argv[1]
    if cmd == "list":
        opts = dict(zip(argv[2::2], argv[3::2]))
        sel = [r for r in rows if all(r.get(k.lstrip("-"), "") == v for k, v in opts.items())]
        for r in sel:
            print(f"{r['id']:9} {r['execution']:8} {r['accounts']:15} {r['risk']:17} {r['result']:8} {r['title']}")
        print(f"{len(sel)} case(s)")
        return 0
    if cmd == "set":
        if len(argv) < 4:
            print(__doc__)
            return 2
        cid, res = argv[2], argv[3].upper()
        opts = {}
        it = iter(argv[4:])
        for k in it:
            if not k.startswith("--"):
                print(f"unexpected {k}")
                return 2
            opts[k[2:]] = next(it, "")
        match = [r for r in rows if r["id"] == cid]
        if not match:
            print(f"no case {cid}")
            return 2
        r = match[0]
        r["result"] = res
        r["date"] = opts.get("date", datetime.date.today().isoformat() if res != "NOT_RUN" else "")
        for k in ("build", "device", "evidence", "notes"):
            if k in opts:
                r[k] = opts[k]
        bad = problems([r])
        if bad:
            print("\n".join(bad))
            return 1
        save(rows)
        render(rows)
        print(f"{cid} → {res}")
        return 0
    bad = problems(rows)
    table, total = summary(rows)
    print(table)
    if bad:
        print("\nInvalid records:\n" + "\n".join(bad))
        return 1
    if cmd == "render":
        render(rows)
        print(f"\nwrote {os.path.relpath(DOC, ROOT)}")
    if cmd == "gate":
        open_cases = [r["id"] for r in rows if r["result"] not in ("PASS", "SKIPPED")]
        if open_cases:
            print(f"\nGATE: NOT READY — {len(open_cases)} case(s) not passed")
            return 1
        print("\nGATE: all cases passed")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
