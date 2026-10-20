import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("selector", Path(__file__).resolve().parents[1] / "tools/select_backend.py")
selector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(selector)


class SelectorTests(unittest.TestCase):
    def test_legacy(self):
        for version in ["1.7.10", "1.12.2", "1.16.5"]:
            result = selector.select(version, "3.0")
            self.assertEqual(result["backend"], "krypton")
            self.assertFalse(result["verified_compatible"])

    def test_modern(self):
        for version in ["1.17", "1.20.1", "1.21.4"]:
            self.assertEqual(selector.select(version, "3.2")["backend"], "ltw")

    def test_fail_closed(self):
        for version, gles, backend in [("1.21", "3.0", "auto"), ("snapshot", "3.2", "auto"),
                                        ("1.12.2", "3.2", "ltw"), ("1.16.5", "2.0", "auto")]:
            with self.assertRaises(ValueError):
                selector.select(version, gles, backend)

    def test_modern_krypton_opt_in(self):
        self.assertEqual(selector.select("1.21", "3.2", "krypton")["backend"], "krypton")
