# Full launcher theme and Rust core

## Minecraft-inspired native UI

The selected settings concept extends the existing home design: dark stone panels, grass-green
selection/focus accents, square controls and a wood settings footer. Shared cards, lists, edit boxes,
buttons and dialogs now use this palette and shape language, covering settings, versions/profiles,
mod/download browsers, accounts and file/tools screens without replacing their functional layouts.
Settings retain all five existing categories, their order, pager bindings and persistence behavior.
Light/system and night modes both use the chosen stone style; night uses a darker backdrop.

The launcher activity now owns one shared static overworld background; the home screen no longer
draws an extra copy. User-selected custom backgrounds still take precedence. There are no live
panoramas, web menus, new frame loops or forced game graphics changes. Square switches/seek bars
retain native semantics and touch behavior. External system file pickers, authentication web pages,
Minecraft's own UI and user-authored in-game control layouts are not redesigned by these resources.

Generated concepts are references, not screenshots or an exact pixel-for-pixel Android layout.
Automated resource checks/builds do not replace phone checks for clipping, large fonts, focus,
screen readers and touch targets. Existing dialogs/layout controls remain usable; no fake theme
selector or dummy setting from an illustration was added.

## Renderer retirement

The standalone **Freedreno (Adreno)** renderer and its EGL dispatch branch are removed. Its old
renderer ID and UUID are recognized at read/selection time and resolve to the built-in GL4ES GLES
fallback. Other saved choices, including RustGL, are untouched. No game profile is deleted or
rewritten. Plugins cannot re-register the retired ID/UUID.

`libvulkan_freedreno.so` referenced by the separate **Turnip Vulkan driver loader** is not the removed
Gallium renderer. That driver path remains for existing Zink/driver functionality on Adreno; it is
not selected for GE8300 by this change.

## Rust and stability

See [launcher-core](../launcher-core/README.md): this adds real launcher-side Rust for streaming
SHA-256 and atomic settings/cache replacement, independent of RustGL. It also fixes a concurrent
settings merge race and avoids publishing partial mod caches after failure/cancellation. Java
fallbacks retain compatibility if native linking fails; CI requires native binaries in every APK.

This is an incremental native core, not a claim that Java/Kotlin is inherently unstable or that all
launcher code is Rust. It does not promise more Minecraft FPS or resolve unmeasured gameplay spikes.
The previous tested RustGL rendering fixes remain intact. Physical Helio A22/GE8300 measurements,
end-to-end Android cancellation tests and UI visual/accessibility checks remain necessary.
