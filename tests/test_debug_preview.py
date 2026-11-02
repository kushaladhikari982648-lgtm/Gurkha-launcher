import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('preview', ROOT / 'tools/publish_debug_preview.py')
PREVIEW = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREVIEW)


class PreviewPublicationTests(unittest.TestCase):
    def test_tag_is_separate_from_the_signed_release(self):
        data = PREVIEW.request()
        self.assertTrue(data['tag'].startswith('preview-v'))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'request.json'
            data['tag'] = 'v' + data['version']
            path.write_text(json.dumps(data))
            with patch.object(PREVIEW, 'REQUEST', path), self.assertRaises(RuntimeError):
                PREVIEW.request()

    def test_only_stages_existing_artifacts_without_secrets_or_publication(self):
        flow = (ROOT / '.github/workflows/publish-debug-preview.yml').read_text()
        self.assertIn('actions/download-artifact@v8', flow)
        self.assertIn('run-id: ${{ steps.source.outputs.run_id }}', flow)
        self.assertNotIn('secrets.', flow)
        self.assertNotIn('--clobber', flow)
        self.assertNotIn('--draft=false', flow)
        self.assertNotIn('assembleRelease', flow)
        self.assertIn('powervr-unified-sources', flow)

    def test_apk_package_version_and_abi_guards(self):
        data = PREVIEW.request()
        badging = f"package: name='com.gurkha.launcher.debug' versionCode='{data['version_code']}' versionName='{data['version']}'\napplication-debuggable"
        signature = 'Signer #1 certificate SHA-256 digest: ' + 'a' * 64
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'fixture.apk'
            elf = bytearray(20)
            elf[:4] = b'\x7fELF'
            elf[18] = 40
            with zipfile.ZipFile(path, 'w') as archive:
                for library in ('libgurkha_core.so', 'librust_gl.so'):
                    archive.writestr('lib/armeabi-v7a/' + library, elf)
            with patch.object(PREVIEW, 'run', side_effect=[badging, signature]):
                self.assertEqual(PREVIEW.inspect_apk(path, 'arm', data, 'aapt', 'apksigner'), 'a' * 64)
            with patch.object(PREVIEW, 'run', return_value=badging.replace('.debug', '')), self.assertRaises(RuntimeError):
                PREVIEW.inspect_apk(path, 'arm', data, 'aapt', 'apksigner')
            with patch.object(PREVIEW, 'run', return_value=badging.replace(str(data['version_code']), '1')), self.assertRaises(RuntimeError):
                PREVIEW.inspect_apk(path, 'arm', data, 'aapt', 'apksigner')
            with patch.object(PREVIEW, 'run', side_effect=[badging, signature]), self.assertRaises(RuntimeError):
                PREVIEW.inspect_apk(path, 'arm64', data, 'aapt', 'apksigner')

    def test_non_draft_or_wrong_target_is_rejected_before_download(self):
        data = PREVIEW.request()
        draft = dict(id=data['draft_id'], isDraft=False, tagName=data['tag'], targetCommitish=data['commit'], assets=[])
        with patch.object(PREVIEW, 'run', return_value=json.dumps(draft)), self.assertRaises(RuntimeError):
            PREVIEW.validate(data)
        draft['isDraft'] = True
        draft['targetCommitish'] = 'main'
        with patch.object(PREVIEW, 'run', return_value=json.dumps(draft)), self.assertRaises(RuntimeError):
            PREVIEW.validate(data)

    def test_sha256_streaming(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'sample'
            path.write_bytes(b'abc')
            self.assertEqual(PREVIEW.sha256(path), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')


if __name__ == '__main__':
    unittest.main()
