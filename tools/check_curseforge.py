#!/usr/bin/env python3
"""Check the build's CurseForge credential without logging credentials or response bodies.

No dependencies. Used by CI and by maintainers with CURSEFORGE_API_KEY in their
local environment. Never follows redirects: the key must only go to CurseForge.
"""
import argparse
import json
import os
import time
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener

ENDPOINT = "https://api.curseforge.com/v1/mods/search?gameId=432&classId=6&pageSize=1"


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def check_key(key, required=False, *, opener=None, sleep=time.sleep):
    """Return (success, status, safe diagnostic); never return response/exception text."""
    key = (key or "").strip()
    if not key or key.upper() == "DUMMY":
        return (not required, "missing",
                "CurseForge is DISABLED in this build. Add the CURSEFORGE_API_KEY repository "
                "secret under Settings > Secrets and variables > Actions, then rebuild. "
                "Modrinth remains available. See RELEASING.md.")
    if not key.isascii() or any(c.isspace() or ord(c) < 32 or ord(c) == 127 for c in key):
        return False, "invalid", "The CurseForge key contains invalid header characters. Replace the secret; do not post it in logs or issues."

    opener = opener or build_opener(NoRedirects())
    request = Request(ENDPOINT, headers={
        "x-api-key": key,
        "Accept": "application/json",
        "User-Agent": "GurkhaLauncher-CI/1.0",
    })
    for attempt in range(3):
        try:
            with opener.open(request, timeout=20) as response:
                # Bound memory even if the service sends an unexpected response.
                payload = json.loads(response.read(65536))
            if not isinstance(payload, dict) or not isinstance(payload.get("data"), list):
                return False, "invalid-response", "CurseForge returned an unexpected search response. Try the check again later."
            return True, "authenticated", "CurseForge authentication and Minecraft mod search passed. This does not verify every file download."
        except HTTPError as error:
            status = error.code
            error.close()
            if status in (401, 403):
                return False, "rejected", "CurseForge rejected the key (HTTP 401/403). Check the app approval/key in the CurseForge console and replace CURSEFORGE_API_KEY."
            if status == 429 or 500 <= status < 600:
                if attempt < 2:
                    sleep(2 ** attempt)
                    continue
                return False, "unavailable", "CurseForge is rate-limiting requests or unavailable after three attempts. Retry the workflow later."
            return False, "http-error", f"CurseForge returned HTTP {status}. Redirects are not followed to protect the key."
        except (URLError, TimeoutError, OSError):
            if attempt < 2:
                sleep(2 ** attempt)
                continue
            return False, "unreachable", "Could not reach CurseForge after three attempts. Check service/network availability and retry."
        except (ValueError, UnicodeError):
            return False, "invalid-response", "CurseForge returned invalid JSON. Try the check again later."


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-key", action="store_true",
                        help="Fail rather than warn when no key is configured (required for releases).")
    args = parser.parse_args()
    ok, status, message = check_key(os.environ.get("CURSEFORGE_API_KEY"), args.require_key)
    if os.environ.get("GITHUB_ACTIONS") == "true":
        level = "error" if not ok else "warning" if status == "missing" else "notice"
        print(f"::{level} title=CurseForge API: {status}::{message}")
    else:
        print(f"CurseForge API: {status}. {message}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as output:
            output.write(f"## CurseForge API — {status}\n\n{message}\n")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
