# Minecraft-inspired home and draw-path stability work

## Selected design

The user chose the grass-and-stone concept from two generated image previews. The native home screen
now uses a static voxel overworld, squared stone controls, a grass-accented header/Play button and
wood footer. This is an adaptation, not a screenshot-perfect reproduction of the generated mockup.

Navigation remains functional: versions, per-version mods, settings, controls, files, JAR installer,
log sharing, About, account switching and launching. The profile, renderer preference and RAM limit
are read from real settings; no hardcoded Minecraft version, fake FPS or “all systems ready” claim.
The renderer label is a preference: launch-time compatibility policy can still choose a fallback.

There are separate landscape and portrait layouts. Both navigation and profile panes scroll for
smaller screens / larger fonts. Existing custom backgrounds remain visible instead of being replaced
by the bundled scenery. The shared account-management layout is unchanged; only its home instance
gets a compact presentation. Other settings/management pages keep their existing layouts.

The background is a single WebP no larger than 960 × 540 (about 2 MB decoded as RGBA); tiles are
32 × 32. There is no WebView, live blur, animated panorama, polling UI or new rendering engine for
menus. Home version labels no longer run endless marquees. These choices avoid adding continuous
UI animation load; they do not imply improved Minecraft FPS.

## What was actually addressed

The report identifies gameplay spikes on Helio A22 / GE8300, but provides no game version, mods,
frame-time trace or log. The exact cause remains unconfirmed.

1. RustGL's legacy draw path read an environment variable repeatedly, incremented diagnostic counters
   and queried driver errors after every draw. The debug flag is now cached, and counters/post-draw
   error polling are debug-only. Normal `glGetError` still exposes errors to applications.
2. The quad cache grew to exactly each new requested size. Gradually increasing batches repeatedly
   allocated CPU vectors and uploaded/reallocated the index buffer. It now grows geometrically for
   smaller requests, limiting extra capacity to 96 KiB for larger requests. The requested prefix is
   still the draw count. Allocation/upload failure does not publish an invalid cache.
3. The previous low-end mode forced a common 500 MB Java allocation to start at 256 MB. The initial
   ceiling is now 512 MB, so a 500 MB allocation starts at 500 MB without forced growth. This trades
   higher initial memory commitment for less growth pressure. Maximum RAM and GC choice are unchanged.

The legacy-shader quad path also restores the game VAO’s element-buffer binding, preventing our
quad cache from silently replacing the game’s buffer for subsequent indexed draws.

No GPU synchronization or shader validation was bypassed, and no unsupported Vulkan path was added.
RustGL already is Rust; rewriting Android menus in Rust would not fix game-world generation or GC.

## Validation and remaining work

- Offline XML/reference/control-wiring tests, source/patch digest checks and Java resource-policy tests.
- Rust unit test: requests increasing from 1 to 4096 quads require 13 capacity increases, not 4096.
  This measures allocation-count policy, **not phone frame times**.
- Mesa pixel readbacks for increasing and decreasing quad batches plus the existing GLES fixture suite.
- Android CI compiles all APK variants and verifies renderer packaging.
- Native Android layout screenshots, touch/focus/accessibility checks and gameplay timings must still
  be checked on a phone. No claim that every reported lag spike has been fixed.

For reproduction, record the exact Minecraft version, loader/mods, Java runtime, renderer, RAM limit,
resolution and render distance. Test the same world/route with a cool phone. Note whether hitches
happen only on first load, on new chunks, periodically while standing still, or after the device heats
up. Share logs using the home-screen log button, redacting account tokens and personal paths. Do not
turn on verbose renderer debugging for a performance comparison; it deliberately adds diagnostics.
