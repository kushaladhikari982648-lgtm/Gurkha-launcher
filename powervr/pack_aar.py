#!/usr/bin/env python3
"""Pack both verified unified builds into Gurkha's built-in native AAR.

Run after tools/build_unified.py for arm64-v8a and armeabi-v7a. The upstream
ZIPs (including full corresponding source) remain separate distributable artifacts.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'tools'))
from build import ABIS


def verified_library(archive, abi, locks):
    metadata = json.loads(archive.read('build.json'))
    if metadata['abi'] != abi or metadata['base'] != locks['krypton'] or metadata['ltw_port_origin'] != locks['ltw']:
        raise ValueError('Build metadata does not match pinned ABI/sources')
    library = archive.read('libpowervr.so')
    if hashlib.sha256(library).hexdigest() != metadata['sha256']:
        raise ValueError('PowerVR library checksum mismatch')
    expected_class = 2 if abi == 'arm64-v8a' else 1
    if (len(library) < 20 or library[:4] != b'\x7fELF' or library[4:6] != bytes([expected_class, 1])
            or int.from_bytes(library[16:18], 'little') != 3
            or int.from_bytes(library[18:20], 'little') != ABIS[abi]):
        raise ValueError('PowerVR library has wrong ELF architecture/type')
    if not archive.getinfo('corresponding-source.tar.gz').file_size:
        raise ValueError('Modified corresponding source is missing')
    return library, metadata


def pack(artifacts, output):
    locks = json.loads((ROOT / 'backends.json').read_text())
    builds = {}
    for abi in ABIS:
        with zipfile.ZipFile(Path(artifacts) / abi / 'powervr-unified.zip') as archive:
            builds[abi] = verified_library(archive, abi, locks)
    jar = io.BytesIO()
    with zipfile.ZipFile(jar, 'w') as z:
        z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n')
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('AndroidManifest.xml', '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.gurkha.powervr" />\n')
        z.writestr('classes.jar', jar.getvalue())
        z.writestr('R.txt', '')
        z.writestr('META-INF/com/android/build/gradle/aar-metadata.properties',
                   'aarFormatVersion=1.0\naarMetadataVersion=1.0\nminCompileSdk=1\nminAndroidGradlePluginVersion=1.0.0\n')
        for abi, (library, metadata) in builds.items():
            z.writestr(f'jni/{abi}/libpowervr.so', library)
            z.writestr(f'assets/powervr/{abi}-build.json', json.dumps(metadata, indent=2))
        for filename in ['NOTICE.txt', 'source-lock.json', 'LICENSE']:
            z.write(ROOT / filename, f'assets/powervr/{filename}')
        for license_file in sorted((ROOT / 'licenses').glob('*.txt')):
            z.write(license_file, f'assets/powervr/licenses/{license_file.name}')
    return builds


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifacts', type=Path, default=ROOT / 'artifacts')
    parser.add_argument('--output', type=Path, default=ROOT.parent / 'ZalithLauncher/libs/powervr-release.aar')
    args = parser.parse_args()
    builds = pack(args.artifacts, args.output)
    tree = subprocess.check_output(['git', 'rev-parse', 'HEAD:powervr'], cwd=ROOT.parent, text=True).strip()
    lock = json.loads((ROOT / 'source-lock.json').read_text())
    run = os.environ.get('GITHUB_RUN_ID')
    run_url = (f"{os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{run}"
               if run else 'local build')
    lines = [
        'Proton Renderer (experimental): PowerVR Unified r2, a single Krypton-based libpowervr.so with an LTW-derived feature port.',
        f"Source: {lock['repository']} @ {lock['commit']} (revision {lock.get('revision', 'unified')})",
        f'Source-tree: {tree}', f'CI run: {run_url}',
        'Build: NDK 28.2.13676358, CMake 3.31.6, android-21, tools/build_unified.py.',
        'ABIs: arm64-v8a armeabi-v7a (no x86 binaries).',
        'Licenses: upstream Krypton/bundled licenses; LTW-derived module LGPL-3.0-only; tooling MIT.',
        'Corresponding source: powervr-unified-sources artifact of the same CI run; also attached to tagged releases.',
    ]
    for abi, (_, metadata) in builds.items():
        lines.append(f"SHA256 ({abi}): {metadata['sha256']}")
    args.output.with_suffix('.txt').write_text('\n'.join(lines) + '\n')
    print(f'Packed {args.output} with both ARM ABIs')


if __name__ == '__main__':
    main()
