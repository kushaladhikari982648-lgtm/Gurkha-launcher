#!/usr/bin/env python3
"""Fail packaging if an ARM APK omits PowerVR, or an x86 APK contains it."""
from pathlib import Path
import sys
import zipfile


def check(apk):
    with zipfile.ZipFile(apk) as z:
        names = set(z.namelist())
        abis = {n.split('/')[1] for n in names if n.startswith('lib/') and n.endswith('.so')}
        if not abis:
            raise ValueError(f'{apk}: no native libraries')
        for abi in abis:
            member = f'lib/{abi}/libpowervr.so'
            expected = abi in {'arm64-v8a', 'armeabi-v7a'}
            if (member in names) != expected:
                raise ValueError(f'{apk}: wrong PowerVR packaging for {abi}')
            if expected and z.read(member)[:4] != b'\x7fELF':
                raise ValueError(f'{apk}: invalid PowerVR ELF')
    print(f'{apk}: PowerVR packaging verified for {sorted(abis)}')


if __name__ == '__main__':
    apks = sorted(Path(sys.argv[1]).rglob('*.apk'))
    if not apks:
        raise SystemExit('No APKs to verify')
    for apk in apks:
        check(apk)
