#!/usr/bin/env python3
"""Suggest a single backend BEFORE launcher context creation; never spoof GL."""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def select(version, gles, requested="auto"):
    match = re.fullmatch(r"1\.(\d+)(?:\.(\d+))?", version)
    if requested not in {"auto", "krypton", "ltw"}:
        raise ValueError("Unknown backend")
    if gles not in {"2.0", "3.0", "3.1", "3.2"}:
        raise ValueError("Supply the device's actual GLES version")
    if not match and requested == "auto":
        raise ValueError("Snapshots, pre-releases and non-1.x versions require an explicit backend")
    modern = bool(match and int(match[1]) >= 17)
    name = ("ltw" if modern else "krypton") if requested == "auto" else requested
    if name == "ltw" and match and not modern:
        raise ValueError("LTW is a core-profile backend; use Krypton for pre-1.17 Minecraft")
    # Deliberately conservative eligibility, NOT a certification of support.
    required = "3.2" if name == "ltw" or modern else "3.0"
    if tuple(map(int, gles.split('.'))) < tuple(map(int, required.split('.'))):
        raise ValueError(f"This experimental route requires GLES {required}; device reports {gles}")
    lock = json.loads((ROOT / "backends.json").read_text())[name]
    return {"backend": name, "library": f"{name}/{lock['library']}",
            "environment": lock["environment"], "minimum_gles_policy": required,
            "verified_compatible": False,
            "warning": "Heuristic only. Launcher bridge, actual GL features, JVM and device validation are required. Load only this backend; restart to switch."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--gles", required=True, choices=["2.0", "3.0", "3.1", "3.2"])
    parser.add_argument("--backend", default="auto", choices=["auto", "krypton", "ltw"])
    args = parser.parse_args()
    try:
        print(json.dumps(select(args.minecraft, args.gles, args.backend), indent=2))
    except ValueError as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
