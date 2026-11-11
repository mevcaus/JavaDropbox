"""Turns a coverage report into a table for the job summary and a badge for the README.

    coverage.py jacoco build/reports/jacoco/test/jacocoTestReport.xml --name Backend --badge backend.json
    coverage.py vitest frontend/coverage/coverage-summary.json --name Frontend --badge frontend.json

The table goes to stdout. The badge is a shields.io endpoint file (https://shields.io/badges/endpoint-badge)
showing the share of lines covered, which the coverage-badge jobs publish on the badges branch.
"""

import argparse
import json
import xml.etree.ElementTree as ET

# JaCoCo's counter names, in the order and with the names the table uses.
JACOCO_COUNTERS = {"LINE": "Lines", "BRANCH": "Branches", "INSTRUCTION": "Instructions", "METHOD": "Methods"}
VITEST_COUNTERS = {"lines": "Lines", "branches": "Branches", "statements": "Statements", "functions": "Functions"}


def read_jacoco(path):
    """(name, covered, total) for each counter of the whole report."""
    # The report names a DTD it doesn't ship. ElementTree never fetches it.
    report = ET.parse(path).getroot()
    found = {c.get("type"): c for c in report.findall("counter")}
    rows = []
    for key, name in JACOCO_COUNTERS.items():
        # JaCoCo leaves out a counter with nothing to count, such as branches in code with none.
        counter = found.get(key)
        covered = int(counter.get("covered")) if counter is not None else 0
        missed = int(counter.get("missed")) if counter is not None else 0
        rows.append((name, covered, covered + missed))
    return rows


def read_vitest(path):
    with open(path) as f:
        total = json.load(f)["total"]
    return [(name, total[key]["covered"], total[key]["total"]) for key, name in VITEST_COUNTERS.items()]


def percent(covered, total, places):
    # Rounded down, so 90.99% never shows as 91% next to a minimum of 91%.
    scale = 10**places
    return 100.0 if total == 0 else (covered * 100 * scale // total) / scale


def color(pct):
    if pct >= 90:
        return "brightgreen"
    if pct >= 80:
        return "green"
    if pct >= 70:
        return "yellow"
    return "red"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("format", choices=["jacoco", "vitest"])
    parser.add_argument("report")
    parser.add_argument("--name", required=True)
    parser.add_argument("--badge", help="where to write the badge file")
    args = parser.parse_args()

    rows = read_jacoco(args.report) if args.format == "jacoco" else read_vitest(args.report)

    print(f"### {args.name} coverage\n")
    print("| | Covered | Total | % |")
    print("|---|---:|---:|---:|")
    for name, covered, total in rows:
        print(f"| {name} | {covered} | {total} | {percent(covered, total, 2):.2f}% |")
    print()

    if args.badge:
        _, covered, total = next(row for row in rows if row[0] == "Lines")
        lines = percent(covered, total, 1)
        badge = {
            "schemaVersion": 1,
            "label": f"{args.name} coverage",
            "message": f"{lines:.1f}%",
            "color": color(lines),
        }
        with open(args.badge, "w") as f:
            json.dump(badge, f)


if __name__ == "__main__":
    main()
