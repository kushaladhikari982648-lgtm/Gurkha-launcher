"""Wait for Android rotation without attaching UiAutomation (API 29).

DisplayRotation.setUserRotation updates Settings asynchronously. UiAutomationConnection
saves the current rotation on connect and restores it on disconnect, so starting a
hierarchy dump immediately after `wm` can silently undo the pending rotation.
Reference: aosp-mirror/platform_frameworks_base android-10.0.0_r1,
core/java/android/app/UiAutomationConnection.java and services/core/java/com/android/server/wm/DisplayRotation.java.
"""
import re
import time


def lock_rotation(adb, rotation, timeout=45):
    if rotation not in (0, 1, 2, 3):
        raise ValueError('Expected a Surface.ROTATION_* value')
    expected = 'ROTATION_' + str(rotation * 90)
    adb('shell', 'wm', 'set-user-rotation', 'lock', str(rotation))
    deadline = time.monotonic() + timeout
    consecutive = 0
    while time.monotonic() < deadline:
        state = adb('shell', 'dumpsys', 'window', 'displays')
        ready = all(re.search(r'\b' + name + '=' + expected + r'\b', state)
                    for name in ('mCurrentRotation', 'mUserRotation'))
        consecutive = consecutive + 1 if ready else 0
        if consecutive >= 2:
            return
        time.sleep(0.5)
    raise AssertionError('WindowManager did not settle at ' + expected + ' before UI automation')
