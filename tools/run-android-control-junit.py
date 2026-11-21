#!/usr/bin/env python3
"""Run the module's JUnit tests and the app approval contract from freshly exported classpaths.

This command never invokes Gradle, searches its temporary worker files, or accesses a device.
First use tools/android-control-junit.gradle to export each runtime with its build dependencies.
"""
from __future__ import annotations

import argparse
import ctypes
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

APPROVAL_TEST = "me.rerere.rikkahub.data.ai.tools.AndroidControlApprovalsTest"
SUMMARY = re.compile(r"^JUNIT_SUMMARY classes=(\d+) tests=(\d+) failures=(\d+) ignored=(\d+) time_ms=(\d+)$", re.MULTILINE)


def native_argument_encoding() -> str:
    # JDK's Windows launcher reads @files using the native system encoding, which can
    # differ from Python UTF-8 mode and Java's default file.encoding since JDK 18.
    return f"cp{ctypes.windll.kernel32.GetACP()}" if os.name == "nt" else "utf-8"


def argument_line(value: str) -> str:
    # Java @file quoting, not shell quoting. No command is passed through a shell.
    if "\n" in value or "\r" in value or "\x00" in value:
        raise ValueError("A Java argument contains a line break or NUL")
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def java_executable() -> str:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        executable = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if not executable.is_file():
            raise ValueError("JAVA_HOME does not contain a Java launcher")
        return str(executable)
    executable = shutil.which("java")
    if executable is None:
        raise ValueError("Set JAVA_HOME to the project's JDK before running this command")
    return executable


def test_classes(repo: Path, module: str, runtime_files: list[Path]) -> list[str]:
    module_build = (repo / module / "build").resolve()
    classes: set[str] = set()
    for directory in runtime_files:
        if not directory.is_dir():
            continue
        try:
            relative = directory.resolve().relative_to(module_build)
        except ValueError:
            continue
        if "debugUnitTest" not in relative.parts:
            continue
        for test in directory.rglob("*Test.class"):
            if "$" not in test.name:
                classes.add(".".join(test.relative_to(directory).with_suffix("").parts))
    if module == "app":
        if APPROVAL_TEST not in classes:
            raise ValueError("AndroidControlApprovalsTest was not found in the exported app test classpath")
        return [APPROVAL_TEST]
    if not classes:
        raise ValueError("No AndroidControl *Test.class files found; export the current Debug test classpath first")
    return sorted(classes)


def run_module(repo: Path, module: str, java: str) -> dict:
    classpath_file = repo / module / "build" / "control-test-classpath.txt"
    if not classpath_file.is_file():
        raise ValueError(f"Missing {module}/build/control-test-classpath.txt; run :{module}:exportAndroidControlTestClasspath with tools/android-control-junit.gradle")
    classpath = classpath_file.read_text(encoding="utf-8-sig").strip()
    if not classpath:
        raise ValueError(f"The exported {module} test classpath is empty")
    runtime_files = [Path(entry) for entry in classpath.split(os.pathsep) if entry]
    classes = test_classes(repo, module, runtime_files)
    report_dir = repo / module / "build" / "control-junit"
    report_dir.mkdir(parents=True, exist_ok=True)
    arguments = ["-ea", "-Dfile.encoding=UTF-8", "-cp", classpath, str(repo / "tools" / "JunitSummaryRunner.java"), *classes]
    argument_file = report_dir / "java-native.args"
    encoding = native_argument_encoding()
    try:
        argument_file.write_text("\n".join(map(argument_line, arguments)) + "\n", encoding=encoding, errors="strict")
    except UnicodeEncodeError as error:
        raise ValueError(f"A repository or SDK path cannot be represented by the Windows Java launcher's {encoding} encoding") from error
    result = subprocess.run([java, "@" + str(argument_file)], cwd=repo / module,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    output = result.stdout.decode("utf-8", errors="replace").replace("\r\n", "\n")
    log_file = report_dir / "junit.log"
    log_file.write_text(output, encoding="utf-8")
    match = SUMMARY.search(output)
    counts = dict(zip(("classes", "tests", "failures", "ignored", "time_ms"), map(int, match.groups()))) if match else None
    passed = result.returncode == 0 and counts is not None and counts["tests"] > 0 and counts["failures"] == 0
    report = {"module": module, "passed": passed, "exit_code": result.returncode,
              "selected_classes": classes, "counts": counts, "argument_encoding": encoding,
              "classpath_file": str(classpath_file), "log_file": str(log_file)}
    (report_dir / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for line in output.splitlines():
        if line.startswith(("JUNIT_SUMMARY", "FAIL:")):
            print(f"{module}: {line}", flush=True)
    print(f"{module}: {'PASS' if passed else 'FAIL'}; log={log_file}", flush=True)
    if not passed:
        print("\n".join(output.splitlines()[-35:]), file=sys.stderr)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--module", choices=("all", "android-control", "app"), default="all")
    options = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    java = java_executable()
    modules = ("android-control", "app") if options.module == "all" else (options.module,)
    reports = [run_module(repo, module, java) for module in modules]
    output = repo / "build" / "control-junit-summary.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"results": reports, "passed": all(report["passed"] for report in reports)}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Combined report: {output}", flush=True)
    return 0 if all(report["passed"] for report in reports) else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(2)
