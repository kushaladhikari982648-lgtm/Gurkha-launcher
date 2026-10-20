#!/usr/bin/env python3
"""Build pinned GL4ES for Android; no SDK, Gradle, or Python packages required."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ABIS = {"arm64-v8a": 183, "armeabi-v7a": 40}


def run(*args):
    subprocess.run([str(a) for a in args], check=True)


def load_profile(path):
    profile = json.loads(Path(path).read_text())
    env = profile["environment"]
    allowed = {"LIBGL_ES": {"2"}, "LIBGL_GL": {"21"}, "LIBGL_BATCH": {"0", "1"}}
    if set(env) != set(allowed) or any(v not in allowed[k] for k, v in env.items()):
        raise ValueError("Profile must use supported, explicitly reviewed GL4ES settings")
    return profile


def verify_elf(path, abi):
    header = Path(path).read_bytes()[:20]
    expected_class = 2 if abi == "arm64-v8a" else 1
    if (len(header) < 20 or header[:4] != b"\x7fELF" or header[4] != expected_class
            or header[5] != 1 or int.from_bytes(header[16:18], "little") != 3
            or int.from_bytes(header[18:20], "little") != ABIS[abi]):
        raise ValueError(f"Not an Android {abi} shared ELF: {path}")


def fetch(lock, source):
    if not re.fullmatch(r"[0-9a-f]{40}", lock["commit"]):
        raise ValueError("Upstream must be pinned to a full commit SHA")
    if source.exists():
        actual = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
        if actual != lock["commit"]:
            raise ValueError("Cached source has wrong revision; remove .work and retry")
        # Ignore build products, but never compile edited tracked upstream sources.
        run("git", "-C", source, "diff", "--exit-code", "HEAD")
        return
    source.mkdir(parents=True)
    run("git", "-C", source, "init")
    run("git", "-C", source, "fetch", "--depth=1", lock["repository"], lock["commit"])
    run("git", "-C", source, "checkout", "--detach", "FETCH_HEAD")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=ABIS, default="arm64-v8a")
    parser.add_argument("--ndk", default=os.environ.get("ANDROID_NDK_HOME"))
    parser.add_argument("--profile", default="powervr-safe", choices=["powervr-safe", "powervr-batch-experimental"])
    parser.add_argument("--jobs", type=int, default=min(os.cpu_count() or 2, 8))
    args = parser.parse_args()
    if not args.ndk:
        parser.error("Set ANDROID_NDK_HOME or supply --ndk (tested CI version: 27.2.12479018)")
    toolchain = Path(args.ndk).resolve() / "build/cmake/android.toolchain.cmake"
    if not toolchain.is_file():
        parser.error(f"Missing NDK toolchain: {toolchain}")
    if args.jobs < 1:
        parser.error("--jobs must be positive")
    if not shutil.which("cmake") or not shutil.which("git"):
        parser.error("cmake and git must be installed")
    profile_path = ROOT / "profiles" / (args.profile + ".json")
    profile = load_profile(profile_path)
    lock = json.loads((ROOT / "upstream.json").read_text())
    # Separate source trees: upstream writes libraries into its source directory.
    source = ROOT / ".work" / args.abi / "source"
    build = ROOT / ".work" / args.abi / "build"
    fetch(lock, source)
    run("cmake", "-S", source, "-B", build,
        f"-DCMAKE_TOOLCHAIN_FILE={toolchain}", f"-DANDROID_ABI={args.abi}",
        "-DANDROID_PLATFORM=android-21", "-DCMAKE_BUILD_TYPE=Release",
        "-DANDROID=ON", "-DNOX11=ON", "-DGLX_STUBS=ON", "-DDEFAULT_ES=2",
        "-DUSE_ANDROID_LOG=ON", "-DCMAKE_C_FLAGS_RELEASE=-O2 -DNDEBUG")
    run("cmake", "--build", build, "--target", "GL", "--parallel", args.jobs)
    library = source / "lib/libGL.so"
    verify_elf(library, args.abi)
    output = ROOT / "artifacts" / args.abi / args.profile
    output.mkdir(parents=True, exist_ok=True)
    shutil.copy2(library, output / "libGL.so")
    shutil.copy2(source / "LICENSE", output / "GL4ES-LICENSE.txt")
    shutil.copy2(source / "USAGE.md", output / "GL4ES-USAGE.md")
    shutil.copy2(profile_path, output / "profile.json")
    (output / "launcher.env").write_text("".join(f"{k}={v}\n" for k, v in sorted(profile["environment"].items())))
    metadata = {"upstream": lock, "abi": args.abi, "android_api": 21,
                "profile": args.profile, "sha256": hashlib.sha256(library.read_bytes()).hexdigest(),
                "ndk": (Path(args.ndk) / "source.properties").read_text(),
                "status": "experimental; launcher and device validation required"}
    (output / "build.json").write_text(json.dumps(metadata, indent=2) + "\n")
    with zipfile.ZipFile(output.parent / (args.profile + ".zip"), "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(output.iterdir()):
            archive.write(path, path.name)
    print(f"Packaged {output.parent / (args.profile + '.zip')}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"Build failed: {error}", file=sys.stderr)
        sys.exit(1)
