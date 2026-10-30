# Third-party components added by Gurkha Launcher

Gurkha Launcher is based on [Zalith Launcher](https://github.com/ZalithLauncher/ZalithLauncher) and
[PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher). On top of the components those
projects already ship, it bundles:

| Component | Files | License | Source |
|---|---|---|---|
| LTW (Large Thin Wrapper) OpenGL renderer | `ZalithLauncher/libs/ltw-release.aar` | LGPL-3.0 | [MojoLauncher/LTW](https://github.com/MojoLauncher/LTW), commit in `ZalithLauncher/libs/ltw-release.txt` |
| SDL 3 and sdl2-compat | `ZalithLauncher/libs/SDL-release.aar` | zlib | [libsdl-org/SDL](https://github.com/libsdl-org/SDL), [libsdl-org/sdl2-compat](https://github.com/libsdl-org/sdl2-compat); build of [AngelAuraMC/amethyst-prebuilt-libraries](https://github.com/AngelAuraMC/amethyst-prebuilt-libraries) |
| SDL Android Java glue | `ZalithLauncher/src/main/java/org/libsdl/app/` | zlib (SDL), with changes from Amethyst (LGPL-3.0) | SDL `android-project`, as modified by [AngelAuraMC/Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android) |
| LWJGL 3.4.1 jars and natives | `ZalithLauncher/src/main/assets/components/lwjgl3-sdl/`, `ZalithLauncher/libs/lwjgl-3.4.1-natives-release.aar` | BSD-3-Clause (LWJGL), see the licenses inside the aar for its bundled libraries (FreeType, shaderc, spng, VMA, ...) | [LWJGL](https://github.com/LWJGL/lwjgl3), builds of [AngelAuraMC/lwjgl3](https://github.com/AngelAuraMC/lwjgl3) used by Amethyst |
| Patched LWJGL classes `SDLInit`, `SDLMouse` | `jre_lwjgl3sdl/src/main/java/org/lwjgl/sdl/` | BSD-3-Clause (LWJGL), changes from Amethyst (LGPL-3.0) | Amethyst-Android `jre_lwjgl3glfw/lwjgl-3.4.1` |
| SPIRV-Cross | `ZalithLauncher/libs/spirv-cross-natives.aar` | Apache-2.0 | [KhronosGroup/SPIRV-Cross](https://github.com/KhronosGroup/SPIRV-Cross), build used by Amethyst |
| Krypton Wrapper (NG-GL4ES) OpenGL renderer | `ZalithLauncher/libs/ng_gl4es-release.aar` | MIT (based on gl4es, MIT); contains glslang (BSD-3-Clause and others), SPIRV-Cross (Apache-2.0), cJSON (MIT) and code from MobileGlues (LGPL-2.1) | [BZLZHH/NG-GL4ES](https://github.com/BZLZHH/NG-GL4ES) R0.4.5, built for this launcher, details in `ZalithLauncher/libs/ng_gl4es-release.txt` |
| RustGL OpenGL renderer (`librust_gl.so`) | `ZalithLauncher/libs/rustgl-release.aar`, source in `rustgl/` | **No license file upstream** — used with the author's permission; confirm terms with [whaltermc/rust-renderer](https://github.com/whaltermc/rust-renderer) before redistribution | [RustGL-for-Gurkha-launcher](https://github.com/kushaladhikari982648-lgtm/RustGL-for-Gurkha-launcher) at `3d132103477ab1a3e0465f4c0eba01f5d389c90f` (`rustgl/`), derived from whaltermc/rust-renderer via Gurkha; pin/hashes in `rustgl/source-lock.json`, retained notice in `rustgl/NOTICE-source.txt` |
| PowerVR Unified renderer (`libpowervr.so`) | `ZalithLauncher/libs/powervr-release.aar`, integration in `powervr/` | Krypton/bundled dependency licenses; LTW-derived module **LGPL-3.0-only**; source-project tooling MIT | [Powervr-the-poop](https://github.com/kushaladhikari982648-lgtm/Powervr-the-poop) @ `1999cd5ee8245b20cacd322eeab6ee2d00a81110`; pinned base/port revisions in `powervr/backends.json` |

PowerVR's complete modified corresponding source, recursive submodules and original notices
are distributed in the **powervr-unified-sources** CI artifact and the source-containing ZIPs
attached to tagged releases. Keep those ZIPs with redistributed APKs; see `powervr/NOTICE.txt`
and `powervr/README.md`.

The launcher-side SDL integration (`CallbackBridge`, `MinecraftGLSurface`, `SdlSupport`, the
`nativeNotifyLauncher` bridge) is ported from Amethyst-Android (LGPL-3.0). `ltw_sdl_egl.c` is original
to Gurkha Launcher and is distributed under the GPL-3.0 like the rest of the launcher.

The RustGL snapshot also carries explicit Gurkha draw-path patches; upstream and patched digests are
separately recorded in `rustgl/source-lock.json`. See `rustgl/README.md` for their scope.

The Minecraft-inspired launcher scenery is AI-generated original artwork; the small grass, wood and
stone UI tiles are generated for Gurkha. They are not extracted Minecraft/Mojang assets.
