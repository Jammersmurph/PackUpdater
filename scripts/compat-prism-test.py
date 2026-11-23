#!/usr/bin/env python3
"""Run visible, sequential Prism smoke tests against the local test pack.

Only operates on instances whose names start with 'PackUpdater Testing '.
Uses the fixture server at http://127.0.0.1:18761/. Does not alter configs or jars.
"""
import argparse
import importlib.util
import os
from pathlib import Path
import select
import shutil
import signal
import subprocess
import time

INSTANCES = Path.home() / ".local/share/PrismLauncher/instances"


def games():
    result = {}
    for proc in Path("/proc").iterdir():
        if not proc.name.isdigit():
            continue
        try:
            command = (proc / "cmdline").read_bytes()
            if b"org.prismlauncher.EntryPoint" in command:
                result[int(proc.name)] = (proc / "cwd").resolve()
        except OSError:
            pass
    return result


def stop(root):
    for pid, directory in games().items():
        if directory != root:
            raise RuntimeError(f"Another instance is running: {directory}")
        fd = os.pidfd_open(pid)
        try:
            os.kill(pid, signal.SIGTERM)
            if not select.select([fd], [], [], 30)[0]:
                raise RuntimeError(f"Test game {pid} did not exit; refusing another launch")
        finally:
            os.close(fd)


def run(instance, phase, output, visible_seconds):
    if not instance.startswith("PackUpdater Testing "):
        raise ValueError("Only dedicated PackUpdater test instances are allowed")
    root = INSTANCES / instance / "minecraft"
    stop(root)
    spec = importlib.util.spec_from_file_location("fixture", Path(__file__).with_name("compat-test-pack.py"))
    fixture = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fixture)
    fixture.create(output / "pack", phase)
    log = root / "logs/latest.log"
    old_stamp = log.stat().st_mtime_ns if log.exists() else None
    print(f"LAUNCH {instance}, phase {phase}", flush=True)
    # A first Prism process owns the launcher UI and remains alive; subsequent
    # CLI invocations forward to it and exit. Neither is the game process.
    with (output / "prism-launch.log").open("a") as console:
        subprocess.Popen(["prismlauncher", "--launch", instance], stdout=console, stderr=console)
    deadline = time.monotonic() + 300
    text = ""
    try:
        while time.monotonic() < deadline:
            if log.exists() and log.stat().st_mtime_ns != old_stamp:
                text = log.read_text(errors="replace")
                if "[PackUpdater] Update failed:" in text:
                    raise RuntimeError("Updater failed; see captured log")
                if "Created: " in text and "atlas" in text and "[PackUpdater] Update complete." in text:
                    break
            time.sleep(1)
        else:
            raise RuntimeError("Timed out waiting for updater and game resources")
        expected = f"phase-{phase}"
        assert (root / "config/packupdater-compat-version.txt").read_text().strip() == expected
        if phase > 1:
            assert not (root / "config/packupdater-compat-obsolete.txt").exists()
        assert (root / "config/packupdater-compat-optional.txt").read_text().strip() == "Optional selection works."
        assert text.count("[PackUpdater] Early update starting:") == 1, "Updater ran twice"
        time.sleep(visible_seconds)
        if root not in games().values():
            print("Game closed after resource loading; updater checks completed.", flush=True)
        print(f"PASS {instance}, phase {phase}", flush=True)
    finally:
        evidence = output / "results" / instance / f"phase-{phase}"
        evidence.mkdir(parents=True, exist_ok=True)
        if log.exists():
            shutil.copy2(log, evidence / "latest.log")
        for path in [root / "config/packupdater-compat-version.txt"]:
            if path.exists():
                shutil.copy2(path, evidence / path.name)
        stop(root)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("instance")
    parser.add_argument("--phases", type=int, nargs="+", choices=[1, 2, 3], default=[1])
    parser.add_argument("--output", type=Path, default=Path("/tmp/opencode/packupdater-compat"))
    parser.add_argument("--visible-seconds", type=int, default=10)
    args = parser.parse_args()
    for phase in args.phases:
        run(args.instance, phase, args.output, args.visible_seconds)
