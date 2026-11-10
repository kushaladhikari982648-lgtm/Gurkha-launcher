import unittest
from unittest.mock import Mock, patch
from tools.ui_rotation import lock_rotation

class RotationTests(unittest.TestCase):
    @patch('tools.ui_rotation.time.sleep')
    def test_waits_for_both_requested_and_actual_rotation(self, sleep):
        adb = Mock(side_effect=['',
            'mCurrentRotation=ROTATION_0 mUserRotation=ROTATION_90',
            'mCurrentRotation=ROTATION_90 mUserRotation=ROTATION_90',
            'mCurrentRotation=ROTATION_0 mUserRotation=ROTATION_90',
            'mCurrentRotation=ROTATION_90 mUserRotation=ROTATION_90',
            'mCurrentRotation=ROTATION_90 mUserRotation=ROTATION_90'])
        lock_rotation(adb, 1)
        self.assertEqual(adb.call_count, 6)
        for call in adb.call_args_list[1:]:
            self.assertEqual(call.args, ('shell', 'dumpsys', 'window', 'displays'))

    def test_does_not_hide_a_real_rotation_failure(self):
        adb = Mock(return_value='mCurrentRotation=ROTATION_0 mUserRotation=ROTATION_0')
        with self.assertRaisesRegex(AssertionError, 'WindowManager'):
            lock_rotation(adb, 1, timeout=0)
        with self.assertRaises(ValueError):
            lock_rotation(adb, 90)
