# Low-end device mode

Designed as a conservative starting point for Helio A22 / PowerVR GE8300-class phones, not an
FPS guarantee. Open **Settings → Launcher → Performance → Low-end device mode**.

The setting defaults **on** when Android reports less than 3072 MB total RAM, or its hardware/board/
SoC name identifies MT6761 / Helio A22 (including 4 GB models). Unknown hardware is not classified
solely by CPU core count. Other weak devices can enable the setting manually. Saved on/off choices
are respected; no GPU capabilities are spoofed by this detection.

## What changes

| Resource | Mode on | Mode off |
|---|---|---|
| Initial Java heap (`-Xms`) | Up to 512 MB, never greater than your selected maximum | Equal to your selected maximum, as before |
| Maximum Java heap (`-Xmx`) | **Your selected RAM allocation, unchanged** | Your selected RAM allocation |
| Game/modpack download workers | Effective maximum 4, or a lower saved setting/task count | Saved maximum, validated within 1–64 |
| Concurrent mod-jar scans | Up to 2 (or fewer CPUs/files) | Up to the CPU count, capped at 8 |
| Download progress polling | At most 5 updates/second | Existing 30/50 updates/second |
| Clouds in a **new** game options file | Disabled | Existing default template |
| Existing graphics, renderer and FPS settings | **Unchanged** | Unchanged |

Downloads retain the existing core-worker/queue strategy; the maximum is a ceiling, not a promise
that all workers are active. The profile does **not** overwrite the saved download-thread slider;
disabling it restores the saved limit for the next operation. Checksum verification stays enabled.
The game download pool now also handles an empty task list safely, and download workers are shut
down on failure/interruption rather than left working in the background.

Mod scans bound coroutine creation as well as simultaneous hashing/decompression. A quad-core A22
no longer schedules up to 32 jar readers competing for the same flash storage.

A detected low-end device also gets **75% resolution as its default** when no resolution preference
has been saved, extending the existing low-RAM default to MT6761 models with more RAM. This is a
separate editable graphics preference; toggling the mode does not reset an existing resolution.

### Revised initial-heap budget

The initial-heap ceiling was raised from 256 MB to 512 MB after a report of game stutters. The common
500 MB selection now starts at 500 MB instead of being forced to grow from 256 MB. This removes that
particular growth pressure, but commits more memory initially and is not proof that GC caused the
reported spikes. The maximum selected allocation is still unchanged.

## Important trade-offs

- A smaller initial heap lowers initial heap commitment, not the maximum or necessarily the peak
  memory used during gameplay. It can mean more garbage collection while a large game is starting.
  Disable the mode and compare if startup stutters get worse. There are no new collector flags,
  Java-version workarounds, CPU-affinity changes or forced processor-count reductions.
- Less download/scanning concurrency may lower peak throughput on fast storage/networks. It is
  intended to leave more CPU and memory for the interface and Android, not to maximize download speed.
- Clouds are changed **only** when the launcher first copies its bundled `options.txt` into a game
  directory where that file is absent. Existing profiles and worlds are not rewritten. For an existing
  profile, turn clouds off in Minecraft yourself if desired. The change to a new profile remains an
  editable game preference even after disabling the mode.
- Settings apply to the **next** download, scan or game launch. An operation already running retains
  its original budget. The launcher logs the mode, initial heap and maximum heap when starting Java.
- The mode does not cap FPS, force V-Sync off, switch renderers, install mods or touch thermal controls.

## RustGL update

The built-in renderer is pinned to
[`3d132103477ab1a3e0465f4c0eba01f5d389c90f`](https://github.com/kushaladhikari982648-lgtm/RustGL-for-Gurkha-launcher/commit/3d132103477ab1a3e0465f4c0eba01f5d389c90f)
on the source repository's Android branch. It adds current-program tracking, reusable quad indices,
legacy shader attribute/matrix handling and additional shader fixtures on top of the previous caching
work. See [RustGL provenance and limits](../rustgl/README.md). These changes do not establish full
shader-pack support or support for every Minecraft version. SDL-based games still follow the LTW
fallback; no Vulkan dependency is added for GE8300.

## Checks and device comparison

Automated checks cover the actual Java budget calculations and thread-pool execution (including
zero/one/many tasks), source hashes, settings/resource wiring and all twenty shader fixtures plus
Mesa GLES rendering regressions. These are **not** Helio A22 benchmarks or Android UI visual tests.

On the phone, compare mode on/off with the same vanilla Minecraft version, runtime, renderer,
world, resolution, render distance and FPS setting:

1. Back up important worlds and settings. Keep a copy of the previous working APK.
2. Start with render distance 4, simulation distance 5, clouds off and 75% resolution; these are
   starting points, not guaranteed optimal values. Avoid heavy shader packs for the baseline.
3. Close other heavy apps, allow the phone to cool, and test plugged/unplugged consistently.
4. Measure time to the title screen and world, sustained frame times, and process memory after the
   same several-minute route—not just the highest FPS seen. Repeat both modes.
5. Check background/resume, mod scanning, a small download, and cancellation. If something regresses,
   disable the mode and share the exact game version plus the launcher/game log (redact credentials).

Do not assume a 500 MB heap can run every modern version or modpack. Too much heap can also leave
Android/native textures without enough memory, particularly on 32-bit systems.
