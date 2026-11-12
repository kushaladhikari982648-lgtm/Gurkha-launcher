#!/usr/bin/env python3
"""Stage verified debug APKs on an empty, explicitly prepared draft; never publish/overwrite."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''): h.update(block)
    return h.hexdigest()


def require(ok, message):
    if not ok: raise RuntimeError(message)


def main():
    props = dict(line.split('=', 1) for line in Path('ZalithLauncher/gradle.properties').read_text().splitlines()
                 if line and not line.startswith('#') and '=' in line)
    version = props['launcher_version_name']
    require(re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+-(?:alpha|beta)\.[0-9]+', version), 'Expected alpha or beta development version')
    tag = 'preview-v' + version
    # Ordinary development pushes must not fail or replace an already published release.
    # Only an explicitly prepared draft is eligible. API/auth failures still fail the job.
    releases = json.loads(run('gh', 'api', 'repos/' + os.environ['GH_REPO'] + '/releases?per_page=100'))
    requested = next((r for r in releases if r['tag_name'] == tag and r['draft']), None)
    if requested is None:
        print('No requested draft for ' + tag + '; keeping APKs as workflow artifacts only')
        return
    draft = json.loads(run('gh', 'release', 'view', tag, '--json', 'isDraft,targetCommitish,assets'))
    require(draft['isDraft'] and draft['targetCommitish'] == os.environ['GITHUB_SHA'], 'Wrong/non-draft release target')
    require(not draft['assets'], 'Refusing to replace draft assets')
    info = json.loads(Path('release-extra/NATIVE_BUILD_INFO.json').read_text())
    lock = json.loads(Path('tools/renderer-lock.json').read_text())
    require(info['ltw']['source_commit'] == lock['commit'], 'Wrong renderer revision')
    require(info['launcher_commit'] == os.environ['GITHUB_SHA'], 'Native/source mismatch')
    require(info['workflow_run'] == os.environ['GITHUB_RUN_ID'], 'Native artifacts from a different run')
    require(digest('release-extra/ltw-release.aar') == info['ltw']['sha256'], 'Renderer AAR checksum mismatch')
    require(digest('release-extra/launcher-core-release.aar') == info['launcher-core']['sha256'], 'Core AAR checksum mismatch')
    require(digest('release-extra/rustgl-release.aar') == info['rustgl']['sha256'], 'RustGL AAR checksum mismatch')
    with zipfile.ZipFile('release-extra/LTW-corresponding-source.zip') as archive:
        require(any(n.endswith('/CMakeLists.txt') for n in archive.namelist()), 'Incomplete renderer source archive')
    tools = sorted(Path(os.environ['ANDROID_HOME']).glob('build-tools/*/apksigner'),
                   key=lambda p: tuple(map(int, re.findall(r'\d+', p.parent.name))))
    require(tools, 'APK verifier unavailable')
    signer, aapt = tools[-1], tools[-1].parent / 'aapt'
    abis = {'arm': {'armeabi-v7a'}, 'arm64': {'arm64-v8a'}, 'x86': {'x86'}, 'x86_64': {'x86_64'},
            'all': {'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'}}
    out = Path('preview-output'); out.mkdir(exist_ok=True)
    require(not list(out.iterdir()), 'Staging directory must be empty')
    records = []
    for arch, wanted in abis.items():
        files = list((Path('downloaded-apks') / f'GurkhaLauncher(Debug) {arch}').rglob('*.apk'))
        require(len(files) == 1, f'Expected exactly one {arch} APK')
        apk = files[0]
        badging = run(str(aapt), 'dump', 'badging', str(apk))
        package = next(line for line in badging.splitlines() if line.startswith('package:'))
        values = dict(re.findall(r"(\w+)='([^']*)'", package))
        require(values.get('name') == 'com.gurkha.launcher.debug' and 'application-debuggable' in badging, 'Not a debug preview')
        require(values.get('versionName') == version and values.get('versionCode') == props['launcher_version_code'], 'APK version mismatch')
        report = run(str(signer), 'verify', '--verbose', '--print-certs', str(apk))
        certificates = {line.split('certificate SHA-256 digest:', 1)[1].strip().lower() for line in report.splitlines()
                        if re.match(r'^(?:V\d+(?:\.\d+)? )?Signer[ :#(]', line) and 'certificate SHA-256 digest:' in line}
        require(len(certificates) == 1 and all(re.fullmatch('[0-9a-f]{64}', c) for c in certificates), 'Unexpected APK signer')
        with zipfile.ZipFile(apk) as archive:
            actual = {n.split('/')[1] for n in archive.namelist() if n.startswith('lib/') and n.endswith('.so')}
            require(actual == wanted, f'Wrong ABI set for {arch}')
            for abi in wanted:
                for library in ('libltw.so', 'libltw_sdl_egl.so', 'libgurkha_core.so', 'librust_gl.so'):
                    require(f'lib/{abi}/{library}' in archive.namelist(), f'Missing {abi}/{library}')
        suffix = {'arm': 'armeabi-v7a', 'arm64': 'arm64-v8a', 'all': 'universal'}.get(arch, arch)
        dest = out / f'GurkhaLauncher-{version}-debug-{suffix}.apk'
        shutil.copyfile(apk, dest)
        records.append(dict(file=dest.name, sha256=digest(dest), bytes=dest.stat().st_size, signer_sha256=next(iter(certificates))))
    require(len({r['signer_sha256'] for r in records}) == 1, 'Inconsistent signing certificates')
    for name in ('NATIVE_BUILD_INFO.json', 'LTW-corresponding-source.zip', 'ltw-release.aar', 'launcher-core-release.aar', 'Rust-core-corresponding-source.zip', 'rustgl-release.aar', 'RustGL-corresponding-source.zip'):
        shutil.copyfile(Path('release-extra') / name, out / name)
    build = dict(version=version, version_code=int(props['launcher_version_code']), commit=os.environ['GITHUB_SHA'],
                 run=os.environ['GITHUB_RUN_ID'], package='com.gurkha.launcher.debug', build_type='debug', apks=records)
    (out / 'BUILD_INFO.json').write_text(json.dumps(build, indent=2)+'\n')
    (out / 'SHA256SUMS.txt').write_text('\n'.join(f'{digest(p)}  {p.name}' for p in sorted(out.iterdir()))+'\n')
    run('gh', 'release', 'upload', tag, *[str(p) for p in out.iterdir()])
    run('gh', 'release', 'edit', tag, '--notes-file', f'.github/release-notes/{tag}.md')
    print('::notice title=Verified APK signatures and checksums::' + json.dumps(build))
    print(f'Verified and attached {len(list(out.iterdir()))} assets to {tag}; release remains a draft')


if __name__ == '__main__': main()
