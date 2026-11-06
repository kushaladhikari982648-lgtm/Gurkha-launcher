"""Validate freshly compiled native dependencies and write release provenance."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
lock = json.loads((root / 'tools/renderer-lock.json').read_text())
abis = {'armeabi-v7a': (1, 40), 'arm64-v8a': (2, 183), 'x86': (1, 3), 'x86_64': (2, 62)}
records = {}
for stem, library in [('ltw', 'libltw.so'), ('launcher-core', 'libgurkha_core.so')]:
    path = root / f'ZalithLauncher/libs/{stem}-release.aar'
    with zipfile.ZipFile(path) as archive:
        for abi, (bits, machine) in abis.items():
            data = archive.read(f'jni/{abi}/{library}')
            assert data[:4] == b'\x7fELF' and data[4] == bits and data[5] == 1
            assert struct.unpack_from('<H', data, 18)[0] == machine
    records[stem] = dict(sha256=hashlib.sha256(path.read_bytes()).hexdigest(), abis=list(abis))
records['ltw']['source_commit'] = lock['commit']
records['ltw']['repository'] = lock['repository']
records['launcher-core']['source_tree'] = subprocess.check_output(['git', 'rev-parse', 'HEAD:launcher-core'], text=True).strip()
records['workflow_run'] = os.environ['GITHUB_RUN_ID']
records['launcher_commit'] = os.environ['GITHUB_SHA']
extra = root / 'release-extra'
extra.mkdir(exist_ok=True)
(extra / 'NATIVE_BUILD_INFO.json').write_text(json.dumps(records, indent=2)+'\n')
for stem in ('ltw', 'launcher-core'):
    (root / f'ZalithLauncher/libs/{stem}-release.txt').write_text(json.dumps(records[stem], indent=2)+'\n')
shutil.copyfile(root / 'ZalithLauncher/libs/ltw-release.aar', extra / 'ltw-release.aar')
print('Verified LTW and Rust launcher core for all four ABIs')
