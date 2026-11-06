#!/usr/bin/env python3
"""Inventory and run Fabric GameTests in separate server JVMs.

The inventory comes from a dedicated lightweight GameTest server. A partitioned
run does not replace the concurrent full suite.
"""

import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parent.parent


def results(path):
    cases = ET.parse(path).getroot().iter("testcase")
    found = {}
    for case in cases:
        name = case.attrib["name"]
        if name in found:
            raise ValueError(f"duplicate test result: {name}")
        found[name] = [child.tag for child in case if child.tag in {"failure", "error", "skipped"}]
    if not found:
        raise ValueError(f"no tests in {path}")
    return found


def partitions(names, limit, prefix=""):
    """Split the complete inventory into disjoint Minecraft wildcard prefixes."""
    # Minecraft adds the default namespace when a selector has no colon, so a
    # namespace prefix such as "m*" would not select "minecraft:...".
    if len(names) <= limit and ":" in prefix:
        return [(prefix + "*", names)]
    groups = {}
    result = []
    for name in names:
        if len(name) == len(prefix):
            result.append((name, [name]))
        else:
            groups.setdefault(name[len(prefix)], []).append(name)
    for character, members in sorted(groups.items()):
        result.extend(partitions(members, limit, prefix + character))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--max-tests", type=int, default=8, help="maximum tests per prefix (default: 8)")
    parser.add_argument("--plan", action="store_true", help="print selectors and counts without running servers")
    parser.add_argument("--mode", choices=("development", "production"), default="development", help="server launch mode")
    parser.add_argument("--skip-lifecycle", action="store_true", help="run isolated lifecycle checks in another aggregate invocation")
    args = parser.parse_args()
    if args.max_tests < 1:
        parser.error("--max-tests must be positive")
    gradle = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    task = "runGameTest" if args.mode == "development" else "runProductionGameTest"
    gradle_args = [str(gradle), task, "-PacceptMinecraftEula=true", "-PtumbleSkipRecoveryLifecycle=true", "--console=plain"]
    with tempfile.TemporaryDirectory(prefix="tumble-gametest-partitions-") as directory:
        inventory = Path(directory, "inventory.txt")
        env = os.environ.copy()
        env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") +
            ' -Dfabric-api.gametest.filter=tumble_test:game_test_inventory_dump' +
            f' -Dtumble.test.inventoryFile="{inventory.as_posix()}"')
        print("Enumerating GameTest registry in a separate server", flush=True)
        run = subprocess.run(gradle_args,
                             cwd=ROOT, env=env, check=False)
        if run.returncode or not inventory.is_file():
            print(f"Inventory server failed: exit={run.returncode}", file=sys.stderr)
            return 1
        names = inventory.read_text(encoding="utf-8").splitlines()
        if not names or len(set(names)) != len(names):
            print("Inventory is empty or contains duplicate IDs", file=sys.stderr)
            return 1
        expected = set(names)
        plan = partitions(sorted(expected), args.max_tests)
        print(f"Inventory: {len(expected)} tests; {len(plan)} partitions", flush=True)
        if args.plan:
            for selector, members in plan:
                print(f"{selector} {len(members)}")
            return 0

        all_seen = set()
        failed = False
        for number, (selector, members) in enumerate(plan, 1):
            report = Path(directory, f"{number}.xml").as_posix()
            env = os.environ.copy()
            env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") +
                f' -Dfabric-api.gametest.filter={selector} -Dfabric-api.gametest.report-file="{report}"')
            print(f"[{number}/{len(plan)}] {selector} ({len(members)} tests)", flush=True)
            run = subprocess.run(gradle_args,
                                 cwd=ROOT, env=env, check=False)
            try:
                actual = results(report)
            except (OSError, ValueError, ET.ParseError) as error:
                print(f"Missing or invalid partition report: {error}", file=sys.stderr)
                failed = True
                continue
            if set(actual) != set(members):
                print(f"Selection mismatch: missing={sorted(set(members) - set(actual))}, "
                      f"unexpected={sorted(set(actual) - set(members))}", file=sys.stderr)
                failed = True
            if all_seen.intersection(actual):
                print(f"Duplicate partition results: {sorted(all_seen.intersection(actual))}", file=sys.stderr)
                failed = True
            all_seen.update(actual)
            failures = {name: status for name, status in actual.items() if status}
            if run.returncode or failures:
                print(f"Partition failed: exit={run.returncode}, tests={failures}", file=sys.stderr)
                failed = True
        if not args.skip_lifecycle:
            print("Running isolated recovery lifecycle tests once", flush=True)
            lifecycle = subprocess.run([str(gradle), "runRecoveryLifecycleTests", "-PacceptMinecraftEula=true", "--console=plain"],
                                       cwd=ROOT, env=os.environ.copy(), check=False)
            if lifecycle.returncode:
                print(f"Isolated recovery lifecycle tests failed: exit={lifecycle.returncode}", file=sys.stderr)
                failed = True
    if all_seen != expected:
        print(f"Coverage mismatch: missing={sorted(expected - all_seen)}, "
              f"unexpected={sorted(all_seen - expected)}", file=sys.stderr)
        failed = True
    print(f"Observed {len(all_seen)} of {len(expected)} inventoried tests; "
          f"{'FAILED' if failed else 'all passed'}")
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
