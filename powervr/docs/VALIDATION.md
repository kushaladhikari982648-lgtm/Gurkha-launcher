# Validation and optimization checklist

## Automated checks

`python3 -m unittest discover -s tests -v` tests tooling, not rendered output.
GitHub Actions cross-compiles the pinned source for ARM64 and ARMv7 and checks
the output's ELF class, endianness, shared-object type and machine ID. A valid ELF
header does not prove Android loadability, exported symbols, or GPU correctness.

No Android device or NDK is installed in the development sandbox. Native build
success must be confirmed from CI; a passing CI build still is not a device test.

## Device acceptance gate

Record phone model, PowerVR GPU model, Android/driver version, launcher version,
JVM ABI/version, Minecraft version, renderer hash, mod list and environment.

1. Verify the launcher can load the library and resolve all required GL/context
   bridge symbols. Collect logcat and launcher logs; do not hide missing symbols.
2. Verify EGL context creation and reported vendor/renderer/version against the
   expected PowerVR device. Check GL errors during a small triangle/texture test.
3. Test menus, world loading, chunk rebuilds, transparency, fog, mipmaps, text,
   lighting, framebuffer resizing, background/resume and repeated world switching.
4. Run identical fixed-route scenes for 5 minutes after warm-up. Keep render
   distance, resolution, world seed, power mode and thermal state the same.
5. Record median, p95 and p99 frame times (not only average FPS), peak memory,
   thermal throttling, battery draw, visual artifacts and crashes.
6. Repeat at least three times for safe vs experimental batching, alternating
   order. Revert if correctness changes or tail latency worsens.

Keep experimental batching opt-in until multiple PowerVR driver generations pass.
Do not add vendor workarounds without a reproducible failing scene and a narrowly
scoped capability/driver gate. Never claim Vulkan, modern GL, or shader-pack support
from changing version strings alone.
