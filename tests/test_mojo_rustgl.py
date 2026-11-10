from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'ZalithLauncher/src/main/java'

class MojoRustGLTests(unittest.TestCase):
    def test_actual_jni_backend_separation(self):
        javac = shutil.which('javac')
        if not javac or not shutil.which('cc'):
            self.skipTest('JDK and C compiler required; run by CompatibilityChecks CI')
        java_home = Path(javac).resolve().parents[1]
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            subprocess.run(['cc', '-Wall', '-Wextra', '-Werror', '-Wno-unused-parameter', '-shared', '-fPIC',
                '-I' + str(java_home / 'include'), '-I' + str(java_home / 'include/linux'),
                '-I' + str(ROOT / 'ZalithLauncher/src/main/jni'), str(ROOT / 'tests/native/bridge_probe.c'),
                '-o', str(tmp / 'libbridgeprobe.so')], check=True)
            subprocess.run([javac, '--release', '8', '-d', str(tmp), str(ROOT / 'tests/java/BridgeProbe.java')], check=True)
            def probe(sdl, expected):
                subprocess.run(['java', '-Xcheck:jni', '-Djava.library.path=' + str(tmp), '-cp', str(tmp),
                                'BridgeProbe', str(sdl).lower(), str(expected).lower()], check=True)
            probe(True, True)  # NO GLFW class at all; must never look it up.
            probe(False, False)  # Missing shim fails safely, never GetMethodID(null).
            fixture = tmp / 'GLFW.java'
            for fields, expected in [('', False),
                ('public static java.nio.ByteBuffer keyDownBuffer = null, mouseDownBuffer = null;', False),
                ('public static java.nio.ByteBuffer keyDownBuffer = java.nio.ByteBuffer.allocate(317), mouseDownBuffer = java.nio.ByteBuffer.allocate(8);', False),
                ('public static java.nio.ByteBuffer keyDownBuffer = java.nio.ByteBuffer.allocateDirect(317), mouseDownBuffer = java.nio.ByteBuffer.allocateDirect(8);', True)]:
                fixture.write_text('package org.lwjgl.glfw; public class GLFW { public static void glfwSetWindowAttrib(long w,int a,int v) {} public static void internalWindowSizeChanged(long w,int x,int y) {} ' + fields + '}')
                subprocess.run([javac, '--release', '8', '-d', str(tmp), str(fixture)], check=True)
                probe(False, expected)
                probe(True, True)

    def test_runtime_backend_is_set_and_checked(self):
        java = (SRC / 'net/kdt/pojavlaunch/utils/JREUtils.java').read_text()
        self.assertIn('"POJAV_WINDOW_BACKEND", MinecraftGLSurface.sdlVersion ? "sdl" : "glfw"', java)
        native = (ROOT / 'ZalithLauncher/src/main/jni/input_bridge_v3.c').read_text()
        self.assertIn('if (!gurkha_init_runtime_bridge', native)
        self.assertIn('return JNI_ERR;', native)

    def test_rustgl_is_real_opt_in_gles_and_sdl_capable(self):
        renderer = (SRC / 'com/movtery/zalithlauncher/renderer/renderers/RustGLRenderer.kt').read_text()
        self.assertIn('"RENDERER_BACKEND" to "gles"', renderer)
        self.assertIn('getRendererEGL() = LIBRARY', renderer)
        self.assertNotIn('JAVA_TOOL_OPTIONS', renderer)
        self.assertNotIn('NoChecks', renderer)
        policy = (SRC / 'com/movtery/zalithlauncher/launch/RendererPolicy.kt').read_text()
        self.assertIn('current !is RustGLRenderer', policy)
        self.assertTrue((ROOT / 'rustgl/crates/gl-compat/src/lib.rs').is_file())
        workflow = (ROOT / '.github/workflows/android.yml').read_text()
        self.assertIn('python3 rustgl/pack_aar.py', workflow)
        self.assertIn('MESA_GLES_VERSION_OVERRIDE=3.0', workflow)
