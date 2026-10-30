#!/usr/bin/env python3
"""Build LTW and Krypton into one package, keeping their GL namespaces isolated."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import zipfile

from build import ROOT, ABIS, fetch, run, verify_elf


def source_archive(source, destination):
    # Archive BEFORE configuring: contains exact checked-out code and submodules,
    # including license files, but no Git internals or compiled artifacts.
    with tarfile.open(destination, "w:gz") as archive:
        def clean(info):
            return None if ".git" in Path(info.name).parts else info
        archive.add(source, arcname="source", filter=clean)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=ABIS, default="arm64-v8a")
    parser.add_argument("--ndk", default=os.environ.get("ANDROID_NDK_HOME"))
    parser.add_argument("--jobs", type=int, default=2)
    args = parser.parse_args()
    if not args.ndk or args.jobs < 1:
        parser.error("Supply --ndk / ANDROID_NDK_HOME and a positive --jobs")
    ndk = Path(args.ndk).resolve()
    toolchain = ndk / "build/cmake/android.toolchain.cmake"
    if not toolchain.is_file():
        parser.error("NDK toolchain not found")
    locks = json.loads((ROOT / "backends.json").read_text())
    output = ROOT / "artifacts" / args.abi / "combined"
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    metadata = {"abi": args.abi, "android_api": 21, "backends": {},
                "ndk": (ndk / "source.properties").read_text(),
                "status": "experimental, not a launcher plugin or universal compatibility guarantee"}
    for name, lock in locks.items():
        work = ROOT / ".work" / "combined" / args.abi / name
        source = work / "source"
        # Fresh source ensures the redistributable source archive never includes
        # products written into the upstream source tree by earlier builds.
        if source.exists():
            shutil.rmtree(source)
        fetch(lock, source)
        run("git", "-C", source, "submodule", "update", "--init", "--recursive", "--depth=1")
        dest = output / name
        dest.mkdir()
        source_archive(source, dest / "corresponding-source.tar.gz")
        build = work / "build"
        run("cmake", "-S", source / lock["cmake_path"], "-B", build,
            f"-DCMAKE_TOOLCHAIN_FILE={toolchain}", f"-DANDROID_ABI={args.abi}",
            "-DANDROID_PLATFORM=android-21", "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_POLICY_VERSION_MINIMUM=3.5")
        run("cmake", "--build", build, "--target", lock["target"], "--parallel", args.jobs)
        matches = list(build.rglob(lock["library"]))
        if len(matches) != 1:
            raise ValueError(f"Expected one {lock['library']}, found {len(matches)}")
        library = matches[0]
        verify_elf(library, args.abi)
        shutil.copy2(library, dest / lock["library"])
        metadata["backends"][name] = dict(lock, sha256=hashlib.sha256(library.read_bytes()).hexdigest())
    (output / "build.json").write_text(json.dumps(metadata, indent=2) + "\n")
    for filename in ["backends.json", "LICENSE"]:
        shutil.copy2(ROOT / filename, output / filename)
    shutil.copy2(ROOT / "docs/COMBINED.md", output / "README.md")
    tools = output / "tools"
    tools.mkdir()
    # Keep the build recipe and selector with the distributed sources.
    for filename in ["build.py", "build_combined.py", "select_backend.py"]:
        shutil.copy2(ROOT / "tools" / filename, tools / filename)
    archive_path = output.parent / "powervr-combined.zip"
    with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(output.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(output))
    print(archive_path)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"Combined build failed: {error}")
