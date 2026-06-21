#!/usr/bin/env python3
"""Inspector: derive an ANIME extension's `sources[]` (id/lang/name/baseUrl/versionId) statically.

This is the anime port of the manga repo's tools/inspector.py. It replaces the prebuilt
komikku `Inspector.jar` (which loads the dex on a device and instantiates Source classes) so
the publish pipeline stays build-from-source only — no external prebuilt artifacts pulled into
CI, matching the repo's trust model.

WHY STATIC (not dex/JVM instantiation):
Anime sources extend `ConfigurableAnimeSource` / `AnimeHttpSource` and initialize Android/injekt
-backed fields in their constructor (NetworkHelper via injectLazy, getSharedPreferences keyed on
the source id, ...). Reflectively instantiating those on a plain JVM throws. So we read the
constant `name`/`baseUrl`/`lang`/`versionId` *statically* and recompute each `id` with the EXACT
source-api formula.

EXACT ID FORMULA — source-api AnimeHttpSource.kt (verified against aniyomi-revived):
  id = generateId(name, lang, versionId)
    key   = "${name.lowercase()}/$lang/$versionId"
    bytes = MD5(key.toByteArray())                  # UTF-8, 16 bytes
    id    = (first 8 bytes as big-endian) and Long.MAX_VALUE   # clear sign bit
versionId defaults to 1 unless a source overrides it. This is byte-for-byte identical to the
manga HttpSource formula, so the port is the same arithmetic.

For an AnimeSourceFactory (createSources()), each list entry is a distinct source with its own
(lang -> id); name/baseUrl/versionId come from the Source class itself.
"""
from __future__ import annotations

import hashlib
import os
import re
import sys
from typing import Optional


def generate_id(name: str, lang: str, version_id: int) -> int:
    """Port of AnimeHttpSource.generateId. Returns a non-negative Long."""
    key = f"{name.lower()}/{lang}/{version_id}"
    digest = hashlib.md5(key.encode("utf-8")).digest()  # 16 bytes
    value = int.from_bytes(digest[:8], byteorder="big", signed=False)
    return value & 0x7FFF_FFFF_FFFF_FFFF  # == Long.MAX_VALUE


# ---------------------------------------------------------------------------
# Static extraction from a module's Kotlin sources.
# ---------------------------------------------------------------------------

def _read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def _kt_files(module_dir: str) -> list[str]:
    out = []
    for root, _dirs, files in os.walk(os.path.join(module_dir, "src")):
        for fn in files:
            if fn.endswith(".kt"):
                out.append(os.path.join(root, fn))
    return out


def _find_class_const(src: str, class_name: str, prop: str) -> Optional[str]:
    """Find `override val <prop> = "literal"` (or `val <prop> = "literal"`)."""
    pat = re.compile(
        rf'\b(?:override\s+)?val\s+{re.escape(prop)}\s*[:=][^"\n]*?"([^"]*)"'
    )
    m = pat.search(src)
    return m.group(1) if m else None


def _find_version_id(src: str) -> int:
    m = re.search(r'\b(?:override\s+)?val\s+versionId\s*=\s*(\d+)', src)
    return int(m.group(1)) if m else 1


def _extract_factory_langs(src: str, source_class: str) -> list[str]:
    """Return the per-variant `lang` values from a SourceFactory.createSources()."""
    m = re.search(r'createSources\s*\(\s*\)\s*:[^=]*=\s*listOf\s*\((.*?)\n\s*\)',
                  src, re.DOTALL)
    body = m.group(1) if m else src
    langs = []
    for em in re.finditer(rf'\b{re.escape(source_class)}\s*\(\s*"([^"]*)"', body):
        langs.append(em.group(1))
    return langs


def inspect_module(module_dir: str, ext_class: Optional[str] = None) -> list[dict]:
    """Return sources[] = [{id, lang, name, baseUrl, versionId}, ...] for one extension module."""
    kt_files = _kt_files(module_dir)
    if not kt_files:
        return []

    if not ext_class:
        bg = os.path.join(module_dir, "build.gradle")
        if os.path.isfile(bg):
            txt = _read(bg)
            mm = re.search(r"extClass\s*=\s*'([^']+)'", txt) or \
                re.search(r'extClass\s*=\s*"([^"]+)"', txt)
            ext_class = mm.group(1) if mm else None
    entry_simple = (ext_class or "").rsplit(".", 1)[-1] or None

    by_class: dict[str, str] = {}
    for path in kt_files:
        src = _read(path)
        for cm in re.finditer(r'\bclass\s+([A-Z][A-Za-z0-9_]*)', src):
            by_class[cm.group(1)] = src

    entry_src = by_class.get(entry_simple) if entry_simple else None
    if entry_src is None:
        candidates = {
            c: s for c, s in by_class.items()
            if _find_class_const(s, c, "name") and _find_class_const(s, c, "baseUrl")
        }
        if len(candidates) == 1:
            c, s = next(iter(candidates.items()))
            return [_single_source(s, c)]
        return []

    # AnimeSourceFactory? ("SourceFactory" also matches "AnimeSourceFactory")
    is_factory = "SourceFactory" in entry_src and "createSources" in entry_src

    if is_factory:
        m = re.search(r'createSources\s*\([^)]*\)\s*:[^=]*=\s*listOf\s*\(\s*([A-Z][A-Za-z0-9_]*)\s*\(',
                      entry_src, re.DOTALL)
        source_class = m.group(1) if m else None
        if not source_class or source_class not in by_class:
            return []
        src_body = by_class[source_class]
        name = _find_class_const(src_body, source_class, "name")
        base_url = _find_class_const(src_body, source_class, "baseUrl")
        version_id = _find_version_id(src_body)
        # Best-effort: theme-based factories pass name/baseUrl as constructor args rather than
        # `val name = "..."`, so they can't be resolved statically. Skip rather than crash;
        # the extension still installs and exposes its real sources once loaded.
        if not name:
            return []
        langs = _extract_factory_langs(entry_src, source_class)
        sources = []
        for lang in langs:
            sources.append({
                "id": str(generate_id(name, lang, version_id)),
                "lang": lang,
                "name": name,
                "baseUrl": base_url or "",
                "versionId": version_id,
            })
        return sources

    return [_single_source(entry_src, entry_simple)]


def _single_source(src: str, class_name: str) -> dict:
    name = _find_class_const(src, class_name, "name") or class_name
    lang = _find_class_const(src, class_name, "lang") or "all"
    base_url = _find_class_const(src, class_name, "baseUrl") or ""
    version_id = _find_version_id(src)
    return {
        "id": str(generate_id(name, lang, version_id)),
        "lang": lang,
        "name": name,
        "baseUrl": base_url,
        "versionId": version_id,
    }


def _selftest() -> None:
    assert 0 <= generate_id("AllAnime", "en", 1) <= 0x7FFF_FFFF_FFFF_FFFF
    assert generate_id("AllAnime", "en", 1) == generate_id("allanime", "en", 1)
    print("inspector selftest OK")


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "--selftest":
        _selftest()
    elif len(sys.argv) >= 2:
        import json
        print(json.dumps(inspect_module(sys.argv[1],
                                        sys.argv[2] if len(sys.argv) > 2 else None),
                         indent=2, ensure_ascii=False))
    else:
        sys.exit("usage: inspector.py <module_dir> [extClass] | --selftest")
