#!/usr/bin/env python3
"""Check exported bridge symbols and Android-system-only shared dependencies."""
from pathlib import Path
import re
import subprocess
import sys


def verify(readelf, library):
    symbols = subprocess.check_output([str(readelf), '--dyn-syms', '--wide', str(library)], text=True)
    defined = {line.split()[-1] for line in symbols.splitlines() if len(line.split()) >= 8 and line.split()[6] != 'UND'}
    for name in ['glXGetProcAddress', 'glGetString', 'glDrawArrays', 'glMultiDrawArraysIndirect', 'glMultiDrawElementsIndirect']:
        if name not in defined:
            raise ValueError(f'{library}: missing export {name}')
    dynamic = subprocess.check_output([str(readelf), '-d', str(library)], text=True)
    needed = set(re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic))
    allowed = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libEGL.so', 'libGLESv2.so', 'libGLESv3.so', 'libz.so'}
    if needed - allowed:
        raise ValueError(f'{library}: unbundled dependencies {needed - allowed}')
    print(f'{library}: bridge exports and dependencies verified ({sorted(needed)})')


if __name__ == '__main__':
    for abi in ['arm64-v8a', 'armeabi-v7a']:
        verify(Path(sys.argv[1]), Path(__file__).parent / 'artifacts' / abi / 'unified/libpowervr.so')
