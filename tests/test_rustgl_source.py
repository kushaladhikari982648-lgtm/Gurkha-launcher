"""Pin the imported renderer, not the unrelated desktop demo at the source repo root.

When deliberately updating renderer code, update source-lock.json alongside it.
README and integration provenance are maintained separately from upstream code.
"""
import hashlib
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
RUSTGL = ROOT / "rustgl"


class RustGLSourceTests(unittest.TestCase):
    def setUp(self):
        self.lock = json.loads((RUSTGL / "source-lock.json").read_text())

    def test_snapshot_is_an_immutable_android_renderer_revision(self):
        self.assertEqual(self.lock["repository"],
                         "https://github.com/kushaladhikari982648-lgtm/RustGL-for-Gurkha-launcher")
        self.assertRegex(self.lock["commit"], r"^[0-9a-f]{40}$")
        self.assertEqual(self.lock["subdirectory"], "rustgl")
        self.assertIn('"crates/gl-compat"', (RUSTGL / "Cargo.toml").read_text())
        self.assertIn('name = "rust_gl"', (RUSTGL / "crates/gl-compat/Cargo.toml").read_text())

    def test_every_imported_source_file_matches_pinned_digest(self):
        for relative, expected in self.lock["sha256"].items():
            with self.subTest(path=relative):
                patch = self.lock.get("local_patches", {}).get(relative)
                if patch:
                    self.assertEqual(patch["upstream_sha256"], expected)
                    expected = patch["sha256"]
                path = RUSTGL / relative
                self.assertTrue(path.resolve().is_relative_to(RUSTGL.resolve()))
                self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), expected)

    def test_manifest_covers_all_renderer_code_and_fixtures(self):
        expected = {str(p.relative_to(RUSTGL))
                    for folder in ("crates", "tests")
                    for p in (RUSTGL / folder).rglob("*")
                    if p.is_file() and "__pycache__" not in p.parts}
        expected.update({"Cargo.toml", "Cargo.lock", "pack_aar.py", "README-upstream.md"})
        self.assertEqual(set(self.lock["sha256"]), expected)
        self.assertLessEqual(set(self.lock.get("local_patches", {})), expected)

    def test_all_twenty_shader_fixtures_are_checked(self):
        fixtures = (RUSTGL / "crates/shader-translate/tests/mc_shader_fixtures.rs").read_text()
        names = re.findall(r'const (\w+): &str = r#?"', fixtures)
        self.assertEqual(len(names), 20)
        self.assertIn("assert fixture_count == 20", (RUSTGL / "tests/render_smoke.py").read_text())
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        self.assertIn("MESA_GLES_VERSION_OVERRIDE=3.1 python3 tests/render_smoke.py", workflow)


if __name__ == "__main__":
    unittest.main()
