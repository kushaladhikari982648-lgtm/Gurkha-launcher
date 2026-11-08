# Modern UI concept — approval preview only

Open index.html or serve this folder using `python3 -m http.server 8765 --bind 0.0.0.0`.
No Android layouts, themes, navigation, or game settings are changed by this prototype.
All accounts, profiles and settings are sample data. No network APIs, analytics, remote fonts,
or downloads are used. Interactive settings live in memory and reset on reload.

## Proposed design
- Charcoal/soft-white surfaces, muted green accent, 20px cards and 13px buttons.
- Landscape sidebar; portrait bottom navigation and a single-column play card.
- Home, versions, mods empty state, settings and tools; existing secondary actions remain accessible.
- 44–48px controls, visible keyboard focus, wrapping text, native dialog, safe-area padding.
- Short opacity/translation transitions; reduced-motion support; no blur or animated wallpaper.

## Implementation gate
User approval is required before applying this design to the launcher.
Native follow-up should audit insets, keyboard overlap, font scaling, portrait/landscape rotation,
long/localized labels, click listeners, disabled/downloading states, and lifecycle cleanup.
Profile native frame timings on Helio A22 before claiming that Android scrolling is smooth.
Browser-preview tests are not Android performance or gameplay tests.

## Preview validation
Headless Chromium checks passed on all five screens at 1280×800, 800×360,
390×844 and 320×640, with no horizontal document overflow or JavaScript errors.
Tested modal open/Escape close, version search/no-results, selection, memory slider,
switches, dark/light themes and OS/app reduced-motion behavior.
Screenshots: home-preview.png, settings-preview.png and mobile-preview.png.
These are screenshots of this HTML concept, not screenshots of an Android APK.
