# CLAUDE.md — QR Lens

Guidance for Claude (and any AI coding tool) working in this repo. Read this before making changes.

## What this app is

Android app (Kotlin, min SDK 30 / Android 11+) that scans QR codes already on the
screen. The user triggers it with a volume-key shortcut; an AccessibilityService
takes a screenshot, detects QR codes on-device with ZXing, and shows a zoom-in
overlay with the decoded content and actions (Open / Copy / Share / Wi-Fi connect).

**Privacy is a core feature, not a nicety:**
- The app has **no `INTERNET` permission** and must never request it. Everything runs on-device.
- Do not add analytics, crash reporters, ad SDKs, or any network call.
- The AccessibilityService uses `canRetrieveWindowContent="false"` — it must never read
  other apps' window content. It is used only for volume-key events and `takeScreenshot()`.

## Build & release

- Version lives in **one place**: `val appVersion` at the top of `app/build.gradle.kts`
  (semantic, `MAJOR.MINOR.PATCH`). `versionCode` is derived from it. Bump it to release.
- Pushing changes under `app/**` or the build files triggers `.github/workflows/build.yml`,
  which builds a signed APK and, if the version is new, publishes a GitHub Release `vX.Y.Z`.
- The release keystore is **not** in the repo (`.gitignore`). CI restores it from the
  `KEYSTORE_BASE64` secret. Never commit a keystore or paste its base64 anywhere.
- There is no local Gradle cache in most dev setups here; rely on CI to compile unless the
  user says they have Android Studio / SDK installed.

## Code map (`app/src/main/java/com/qrlens/app/`)

| File | Responsibility |
|---|---|
| `QrLensService.kt` | AccessibilityService: key events, screenshot capture, auto-pause for bank apps, `Prefs` |
| `TriggerEngine.kt` | Volume-key gesture recognition; replays non-matching presses as real volume changes |
| `QrDetector.kt` | ZXing multi-pass detection (full + half res, normal + inverted) |
| `ScanView.kt` | All drawing/animation in one custom View via a "camera" transform |
| `OverlayController.kt` | Overlay window lifecycle, result card, action bar, multi-code overview, Wi-Fi countdown |
| `QrContent.kt` | Parses raw QR text into typed content (link, UPI, Wi-Fi, vCard, event, sms, email, text) |
| `QrActions.kt` | Executes actions: open/copy/share, Wi-Fi connect, writes files for native viewers |
| `ShareProvider.kt` | Read-only FileProvider-style content provider for those files |
| `QrLensTile.kt` | Quick Settings tile (pause/resume) |
| `MainActivity.kt` | Setup screen, shortcut picker, options |
| `Blur.kt` | Fast box-blur for the overlay background |

## Conventions

- **No new dependencies** without a strong reason. Current deps: ZXing core only. No Compose
  (UI is built in code / custom View), no AndroidX beyond what's transitively needed.
- Keep everything **offline and fast**. Detection and any content checks run on the hot path
  right after a screenshot; avoid anything that adds perceptible latency.
- All animation goes through `ScanView`'s camera transform (log-interpolated scale + linear
  focal point). Match the existing Material-style interpolators in `Motion`.
- Parsing lives in `QrContent.kt`; presentation/flagging logic should consume the parsed
  `QrContent`, not re-parse raw strings.
- Wrap anything touching the overlay window or key handling in try/catch — an uncaught
  exception in the AccessibilityService leaves it "on" but dead (see the 1.3 stuck-state fix).

## Safety boundaries (important)

This app handles payments and runs with elevated (accessibility) access, so some changes are
off-limits. Do **not** implement:
- Anything that defeats a payment or anti-fraud control — e.g. making a gallery/screenshot scan
  look like a live camera scan to bypass UPI's reduced limit, or disguising the install source.
- Reading or exfiltrating other apps' content, keystrokes, or the screen outside the user's
  explicit scan action.
- Auto-submitting payments, or opening payment flows without the user tapping.

Security features that *inform* the user (e.g. flagging suspicious links, cautioning on UPI
pay codes) are welcome. Favor warning the user over silently acting for them.

## Testing without a device

There is usually no emulator here. Prefer logic that can be unit-tested as plain Kotlin:
- `TriggerEngine` is deliberately decoupled from Android (takes a Context + Handler) so its
  gesture logic can be driven by a simulated clock. Keep new logic (e.g. a link flagger)
  similarly pure/testable — a function from parsed content to a result, no Android UI needed.
