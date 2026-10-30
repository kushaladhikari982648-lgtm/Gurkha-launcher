# Branding

| File | What it is |
|---|---|
| `gurkha-logo.png` | The logo: white line-art crossed khukuris on crimson (`#C42425`), 1600×1600 source image |
| `gurkha-icon.png` | 1024×1024 app icon made from it (README, release page, store listings) |
| `make_icons.py` | Builds the launcher icons (adaptive foreground and themed-icon layer, legacy icons, notification icon) and `gurkha-icon.png` from the logo |

After changing the logo, run `python3 branding/make_icons.py` from the repository root
(`pip install pillow numpy` first). If the new logo has a different background colour or position,
update `BG`, `CENTRE` and `RADIUS` at the top of the script and the colour in
`ZalithLauncher/src/main/res/drawable/ic_launcher_background.xml`.
