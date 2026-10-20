import shutil
import subprocess
import tempfile
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class NativeTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("cc"), "C compiler required")
    def test_integrated_multidraw(self):
        with tempfile.TemporaryDirectory() as tmp:
            exe = Path(tmp) / "test"
            subprocess.run(["cc", "-std=gnu11", "-Wall", "-Wextra", "-Werror",
                            str(ROOT / "tests/native_multidraw.c"), "-o", str(exe)], check=True)
            subprocess.run([str(exe)], check=True)
