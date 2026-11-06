from pathlib import Path
import unittest
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

    def test_theme_and_renderer_retirement(self):
        self.assertTrue((ROOT / 'ZalithLauncher/src/main/res/values/minecraft_theme.xml').exists())
        self.assertFalse((SRC / 'com/movtery/zalithlauncher/renderer/renderers/FreedrenoRenderer.kt').exists())
        manager = (SRC / 'com/movtery/zalithlauncher/renderer/Renderers.kt').read_text()
        self.assertIn('Tools.DEVICE_ARCHITECTURE != Architecture.ARCH_X86', manager)
        self.assertIn('RendererSelection.normalize', manager)
        self.assertNotIn('RustGLRenderer', manager)  # not imported merely to claim Rust GPU acceleration

    def test_preview_is_draft_only(self):
        source = (ROOT / 'tools/stage_test_release.py').read_text()
        self.assertIn("require(not draft['assets']", source)
        self.assertNotIn('--clobber', source)
        self.assertNotIn('--draft=false', source)
        self.assertIn('signer', source)

if __name__ == '__main__': unittest.main()
