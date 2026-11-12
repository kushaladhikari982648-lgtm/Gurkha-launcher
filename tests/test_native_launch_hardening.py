from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JNI = ROOT/'ZalithLauncher/src/main/jni'
SRC = ROOT/'ZalithLauncher/src/main/java'

class NativeLaunchHardeningTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which('cc'), 'C compiler required')
    def test_actual_egl_loader_and_surface_state(self):
        with tempfile.TemporaryDirectory() as out:
            executable=str(Path(out)/'egl-probe')
            subprocess.run(['cc','-std=gnu11','-Wall','-Wextra','-Werror','-Wno-unused-function',
                '-DGURKHA_BRIDGE_TEST','-Ddlopen=test_dlopen','-Ddlclose=test_dlclose','-Ddlerror=test_dlerror',
                '-I'+str(ROOT/'tests/native/stubs'),'-I'+str(JNI),str(ROOT/'tests/native/egl_probe.c'),
                *[str(JNI/'ctxbridges'/name) for name in ('egl_loader.c','gl_bridge.c','window_slot.c')],
                '-pthread','-o',executable],check=True)
            for mode in ('missing-provider','missing-symbol','invalid-es','bind-failure','no-config',
                         'context-failure','current-failure','lost-context','lifecycle'):
                subprocess.run([executable,mode],check=True,timeout=15)

    @unittest.skipUnless(shutil.which('javac'), 'JDK required; executed in CI')
    def test_real_java_policy_and_bounded_log_copy(self):
        with tempfile.TemporaryDirectory() as out:
            subprocess.run(['javac','--release','8','-d',out,str(ROOT/'tests/java/NativeLaunchPolicyTest.java'),
                str(SRC/'net/kdt/pojavlaunch/utils/NativeLaunchPolicy.java'),
                str(SRC/'net/kdt/pojavlaunch/utils/RuntimeLibraryPaths.java'),
                str(SRC/'com/movtery/zalithlauncher/utils/file/CompactLogs.java')],check=True)
            subprocess.run(['java','-ea','-cp',out,'NativeLaunchPolicyTest'],check=True)

    @unittest.skipUnless(shutil.which('cc'), 'C compiler required')
    def test_log_buffer_never_reads_beyond_read_result(self):
        with tempfile.TemporaryDirectory() as out:
            exe = str(Path(out)/'log-buffer-probe')
            subprocess.run(['cc','-std=gnu11','-Wall','-Wextra','-Werror','-I'+str(JNI),
                            str(ROOT/'tests/native/log_buffer_probe.c'),'-o',exe],check=True)
            subprocess.run([exe],check=True,timeout=10)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('cc'), 'JDK and C compiler required; executed in CI')
    def test_actual_native_logger_without_logcat(self):
        java_home = Path(shutil.which('javac')).resolve().parents[1]
        with tempfile.TemporaryDirectory() as out:
            folder = Path(out)
            annotation = folder/'Keep.java'
            annotation.write_text('package androidx.annotation; public @interface Keep {}')
            subprocess.run(['javac','--release','8','-d',out,str(annotation),str(SRC/'net/kdt/pojavlaunch/Logger.java'),
                            str(ROOT/'tests/java/NativeLoggerTest.java')],check=True)
            library = folder/'libloggerprobe.so'
            subprocess.run(['cc','-std=gnu11','-fPIC','-shared','-pthread',
                            '-I'+str(java_home/'include'),'-I'+str(java_home/'include/linux'),'-I'+str(JNI),
                            str(JNI/'stdio_is.c'),str(ROOT/'tests/native/logger_jni_probe.c'),'-o',str(library)],check=True)
            result = subprocess.run(['java','-Xcheck:jni','-cp',out,'NativeLoggerTest',str(library),out],timeout=20)
            if result.returncode:
                self.fail('Native logger probe failed: '+str([(f.name,f.read_text(errors='replace')[-3000:]) for f in folder.glob('*.log')]))

    def test_diagnostics_survive_logcat_eof(self):
        jre=(SRC/'net/kdt/pojavlaunch/utils/JREUtils.java').read_text()
        self.assertNotIn('command("logcat", "-c")',jre)
        self.assertNotIn('failTime',jre)
        self.assertNotIn('dlopen("libjvm.so")',jre)
        self.assertIn('requireRuntimeLibrary(jvm)',jre)
        self.assertIn('LD_LIBRARY_PATH = jvmLibraryPath + ":" + LD_LIBRARY_PATH',jre)
        self.assertIn('lockedNativeProperties.put("java.library.path", libraryPath)',jre)
        native=(JNI/'utils.c').read_text()
        self.assertIn('fprintf(stderr, "RuntimeLoader:',native)
        self.assertIn('JLI_Launch returned %d', (JNI/'jre_launcher.c').read_text())
        stdio=(JNI/'stdio_is.c').read_text()
        self.assertNotIn('strstr(buf',stdio)
        self.assertNotIn('char newChars[',stdio)

    def test_locked_bundle_really_matches_all_four_abis(self):
        manifest=json.loads((ROOT/'ZalithLauncher/src/main/assets/components/lwjgl3-sdl/native-manifest.json').read_text())
        aar=ROOT/'ZalithLauncher/libs/lwjgl-3.4.1-natives-release.aar'
        self.assertEqual(hashlib.sha256(aar.read_bytes()).hexdigest(),manifest['aar_sha256'])
        with zipfile.ZipFile(aar) as z:
            self.assertEqual(set(manifest['natives']),{'armeabi-v7a','arm64-v8a','x86','x86_64'})
            for abi,files in manifest['natives'].items():
                self.assertIn('liblwjgl.so',files)
                for name,digest in files.items():
                    self.assertEqual(hashlib.sha256(z.read('assets/components/lwjgl-3.4.1-natives/'+abi+'/'+name)).hexdigest(),digest)
        support=(SRC/'com/movtery/zalithlauncher/launch/SdlSupport.kt').read_text()
        self.assertIn(manifest['bundle'],support)

    def test_error_and_argument_boundaries(self):
        jre=(SRC/'net/kdt/pojavlaunch/utils/JREUtils.java').read_text()
        self.assertIn('NativeLaunchPolicy.enforceProperties(userArgs, lockedNativeProperties);\n            userArgs.addAll(mainArgs);',jre)
        self.assertNotIn('!dlopen(rendererLib) &&',jre)
        adapter=(JNI/'ltw_sdl_egl.c').read_text()
        self.assertNotIn('abort();',adapter)
        self.assertIn('static __thread EGLint bridge_error',adapter)
        self.assertIn('if (!init()) return',adapter)
        bridge=(JNI/'egl_bridge.c').read_text()
        self.assertIn('return valid ? pojavInitOpenGL() : 0',bridge)
        self.assertIn('bridge_publish_window(NULL)',bridge)
        self.assertNotIn('ANativeWindow_release(pojav_environ->pojavWindow)',bridge)
        shim=(ROOT/'jre_lwjgl3glfw/src/main/java/org/lwjgl/glfw/GLFW.java').read_text()
        self.assertIn('if (ptr == 0L) throw new IllegalStateException',shim)
