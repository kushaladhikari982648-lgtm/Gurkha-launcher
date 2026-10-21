#!/usr/bin/env python3
"""Give the Proton Renderer its own name in the GL strings the game reports.

The unified build is Krypton/NG-GL4ES with an LTW-derived multidraw port compiled
in, so Krypton's own brand is hardcoded in the strings Minecraft prints on the
F3 debug screen and in ``latest.log``: ``GL_VERSION``,
``GL_SHADING_LANGUAGE_VERSION`` and the launcher-side version banner. A user who
selects the Proton Renderer must not be told that the Krypton Wrapper is running,
so the brand text is replaced at build time.

This is a name-only patch: no capability, version number, extension list or error
behaviour is changed. Upstream attribution is kept: ``GL_VENDOR`` still returns
"ptitSeb & BZLZHH", and the license/notice files are untouched.

The replacement is fail-closed: if the pinned upstream sources no longer contain
exactly the expected number of brand strings, the build stops instead of shipping
a renderer that reports someone else's name.
"""

from pathlib import Path

OLD = "Krypton Wrapper"
NEW = "Proton Renderer"

# Exact occurrence counts at NG-GL4ES 6e153a1622a8d9ef903f7975aafbef2453571bab.
# getter.cpp: GL_VERSION (coloured + plain) and GL_SHADING_LANGUAGE_VERSION.
# init.c: the version banner (coloured + plain, two snprintf calls) and a log line.
EXPECTED = {
    "src/gl/getter.cpp": 4,
    "src/gl/init.c": 5,
}


def occurrences(path: Path) -> int:
    return path.read_text(encoding="utf-8").count(OLD)


def apply(source: Path, name: str = NEW, expected: dict = EXPECTED) -> dict:
    """Replace the Krypton brand in ``source``; return {file: replacements made}."""
    if len(name) > len(OLD):
        # globals4es.version is a fixed 64-byte buffer filled by snprintf.
        raise ValueError(f"Renderer name {name!r} is longer than {OLD!r}; the version buffer may truncate")
    changed = {}
    for relative, count in expected.items():
        path = Path(source) / relative
        text = path.read_text(encoding="utf-8")
        found = text.count(OLD)
        if found != count:
            raise ValueError(
                f"{relative}: expected {count} {OLD!r} strings, found {found}. "
                "Upstream changed its GL strings; review the identity patch.")
        path.write_text(text.replace(OLD, name), encoding="utf-8")
        changed[relative] = count
    return changed
