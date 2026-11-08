#!/usr/bin/env python3
"""Line coverage per package from the JaCoCo XML report, as a Markdown table.

Usage: tools/coverage-summary.py [report.xml]
Default report: app/build/reports/coverage/test/debug/report.xml
(written by ./gradlew :app:createDebugUnitTestCoverageReport).
"""

import sys
import xml.etree.ElementTree as ET

DEFAULT = "app/build/reports/coverage/test/debug/report.xml"
# Generated code says nothing about the tests.
SKIP = ("databinding", "/R$", "BuildConfig")


def lines(element):
    for counter in element.findall("counter"):
        if counter.get("type") == "LINE":
            covered = int(counter.get("covered"))
            return covered, covered + int(counter.get("missed"))
    return 0, 0


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT
    root = ET.parse(path).getroot()
    rows, total_cov, total_all = [], 0, 0
    for package in root.findall("package"):
        name = package.get("name")
        if any(s in name for s in SKIP):
            continue
        covered, total = 0, 0
        for cls in package.findall("class"):
            if any(s in cls.get("name") for s in SKIP):
                continue
            c, t = lines(cls)
            covered += c
            total += t
        if total:
            rows.append((name.replace("/", "."), covered, total))
            total_cov += covered
            total_all += total
    print("| Package | Lines covered | Coverage |")
    print("| --- | ---: | ---: |")
    for name, covered, total in sorted(rows):
        print("| %s | %d / %d | %.0f%% |" % (name, covered, total, 100.0 * covered / total))
    print("| **total** | **%d / %d** | **%.0f%%** |" % (total_cov, total_all, 100.0 * total_cov / max(total_all, 1)))


if __name__ == "__main__":
    main()
