# QR Lens

Use a volume-key shortcut anywhere and the screen freezes, a scan sweeps across it, and the view zooms into the QR code. Links get an **Open** button; anything else (text, contacts, events, Wi‑Fi, UPI) opens in the phone's own app or viewer. With several codes on screen you get an overview and tap the one you want.

Android 11+ · on-device detection (ZXing) · no internet permission.

## Features

- **Shortcuts** (picked in the app): 3× / 2× Volume Up, 3× / 2× Volume Down, both volume keys together, Volume Up then Down, long-press Volume Up. Presses that aren't part of the shortcut still change the volume normally.
- **Multiple codes:** all codes are outlined and labelled; tap one to zoom in, tap outside or press Back to return.
- **Wi‑Fi:** the Connect button fills up over ~1.5 s and then opens Android's "Save network?" sheet: in range it connects, otherwise it's saved and joins automatically later.
- **Banking apps:** QR Lens switches itself off when BHIM or some bank apps open (they refuse to run while a sideloaded accessibility app is on). Turn it back on with Android's accessibility shortcut (hold both volume keys 3 s) or the Quick Settings tile.

## Code map (`app/src/main/java/com/qrlens/app/`)

| File | What it does |
|---|---|
| `QrLensService.kt` | Accessibility service: key events, screenshot, auto-pause for bank apps, settings (`Prefs`) |
| `TriggerEngine.kt` | Turns volume-key presses into the chosen shortcut; replays stray presses as normal volume changes |
| `QrDetector.kt` | Finds every QR code in the screenshot (ZXing, normal + inverted, full + half resolution) |
| `ScanView.kt` | All animation: shrink, scan sweep, lock-on brackets, zoom camera, overview, blur |
| `OverlayController.kt` | Overlay window, result card, action bar, overview flow, Wi‑Fi countdown |
| `QrContent.kt` | Parses QR text into links, UPI, Wi‑Fi, contacts, events, SMS, email, plain text |
| `QrActions.kt` | Opens / copies / shares results, Wi‑Fi connect, writes files for native viewers |
| `ShareProvider.kt` | Tiny read-only content provider so viewer apps can open those files |
| `QrLensTile.kt` | Quick Settings tile to pause / resume |
| `MainActivity.kt` | Setup screen, shortcut picker, options |
| `Blur.kt` | Fast background blur |

## Signing key (important)

`app/qrlens.keystore` is **not** in this repo (it's in `.gitignore`). It's the key that signed the APKs you already have, so builds must use it to install as updates.

- **Local builds:** put `qrlens.keystore` in the `app/` folder. Without it, builds use a debug key and you'd have to uninstall the old app first.
- **GitHub Actions:** repo → Settings → Secrets and variables → Actions → New repository secret
  - `KEYSTORE_BASE64` = the full contents of `qrlens.keystore.b64.txt`
  - optional `KEYSTORE_PASSWORD` if you ever change the password (default `qrlens123`)

Never commit the keystore or post the base64 text anywhere public: anyone with it can sign apps that install as updates to yours.

## Build

- **GitHub:** push → the "Build APK" workflow runs (~5 min) → download `QR-Lens.apk` from **Releases**.
- **Local:** Android Studio → Open this folder → Build → Build APK(s), or `./gradlew assembleRelease` (`gradlew.bat` on Windows) → `app/build/outputs/apk/release/app-release.apk`.

Bump `versionCode` / `versionName` in `app/build.gradle.kts` for each release you install over an older one.

## First-time setup on the phone

1. Play Protect blocks sideloaded apps with accessibility in India: Play Store → profile → Play Protect → ⚙️ → turn off **Scan apps with Play Protect**, install, then turn it back on. The app keeps working with it on.
2. Open QR Lens → **Turn on** → turn QR Lens on in Accessibility.
3. Android 13+ "restricted setting": tap it once, then App info → ⋮ → **Allow restricted settings**, and repeat step 2.
4. Optional: in the app, tap **Set up volume-keys shortcut** so holding both volume keys turns QR Lens back on after it pauses for a bank app.

## Notes

- Apps that block screenshots (banking, DRM video) show a black screen to every screenshot tool, so QR Lens can't scan them.
- The Power button can't be used as a shortcut: Android doesn't pass it to apps.
- `preview.gif` is a rendered preview of the scan animation.
