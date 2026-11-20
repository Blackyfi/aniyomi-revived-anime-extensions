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


LANG_RE = re.compile(r"^(?:all|other|[a-z]{2,3}(?:-[A-Za-z0-9]{2,4})?)$")
_MODS = r"(?:(?:override|private|protected|internal|open|final)\s+)*"


def _is_lang(value: Optional[str]) -> bool:
    return bool(value) and bool(LANG_RE.match(value))


def _literal(value: str) -> Optional[str]:
    """A plain string literal, without templates; None otherwise."""
    return None if "$" in value or "\\" in value else value


def _find_class_const(src: str, class_name: str, prop: str) -> Optional[str]:
    """Value of `[override] val <prop>[: String] = "literal"` when the literal is the whole
    right-hand side. Falls back to `= SOME_CONST` resolved against `const val SOME_CONST = "..."`.
    Anything else (expressions, preferences, local variables in functions) is unresolved."""
    pat = re.compile(
        rf'^[ \t]*{_MODS}val\s+{re.escape(prop)}\s*(?::\s*String\s*)?=\s*"([^"\n]*)"[ \t]*(?://.*)?$',
        re.MULTILINE,
    )
    for m in pat.finditer(src):
        value = _literal(m.group(1))
        if value is not None:
            return value
    ref = re.compile(
        rf'^[ \t]*override\s+val\s+{re.escape(prop)}\b[^\n]*$',
        re.MULTILINE,
    )
    m = ref.search(src)
    if m:
        # `= SOME_CONST` or `by lazy { prefs.getString(KEY, SOME_DEFAULT)!! }`: take the first
        # referenced constant that holds a usable value (a URL, for baseUrl).
        for ident in re.findall(r'\b([A-Z][A-Z0-9_]{2,})\b', m.group(0)):
            cm = re.search(rf'\bconst\s+val\s+{ident}\s*(?::\s*String\s*)?=\s*"([^"\n]*)"', src)
            value = _literal(cm.group(1)) if cm else None
            if value and (prop != "baseUrl" or value.startswith("http")):
                return value
    return None


def _find_version_id(src: str) -> int:
    m = re.search(r'^[ \t]*override\s+val\s+versionId\s*(?::\s*Int\s*)?=\s*(\d+)', src, re.MULTILINE)
    return int(m.group(1)) if m else 1


def _find_explicit_id(src: str) -> Optional[int]:
    m = re.search(r'^[ \t]*override\s+val\s+id\s*(?::\s*Long\s*)?=\s*(\d+)L?\b', src, re.MULTILINE)
    return int(m.group(1)) if m else None


def _super_ctor_strings(src: str, class_name: str) -> list[str]:
    """String literals passed to the superclass constructor, e.g.
    `class AnimeXin : AnimeStream("all", "AnimeXin", "https://animexin.dev")`."""
    m = re.search(
        rf'\bclass\s+{re.escape(class_name)}\b[^{{:]*:\s*[A-Z][A-Za-z0-9_.]*\s*\(',
        src,
    )
    if not m:
        return []
    depth, i = 1, m.end()
    while i < len(src) and depth:
        depth += {"(": 1, ")": -1}.get(src[i], 0)
        i += 1
    args = src[m.end():i - 1]
    return [v for v in re.findall(r'"([^"\n]*)"', args) if _literal(v) is not None]


def _extract_factory_langs(src: str, source_class: str) -> list[str]:
    """Return the per-variant `lang` values from a SourceFactory.createSources()."""
    m = re.search(r'createSources\s*\(\s*\)\s*:[^=]*=\s*listOf\s*\((.*?)\n\s*\)',
                  src, re.DOTALL)
    body = m.group(1) if m else src
    langs = []
    for em in re.finditer(rf'\b{re.escape(source_class)}\s*\(\s*"([^"]*)"', body):
        langs.append(em.group(1))
    return langs


def _gradle_ext(module_dir: str, key: str) -> Optional[str]:
    bg = os.path.join(module_dir, "build.gradle")
    if not os.path.isfile(bg):
        return None
    m = re.search(rf"\b{key}\s*=\s*(['\"])([^'\"\n]*)\1", _read(bg))
    return m.group(2) if m else None


def _class_regions(src: str) -> dict[str, str]:
    """Split a file into top-level declarations so one class's `val`s aren't read for another."""
    starts = [m.start() for m in re.finditer(
        r'^(?:(?:private|internal|open|abstract|data|sealed|enum)\s+)*(?:class|object|interface)\s',
        src, re.MULTILINE)]
    regions = {}
    for i, start in enumerate(starts):
        chunk = src[start:starts[i + 1] if i + 1 < len(starts) else len(src)]
        m = re.search(r'\b(?:class|object|interface)\s+([A-Z][A-Za-z0-9_]*)', chunk)
        if m:
            regions[m.group(1)] = chunk
    return regions


def inspect_module(module_dir: str, ext_class: Optional[str] = None) -> list[dict]:
    """Return sources[] = [{id, lang, name, baseUrl, versionId}, ...] for one extension module.

    Values that can't be read as plain literals fall back to the module's build.gradle
    (extName, baseUrl) and its src/<lang>/ directory, never to unrelated strings."""
    kt_files = _kt_files(module_dir)
    if not kt_files:
        return []

    module_dir = module_dir.rstrip("/")
    dir_lang = os.path.basename(os.path.dirname(module_dir))
    ctx = {
        "dir_lang": dir_lang if _is_lang(dir_lang) else "all",
        "ext_name": _gradle_ext(module_dir, "extName"),
        "base_url": _gradle_ext(module_dir, "baseUrl"),
    }
    if not ext_class:
        ext_class = _gradle_ext(module_dir, "extClass")
    entry_simple = (ext_class or "").rsplit(".", 1)[-1] or None

    by_class: dict[str, str] = {}
    consts = []
    for path in kt_files:
        src = _read(path)
        consts += re.findall(r'^[ \t]*(?:private\s+)?const\s+val\s+.*$', src, re.MULTILINE)
        by_class.update(_class_regions(src))
    const_block = "\n" + "\n".join(consts)

    entry_src = by_class.get(entry_simple) if entry_simple else None
    if entry_src is None:
        candidates = {
            c: s for c, s in by_class.items()
            if _find_class_const(s, c, "name") and _find_class_const(s + const_block, c, "baseUrl")
        }
        if len(candidates) == 1:
            c, s = next(iter(candidates.items()))
            return [_single_source(s + const_block, c, ctx)]
        return []

    # AnimeSourceFactory? ("SourceFactory" also matches "AnimeSourceFactory")
    if "SourceFactory" in entry_src and "createSources" in entry_src:
        m = re.search(r'createSources\s*\([^)]*\)\s*:[^=]*=\s*listOf\s*\(\s*([A-Z][A-Za-z0-9_]*)\s*\(',
                      entry_src, re.DOTALL)
        source_class = m.group(1) if m else None
        if not source_class or source_class not in by_class:
            return []
        body = by_class[source_class] + const_block
        # A name built at runtime (string template, constructor parameter) gives a different
        # id per variant; guessing it would publish wrong, duplicated ids, so publish none.
        if re.search(r'^[ \t]*override\s+val\s+name\b', body, re.MULTILINE) and \
                not _find_class_const(body, source_class, "name"):
            return []
        langs = [lang for lang in _extract_factory_langs(entry_src, source_class) if _is_lang(lang)]
        sources = {}
        for lang in langs:
            source = _single_source(body, source_class, {**ctx, "dir_lang": lang}, lang=lang)
            sources.setdefault(source["id"], source)
        return list(sources.values())

    return [_single_source(entry_src + const_block, entry_simple, ctx)]


def _single_source(src: str, class_name: str, ctx: dict, lang: Optional[str] = None) -> dict:
    ctor = _super_ctor_strings(src, class_name)
    ctor_url = next((v for v in ctor if v.startswith("http")), None)
    ctor_lang = next((v for v in ctor if _is_lang(v) and not v.startswith("http")), None)
    ctor_name = next((v for v in ctor if v and v not in (ctor_url, ctor_lang)), None)

    name = _find_class_const(src, class_name, "name") or ctor_name or ctx["ext_name"] or class_name
    if lang is None:
        own_lang = _find_class_const(src, class_name, "lang")
        lang = next(v for v in (own_lang, ctor_lang, ctx["dir_lang"]) if _is_lang(v))
    base_url = _find_class_const(src, class_name, "baseUrl")
    if not (base_url or "").startswith("http"):
        base_url = ctor_url or ctx["base_url"] or ""
    version_id = _find_version_id(src)
    source_id = _find_explicit_id(src) or generate_id(name, lang, version_id)
    return {
        "id": str(source_id),
        "lang": lang,
        "name": name,
        "baseUrl": base_url,
        "versionId": version_id,
    }


def index_lang(apk_lang: str, sources: list[dict]) -> str:
    """The extension's `lang` in the index: the APK's language, or its single source's language
    when that is more specific. Never anything that isn't a language tag: the app groups the
    extension list by it, and two unreadable tags render as the same empty header."""
    lang = apk_lang if _is_lang(apk_lang) else "all"
    if len(sources) == 1:
        source_lang = sources[0]["lang"]
        if _is_lang(source_lang) and source_lang != lang and \
                source_lang not in {"all", "other"} and lang not in {"all", "other"}:
            lang = source_lang
    return lang


def _selftest() -> None:
    assert 0 <= generate_id("AllAnime", "en", 1) <= 0x7FFF_FFFF_FFFF_FFFF
    assert generate_id("AllAnime", "en", 1) == generate_id("allanime", "en", 1)
    for bad in ("p", "lang", "Subtitulado", "span.title", ""):
        assert not _is_lang(bad), bad
    for good in ("ar", "all", "pt-BR", "zh-Hant", "fil"):
        assert _is_lang(good), good
    assert index_lang("es", [{"lang": "p"}]) == "es"
    assert index_lang("pt", [{"lang": "pt-BR"}]) == "pt-BR"
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
