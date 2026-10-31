#!/usr/bin/env python3
"""Fail APK publication if any packaged ABI lacks the standalone Rust launcher core."""
from pathlib import Path
import struct
import sys
import zipfile

apks = list(Path(sys.argv[1]).rglob('*.apk'))
assert apks, 'No APKs found'
for apk in apks:
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        abis = {n.split('/')[1] for n in names if n.startswith('lib/') and n.endswith('.so')}
        assert abis, apk
        for abi in abis:
            data = archive.read(f'lib/{abi}/libgurkha_core.so')
            assert data[:4] == b'\x7fELF'
            assert struct.unpack_from('<H', data, 18)[0] == {'armeabi-v7a': 40, 'arm64-v8a': 183, 'x86': 3, 'x86_64': 62}[abi]
    print(f'PASS: {apk.name}: Rust launcher core in {sorted(abis)}')
