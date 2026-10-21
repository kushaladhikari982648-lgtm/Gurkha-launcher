import sys
from pathlib import Path
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1] / "tools"
sys.path.insert(0, str(TOOLS))
import identity


class IdentityTests(unittest.TestCase):
    def make(self, root, getter="4", init="5"):
        (root / "src/gl").mkdir(parents=True)
        (root / "src/gl/getter.cpp").write_text(
            "Krypton Wrapper " * int(getter) + 'return (GLubyte*)"ptitSeb & BZLZHH";')
        (root / "src/gl/init.c").write_text("Krypton Wrapper " * int(init))

    def test_brand_is_replaced_but_vendor_is_kept(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.make(root)
            changed = identity.apply(root)
            self.assertEqual(changed, identity.EXPECTED)
            for relative in identity.EXPECTED:
                text = (root / relative).read_text()
                self.assertNotIn(identity.OLD, text)
                self.assertIn(identity.NEW, text)
            self.assertIn('"ptitSeb & BZLZHH"', (root / "src/gl/getter.cpp").read_text())

    def test_changed_upstream_strings_stop_the_build(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.make(root, getter="3")
            with self.assertRaises(ValueError):
                identity.apply(root)

    def test_name_fits_the_version_buffer(self):
        # globals4es.version is a 64-byte snprintf buffer; do not grow the brand.
        self.assertLessEqual(len(identity.NEW), len(identity.OLD))
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.make(root)
            with self.assertRaises(ValueError):
                identity.apply(root, name="Proton Renderer Unified experimental")


if __name__ == "__main__":
    unittest.main()
