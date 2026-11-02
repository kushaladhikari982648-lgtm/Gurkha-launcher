#!/usr/bin/env python3
"""Validate and stage already-tested debug APKs; never rebuild or bypass signed-release gates."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REQUEST = ROOT / '.github/release-requests/debug-preview.json'
ARCHES = {'all': {'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'}, 'arm': {'armeabi-v7a'},
          'arm64': {'arm64-v8a'}, 'x86': {'x86'}, 'x86_64': {'x86_64'}}
MACHINES = {'armeabi-v7a': 40, 'arm64-v8a': 183, 'x86': 3, 'x86_64': 62}


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def api(path):
    return json.loads(run('gh', 'api', path))


def request():
    data = json.loads(REQUEST.read_text())
    require(type(data['run_id']) is int and data['run_id'] > 0, 'Invalid run ID')
    for key in ('commit', 'tested_merge_commit', 'source_tree'):
        require(re.fullmatch('[0-9a-f]{40}', data[key]), f'Invalid {key}')
    require(re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+-[A-Za-z0-9.-]+', data['version']), 'Preview must have a prerelease version')
    require(data['tag'] == 'preview-v' + data['version'], 'Preview tag must not occupy the signed-release tag')
    require(type(data['version_code']) is int and data['version_code'] > 0, 'Invalid version code')
    return data


def validate(data):
    draft = json.loads(run('gh', 'release', 'view', data['tag'], '--json', 'id,isDraft,tagName,targetCommitish,assets'))
    require(draft['id'] == data['draft_id'] and draft['isDraft'], 'Expected the explicitly prepared draft release')
    require(draft['targetCommitish'] == data['commit'] and draft['tagName'] == data['tag'], 'Draft target mismatch')
    require(not draft['assets'], 'Draft already contains assets; inspect it rather than overwriting')
    repo = os.environ['GITHUB_REPOSITORY']
    require(repo == 'kushaladhikari982648-lgtm/Gurkha-launcher', 'Wrong repository')
    build = api(f'repos/{repo}/actions/runs/{data["run_id"]}')
    require(build['status'] == 'completed' and build['conclusion'] == 'success', 'Source build did not succeed')
    require(build['head_sha'] == data['commit'], 'Run/source commit mismatch')
    require(build['head_repository']['full_name'] == repo, 'Do not publish fork artifacts')
    require(build['path'] == '.github/workflows/android.yml', 'Unexpected build workflow')
    head = api(f'repos/{repo}/git/commits/{data["commit"]}')
    tested = api(f'repos/{repo}/git/commits/{data["tested_merge_commit"]}')
    require(head['tree']['sha'] == tested['tree']['sha'] == data['source_tree'], 'Tested tree differs from tagged source')
    require(data['commit'] in [p['sha'] for p in tested['parents']], 'Tested merge does not include source commit')
    jobs = api(f'repos/{repo}/actions/runs/{data["run_id"]}/jobs?per_page=100')['jobs']
    completed = {j['name'] for j in jobs if j['conclusion'] == 'success'}
    expected = {f'BuildDebug ({arch})' for arch in ARCHES}
    expected.update({'LauncherUiChecks', 'LauncherCoreChecks', 'RustGLChecks', 'PowerVRRenderer', 'CurseForgeChecks'})
    require(expected <= completed, f'Missing successful checks: {sorted(expected - completed)}')
    # This publication is deliberately credential-free: require the recorded disabled-CF build.
    cf = next(j for j in jobs if j['name'] == 'CurseForgeChecks')
    annotations = api(f'repos/{repo}/check-runs/{cf["id"]}/annotations')
    require(any(a['title'] == 'CurseForge API: missing' for a in annotations), 'Expected a build with CurseForge disabled')
    artifacts = api(f'repos/{repo}/actions/runs/{data["run_id"]}/artifacts?per_page=100')['artifacts']
    available = {a['name'] for a in artifacts if not a['expired']}
    needed = {f'GurkhaLauncher(Debug) {arch}' for arch in ARCHES} | {'powervr-unified-sources'}
    require(needed <= available, 'APK/source artifacts missing or expired')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
        for key in ('run_id', 'commit', 'tag', 'version'):
            out.write(f'{key}={data[key]}\n')
    print('Verified successful checks, source identity and all six required artifacts')


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def inspect_apk(path, arch, data, aapt, apksigner):
    badging = run(str(aapt), 'dump', 'badging', str(path))
    package_line = next(line for line in badging.splitlines() if line.startswith('package:'))
    values = dict(re.findall(r"(\w+)='([^']*)'", package_line))
    require(values.get('name') == 'com.gurkha.launcher.debug', 'This is not a debug-package APK')
    require(values.get('versionName') == data['version'], 'APK version name mismatch')
    require(values.get('versionCode') == str(data['version_code']), 'APK version code mismatch')
    require('application-debuggable' in badging, 'Expected a debuggable preview, not a production build')
    signature = run(str(apksigner), 'verify', '--print-certs', str(path))
    # New build-tools label v3/v3.1 signers with SDK ranges rather than "#1", and
    # can report the same certificate once per range. Require one unique certificate.
    digest_lines = [line for line in signature.splitlines()
                    if line.startswith('Signer') and 'certificate SHA-256 digest:' in line]
    fingerprints = {line.split('certificate SHA-256 digest:', 1)[1].strip().replace(':', '').lower()
                    for line in digest_lines}
    require(len(fingerprints) == 1 and all(re.fullmatch('[0-9a-f]{64}', f) for f in fingerprints),
            f'Expected one verified APK signer; public fingerprint lines: {digest_lines!r}')
    with zipfile.ZipFile(path) as archive:
        abis = {n.split('/')[1] for n in archive.namelist() if n.startswith('lib/') and n.endswith('.so')}
        require(abis == ARCHES[arch], f'Wrong ABIs for {arch}: {abis}')
        for abi in abis:
            for library in ('libgurkha_core.so', 'librust_gl.so'):
                blob = archive.read(f'lib/{abi}/{library}')
                require(blob[:4] == b'\x7fELF' and struct.unpack_from('<H', blob, 18)[0] == MACHINES[abi], f'Invalid {library}/{abi}')
    return next(iter(fingerprints))


def stage(data, downloads, sources, output):
    tools = sorted((Path(os.environ['ANDROID_HOME']) / 'build-tools').glob('*/apksigner'),
                   key=lambda p: tuple(int(v) for v in re.findall(r'\d+', p.parent.name)))
    require(tools, 'Android APK verification tools unavailable')
    apksigner = tools[-1]
    aapt = apksigner.parent / 'aapt'
    require(aapt.is_file(), 'aapt unavailable')
    output.mkdir(parents=True, exist_ok=True)
    require(not list(output.iterdir()), 'Staging output must be empty')
    records = []
    for arch in ARCHES:
        matches = list((downloads / f'GurkhaLauncher(Debug) {arch}').rglob('*.apk'))
        require(len(matches) == 1, f'Expected one {arch} APK')
        original = matches[0]
        signer = inspect_apk(original, arch, data, aapt, apksigner)
        suffix = {'all': 'universal', 'arm': 'armeabi-v7a', 'arm64': 'arm64-v8a'}.get(arch, arch)
        destination = output / f'GurkhaLauncher-{data["version"]}-debug-{suffix}.apk'
        shutil.copyfile(original, destination)
        records.append({'file': destination.name, 'sha256': sha256(destination), 'bytes': destination.stat().st_size,
                        'abis': sorted(ARCHES[arch]), 'certificate_sha256': signer})
    require(len({r['certificate_sha256'] for r in records}) == 1, 'APK variants use different signing certificates')
    for abi in ('arm64-v8a', 'armeabi-v7a'):
        source = sources / abi / 'powervr-unified.zip'
        require(source.is_file(), f'Corresponding PowerVR source missing for {abi}')
        shutil.copyfile(source, output / f'powervr-unified-{abi}.zip')
    info = dict(data, build_type='DEBUG', package='com.gurkha.launcher.debug', curseforge_enabled=False,
                ci_url=f'https://github.com/{os.environ["GITHUB_REPOSITORY"]}/actions/runs/{data["run_id"]}', apks=records)
    (output / 'BUILD_INFO.json').write_text(json.dumps(info, indent=2) + '\n')
    hashes = [f'{sha256(p)}  {p.name}' for p in sorted(output.iterdir()) if p.is_file()]
    (output / 'SHA256SUMS.txt').write_text('\n'.join(hashes) + '\n')
    notes = f'''# Gurkha Launcher {data['version']} — DEBUG preview

These are the existing APKs from [successful CI run {data['run_id']}]({info['ci_url']}).
They are **debug-key-signed, debuggable preview builds**, not production-signed release APKs.
All five variants passed CI and their signatures, package/version, ABI sets and Rust libraries
were checked before publication. The tested PR merge tree matches the tagged source tree.

## Included
- Minecraft-style home, settings, shared controls and launcher screens.
- Independent Rust launcher core for streaming SHA-256 and atomic settings/cache replacement.
- Standalone Freedreno renderer removed; old selections fall back to GL4ES.
- Built-in RustGL rendering fixes and low-end resource budgets.

## Downloads and caveats
- `armeabi-v7a`: 32-bit ARM Android. `arm64-v8a`: 64-bit ARM Android.
- `universal`: all four ABIs; x86 and x86_64 APKs are also provided.
- The package is `com.gurkha.launcher.debug`. These do not replace the production package or
  automatically migrate its data. Back up worlds/settings. Do not uninstall to fix a signing
  mismatch without a backup. Debug keys are development keys, not secure production identities.
- **CurseForge is disabled** in these APKs. Modrinth remains available. No newly supplied key
  was embedded; downloading an APK cannot update its build-time API key.
- Phone UI/accessibility and Helio A22/GE8300 gameplay testing are still needed. No promise of
  eliminating every lag spike or measured FPS improvement.

`SHA256SUMS.txt`, `BUILD_INFO.json`, and PowerVR corresponding-source archives are included.
No production auto-update metadata is published by this preview release.
'''
    (output.parent / 'preview-notes.md').write_text(notes)
    print('Verified and staged five debug APKs, corresponding sources, provenance and checksums')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['validate', 'stage'])
    parser.add_argument('--downloads', type=Path, default=Path('downloaded-apks'))
    parser.add_argument('--sources', type=Path, default=Path('downloaded-sources'))
    parser.add_argument('--output', type=Path, default=Path('preview-output'))
    args = parser.parse_args()
    data = request()
    if args.mode == 'validate':
        validate(data)
    else:
        stage(data, args.downloads, args.sources, args.output)
