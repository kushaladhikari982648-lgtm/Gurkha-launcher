"""Bundle the compiled core's source, locked dependencies and offline build configuration."""
from pathlib import Path
import zipfile
root = Path(__file__).resolve().parents[1]
core = root / 'launcher-core'
extra = root / 'release-extra'
extra.mkdir(exist_ok=True)
assert (core / 'vendor').is_dir()
with zipfile.ZipFile(extra / 'Rust-core-corresponding-source.zip', 'w', zipfile.ZIP_DEFLATED, strict_timestamps=False) as z:
    for name in ('LICENSE', 'THIRD_PARTY_NOTICES.md'):
        z.write(root / name, 'Gurkha-launcher/' + name)
    for name in ('Cargo.toml', 'Cargo.lock', 'README.md', 'pack_aar.py'):
        z.write(core / name, 'Gurkha-launcher/launcher-core/' + name)
    for folder in ('src', 'vendor'):
        for path in sorted((core / folder).rglob('*')):
            if path.is_file(): z.write(path, 'Gurkha-launcher/' + str(path.relative_to(root)))
    z.writestr('Gurkha-launcher/launcher-core/.cargo/config.toml',
               '[source.crates-io]\nreplace-with = "vendored-sources"\n[source.vendored-sources]\ndirectory = "vendor"\n')
    z.writestr('Gurkha-launcher/ZalithLauncher/libs/', '')
