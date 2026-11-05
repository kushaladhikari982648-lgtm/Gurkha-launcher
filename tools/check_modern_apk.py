#!/usr/bin/env python3
"""Validate shipped modern Minecraft dependencies for every ABI present in an APK.
This is a packaging check, not a gameplay test.
"""
import glob
import io
import lzma
import struct
import sys
import zipfile

ABIS = {'armeabi-v7a': ('arm', 1, 40), 'arm64-v8a': ('arm64', 2, 183),
        'x86': ('x86', 1, 3), 'x86_64': ('x86_64', 2, 62)}


def check_apk(path):
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        shipped = {p.split('/')[1] for p in names if p.startswith('lib/') and p.endswith('.so')}
        if not shipped or not shipped <= ABIS.keys():
            raise ValueError(f'Invalid APK ABI set: {shipped}')
        for abi in shipped:
            arch, bits, machine = ABIS[abi]
            for lib in ('libSDL3.so', 'libltw.so', 'libltw_sdl_egl.so', 'libspirv-cross-c-shared.so'):
                header = archive.read(f'lib/{abi}/{lib}')[:20]
                if (header[:4] != b'\x7fELF' or header[4] != bits or header[5] != 1 or
                        struct.unpack_from('<H', header, 18)[0] != machine):
                    raise ValueError(f'Wrong ELF architecture: {abi}/{lib}')
            for lib in ('liblwjgl.so', 'liblwjgl_opengl.so', 'liblwjgl_stb.so', 'libfreetype.so',
                        'liblwjgl_tinyfd.so', 'libshaderc.so', 'liblwjgl_vma.so', 'liblwjgl_spng.so', 'liblwjgl_nanovg.so'):
                header = archive.read(f'assets/components/lwjgl-3.4.1-natives/{abi}/{lib}')[:20]
                if (header[:4] != b'\x7fELF' or header[4] != bits or header[5] != 1 or
                        struct.unpack_from('<H', header, 18)[0] != machine):
                    raise ValueError(f'Wrong modern LWJGL native: {abi}/{lib}')
            for version in (21, 25):
                if version == 25 and abi == 'x86':
                    print('NOTE: 32-bit x86 has no bundled Java 25; Java-25-required games are unsupported on that ABI.')
                    continue
                for part in (f'bin-{arch}.tar.xz', 'universal.tar.xz', 'version'):
                    if f'assets/components/jre-{version}/{part}' not in names:
                        raise ValueError(f'Missing Java {version}/{part} for {abi}')
        for component, jar, entry in (
                ('lwjgl3', 'lwjgl-glfw-stub.jar', 'org/lwjgl/glfw/GLFW.class'),
                ('lwjgl3-sdl', 'lwjgl-sdl-patches.jar', 'org/lwjgl/sdl/SDLInit.class'),
                ('lwjgl3-sdl', 'lwjgl-android-jni.jar', 'org/lwjgl/system/MemoryUtil.class'),
                ('lwjgl3-sdl', 'lwjgl-sdl.jar', 'org/lwjgl/sdl/SDL.class'),
                ('lwjgl3-sdl', 'lwjgl-vulkan.jar', 'org/lwjgl/vulkan/VK.class')):
            prefix = f'assets/components/{component}/'
            if prefix + 'version' not in names:
                raise ValueError(f'Missing component version: {component}')
            data = (lzma.decompress(archive.read(prefix + jar + '.xz')) if prefix + jar + '.xz' in names
                    else archive.read(prefix + jar))
            with zipfile.ZipFile(io.BytesIO(data)) as classes:
                if jar == 'lwjgl-android-jni.jar':
                    if any(n.startswith('META-INF/versions/25/') for n in classes.namelist()):
                        raise ValueError('Android LWJGL must not switch to the Java 25 FFM backend')
                    if b'java/lang/foreign/' in classes.read('org/lwjgl/system/JNI.class'):
                        raise ValueError('Expected the native JNI dispatch backend')
                if entry not in classes.namelist():
                    raise ValueError(f'Missing {entry} in {jar}')
        print(f'{path}: modern GLFW/SDL components, Java runtimes and native ABI checks passed: {sorted(shipped)}')


if __name__ == '__main__':
    paths = [path for argument in sys.argv[1:] for path in glob.glob(argument)]
    if not paths:
        sys.exit('No APKs supplied')
    for path in paths:
        check_apk(path)
