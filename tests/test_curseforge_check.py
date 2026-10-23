"""Offline regression tests for the actual CI authentication checker."""
import io
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError
from urllib.request import Request

from tools.check_curseforge import ENDPOINT, NoRedirects, check_key


class CurseForgeCheckTests(unittest.TestCase):
    def test_missing_key_warns_for_debug_but_blocks_release(self):
        for key in (None, "", " \n", "DUMMY", " dummy "):
            with self.subTest(key=key):
                opener = Mock()
                self.assertEqual(check_key(key, opener=opener)[:2], (True, "missing"))
                self.assertEqual(check_key(key, True, opener=opener)[:2], (False, "missing"))
                opener.open.assert_not_called()

    def test_invalid_header_is_not_sent(self):
        for key in ("abc\ndef", "abc\rdef", "abc def", "abc\x00def", "abc\x7fdef", "キー"):
            with self.subTest(key=key):
                opener = Mock()
                self.assertEqual(check_key(key, opener=opener)[:2], (False, "invalid"))
                opener.open.assert_not_called()

    def test_search_uses_official_endpoint_and_trimmed_key(self):
        opener = Mock()
        opener.open.return_value = io.BytesIO(b'{"data": [{"id": 123}]}')
        result = check_key(" test-only-credential\n", True, opener=opener)
        self.assertEqual(result[:2], (True, "authenticated"))
        request = opener.open.call_args.args[0]
        self.assertEqual(request.full_url, ENDPOINT)
        self.assertEqual(request.get_header("X-api-key"), "test-only-credential")
        self.assertEqual(opener.open.call_args.kwargs["timeout"], 20)
        self.assertNotIn("test-only-credential", result[2])

    def test_rejected_keys_do_not_retry_or_leak_response(self):
        for status in (401, 403):
            opener = Mock()
            body = io.BytesIO(b'private-credential')
            opener.open.side_effect = HTTPError(ENDPOINT, status, "private-credential", {}, body)
            result = check_key("private-credential", opener=opener)
            self.assertEqual(result[:2], (False, "rejected"))
            self.assertNotIn("private-credential", result[2])
            self.assertEqual(opener.open.call_count, 1)
            self.assertTrue(body.closed)

    def test_retries_transient_failures_then_succeeds(self):
        opener = Mock()
        opener.open.side_effect = [HTTPError(ENDPOINT, 429, "rate limit", {}, None),
                                   HTTPError(ENDPOINT, 503, "unavailable", {}, None),
                                   io.BytesIO(b'{"data": []}')]
        sleep = Mock()
        self.assertTrue(check_key("test-key", opener=opener, sleep=sleep)[0])
        self.assertEqual(opener.open.call_count, 3)
        self.assertEqual(sleep.call_count, 2)

    def test_retries_are_bounded_and_errors_are_redacted(self):
        for error, status in ((URLError("private-credential"), "unreachable"),
                              (TimeoutError("private-credential"), "unreachable"),
                              (HTTPError(ENDPOINT, 500, "private-credential", {}, None), "unavailable")):
            opener = Mock()
            opener.open.side_effect = error
            result = check_key("private-credential", opener=opener, sleep=Mock())
            self.assertEqual(result[:2], (False, status))
            self.assertNotIn("private-credential", result[2])
            self.assertEqual(opener.open.call_count, 3)

    def test_unexpected_and_invalid_json(self):
        for body in (b'<html>error</html>', b'null', b'{}', b'{"data": null}', b'[]'):
            opener = Mock()
            opener.open.return_value = io.BytesIO(body)
            self.assertEqual(check_key("test-key", opener=opener)[:2], (False, "invalid-response"))

    def test_redirects_never_forward_credentials(self):
        handler = NoRedirects()
        request = Request(ENDPOINT, headers={"x-api-key": "test-key"})
        for status in (301, 302, 303, 307, 308):
            self.assertIsNone(handler.redirect_request(request, None, status, "redirect", {}, "https://example.org"))
            opener = Mock()
            opener.open.side_effect = HTTPError(ENDPOINT, status, "redirect", {}, None)
            self.assertEqual(check_key("test-key", opener=opener)[:2], (False, "http-error"))
            opener.open.assert_called_once()

    def test_cli_missing_key_sets_exit_code_and_safe_summary(self):
        import tempfile
        from pathlib import Path
        from tools.check_curseforge import main
        with tempfile.TemporaryDirectory() as directory:
            summary = Path(directory) / "summary.md"
            with patch.dict("os.environ", {"GITHUB_ACTIONS": "true", "GITHUB_STEP_SUMMARY": str(summary)}, clear=True), \
                    patch("sys.argv", ["check_curseforge.py", "--require-key"]), \
                    patch("sys.stdout", new_callable=io.StringIO) as output:
                self.assertEqual(main(), 1)
                self.assertIn("::error title=CurseForge API: missing::", output.getvalue())
                self.assertIn("CurseForge API — missing", summary.read_text())


if __name__ == "__main__":
    unittest.main()
