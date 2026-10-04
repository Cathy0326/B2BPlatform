#!/usr/bin/env python3
"""
Quality dashboard for CI: test pass rate and code coverage, from the reports the test tools already write.

Inputs (all optional; missing reports are listed as "not found" instead of failing):
  backend/target/surefire-reports/TEST-*.xml     JUnit XML, backend unit tests
  backend/target/failsafe-reports/TEST-*.xml     JUnit XML, backend integration tests (Testcontainers)
  backend/target/site/jacoco/jacoco.csv          JaCoCo coverage, unit + integration merged
  frontend/reports/junit.xml                     JUnit XML, Vitest
  frontend/reports/coverage/coverage-summary.json  Vitest (v8) coverage of the logic layer

Outputs:
  Markdown on stdout (CI appends it to $GITHUB_STEP_SUMMARY, so it shows on every run page)
  --badges DIR: shields.io endpoint JSON files (tests.json, coverage-backend.json, coverage-frontend.json)

Usage: python3 scripts/quality-report.py [--root .] [--badges badges]
"""
import argparse
import csv
import glob
import json
import os
import xml.etree.ElementTree as ET


def junit(paths):
    """Count test cases in JUnit XML files. Works for <testsuite> and <testsuites> roots."""
    total = failed = skipped = 0
    seconds = 0.0
    failures = []
    for path in paths:
        root = ET.parse(path).getroot()
        for case in root.iter("testcase"):
            total += 1
            seconds += float(case.get("time") or 0)
            if case.find("failure") is not None or case.find("error") is not None:
                failed += 1
                failures.append(f"{case.get('classname', '')}.{case.get('name', '')}")
            elif case.find("skipped") is not None:
                skipped += 1
    return {"total": total, "failed": failed, "skipped": skipped, "passed": total - failed - skipped,
            "seconds": seconds, "failures": failures}


def pct(part, whole):
    return 100.0 * part / whole if whole else 0.0


def bar(percent, width=20):
    filled = round(percent / 100 * width)
    return "█" * filled + "░" * (width - filled)


def color(percent, good, ok):
    return "brightgreen" if percent >= good else "yellow" if percent >= ok else "red"


def jacoco(path):
    rows = list(csv.DictReader(open(path)))
    total = lambda col: sum(int(r[col]) for r in rows)
    modules = {}
    for r in rows:
        name = r["PACKAGE"].removeprefix("com.quipmarket").lstrip(".").split(".")[0] or "(app)"
        m = modules.setdefault(name, [0, 0])
        m[0] += int(r["LINE_COVERED"])
        m[1] += int(r["LINE_COVERED"]) + int(r["LINE_MISSED"])
    return {
        "lines": pct(total("LINE_COVERED"), total("LINE_COVERED") + total("LINE_MISSED")),
        "branches": pct(total("BRANCH_COVERED"), total("BRANCH_COVERED") + total("BRANCH_MISSED")),
        "line_counts": (total("LINE_COVERED"), total("LINE_COVERED") + total("LINE_MISSED")),
        "modules": {k: (pct(c, t), c, t) for k, (c, t) in modules.items()},
    }


def vitest(path):
    t = json.load(open(path))["total"]
    return {"lines": t["lines"]["pct"], "branches": t["branches"]["pct"],
            "line_counts": (t["lines"]["covered"], t["lines"]["total"])}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--badges", help="directory for shields.io endpoint JSON")
    args = ap.parse_args()
    at = lambda *p: os.path.join(args.root, *p)

    suites = [
        ("Backend unit (JUnit)", sorted(glob.glob(at("backend/target/surefire-reports/TEST-*.xml")))),
        ("Backend integration (Testcontainers + PostgreSQL)", sorted(glob.glob(at("backend/target/failsafe-reports/TEST-*.xml")))),
        ("Frontend unit (Vitest)", [p for p in [at("frontend/reports/junit.xml")] if os.path.exists(p)]),
    ]
    results = [(name, junit(paths) if paths else None) for name, paths in suites]
    found = [r for _, r in results if r]
    total = sum(r["total"] for r in found)
    passed = sum(r["passed"] for r in found)
    failed = sum(r["failed"] for r in found)
    rate = pct(passed, total - sum(r["skipped"] for r in found))

    out = ["## Quality report", ""]
    icon = "✅" if failed == 0 and total else "❌"
    out.append(f"### {icon} Test pass rate: **{rate:.1f}%** ({passed}/{total} passed, {failed} failed)")
    out.append("")
    out.append("| Suite | Tests | Passed | Failed | Skipped | Pass rate | Time |")
    out.append("|---|---:|---:|---:|---:|---|---:|")
    for name, r in results:
        if r is None:
            out.append(f"| {name} | – | – | – | – | report not found | – |")
            continue
        p = pct(r["passed"], r["total"] - r["skipped"])
        out.append(f"| {name} | {r['total']} | {r['passed']} | {r['failed']} | {r['skipped']} | `{bar(p, 10)}` {p:.0f}% | {r['seconds']:.1f}s |")
    failures = [f for _, r in results if r for f in r["failures"]]
    if failures:
        out += ["", "**Failing tests:**", ""] + [f"- `{f}`" for f in failures]

    out += ["", "### Code coverage", "", "| Scope | Lines | Branches |", "|---|---|---|"]
    backend = jacoco(at("backend/target/site/jacoco/jacoco.csv")) if os.path.exists(at("backend/target/site/jacoco/jacoco.csv")) else None
    frontend = vitest(at("frontend/reports/coverage/coverage-summary.json")) if os.path.exists(at("frontend/reports/coverage/coverage-summary.json")) else None
    for name, c in [("Backend, all code (JaCoCo, unit + integration)", backend), ("Frontend logic: utils + services (Vitest v8)", frontend)]:
        if c is None:
            out.append(f"| {name} | report not found | |")
        else:
            out.append(f"| {name} | `{bar(c['lines'])}` **{c['lines']:.1f}%** ({c['line_counts'][0]}/{c['line_counts'][1]}) | {c['branches']:.1f}% |")
    if backend:
        out += ["", "<details><summary>Backend line coverage by module</summary>", "", "| Module | Lines | |", "|---|---:|---|"]
        for name, (p, c, t) in sorted(backend["modules"].items(), key=lambda kv: -kv[1][0]):
            out.append(f"| {name} | {c}/{t} | `{bar(p)}` {p:.1f}% |")
        out += ["", "</details>"]
    print("\n".join(out))

    if args.badges:
        os.makedirs(args.badges, exist_ok=True)
        badge = lambda name, label, message, col: json.dump(
            {"schemaVersion": 1, "label": label, "message": message, "color": col}, open(os.path.join(args.badges, name), "w"))
        badge("tests.json", "tests", f"{passed}/{total} passed ({rate:.0f}%)", "brightgreen" if failed == 0 and total else "red")
        if backend:
            badge("coverage-backend.json", "backend coverage", f"{backend['lines']:.0f}%", color(backend["lines"], 80, 60))
        if frontend:
            badge("coverage-frontend.json", "frontend logic coverage", f"{frontend['lines']:.0f}%", color(frontend["lines"], 80, 60))


if __name__ == "__main__":
    main()
