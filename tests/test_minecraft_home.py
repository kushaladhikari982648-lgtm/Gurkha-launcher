"""Native resource/wiring regressions, not a substitute for device visual testing."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'ZalithLauncher/src/main/res'
ANDROID = '{http://schemas.android.com/apk/res/android}'


class MinecraftHomeTests(unittest.TestCase):
    def test_both_orientations_keep_real_launcher_controls(self):
        required = {'launcher_menu', 'play_layout', 'play_buttons_layout', 'view_account',
                    'version', 'version_name', 'version_info', 'version_icon', 'play_button',
                    'manager_profile_button', 'about_button', 'about_text', 'custom_control_button',
                    'install_jar_button', 'share_logs_button', 'open_main_dir_button',
                    'versions_button', 'mods_button', 'settings_button', 'renderer_summary',
                    'memory_summary', 'low_end_status', 'overworld_art'}
        for folder in ('layout', 'layout-port'):
            with self.subTest(folder=folder):
                path = RES / folder / 'fragment_launcher.xml'
                root = ET.parse(path).getroot()
                ids = [n.get(ANDROID + 'id', '').split('/')[-1] for n in root.iter() if n.get(ANDROID + 'id')]
                self.assertEqual(len(ids), len(set(ids)))
                self.assertLessEqual(required, set(ids))
                self.assertNotIn('marquee', path.read_text())
                self.assertGreaterEqual(len(list(root.iter('ScrollView'))), 2)

    def test_new_resources_resolve(self):
        strings = {node.get('name') for p in (RES / 'values').glob('*.xml')
                   for node in ET.parse(p).getroot() if node.tag == 'string'}
        drawables = {p.stem for folder in RES.glob('drawable*') for p in folder.iterdir() if p.is_file()}
        paths = list((RES / 'drawable').glob('mc_*.xml')) + [
            RES / 'layout/fragment_launcher.xml', RES / 'layout-port/fragment_launcher.xml',
            RES / 'values/minecraft_theme.xml']
        for path in paths:
            ET.parse(path)
            for kind, name in re.findall(r'@(string|drawable)/(\w+)', path.read_text()):
                self.assertIn(name, strings if kind == 'string' else drawables, str(path))

    def test_artwork_is_small_and_static(self):
        art = RES / 'drawable-nodpi/mc_overworld.webp'
        self.assertLess(art.stat().st_size, 250_000)
        self.assertEqual(art.read_bytes()[:4], b'RIFF')
        self.assertNotIn(b'ANIM', art.read_bytes()[:100])
        for tile in (RES / 'drawable-nodpi').glob('mc_*_tile.png'):
            self.assertLess(tile.stat().st_size, 4096)

    def test_home_uses_live_preferences_and_existing_navigation(self):
        source = (ROOT / 'ZalithLauncher/src/main/java/net/kdt/pojavlaunch/fragments/MainMenuFragment.java').read_text()
        for snippet in ('version.getRenderer()', 'getRamAllocation().getValue().getValue()',
                        'new LaunchGameEvent()', 'ModsFragment.BUNDLE_ROOT_PATH',
                        'binding.version.performClick()', 'BackgroundManager.hasBackgroundImage',
                        'binding = null;'):
            self.assertIn(snippet, source)
        self.assertNotIn('"500 MB"', source)
        self.assertNotIn('"Minecraft 1.12.2"', source)


if __name__ == '__main__':
    unittest.main()
