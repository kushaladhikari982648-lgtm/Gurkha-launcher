# Rust launcher core

A separate `libgurkha_core.so`, not the RustGL graphics library. Loading it does not create a GPU
context or change the selected renderer. Android UI/navigation stay native Java/Kotlin.

## Integrated operations

- FileTools' default **SHA-256 file hashing**, including mod scans, now uses RustCrypto SHA-256 with
  one 64 KiB streaming buffer and cancellation checks. Other digest algorithms and InputStream APIs
  retain their Java implementations. Output is identical lowercase SHA-256, so existing caches remain
  compatible. File contents are not loaded wholesale into memory. No throughput improvement over
  Android's crypto provider is claimed without device benchmarks.
- **Settings and mod-cache replacement** writes a unique private temporary file beside the target,
  syncs contents and atomically renames it into place. An error before rename leaves the old target
  intact. Rust attempts a directory sync too; unsupported directory sync is best effort, so this is
  not a guarantee against all power-loss/filesystem failures. This is for small metadata files, not
  a world-save migration or a backup system.
- Settings now copy/merge their map under the existing write lock to avoid lost concurrent updates.
  Failed/cancelled mod scans no longer replace a good cache with a partial scan result.

The JNI boundary converts Rust I/O errors to IOException/InterruptedIOException, preserves pending
JVM exceptions, catches ordinary unwinding Rust panics, and does not clear the Java interrupt flag.
Fatal native faults or allocation aborts cannot be recovered this way. If the library or JNI symbol
cannot load, Java falls back to bounded hashing and a synced atomic move (Android API 26+). Real
file errors are **not** silently retried or reported as success. Missing libraries must still fail
our APK packaging checks; fallback is defense in depth, not an excuse for incomplete APKs.

## Build and validation

CI runs Rust tests and loads the actual Linux JNI library from Java, then repeats the Java test in a
separate process with no native library. It covers chunk boundaries, non-ASCII/emoji paths, empty
files, read/write errors, interruption, concurrent whole-file replacement and temporary-file cleanup.
Each APK is checked for the correct ELF library in every ABI it contains.

```sh
cd launcher-core
cargo test --locked
cargo build --locked
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 build --release --locked
python3 pack_aar.py
```

The first bootstrap CI build generates Cargo.lock and commits it alongside the built AAR. Subsequent
builds use that lock. Direct dependencies are exact-pinned; the lock pins their transitive graph.
`ZalithLauncher/libs/launcher-core-release.txt` records the source-tree fingerprint and CI run.
Code is under the launcher's GPLv3-or-later terms; jni and sha2 use their upstream dual MIT/Apache-2.0
licenses. This crate does not inherit the unlicensed upstream renderer's code.
