#!/usr/bin/env python3
"""
Quality dashboard for CI: test pass rate and code coverage, from the reports the test tools already write.

Inputs (all optional; missing reports are listed as "not found" instead of failing):
  backend/target/surefire-reports/TEST-*.xml     JUnit XML, backend unit tests
  backend/target/failsafe-reports/TEST-*.xml     JUnit XML, backend integration tests (Testcontainers)
  backend/target/site/jacoco/jacoco.csv          JaCoCo coverage, unit + integration merged
  frontend/reports/junit.xml                     JUnit XML, Vitest
  frontend/reports/e2e-junit.xml                 JUnit XML, Playwright browser tests (journeys + axe)
  frontend/reports/coverage/coverage-summary.json  Vitest (v8) coverage of the logic layer
  backend/target/pit-reports/mutations.xml       PIT mutation testing of the money and auction rules

Outputs:
  Markdown on stdout (CI appends it to $GITHUB_STEP_SUMMARY, so it shows on every run page)
  --badges DIR: shields.io endpoint JSON files (tests.json, coverage-backend.json, coverage-frontend.json, mutation.json)

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


def gates(root):
    """Coverage floors, read from the build files themselves so the report can never disagree with the gate."""
    import re
    out = {}
    pom = os.path.join(root, "backend/pom.xml")
    if os.path.exists(pom):
        text = open(pom).read()
        for counter, key in (("LINE", "lines"), ("BRANCH", "branches")):
            m = re.search(rf"<counter>{counter}</counter><value>COVEREDRATIO</value><minimum>([0-9.]+)</minimum>", text)
            if m:
                out[("backend", key)] = float(m.group(1)) * 100
        m = re.search(r"<mutationThreshold>([0-9.]+)</mutationThreshold>", text)
        if m:
            out[("backend", "mutation")] = float(m.group(1))
    cfg = os.path.join(root, "frontend/vitest.config.ts")
    if os.path.exists(cfg):
        m = re.search(r"thresholds:\s*\{([^}]*)\}", open(cfg).read())
        if m:
            for key, value in re.findall(r"(\w+):\s*([0-9.]+)", m.group(1)):
                out[("frontend", key)] = float(value)
    return out


def pit(path):
    """PIT mutations.xml: how many planted bugs the tests caught, and the ones they missed."""
    mutations = list(ET.parse(path).getroot().iter("mutation"))
    killed = sum(1 for m in mutations if m.get("status") in ("KILLED", "TIMED_OUT", "MEMORY_ERROR"))
    survivors = [f"{m.findtext('mutatedClass').rsplit('.', 1)[-1]}.{m.findtext('mutatedMethod')} line {m.findtext('lineNumber')}: "
                 f"{m.findtext('description')} ({m.get('status').lower().replace('_', ' ')})"
                 for m in mutations if m.get("status") in ("SURVIVED", "NO_COVERAGE")]
    return {"score": pct(killed, len(mutations)), "killed": killed, "total": len(mutations), "survivors": survivors}


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
        ("Browser journeys + accessibility (Playwright, axe)", [p for p in [at("frontend/reports/e2e-junit.xml")] if os.path.exists(p)]),
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

    floors = gates(args.root)
    out += ["", "### Code coverage", "", "| Scope | Lines | Branches | Gate (CI fails below) |", "|---|---|---|---|"]
    backend = jacoco(at("backend/target/site/jacoco/jacoco.csv")) if os.path.exists(at("backend/target/site/jacoco/jacoco.csv")) else None
    frontend = vitest(at("frontend/reports/coverage/coverage-summary.json")) if os.path.exists(at("frontend/reports/coverage/coverage-summary.json")) else None
    for side, name, c in [("backend", "Backend, all code (JaCoCo, unit + integration)", backend),
                          ("frontend", "Frontend logic: utils + services (Vitest v8)", frontend)]:
        floor_l, floor_b = floors.get((side, "lines")), floors.get((side, "branches"))
        gate = f"lines ≥ {floor_l:.0f}%, branches ≥ {floor_b:.0f}%" if floor_l and floor_b else "none"
        if c is None:
            out.append(f"| {name} | report not found | | {gate} |")
        else:
            ok = (floor_l is None or c["lines"] >= floor_l) and (floor_b is None or c["branches"] >= floor_b)
            out.append(f"| {name} | `{bar(c['lines'])}` **{c['lines']:.1f}%** ({c['line_counts'][0]}/{c['line_counts'][1]}) "
                       f"| {c['branches']:.1f}% | {'✅' if ok else '❌'} {gate} |")
    if backend:
        out += ["", "<details><summary>Backend line coverage by module</summary>", "", "| Module | Lines | |", "|---|---:|---|"]
        for name, (p, c, t) in sorted(backend["modules"].items(), key=lambda kv: -kv[1][0]):
            out.append(f"| {name} | {c}/{t} | `{bar(p)}` {p:.1f}% |")
        out += ["", "</details>"]
    mutation_path = at("backend/target/pit-reports/mutations.xml")
    mutation = pit(mutation_path) if os.path.exists(mutation_path) else None
    floor_m = floors.get(("backend", "mutation"))
    out += ["", "### Mutation testing (PIT)", "",
            "PIT plants small bugs in the money and auction rules (flipped comparisons, changed constants, removed calls) "
            "and reruns the unit tests. A mutant the tests catch is *killed*; a survivor is a bug the tests would miss.", "",
            "| Scope | Mutation score | Killed | Gate (CI fails below) |", "|---|---|---|---|"]
    gate_m = f"score ≥ {floor_m:.0f}%" if floor_m else "none"
    if mutation is None:
        out.append(f"| Fee, ledger, rental pricing, loans, auction engine | report not found | | {gate_m} |")
    else:
        ok = floor_m is None or mutation["score"] >= floor_m
        out.append(f"| Fee, ledger, rental pricing, loans, auction engine | `{bar(mutation['score'])}` **{mutation['score']:.1f}%** "
                   f"| {mutation['killed']}/{mutation['total']} | {'✅' if ok else '❌'} {gate_m} |")
        if mutation["survivors"]:
            out += ["", "<details><summary>Surviving mutants</summary>", ""] + [f"- {x}" for x in mutation["survivors"]] + ["", "</details>"]
    print("\n".join(out))

    if args.badges:
        os.makedirs(args.badges, exist_ok=True)
        badge = lambda name, label, message, col: json.dump(
            {"schemaVersion": 1, "label": label, "message": message, "color": col}, open(os.path.join(args.badges, name), "w"))
        badge("tests.json", "tests", f"{passed}/{total} passed ({rate:.0f}%)", "brightgreen" if failed == 0 and total else "red")
        if backend:
            badge("coverage-backend.json", "backend coverage", f"{backend['lines']:.0f}%", color(backend["lines"], 80, 60))
        if mutation:
            badge("mutation.json", "mutation score", f"{mutation['score']:.0f}%", color(mutation["score"], 80, 60))
        if frontend:
            badge("coverage-frontend.json", "frontend logic coverage", f"{frontend['lines']:.0f}%", color(frontend["lines"], 80, 60))


if __name__ == "__main__":
    main()
