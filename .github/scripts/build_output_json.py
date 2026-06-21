#!/usr/bin/env python3
"""Produce output.json (the file create-repo.py consumes) using the in-repo static inspector
instead of the prebuilt komikku Inspector.jar.

output.json shape (same as Inspector.jar):
  { "<applicationId>": [ {name, lang, id, baseUrl, versionId}, ... ], ... }

Run from the repo root (the dir containing src/ and repo/apk). Each built APK is named
`aniyomi-<lang>.<module>-v<ver>.apk` (archivesName = "aniyomi-$applicationIdSuffix-v$versionName",
applicationIdSuffix = "<lang>.<module>"). From that we derive:
  applicationId = eu.kanade.tachiyomi.animeextension.<lang>.<module>   (namespace + suffix)
  module dir    = src/<lang>/<module>
and statically inspect the module. Modules with no statically-resolvable Source (e.g. some
lib-multisrc theme extensions) get an empty list — create-repo.py handles empty lists fine.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

import inspector as _inspector

NAMESPACE = "eu.kanade.tachiyomi.animeextension"
APK_NAME_RE = re.compile(r"^aniyomi-(.+)-v[0-9][0-9.]*\.apk$")

REPO_APK_DIR = Path("repo/apk")
SRC_DIR = Path("src")


def main() -> None:
    output: dict[str, list[dict]] = {}
    for apk in sorted(REPO_APK_DIR.glob("*.apk")):
        m = APK_NAME_RE.match(apk.name)
        if not m:
            print(f"  skip (unparsable name): {apk.name}")
            continue
        suffix = m.group(1)            # "<lang>.<module>"
        lang, _, module = suffix.partition(".")
        pkg = f"{NAMESPACE}.{suffix}"
        module_dir = SRC_DIR / lang / module
        sources: list[dict] = []
        if module_dir.is_dir():
            try:
                sources = _inspector.inspect_module(str(module_dir))
            except Exception as e:  # never fail the build over best-effort metadata
                print(f"  inspect failed for {pkg}: {e}")
        else:
            print(f"  module dir missing for {pkg}: {module_dir}")
        output[pkg] = sources

    with open("output.json", "w", encoding="utf-8") as f:
        json.dump(output, f, ensure_ascii=False)
    total = sum(len(v) for v in output.values())
    print(f"Wrote output.json: {len(output)} packages, {total} sources")


if __name__ == "__main__":
    main()
