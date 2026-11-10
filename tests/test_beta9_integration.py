from pathlib import Path
import unittest
import os
import subprocess
import sys
import tempfile
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'ZalithLauncher/src/main/java'

class Beta9Tests(unittest.TestCase):
    def test_all_resource_xml_is_well_formed(self):
        for path in (ROOT / 'ZalithLauncher/src/main/res').rglob('*.xml'):
            ET.parse(path)

    def test_rust_and_cache_stability(self):
        settings = (SRC / 'com/movtery/zalithlauncher/setting/Settings.kt').read_text().split('fun save() {')[1]
        self.assertLess(settings.index('synchronized(settingsLock)'), settings.index('ConcurrentHashMap(settingsMap)'))
        self.assertIn('RustCore.atomicWrite', settings)
        parser = (SRC / 'com/movtery/zalithlauncher/feature/mod/parser/ModParser.kt').read_text()
        self.assertIn('if (scanCompleted.get()) persistCache', parser)
        self.assertIn('LowEndPolicy.modScanWorkers', parser)

    def test_sdl_both_initialization_paths_and_failure(self):
        java = (ROOT / 'jre_lwjgl3sdl/src/main/java/org/lwjgl/sdl/SDLInit.java').read_text()
        for method in ('SDL_Init(', 'SDL_InitSubSystem('):
            body = java.split('public static boolean ' + method, 1)[1].split('\n    }', 1)[0]
            self.assertIn('CallbackBridge.initializeSdl()', body)
        bridge = (ROOT / 'jre_lwjgl3sdl/src/main/java/org/lwjgl/glfw/CallbackBridge.java').read_text()
        self.assertIn('if (!nativeNotifyLauncher(SDL, INIT))', bridge)
        surface = (SRC / 'net/kdt/pojavlaunch/MinecraftGLSurface.java').read_text()
        self.assertIn('if (!isCalled) setupSDL', surface)
        activity = (SRC / 'org/libsdl/app/SDLActivity.java').read_text()
        self.assertIn('post(SDLActivity::handleNativeState)', activity)

    def test_theme_and_renderer_retirement(self):
        self.assertTrue((ROOT / 'ZalithLauncher/src/main/res/values/minecraft_theme.xml').exists())
        self.assertFalse((SRC / 'com/movtery/zalithlauncher/renderer/renderers/FreedrenoRenderer.kt').exists())
        manager = (SRC / 'com/movtery/zalithlauncher/renderer/Renderers.kt').read_text()
        self.assertIn('Tools.DEVICE_ARCHITECTURE != Architecture.ARCH_X86', manager)
        self.assertIn('RendererSelection.normalize', manager)
        self.assertIn('RustGLRenderer()', manager)  # now built from the vendored renderer, not a label-only entry

    def test_corresponding_source_accepts_registry_epoch_timestamps(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('LICENSE', 'THIRD_PARTY_NOTICES.md', 'launcher-core/Cargo.toml',
                         'launcher-core/Cargo.lock', 'launcher-core/README.md',
                         'launcher-core/pack_aar.py', 'launcher-core/src/lib.rs',
                         'launcher-core/vendor/example/LICENSE'):
                p = root / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text('fixture')
                os.utime(p, (1, 1))  # Cargo packages may carry pre-1980 mtimes.
            (root / 'tools').mkdir()
            script = root / 'tools/package_core_source.py'
            script.write_text((ROOT / 'tools/package_core_source.py').read_text())
            subprocess.run([sys.executable, str(script)], check=True)
            with zipfile.ZipFile(root / 'release-extra/Rust-core-corresponding-source.zip') as z:
                self.assertIsNone(z.testzip())
                self.assertIn('Gurkha-launcher/launcher-core/vendor/example/LICENSE', z.namelist())
                self.assertIn('vendored-sources', z.read('Gurkha-launcher/launcher-core/.cargo/config.toml').decode())

    def test_preview_is_draft_only(self):
        source = (ROOT / 'tools/stage_test_release.py').read_text()
        self.assertIn("require(not draft['assets']", source)
        self.assertNotIn('--clobber', source)
        self.assertNotIn('--draft=false', source)
        self.assertIn('signer', source)

if __name__ == '__main__': unittest.main()
