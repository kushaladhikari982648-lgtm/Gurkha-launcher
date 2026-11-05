import io
import pathlib
import shutil
import struct
import subprocess
import tarfile
import tempfile
import unittest
import zipfile
from tools.audit_lwjgl_api import read_class, resolves, classpath

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / 'ZalithLauncher/src/main/java'
ABIS = {'armeabi-v7a': (1, 40), 'arm64-v8a': (2, 183), 'x86': (1, 3), 'x86_64': (2, 62)}


def elf_matches(header, abi):
    return (len(header) >= 20 and header[:4] == b'\x7fELF' and
            (header[4], struct.unpack_from('<H', header, 18)[0]) == ABIS[abi] and header[5] == 1)


class ModernCompatibilityTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac'), 'JDK required; executed by CompatibilityChecks CI')
    def test_actual_java_selection_and_native_guards(self):
        sources = list((ROOT / 'tests/java').rglob('*.java')) + [SRC / name for name in (
            'net/kdt/pojavlaunch/Architecture.java', 'net/kdt/pojavlaunch/multirt/Runtime.java',
            'net/kdt/pojavlaunch/utils/EglCapabilities.java', 'net/kdt/pojavlaunch/multirt/RuntimeSelection.java', 'net/kdt/pojavlaunch/utils/NativeLibraryInfo.java')]
        with tempfile.TemporaryDirectory() as output:
            subprocess.run(['javac', '-d', output] + list(map(str, sources)), check=True)
            subprocess.run(['java', '-ea', '-cp', output, 'net.kdt.pojavlaunch.multirt.CompatibilityTest'], check=True)

    def test_bytecode_auditor_and_inherited_members(self):
        root = 'org/lwjgl/example/Parent'
        child = 'org/lwjgl/example/Child'
        registry = {
            root: dict(parent='java/lang/Object', interfaces=[], fields={('VALUE', 'I')}, methods={('free', '()V'), ('<init>', '()V')}),
            child: dict(parent=root, interfaces=[], fields=set(), methods=set())}
        self.assertTrue(resolves(registry, 'method', child, 'free', '()V'))
        self.assertTrue(resolves(registry, 'field', child, 'VALUE', 'I'))
        self.assertFalse(resolves(registry, 'method', child, '<init>', '()V'))
        self.assertFalse(resolves(registry, 'method', child, 'free', '(J)V'))
        enum = 'org/lwjgl/system/Platform$Architecture'
        registry[enum] = dict(parent='java/lang/Enum', interfaces=[], fields=set(), methods=set())
        self.assertTrue(resolves(registry, 'method', enum, 'name', '()Ljava/lang/String;'))
        self.assertTrue(resolves(registry, 'method', enum, 'toString', '()Ljava/lang/String;'))
        self.assertFalse(resolves(registry, 'method', enum, 'nonexistent', '()V'))
        with self.assertRaises(ValueError): read_class(b'\xca\xfe\xba\xbe')
        for jar in ('lwjgl.jar', 'lwjgl-sdl.jar'):
            with zipfile.ZipFile(ROOT / 'ZalithLauncher/src/main/assets/components/lwjgl3-sdl' / jar) as archive:
                count = 0
                for name in archive.namelist():
                    if name.endswith('.class'):
                        owner, info, refs, types = read_class(archive.read(name))
                        self.assertTrue(name.endswith(owner + '.class'))
                        count += 1
                self.assertGreater(count, 100)

    def test_multi_release_classpath_skips_directory_entries(self):
        core = ROOT / 'ZalithLauncher/src/main/assets/components/lwjgl3-sdl/lwjgl.jar'
        with tempfile.TemporaryDirectory() as directory:
            empty = io.BytesIO()
            with zipfile.ZipFile(empty, 'w'): pass
            apk = pathlib.Path(directory) / 'fixture.apk'
            with zipfile.ZipFile(apk, 'w') as out:
                out.writestr('assets/components/lwjgl3-sdl/lwjgl-sdl-patches.jar', empty.getvalue())
                out.write(core, 'assets/components/lwjgl3-sdl/lwjgl-android-jni.jar')
            registry = classpath(apk, 'sdl', 21)
            with zipfile.ZipFile(core) as upstream:
                _, expected, _, _ = read_class(upstream.read('META-INF/versions/17/org/lwjgl/system/MemoryUtilTunables.class'))
                self.assertEqual(registry['org/lwjgl/system/MemoryUtilTunables'], expected)
                _, base_jni, _, _ = read_class(upstream.read('org/lwjgl/system/JNI.class'))
                self.assertEqual(registry['org/lwjgl/system/JNI'], base_jni)

    def test_bundled_modern_native_abis(self):
        for name in ('SDL-release.aar', 'ltw-release.aar', 'lwjgl-3.4.1-natives-release.aar', 'spirv-cross-natives.aar'):
            with self.subTest(aar=name), zipfile.ZipFile(ROOT / 'ZalithLauncher/libs' / name) as archive:
                found = set()
                for path in archive.namelist():
                    if not path.endswith('.so'):
                        continue
                    abi = path.split('/')[-2]
                    self.assertIn(abi, ABIS)
                    self.assertTrue(elf_matches(archive.read(path)[:20], abi), path)
                    found.add(abi)
                self.assertEqual(found, set(ABIS))

    def test_java21_and_java25_arm32_arm64_packaging(self):
        components = ROOT / 'ZalithLauncher/src/main/assets/components'
        for java in (21, 25):
            for arch, abi in (('arm', 'armeabi-v7a'), ('arm64', 'arm64-v8a'), ('x86_64', 'x86_64')):
                with self.subTest(java=java, abi=abi), tarfile.open(components / f'jre-{java}/bin-{arch}.tar.xz') as archive:
                    jvms = [m for m in archive if m.name.endswith('/libjvm.so') and m.isfile()]
                    self.assertTrue(jvms)
                    for member in jvms:
                        self.assertTrue(elf_matches(archive.extractfile(member).read(20), abi), member.name)

    def test_native_component_refresh_and_sdl_policy_order(self):
        build = (ROOT / 'jre_lwjgl3sdl/build.gradle.kts').read_text()
        self.assertIn('inputs.file(nativesAar)', build)
        self.assertIn('nativesAar.inputStream()', build)
        unpack = (SRC / 'com/movtery/zalithlauncher/feature/unpack/UnpackComponentsTask.kt').read_text()
        self.assertIn('!sdlNativesComplete()', unpack)
        self.assertIn('NativeLibraryInfo.matches', unpack)
        runtime_unpack = (SRC / 'com/movtery/zalithlauncher/feature/unpack/UnpackJreTask.kt').read_text()
        self.assertIn('Architecture.archAsInt(installed.arch) != Tools.DEVICE_ARCHITECTURE', runtime_unpack)
        policy = (SRC / 'com/movtery/zalithlauncher/launch/RendererPolicy.kt').read_text()
        self.assertLess(policy.index('if (MinecraftGLSurface.sdlVersion)'), policy.index('val releaseDate'))


if __name__ == '__main__':
    unittest.main()
