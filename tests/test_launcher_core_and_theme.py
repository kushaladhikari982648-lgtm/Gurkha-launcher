"""Offline integration checks, with executable Rust/JNI/Java tests in Android CI."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'ZalithLauncher/src/main/java'
RES = ROOT / 'ZalithLauncher/src/main/res'
A = '{http://schemas.android.com/apk/res/android}'


class LauncherCoreThemeTests(unittest.TestCase):
    def test_standalone_freedreno_is_removed_not_other_drivers(self):
        self.assertFalse((JAVA / 'com/movtery/zalithlauncher/renderer/renderers/FreedrenoRenderer.kt').exists())
        manager = (JAVA / 'com/movtery/zalithlauncher/renderer/Renderers.kt').read_text()
        self.assertNotIn('FreedrenoRenderer', manager)
        self.assertIn('RendererSelection.normalize(uniqueIdentifier)', manager)
        bridge = (ROOT / 'ZalithLauncher/src/main/jni/egl_bridge.c').read_text()
        self.assertNotIn('gallium_freedreno', bridge)
        self.assertIn('vulkan_zink', bridge)

    def test_settings_write_is_atomic_and_merge_is_locked(self):
        source = (JAVA / 'com/movtery/zalithlauncher/setting/Settings.kt').read_text().split('fun save() {', 1)[1]
        self.assertLess(source.index('synchronized(settingsLock)'), source.index('ConcurrentHashMap(settingsMap)'))
        self.assertIn('RustCore.atomicWrite', source)
        self.assertNotIn('createNewFile()', source)
        self.assertNotIn('FileUtils.write', source)
        parser = (JAVA / 'com/movtery/zalithlauncher/feature/mod/parser/ModParser.kt').read_text()
        self.assertIn('if (scanCompleted.get()) persistCache', parser)
        self.assertIn('RustCore.atomicWrite', parser)

    def test_core_is_independent_and_packaged(self):
        core = (ROOT / 'launcher-core/Cargo.toml').read_text()
        self.assertNotIn('gl-compat', core)
        self.assertIn('sha2 = "=0.10.9"', core)
        flow = (ROOT / '.github/workflows/android.yml').read_text()
        self.assertEqual(flow.count('- name: Build Rust launcher core\n'), 2)
        self.assertEqual(flow.count('- name: Check Rust launcher core APK contents\n'), 2)
        self.assertIn('RustCoreTest native', flow)
        self.assertIn('RustCoreTest fallback', flow)
        self.assertIn('com.movtery.zalithlauncher.utils.file.RustCore', (ROOT / 'ZalithLauncher/proguard-rules.pro').read_text())

    def test_shared_surfaces_are_square_including_input_and_dialogs(self):
        for name in ['background_card', 'dialog_background', 'background_item_unselected',
                     'button_background_selected', 'background_edit_box_selected']:
            tree = ET.parse(RES / 'drawable' / (name + '.xml'))
            for corner in tree.iter('corners'):
                self.assertEqual(corner.get(A + 'radius'), '0dp', name)
        for mode, colors in [('values', 'colors.xml'), ('values-night', 'colors_night.xml')]:
            values = {n.get('name'): n.text for n in ET.parse(RES / mode / colors).getroot()}
            self.assertEqual(values['primary_text'], '#F3F2E8')
            self.assertEqual(values['settings_category'], '#BDD987')
            styles = ET.parse(RES / mode / 'styles.xml')
            for name in ['AppTheme', 'CustomDialogStyle', 'CustomAlertDialogTheme']:
                node = next(n for n in styles.getroot() if n.get('name') == name)
                self.assertNotIn('Light', node.get('parent'))
                self.assertIn('MinecraftGlobalButton', ET.tostring(node, encoding='unicode'))

    def test_settings_preserves_categories_and_auto_save_footer(self):
        tree = ET.parse(RES / 'layout/fragment_settings.xml')
        ids = {n.get(A + 'id') for n in tree.iter()}
        for name in ['settings_viewpager', 'settings_tab', 'settings_footer']:
            self.assertIn('@+id/' + name, ids)
        tabs = list(tree.iter('com.angcyo.tablayout.DslTabLayout'))
        self.assertEqual(len(tabs), 1)
        self.assertEqual(len(tabs[0]), 5)
        for child in tabs[0]:
            self.assertEqual(child.get(A + 'background'), '@drawable/background_item')


if __name__ == '__main__':
    unittest.main()
