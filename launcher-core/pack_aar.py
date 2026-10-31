#!/usr/bin/env python3
"""Package the independent Rust launcher core for all four Android ABIs."""
from pathlib import Path
import io
import struct
import zipfile

ROOT = Path(__file__).resolve().parent
ABIS = {'arm64-v8a': ('aarch64-linux-android', 183), 'armeabi-v7a': ('armv7-linux-androideabi', 40),
        'x86': ('i686-linux-android', 3), 'x86_64': ('x86_64-linux-android', 62)}


def main():
    entries = {}
    for abi, (triple, machine) in ABIS.items():
        data = (ROOT / 'target' / triple / 'release/libgurkha_core.so').read_bytes()
        assert data[:4] == b'\x7fELF' and struct.unpack_from('<H', data, 18)[0] == machine, abi
        entries[f'jni/{abi}/libgurkha_core.so'] = data
    entries['AndroidManifest.xml'] = b'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.gurkha.core" />'
    jar = io.BytesIO()
    with zipfile.ZipFile(jar, 'w') as archive:
        archive.writestr(zipfile.ZipInfo('META-INF/MANIFEST.MF'), b'Manifest-Version: 1.0\n')
    entries['classes.jar'] = jar.getvalue()
    entries['R.txt'] = b''
    entries['META-INF/com/android/build/gradle/aar-metadata.properties'] = b'aarFormatVersion=1.0\naarMetadataVersion=1.0\nminCompileSdk=1\n'
    output = ROOT.parent / 'ZalithLauncher/libs/launcher-core-release.aar'
    with zipfile.ZipFile(output, 'w') as archive:
        for name, data in sorted(entries.items()):
            entry = zipfile.ZipInfo(name)
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, data)
    print(f'Packed {output.name}: {output.stat().st_size} bytes')


if __name__ == '__main__':
    main()
