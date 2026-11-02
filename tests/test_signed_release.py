"""Release wiring safety checks; real key validation happens only in Actions."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class SignedReleaseTests(unittest.TestCase):
    def setUp(self):
        self.flow = (ROOT / '.github/workflows/android.yml').read_text()

    def test_version_is_incremented_and_has_notes(self):
        props = dict(line.split('=', 1) for line in (ROOT / 'ZalithLauncher/gradle.properties').read_text().splitlines()
                     if '=' in line and not line.startswith('#'))
        self.assertGreater(int(props['launcher_version_code']), 100005)
        self.assertTrue((ROOT / '.github/release-notes' / ('v' + props['launcher_version_name'] + '.md')).is_file())

    def test_manual_release_keeps_required_gates(self):
        self.assertIn("type: boolean", self.flow)
        self.assertIn("default: false", self.flow)
        self.assertIn("REQUIRE_KEY: ${{ startsWith(github.ref, 'refs/tags/v') || (github.event_name == 'workflow_dispatch' && inputs.release) }}", self.flow)
        debug = self.flow.split('  BuildDebug:', 1)[1].split('    runs-on:', 1)[0]
        self.assertIn("!(github.event_name == 'workflow_dispatch' && inputs.release)", debug)
        release = self.flow.split('  BuildRelease:', 1)[1].split('    runs-on:', 1)[0]
        for gate in ['ReleasePreflight', 'RustGLChecks', 'LauncherCoreChecks', 'CurseForgeChecks']:
            self.assertIn(gate, release)

    def test_release_is_draft_first_and_never_overwrites(self):
        publish = self.flow.split('  PublishRelease:', 1)[1]
        self.assertIn('needs: [BuildRelease, ReleasePreflight]', publish)
        self.assertIn('--draft --target "$GITHUB_SHA"', publish)
        self.assertIn('gh release edit "$TAG" --draft=false', publish)
        self.assertNotIn('--clobber', publish)
        self.assertIn('refusing to overwrite', publish)
        self.assertIn('needs.ReleasePreflight.outputs.release_tag', publish)
        self.assertNotIn('TAG: ${{ github.ref_name }}', publish)

    def test_apks_are_checked_against_release_key_before_cleanup(self):
        release = self.flow.split('  BuildRelease:', 1)[1].split('  PublishRelease:', 1)[0]
        self.assertLess(release.index('Verify release APK signatures'), release.index('Remove keystore'))
        self.assertLess(release.index('Verify release APK signatures'), release.index('Upload Release'))
        self.assertIn('apksigner', release)
        self.assertIn('"$ACTUAL" != "$EXPECTED"', release)
        self.assertIn('-storepass:env GURKHA_KEYSTORE_PASSWORD', release)


if __name__ == '__main__':
    unittest.main()
