#!/usr/bin/env python3
"""Compare real client bytecode LWJGL references against the actual APK classpath.
Downloads only pinned public client artifacts in CI, verifies their hashes, never executes
Minecraft and never republishes game binaries. Passing is NOT an Android gameplay test.
"""
import argparse
import hashlib
import io
import json
import lzma
from pathlib import Path
import struct
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


class Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def take(self, count):
        if self.pos + count > len(self.data):
            raise ValueError('Truncated class file')
        result = self.data[self.pos:self.pos + count]
        self.pos += count
        return result

    def u1(self): return self.take(1)[0]
    def u2(self): return struct.unpack('>H', self.take(2))[0]
    def u4(self): return struct.unpack('>I', self.take(4))[0]


def read_class(data):
    r = Reader(data)
    if r.u4() != 0xcafebabe:
        raise ValueError('Not a Java class')
    r.take(4)
    cp = [None] * r.u2()
    i = 1
    while i < len(cp):
        tag = r.u1()
        if tag == 1: cp[i] = (tag, r.take(r.u2()).decode('utf-8', errors='replace'))
        elif tag in (3, 4): r.take(4)
        elif tag in (5, 6): r.take(8); i += 1
        elif tag in (7, 8, 16, 19, 20): cp[i] = (tag, r.u2())
        elif tag in (9, 10, 11, 12, 17, 18): cp[i] = (tag, r.u2(), r.u2())
        elif tag == 15: r.take(3)
        else: raise ValueError(f'Unknown constant pool tag {tag}')
        i += 1
    def text(index): return cp[index][1]
    def classname(index): return text(cp[index][1]) if index else None
    def attributes():
        for _ in range(r.u2()):
            r.u2()
            r.take(r.u4())
    r.u2()  # access flags
    name, parent = classname(r.u2()), classname(r.u2())
    interfaces = [classname(r.u2()) for _ in range(r.u2())]
    fields, methods = set(), set()
    for members in (fields, methods):
        for _ in range(r.u2()):
            r.u2()
            members.add((text(r.u2()), text(r.u2())))
            attributes()
    references, classes = set(), set()
    for item in cp:
        if not item: continue
        if item[0] == 7:
            owner = text(item[1])
            if owner.startswith('org/lwjgl/'): classes.add(owner)
        elif item[0] in (9, 10, 11):
            owner = classname(item[1])
            if owner.startswith('org/lwjgl/'):
                nt = cp[item[2]]
                references.add(('field' if item[0] == 9 else 'method', owner, text(nt[1]), text(nt[2])))
    return name, dict(parent=parent, interfaces=interfaces, fields=fields, methods=methods), references, classes


def classpath(apk, backend, java_major):
    prefix = 'assets/components/'
    with zipfile.ZipFile(apk) as package:
        available = {name[:-3] if name.endswith('.xz') else name: name for name in package.namelist()
                     if name.startswith(prefix) and (name.endswith('.jar') or name.endswith('.jar.xz'))}
        if backend == 'legacy':
            chosen = sorted(n for n in available if n.startswith(prefix + 'lwjgl3/') and not n.endswith('lwjgl-glfw-stub.jar'))
        else:
            chosen = sorted(n for n in available if n.startswith(prefix + 'lwjgl3-sdl/'))
            patches = prefix + 'lwjgl3-sdl/lwjgl-sdl-patches.jar'
            if backend == 'sdl':
                chosen.remove(patches)
                chosen.insert(0, patches)
            else:
                chosen = [prefix + 'lwjgl3/lwjgl-glfw-stub.jar'] + [n for n in chosen
                    if not n.endswith(('lwjgl-sdl-patches.jar', 'lwjgl-sdl.jar'))]
        registry = {}
        for name in chosen:
            packed = available[name]
            data = package.read(packed)
            if packed.endswith('.xz'): data = lzma.decompress(data)
            with zipfile.ZipFile(io.BytesIO(data)) as jar:
                # Honor the same multi-release class choice as the selected Java runtime.
                versions = {}
                manifest = jar.read('META-INF/MANIFEST.MF').decode('utf-8', errors='replace') if 'META-INF/MANIFEST.MF' in jar.namelist() else ''
                multi_release = 'multi-release: true' in manifest.lower()
                for entry in jar.namelist():
                    if not entry.endswith('.class'): continue
                    effective, release = entry, 0
                    if entry.startswith('META-INF/versions/'):
                        if not multi_release: continue
                        parts = entry.split('/', 3)
                        release = int(parts[2])
                        effective = parts[3]
                        if release > java_major: continue
                    if effective.startswith('org/lwjgl/') and effective.endswith('.class'):
                        if effective not in versions or release > versions[effective][0]:
                            versions[effective] = (release, entry)
                for _, entry in versions.values():
                    owner, info, _, _ = read_class(jar.read(entry))
                    registry.setdefault(owner, info)  # First class on the launch classpath wins
        if not registry:
            raise ValueError('Empty LWJGL classpath')
        return registry


def resolves(registry, kind, owner, name, desc, visited=None):
    visited = set() if visited is None else visited
    if owner is None or owner in visited: return False
    visited.add(owner)
    # Object methods may be referenced with a LWJGL subclass as owner.
    if owner == 'java/lang/Object':
        return kind == 'method' and (name, desc) in {
            ('toString', '()Ljava/lang/String;'), ('equals', '(Ljava/lang/Object;)Z'),
            ('hashCode', '()I'), ('getClass', '()Ljava/lang/Class;'), ('clone', '()Ljava/lang/Object;')}
    if owner == 'java/lang/Enum':
        # Platform/Architecture are enums. These methods are inherited from the JDK,
        # which is intentionally not bundled into the APK's LWJGL class registry.
        if kind == 'method' and (name, desc) in {
                ('name', '()Ljava/lang/String;'), ('ordinal', '()I'),
                ('toString', '()Ljava/lang/String;'), ('getDeclaringClass', '()Ljava/lang/Class;'),
                ('compareTo', '(Ljava/lang/Enum;)I'), ('compareTo', '(Ljava/lang/Object;)I')}:
            return True
        return resolves(registry, kind, 'java/lang/Object', name, desc, visited)
    info = registry.get(owner)
    if info is None: return False
    if (name, desc) in info['fields' if kind == 'field' else 'methods']: return True
    if name == '<init>': return False  # Constructors are not inherited
    return any(resolves(registry, kind, p, name, desc, visited) for p in [info['parent']] + info['interfaces'])


def audit(client, registry):
    references, classes = set(), set()
    with zipfile.ZipFile(client) as game:
        for entry in game.namelist():
            if not entry.endswith('.class'): continue
            _, _, refs, types = read_class(game.read(entry))
            references.update(refs)
            classes.update(types)
    missing = [f'class {c}' for c in sorted(classes - registry.keys())]
    missing += [' '.join(ref) for ref in sorted(references) if not resolves(registry, *ref)]
    return len(references), missing


def download_client(spec, cache):
    path = cache / (spec['sha1'] + '.jar')
    def valid():
        if not path.is_file() or path.stat().st_size != spec['size']: return False
        with path.open('rb') as f:
            digest = hashlib.sha1()
            for block in iter(lambda: f.read(1024 * 1024), b''): digest.update(block)
        return digest.hexdigest() == spec['sha1']
    if not valid():
        if not spec['url'].startswith('https://piston-data.mojang.com/v1/objects/'):
            raise ValueError('Unexpected client download host')
        temp = path.with_suffix('.part')
        with urllib.request.urlopen(spec['url'], timeout=90) as response, temp.open('wb') as output:
            while True:
                block = response.read(1024 * 1024)
                if not block: break
                output.write(block)
        temp.replace(path)
    if not valid(): raise ValueError('Client SHA-1/size mismatch')
    return path


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--cache', type=Path, default=Path('.cache/minecraft-api-audit'))
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    reports = []
    registries = {}
    for fixture in json.loads((ROOT / 'tests/fixtures/modern_versions.json').read_text()):
        backend = fixture['backend']
        result = dict(version=fixture['version'], java=fixture['java'], requested_lwjgl=fixture['lwjgl'], backend=backend)
        try:
            if backend not in registries: registries[backend] = classpath(args.apk, backend, fixture['java'])
            count, missing = audit(download_client(fixture['client'], args.cache), registries[backend])
            result.update(checked_references=count, missing=missing)
        except Exception as error:
            result.update(checked_references=0, missing=[], error=f'{type(error).__name__}: {error}')
        reports.append(result)
        detail = json.dumps(result)
        Path('lwjgl-api-report.json').write_text(json.dumps(reports, indent=2) + '\n')
        print(detail)
        print(('::error' if result['missing'] or 'error' in result else '::notice') + ' title=Minecraft LWJGL API audit::' +
              detail.replace('%', '%25').replace('\n', '%0A'))
    Path('lwjgl-api-report.json').write_text(json.dumps(reports, indent=2) + '\n')
    raise SystemExit(1 if any(r['missing'] or 'error' in r for r in reports) else 0)
