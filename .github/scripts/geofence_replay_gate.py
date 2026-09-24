#!/usr/bin/env python3
"""Decides the geofence corpus replay check from the JUnit XML Gradle wrote.

Gradle cannot decide it: every Test task here sets `ignoreFailures = true`, so the build is green
whatever the tests did. And a green run is not enough on its own either — with no corpus every
replay case *skips*, which a plain "no failures" check reads as a pass that replayed nothing.

The corpus is private, and this repo's Actions logs are public. Failure messages carry fence names
and coordinates, so this prints only test and drive names with their outcome, never a message.

Usage: geofence_replay_gate.py <results-dir>
Exit: 0 every drive replayed green; 1 something failed; 2 no drive was actually replayed.
"""
import pathlib
import sys
import xml.etree.ElementTree as ET

REPLAY_CLASS = "io.customer.geofence.replay.ScenarioReplayTest"


def outcome(case):
    if case.find("failure") is not None or case.find("error") is not None:
        return "failed"
    if case.find("skipped") is not None:
        return "skipped"
    return "passed"


def main(results_dir):
    reports = sorted(pathlib.Path(results_dir).glob("TEST-*.xml"))
    if not reports:
        print(f"::error::No JUnit reports under {results_dir} — the replay tests did not run.")
        return 2

    drives, others = [], []
    for report in reports:
        for case in ET.parse(report).getroot().iter("testcase"):
            entry = (case.get("name", "?"), outcome(case))
            (drives if case.get("classname") == REPLAY_CLASS else others).append(entry)

    print("Recorded drives:")
    for name, result in drives:
        print(f"  {result:<8} {name}")
    failed_others = [name for name, result in others if result == "failed"]
    ran_others = [name for name, result in others if result != "skipped"]
    print(f"Harness tests: {len(ran_others)} run, {len(failed_others)} failed")
    for name in failed_others:
        print(f"  failed   {name}")

    replayed = [d for d in drives if d[1] != "skipped"]
    failed = [d for d in drives if d[1] == "failed"] + failed_others
    if not replayed:
        print("::error::No recorded drive was replayed: the corpus was not found, or holds no "
              "scenarios for this platform. That is a setup failure, not a pass.")
        return 2
    if failed:
        print(f"::error::{len(failed)} geofence replay test(s) failed. Details stay out of this "
              "public log; rerun locally against the pinned corpus to see them.")
        return 1
    print(f"All {len(replayed)} recorded drive(s) replayed green.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
