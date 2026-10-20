#!/usr/bin/env python3
"""Build one Krypton-based renderer with LTW-derived code compiled into it."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

from build import ROOT, ABIS, fetch, run, verify_elf
from build_combined import source_archive


def integrate(source):
    """Apply the small audited port; fail rather than silently patch wrong code."""
    drawing = source / "src/gl/drawing.c"
    text = drawing.read_text()
    if "gl4es_glMultiDrawElementsIndirect(" in text:
        raise ValueError("Upstream now implements MDI; review the integration")
    module = ROOT / "native/ltw_multidraw.inc"
    shutil.copy2(module, drawing.parent / module.name)
    drawing.write_text(text + '\n#include "ltw_multidraw.inc"\n')
    # Cache lifetime follows Krypton's zero-initialized, non-shared context state.
    header = source / "src/gl/glstate.h"
    state = header.read_text()
    marker = "    struct glstate_s {"
    if state.count(marker) != 1:
        raise ValueError("Upstream GL state layout changed; review cache integration")
    shutil.copy2(ROOT / "native/pv_context_cache.h", header.parent / "pv_context_cache.h")
    header.write_text(state.replace(marker,
        '#include "pv_context_cache.h"\n' + marker + '\n        struct pv_mdi_cache pv_mdi;', 1))
    cmake = source / "CMakeLists.txt"
    cmake.write_text(cmake.read_text() + '\nset_target_properties(ng_gl4es PROPERTIES OUTPUT_NAME "powervr")\n')
    shutil.copytree(ROOT / "licenses", source / "powervr-licenses")
    shutil.copy2(ROOT / "docs/UNIFIED.md", source / "POWERVR-INTEGRATION.md")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=ABIS, default="arm64-v8a")
    parser.add_argument("--ndk", default=os.environ.get("ANDROID_NDK_HOME"))
    parser.add_argument("--jobs", type=int, default=2)
    args = parser.parse_args()
    if not args.ndk or args.jobs < 1:
        parser.error("Supply an NDK path and positive --jobs")
    ndk = Path(args.ndk).resolve()
    toolchain = ndk / "build/cmake/android.toolchain.cmake"
    if not toolchain.is_file():
        parser.error("Missing Android NDK toolchain")
    locks = json.loads((ROOT / "backends.json").read_text())
    lock = locks["krypton"]
    work = ROOT / ".work/unified" / args.abi
    if work.exists():
        shutil.rmtree(work)
    source = work / "source"
    fetch(lock, source)
    run("git", "-C", source, "submodule", "update", "--init", "--recursive", "--depth=1")
    integrate(source)
    output = ROOT / "artifacts" / args.abi / "unified"
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    source_archive(source, output / "corresponding-source.tar.gz")
    build = work / "build"
    run("cmake", "-S", source, "-B", build,
        f"-DCMAKE_TOOLCHAIN_FILE={toolchain}", f"-DANDROID_ABI={args.abi}",
        "-DANDROID_PLATFORM=android-21", "-DCMAKE_BUILD_TYPE=Release")
    run("cmake", "--build", build, "--target", "ng_gl4es", "--parallel", args.jobs)
    library = build / "libpowervr.so"
    verify_elf(library, args.abi)
    shutil.copy2(library, output / library.name)
    shutil.copytree(ROOT / "licenses", output / "licenses")
    shutil.copy2(ROOT / "docs/UNIFIED.md", output / "README.md")
    (output / "build.json").write_text(json.dumps({
        "abi": args.abi, "base": lock, "ltw_port_origin": locks["ltw"],
        "integration": "native/ltw_multidraw.inc", "status": "experimental partial integration",
        "cache_header_sha256": hashlib.sha256((ROOT / "native/pv_context_cache.h").read_bytes()).hexdigest(),
        "module_sha256": hashlib.sha256((ROOT / "native/ltw_multidraw.inc").read_bytes()).hexdigest(),
        "sha256": hashlib.sha256(library.read_bytes()).hexdigest(),
        "ndk": (ndk / "source.properties").read_text()}, indent=2) + "\n")
    destination = output.parent / "powervr-unified.zip"
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(output.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(output))
    print(destination)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"Unified build failed: {error}")
