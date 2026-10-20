#!/usr/bin/env python3
"""Package successful unified builds for original Zalith's local ZIP importer."""
import argparse
import json
from pathlib import Path
import struct
import zipfile

from build import ABIS, verify_elf
import tempfile


def encode_config(config):
    # ensure_ascii yields only ASCII: UTF-8 and Java modified UTF-8 are identical
    # for this payload. DataInputStream.readUTF requires the two-byte byte count.
    payload = json.dumps(config, ensure_ascii=True, separators=(",", ":")).encode("ascii")
    if len(payload) > 65535:
        raise ValueError("Config exceeds Java writeUTF limit")
    return struct.pack(">H", len(payload)) + payload


def package(build_zips, output):
    config = {
        "pluginVersion": 1,
        "rendererId": "opengles3",
        "rendererDisplayName": "Proton Renderer (experimental)",
        "glName": "libpowervr.so",
        "eglName": "libEGL.so",
        "boatEnv": {},
        "pojavEnv": {"LIBGL_ES": "3", "POJAV_RENDERER": "opengles3"},
        "dlopenList": []
    }
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    seen = set()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as dest:
        dest.writestr("config", encode_config(config))
        for filename in build_zips:
            with zipfile.ZipFile(filename) as source:
                metadata = json.loads(source.read("build.json"))
                abi = metadata["abi"]
                if abi not in ABIS or abi in seen:
                    raise ValueError("Unsupported or duplicate ABI")
                seen.add(abi)
                library = source.read("libpowervr.so")
                import hashlib
                if hashlib.sha256(library).hexdigest() != metadata["sha256"]:
                    raise ValueError("Native library hash does not match build metadata")
                with tempfile.TemporaryDirectory() as tmp:
                    path = Path(tmp) / "libpowervr.so"
                    path.write_bytes(library)
                    verify_elf(path, abi)
                dest.writestr(f"libs/{abi}/libpowervr.so", library)
                # Keep complete modified corresponding source and its build notes.
                for name in source.namelist():
                    if name != "libpowervr.so" and not name.endswith("/"):
                        dest.writestr(f"sources/{abi}/{name}", source.read(name))
        dest.writestr("README.txt", """Proton Renderer - experimental original Zalith Launcher local plugin
(PowerVR Unified r2 build from the proton-renderer project)
Import this ZIP without extracting it using the launcher's local renderer import.
Select Proton Renderer (experimental) in renderer settings, then restart the game.
Requires a real GLES 3 driver. Native compilation passed; on-device gameplay is
NOT verified. No all-Minecraft, mod or shader compatibility guarantee is made.
Do not replace system libraries. If it crashes, revert to your previous renderer.
Full modified corresponding source, license texts and metadata are under sources/.
Rebuild instructions: sources/<abi>/README.md and its corresponding-source archive.
Importer schema inspected at ZalithLauncher/ZalithLauncher commit:
ed8a5c873b25f19d08aef5bdfc1084fd9aff836c
This is for original Zalith, not a claim of Zalith Launcher 2 support.
""")
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-zip", action="append", required=True, type=Path)
    parser.add_argument("--output", default="artifacts/powervr-zalith-local.zip", type=Path)
    args = parser.parse_args()
    print(package(args.build_zip, args.output))


if __name__ == "__main__":
    main()
