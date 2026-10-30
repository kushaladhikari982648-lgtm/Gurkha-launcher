# Combined LTW + Krypton package (experimental)

This package combines distribution and selection, **not the two GL implementations
inside one shared object**. Both export overlapping GL entry points and maintain
independent context/shader state. Load exactly one backend per game process.
Never switch mid-frame or reuse one backend's GL objects in the other.

## Build from source

Python 3, Git, CMake 3.31.6, and Android NDK 28.2.13676358 are the CI toolchain.

```sh
python3 tools/build_combined.py --ndk /path/to/ndk --abi arm64-v8a
# Also supports --abi armeabi-v7a (upstream support must pass CI).
```

The result is `artifacts/<abi>/powervr-combined.zip`. It contains:

- `krypton/libng_gl4es.so`: pinned Krypton Wrapper / NG-GL4ES.
- `ltw/libltw.so`: pinned MojoLauncher LTW, not an unverified binary collection.
- A full corresponding-source archive per backend, including pinned recursive
  submodules, copyright/license files and any upstream build instructions.
- Build scripts, source revisions, NDK version, and library SHA-256 metadata.

Source and submodule versions come from the pinned upstream commits. Sources are
archived before building; build products are not copied into source archives.
Third-party licenses remain applicable. In particular LTW is LGPL-3.0: keep it as
an independently replaceable library and preserve corresponding source and notices
when redistributing. The root MIT license covers only our original tooling.

## Choose a backend

```sh
python3 tools/select_backend.py --minecraft 1.12.2 --gles 3.0
python3 tools/select_backend.py --minecraft 1.21.4 --gles 3.2
# Explicit experimental alternative, not a compatibility guarantee:
python3 tools/select_backend.py --minecraft 1.21.4 --gles 3.2 --backend krypton
```

`--gles` must be the real driver capability, not a fabricated version string.
The selector recommends Krypton for stable 1.x versions before 1.17 and LTW
starting at 1.17. These are **heuristics**, not a tested version support matrix.
Conservative policy requires GLES 3.0 for legacy Krypton and GLES 3.2 for modern
routes; even that does not establish all required desktop GL features.
Snapshots, pre-releases and new numbering schemes require an explicit backend.
No version, including older ones, has been device-validated by this project.

The JSON output identifies the library and launcher environment hints. An adapter
must apply them BEFORE loading the backend and before creating the EGL context.
Those hints alone do not implement a launcher's native bridge. Fully restart the
game to try the other backend; automatic in-process fallback is unsafe.

## What you can and cannot install

This ZIP is **not an APK or a ready-to-import universal plugin**. Zalith/Pojav/MJ
forks have different renderer registration, JNI and EGL bridge contracts. Their
exact versions need adapters and on-device load tests. An integrator must use the
backend's own entry points and context semantics, not just rename it over an
arbitrary renderer. Do not overwrite Android's system GLES libraries.

## Why “all Minecraft versions” is not promised

Minecraft releases vary in GL profile, shader language, Java/LWJGL requirements
and required features. LTW and Krypton translate different subsets. Neither their
union nor an advertised GL version guarantees every mod, shader pack, GPU driver,
or future release will work. CPU/JVM requirements are outside a renderer's control.

Test one clean instance first, collect launcher/logcat errors, and follow
`docs/VALIDATION.md` in the repository. Only successful device tests should be
added to a future compatibility matrix. There is no measured PowerVR speedup yet.
