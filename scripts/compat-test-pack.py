#!/usr/bin/env python3
"""Create a disposable Packwiz fixture. Serve its output with python -m http.server.

Phases 1/2 install/replace harmless config files; phase 3 tests cleanup.
This never adds a test mod, changes an instance, or publishes anything.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def create(root, phase):
    root.mkdir(parents=True, exist_ok=True)
    version = f"phase-{phase}"
    files = {"config/packupdater-compat-version.txt": (version + "\n").encode()}
    optional = b"Optional selection works.\n"
    (root / "optional.txt").write_bytes(optional)
    files["config/compat-optional.pw.toml"] = f'''name = "Optional compatibility test file"
filename = "packupdater-compat-optional.txt"
side = "both"
[download]
url = "http://127.0.0.1:18761/optional.txt"
hash-format = "sha256"
hash = "{sha(optional)}"
[option]
optional = true
default = true
description = "Harmless test file. Leave checked and continue to verify optional selections."
'''.encode()
    if phase == 1:
        files["config/packupdater-compat-obsolete.txt"] = b"Remove me in phase 2.\n"
    entries = ['hash-format = "sha256"\n']
    for name, data in files.items():
        dest = root / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
        metafile = "metafile = true\n" if name.endswith(".pw.toml") else ""
        entries.append(f'[[files]]\nfile = "{name}"\nhash = "{sha(data)}"\n{metafile}')
    index = "\n".join(entries).encode()
    (root / "index.toml").write_bytes(index)
    (root / "pack.toml").write_text(f'''name = "PackUpdater compatibility test"
author = "PackUpdater"
version = "{version}"
pack-format = "packwiz:1.1.0"
[index]
file = "index.toml"
hash-format = "sha256"
hash = "{sha(index)}"
''')
    print(f"Created {version} at {root}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--phase", type=int, choices=[1, 2, 3], required=True)
    parser.add_argument("--installer", type=Path, help="Pin a known-good installer locally, avoiding GitHub API availability during tests")
    args = parser.parse_args()
    create(args.output, args.phase)
    if args.installer:
        with zipfile.ZipFile(args.installer) as jar:
            manifest = jar.read("META-INF/MANIFEST.MF").decode()
        version = next(line.split(": ", 1)[1] for line in manifest.splitlines()
                       if line.startswith("Implementation-Version: "))
        shutil.copy2(args.installer, args.output / "test-installer.jar")
        url = "http://127.0.0.1:18761/test-installer.jar"
        (args.output / "installer-release.json").write_text(json.dumps({
            "tag_name": version, "assets": [{
                "name": "packwiz-installer.jar", "browser_download_url": url, "url": url
            }]
        }, indent=2))
