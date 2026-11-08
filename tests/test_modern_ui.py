"""Resource/wiring guards; actual inflation/navigation is exercised by the emulator job."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
RES=ROOT/'ZalithLauncher/src/main/res'
SRC=ROOT/'ZalithLauncher/src/main/java'
A='{http://schemas.android.com/apk/res/android}'
class ModernUiTests(unittest.TestCase):
    def test_home_actions_exist_in_both_orientations(self):
        sets=[]
        for directory in ('layout','layout-port'):
            root=ET.parse(RES/directory/'fragment_launcher.xml').getroot()
            ids=[e.get(A+'id','').split('/')[-1] for e in root.iter() if A+'id' in e.attrib]
            self.assertEqual(len(ids),len(set(ids)))
            sets.append(set(ids))
            for name in ('play_button','view_account','version','tools_panel','home_scroll',
                         'custom_control_button','install_jar_button','share_logs_button','settings_button'):
                self.assertIn(name,ids)
            self.assertNotIn('mc_overworld',ET.tostring(root).decode())
        self.assertEqual(*sets)
    def test_settings_categories_and_lifecycle(self):
        root=ET.parse(RES/'layout/fragment_settings.xml').getroot()
        self.assertEqual(5,len([e for e in root.iter('Button')]))
        self.assertIsNotNone(root.find('.//HorizontalScrollView'))
        source=(SRC/'com/movtery/zalithlauncher/ui/fragment/SettingsFragment.kt').read_text()
        self.assertIn('FragmentStateAdapter(fragment)',source)
        self.assertIn('unregisterOnPageChangeCallback',source)
        self.assertIn('currentBinding = null',source)
    def test_no_default_scenery_or_square_cards(self):
        for name in ('background_card','dialog_background','mc_panel'):
            root=ET.parse(RES/f'drawable/{name}.xml').getroot()
            self.assertEqual('20dp',root.find('corners').get(A+'radius'))
        source=(SRC/'com/movtery/zalithlauncher/feature/background/BackgroundManager.kt').read_text()
        self.assertNotIn('R.drawable.mc_overworld',source)
        self.assertIn('getBackgroundImage(backgroundType)',source)
    def test_motion_and_recycled_attributes(self):
        motion=(SRC/'com/movtery/zalithlauncher/utils/anim/MotionPolicy.kt').read_text()
        self.assertIn('ValueAnimator.areAnimatorsEnabled()',motion)
        self.assertIn('AllSettings.animation.getValue()',motion)
        for path in ('ui/layout/AnimRelativeLayout.kt','ui/layout/AnimConstraintLayout.kt','ui/view/AnimTextView.kt'):
            text=(SRC/'com/movtery/zalithlauncher'/path).read_text()
            self.assertIn('attributes.recycle()',text)
            self.assertNotIn('post {',text)
    def test_launcher_rotates_without_changing_game_activity(self):
        root=ET.parse(ROOT/'ZalithLauncher/src/main/AndroidManifest.xml').getroot()
        launcher=next(e for e in root.iter('activity') if e.get(A+'name')=='net.kdt.pojavlaunch.LauncherActivity')
        self.assertEqual('fullUser',launcher.get(A+'screenOrientation'))
        self.assertNotIn('orientation',launcher.get(A+'configChanges'))

    def test_portrait_versions_have_no_conflicting_constraints(self):
        root=ET.parse(RES/'layout-port/fragment_versions_list.xml').getroot()
        app='{http://schemas.android.com/apk/res-auto}'
        for e in root.iter():
            self.assertFalse(e.get(app+'layout_constraintTop_toTopOf') and e.get(app+'layout_constraintTop_toBottomOf'))
            if e.get(A+'id')=='@+id/shadowView':
                self.assertEqual('gone',e.get(A+'visibility'))
                self.assertNotEqual('0dp',e.get(A+'layout_width'))
