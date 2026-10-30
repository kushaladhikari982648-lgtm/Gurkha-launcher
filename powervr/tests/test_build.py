import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("build", ROOT / "tools/build.py")
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class BuildTests(unittest.TestCase):
    def test_profiles(self):
        for path in (ROOT / "profiles").glob("*.json"):
            self.assertEqual(build.load_profile(path)["environment"]["LIBGL_GL"], "21")

    def test_unsafe_profile_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "profile.json"
            path.write_text(json.dumps({"environment": {"LIBGL_NOERROR": "1"}}))
            with self.assertRaises(ValueError):
                build.load_profile(path)

    def test_elf_abi_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "libGL.so"
            for abi, machine in build.ABIS.items():
                header = bytearray(20)
                header[:6] = b"\x7fELF" + bytes([2 if abi == "arm64-v8a" else 1, 1])
                header[16:18] = (3).to_bytes(2, "little")
                header[18:20] = machine.to_bytes(2, "little")
                path.write_bytes(header)
                build.verify_elf(path, abi)
                other = next(a for a in build.ABIS if a != abi)
                with self.assertRaises(ValueError):
                    build.verify_elf(path, other)
            path.write_text("not a library")
            with self.assertRaises(ValueError):
                build.verify_elf(path, "arm64-v8a")

    def test_pin(self):
        lock = json.loads((ROOT / "upstream.json").read_text())
        self.assertRegex(lock["commit"], r"^[0-9a-f]{40}$")
        self.assertEqual(lock["license"], "MIT")


if __name__ == "__main__":
    unittest.main()
