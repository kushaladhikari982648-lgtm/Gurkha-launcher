import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import pack_aar
import check_apk


def elf(abi):
    data = bytearray(64)
    data[:6] = b'\x7fELF' + bytes([2 if abi == 'arm64-v8a' else 1, 1])
    data[16:18] = (3).to_bytes(2, 'little')
    data[18:20] = pack_aar.ABIS[abi].to_bytes(2, 'little')
    return bytes(data)


def build_zip(abi, library=None, checksum=None, source=True):
    library = elf(abi) if library is None else library
    locks = json.loads((ROOT / 'backends.json').read_text())
    metadata = {'abi': abi, 'base': locks['krypton'], 'ltw_port_origin': locks['ltw'],
                'sha256': checksum or hashlib.sha256(library).hexdigest()}
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w') as z:
        z.writestr('libpowervr.so', library)
        z.writestr('build.json', json.dumps(metadata))
        if source:
            z.writestr('corresponding-source.tar.gz', b'test source placeholder')
    return buf.getvalue()


class GurkhaPackagingTests(unittest.TestCase):
    def test_both_arm_abis_and_notices_are_packed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for abi in pack_aar.ABIS:
                (root / abi).mkdir()
                (root / abi / 'powervr-unified.zip').write_bytes(build_zip(abi))
            output = root / 'renderer.aar'
            pack_aar.pack(root, output)
            with zipfile.ZipFile(output) as z:
                libs = {n for n in z.namelist() if n.endswith('.so')}
                self.assertEqual(libs, {f'jni/{abi}/libpowervr.so' for abi in pack_aar.ABIS})
                self.assertIn('assets/powervr/NOTICE.txt', z.namelist())
                self.assertIn('assets/powervr/licenses/LGPL-3.0.txt', z.namelist())

    def test_missing_abi_does_not_make_a_partial_aar(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            output = root / 'renderer.aar'
            with self.assertRaises(FileNotFoundError):
                pack_aar.pack(root, output)
            self.assertFalse(output.exists())

    def test_wrong_elf_checksum_and_missing_source_are_rejected(self):
        locks = json.loads((ROOT / 'backends.json').read_text())
        cases = [build_zip('armeabi-v7a', library=elf('arm64-v8a')),
                 build_zip('armeabi-v7a', checksum='bad'),
                 build_zip('armeabi-v7a', source=False)]
        for data in cases:
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                with self.assertRaises((ValueError, KeyError)):
                    pack_aar.verified_library(z, 'armeabi-v7a', locks)

    def test_apk_arm_requirement_and_x86_exclusion(self):
        for abi, present, valid in [('armeabi-v7a', True, True), ('arm64-v8a', True, True),
                                    ('armeabi-v7a', False, False), ('x86', False, True),
                                    ('x86_64', True, False)]:
            with self.subTest(abi=abi, present=present), tempfile.TemporaryDirectory() as tmp:
                apk = Path(tmp) / 'test.apk'
                with zipfile.ZipFile(apk, 'w') as z:
                    z.writestr(f'lib/{abi}/libother.so', b'other')
                    if present:
                        z.writestr(f'lib/{abi}/libpowervr.so', b'\x7fELFtest')
                if valid:
                    check_apk.check(apk)
                else:
                    with self.assertRaises(ValueError):
                        check_apk.check(apk)
