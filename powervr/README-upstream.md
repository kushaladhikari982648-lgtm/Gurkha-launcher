# PowerVR mobile renderer — experimental GL4ES build

## Primary build: one integrated renderer

```sh
python3 tools/build_unified.py --ndk /path/to/ndk --abi arm64-v8a
```

Produces **one `libpowervr.so`**, not two selectable engines. Krypton owns GL state
and dispatch; LTW-derived indirect multidraw code is compiled directly into it.
See [integration scope and build instructions](docs/UNIFIED.md).

**Partial integration:** only LTW's native indirect multidraw forwarding is ported
so far, with additional validation and Krypton state synchronization. This is not
a wholesale merger, verified launcher plugin, or all-Minecraft-versions guarantee.

The earlier [dual-backend package](docs/COMBINED.md) remains available for research,
but it is separate from the single-renderer build above.

## Original GL4ES baseline

An Android native-library build project for experimenting with PowerVR devices in
Minecraft Java launchers. **This is not yet a tested launcher plugin, a new GL
implementation, or a combination of four renderers.** Rendering is performed by
pinned upstream GL4ES, which translates desktop OpenGL to OpenGL ES.

## What works in this repository

- Fetches an exact MIT-licensed GL4ES source revision and compiles it with the Android NDK.
- Separate ARM64 and ARMv7 builds, API 21 baseline, release `-O2`, no unsafe fast-math.
- Conservative GLES 2 / desktop GL 2.1 profile and optional draw-batching experiment.
- Packages `libGL.so`, upstream license and usage guide, environment settings,
  upstream revision, NDK version, and library SHA-256.
- CI builds both ABIs; Python tests validate configuration and ELF-header checking.

**Not implemented in the original baseline:** LTW/Krypton/Rust integration, APK/plugin packaging,
launcher-specific JNI/context bridging, modern desktop GL features, or measured
PowerVR optimizations. No FPS improvement or launcher compatibility is claimed.

## Build

Install Python 3, Git, CMake (3.x recommended), and Android NDK 27.2.12479018.

```sh
export ANDROID_NDK_HOME=/absolute/path/to/android-ndk
python3 -m unittest discover -s tests -v
python3 tools/build.py --abi arm64-v8a
# For a 32-bit launcher/JVM:
python3 tools/build.py --abi armeabi-v7a
```

Output: `artifacts/<abi>/powervr-safe.zip`. Dependencies and build products are
ignored by Git. Builds fetch upstream from GitHub; no prebuilt third-party binary
is downloaded. The upstream commit is pinned in `upstream.json`. This is a
pinned-source build, not a claim of bit-for-bit reproducibility across toolchains.

For an **unbenchmarked** small-draw merging experiment:

```sh
python3 tools/build.py --abi arm64-v8a --profile powervr-batch-experimental
```

Batching may regress performance or rendering correctness. The safe profile
leaves it disabled. Neither profile lies about GL versions, skips GL errors,
disables mipmaps, or unconditionally forces unsupported GPU extensions.

## Launcher integration

The ZIP is **not an installable APK or a universal renderer import format**.
Zalith, Pojav forks, and MJ versions have different native-loader and plugin APIs.
An integrator must package/load `libGL.so` under the filename expected by that
launcher's native bridge, create a compatible EGL/GLES context, and apply the
entries in `launcher.env` **before** loading GL4ES. Select the ABI of the launcher
process/JVM, not just the phone's CPU. Do not replace system GLES libraries.

GL4ES's default desktop target here is OpenGL 2.1. Minecraft versions or mods
requiring newer core-profile GL are outside this baseline; changing a reported
version string does not implement missing features. Start with older compatible
Minecraft versions, no shader packs, and a clean instance.

See [device validation](docs/VALIDATION.md) and [source research](docs/SOURCES.md).

## License

Repository-authored tooling is MIT licensed; see `LICENSE`. GL4ES retains its own
MIT license, copied into each artifact. Unincorporated projects listed in the
research notes retain their respective licenses. No affiliation with those
projects or launchers is implied.
