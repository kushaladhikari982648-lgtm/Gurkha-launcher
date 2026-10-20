import importlib.util
import json
from pathlib import Path
import struct
import sys
import unittest

TOOLS = Path(__file__).resolve().parents[1] / "tools"
sys.path.insert(0, str(TOOLS))
import package_zalith


class ZalithTests(unittest.TestCase):
    def test_java_utf_config(self):
        value = {"rendererDisplayName": "PowerVR \u2603", "pluginVersion": 1}
        data = package_zalith.encode_config(value)
        self.assertEqual(struct.unpack(">H", data[:2])[0], len(data) - 2)
        self.assertTrue(data[2:].isascii())
        self.assertEqual(json.loads(data[2:]), value)

    def test_utf_limit(self):
        with self.assertRaises(ValueError):
            package_zalith.encode_config({"value": "x" * 65536})
