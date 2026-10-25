# N6 Fold Duo

An iPhone Duo-style fold animation for the **OPPO Find N6** (CPH2765, ColorOS 16 / Android 16).

As you fold or unfold, the left page of the inner screen turns toward you: it becomes a
perspective trapezoid with rounded outer corners, blurred most at its outer edge and sharp at the
hinge, and flattens back out as the phone opens fully. A hinge shadow and a glass sheen sit on
top. The effect follows the hinge angle live and holds its position while you hold the phone still.
It never darkens the screen, and the outer (cover) screen is left alone by default.

Only tested on the Find N6. Other foldables are unlikely to work without changes.

## How it works

- **Hinge angle**: the Find N6 exposes a smooth `android.sensor.hinge_angle` (type 36, ~50 Hz,
  0° closed to ~180° open) that any app can read. Readings arrive on their own thread, and each
  frame predicts the current angle from the hinge's velocity.
- **Displays**: logical display 0 is always the lit panel (inner when open, cover when closed), so
  one overlay on display 0 follows the active screen. The app records the hinge angles at which
  ColorOS switches panels and uses them as the end of each panel's effect range.
- **Overlay**: an accessibility-service overlay (`TYPE_ACCESSIBILITY_OVERLAY`) that never takes
  touches. Everything is drawn into one `SurfaceView`.
- **Live left page (needs Shizuku)**: a Shizuku user service (`ShellService`) runs as the shell user
  and captures just the left page with the hidden `IWindowManager.captureDisplay`, excluding the
  overlay's own surface so it never captures itself. The frame comes back as a `HardwareBuffer`
  (no copy), is blurred at capture resolution with `RenderEffect`, blended from blurred to sharp
  toward the hinge, and drawn through a perspective matrix. Without Shizuku, the app falls back to
  an evenly blurred left half using a window blur.

## Install

1. Install the APK (from Releases, or build it below):
   ```
   adb install n6-fold-duo.apk
   ```
2. Turn on the accessibility service (adb avoids Android's "restricted setting" block for
   sideloaded apps). Note that this replaces any other enabled accessibility services:
   ```
   adb shell settings put secure enabled_accessibility_services com.n6.foldduo/.FoldService
   adb shell settings put secure accessibility_enabled 1
   ```
3. For the angled left page, install [Shizuku](https://github.com/RikkaApps/Shizuku) and start it
   (it has to be started again after every reboot):
   ```
   adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
   ```
   Then open N6 Fold Duo and tap **Authorize Shizuku**.
4. Fold and unfold once, slowly, so the app learns where ColorOS switches screens.

## Settings

| Setting | Default | What it does |
| --- | --- | --- |
| Angle the left page | on | Perspective left page (needs Shizuku) |
| Outer edge shorter by | 40% | How much the outer edge shrinks toward the middle |
| Left page width (from the middle) | 100% | Pulls the outer edge in toward the hinge |
| Corner rounding | 120 px | Outer corner radius, easing to the screen's own radius when open |
| Edge softness | 40 px | Feathers the page edge into the black around it (hinge side stays crisp) |
| Blur | 60 px | Maximum blur, strongest at the outer edge |
| Hinge shadow / Glass sheen | 60% / 100% | Shading along the hinge and a moving highlight |
| Motion smoothing | 20 ms | Lower is snappier |
| Capture resolution | 40% | Lower is faster |
| Fully open / closed at | 172° / 2° | Where the effect ends |
| Also animate the outer screen | off | Blur on the cover screen |

The **Preview** slider shows the effect at any angle without folding, and **Show angle readout**
puts the live angle, panel, switch angles and capture rate on screen. Logs use the tag `N6DUO`:
`adb logcat -s N6DUO`.

## Build

The build uses Ubuntu's Android tools rather than Gradle and the Google SDK:

```
sudo apt install aapt apksigner zipalign dalvik-exchange android-sdk-platform-23
sh build.sh        # writes n6-fold-duo.apk
```

- Resources link against the API 23 platform; Java compiles against the Android 16 framework jar
  from Robolectric (`android-all`, downloaded to `sdk/` on first build), which includes the hidden
  capture APIs `ShellService` uses. App-side code uses only public APIs.
- `dx` can't desugar lambdas, so `libs/api-desugared.jar` is Shizuku's `api.jar` run through
  Retrolambda (`tools/desugar-shizuku.sh`), and the build fails if any lambda bytecode remains.
  Project code uses anonymous classes instead of lambdas for the same reason.
- `build.sh` creates `debug.keystore` on first run. Keep it: APKs signed with a different key can't
  be installed over the existing app.

`probe/` is the small diagnostic app used to find the hinge sensors and display layout
(`sh probe/build.sh`).

## Credits

Inspired by [Duo Fold Live](https://github.com/joeconsorti/duo-fold-live) for the Galaxy Z Fold.
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
