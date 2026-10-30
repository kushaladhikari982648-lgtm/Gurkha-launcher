"""Integration wiring checks; executable policy/executor tests run as Java in CI."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "ZalithLauncher/src/main/java"
RES = ROOT / "ZalithLauncher/src/main/res"


class LowEndIntegrationTests(unittest.TestCase):
    def test_toggle_is_bound_and_explains_limits(self):
        android = "{http://schemas.android.com/apk/res/android}"
        layout = ET.parse(RES / "layout/settings_fragment_launcher.xml")
        ids = {node.get(android + "id") for node in layout.iter()}
        self.assertIn("@+id/low_end_mode_layout", ids)
        self.assertIn("@id/low_end_mode", ids)
        strings = {node.get("name"): node.text for node in ET.parse(RES / "values/strings.xml").getroot()}
        self.assertIn("512 MB", strings["setting_low_end_mode_desc"])
        binding = (JAVA / "com/movtery/zalithlauncher/ui/fragment/settings/LauncherSettingsFragment.kt").read_text()
        self.assertIn("AllSettings.lowEndMode,\n            binding.lowEndModeLayout,\n            binding.lowEndMode", binding)

    def test_jvm_keeps_selected_maximum_and_reports_profile(self):
        text = (JAVA / "net/kdt/pojavlaunch/utils/JREUtils.java").read_text()
        self.assertIn('userArgs.add("-Xms" + initialHeapMb + "M")', text)
        self.assertIn('userArgs.add("-Xmx" + maximumHeapMb + "M")', text)
        self.assertIn("LowEndPolicy.initialHeapMb(maximumHeapMb, lowEndMode)", text)
        self.assertIn('"Info: Low-end device mode="', text)

    def test_downloaders_and_mod_scanner_use_profile(self):
        game = (JAVA / "net/kdt/pojavlaunch/tasks/MinecraftDownloader.java").read_text()
        mods = (JAVA / "net/kdt/pojavlaunch/modloaders/modpacks/api/ModDownloader.java").read_text()
        self.assertIn("LowEndPolicy.gameDownloadPool", game)
        self.assertIn("LowEndPolicy.downloadWorkers", mods)
        for text in (game, mods):
            self.assertIn("LowEndPolicy.progressIntervalMs", text)
            self.assertIn("AllSettings.getLowEndMode().getValue()", text)
            self.assertIn("finally {", text)
            self.assertIn("shutdownNow()", text)
        scan = (JAVA / "com/movtery/zalithlauncher/feature/mod/parser/ModParser.kt").read_text()
        self.assertIn("files.chunked(optimalThreads)", scan)
        self.assertIn("LowEndPolicy.modScanWorkers", scan)
        self.assertNotIn("coreCount * 8", scan)

    def test_only_new_options_files_get_cloud_defaults(self):
        text = (JAVA / "com/movtery/zalithlauncher/feature/MCOptions.kt").read_text()
        setup = text.split("private fun load()", 1)[0]
        self.assertLess(setup.index("if (!exists())"), setup.index("LowEndPolicy.newGameDefaults"))
        self.assertLess(setup.index("LowEndPolicy.newGameDefaults"), setup.index("load()"))
        self.assertEqual(text.count("LowEndPolicy.newGameDefaults"), 1)
        self.assertIn("AllSettings.lowEndMode.getValue()", setup)


if __name__ == "__main__":
    unittest.main()
