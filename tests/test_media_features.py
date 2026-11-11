"""Policy execution and integration checks. Not device playback/capture tests."""
import pathlib
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = ROOT / 'ZalithLauncher/src/main'
JAVA = MAIN / 'java'
APP = JAVA / 'com/movtery/zalithlauncher'

class MediaFeaturesTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required; executed in CI')
    def test_real_java_policy(self):
        with tempfile.TemporaryDirectory() as out:
            subprocess.run(['javac', '--release', '8', '-d', out,
                str(APP / 'feature/media/MediaPolicy.java'), str(ROOT / 'tests/java/MediaPolicyTest.java')], check=True)
            subprocess.run(['java', '-cp', out, 'MediaPolicyTest'], check=True)

    def test_capture_is_never_composed_window(self):
        recorder = (APP / 'feature/media/GameplayRecorder.java').read_text()
        self.assertIn('PixelCopy.request(source,bitmap', recorder)
        self.assertNotIn('getWindow(', recorder)
        self.assertNotIn('createVirtualDisplay(', recorder)
        self.assertIn('COLOR_FormatSurface', recorder)
        self.assertIn('if(closed || copying) return', recorder)
        self.assertIn('partial.delete()', recorder)
        self.assertIn('600_000_000_000L', recorder)
        game = (JAVA / 'net/kdt/pojavlaunch/MainActivity.java').read_text()
        self.assertIn('protected void onPause() {\n        stopGameplayRecording();', game)
        self.assertIn('binding.mainGameRenderView.getGameplayCaptureSurface()', game)

    def test_video_lifecycle_and_image_only_other_backgrounds(self):
        video = (APP / 'feature/background/VideoWallpaper.kt').read_text()
        self.assertIn('p.setVolume(0f, 0f)', video)
        self.assertIn('p.isLooping = true', video)
        self.assertIn('fun pause() { resumed = false; releasePlayer() }', video)
        self.assertIn('prepareAsync()', video)
        picker = (APP / 'ui/fragment/CustomBackgroundFragment.kt').read_text()
        self.assertIn('backgroundType == BackgroundType.MAIN_MENU &&', picker)
        launcher = (JAVA / 'net/kdt/pojavlaunch/LauncherActivity.java').read_text()
        self.assertIn('videoWallpaper.pause()', launcher)
        self.assertIn('videoWallpaper.close()', launcher)

    def test_ui_resources_and_entries(self):
        for name in ['layout/fragment_launcher.xml', 'layout-port/fragment_launcher.xml']:
            self.assertIn('@+id/wardrobe_button', (MAIN/'res'/name).read_text())
        for name in ['layout/activity_wardrobe.xml', 'layout/view_game_menu.xml', 'values/media_strings.xml']:
            ET.parse(MAIN/'res'/name)
        wardrobe = (APP/'ui/activity/WardrobeActivity.kt').read_text()
        self.assertIn('CustomSkinLoader/ExtraList/GurkhaLocal.json', wardrobe)
        self.assertIn('MediaPolicy.validPlayerName(player)', wardrobe)
        self.assertIn('total <= 4 * 1024 * 1024', wardrobe)
        self.assertIn('temp.delete()', wardrobe)
        self.assertIn('worker.shutdown()', wardrobe)
        self.assertNotIn('accessToken', wardrobe)

if __name__ == '__main__': unittest.main()
