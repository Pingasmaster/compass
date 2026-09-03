<p align="center">
  <img src="logo.png" width="100" alt="Compass icon"/>
</p>

<h1 align="center">Compass</h1>

<p align="center">
  <b>A clean, expressive compass for Android</b><br/>
  Rotation-vector fused heading, optional true-north, dynamic theming, and OLED dark mode.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=white" alt="Min SDK 26 (compat); future flavor minSdk 37"/>
  <img src="https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Jetpack_Compose-1.13-4285F4?logo=jetpackcompose&logoColor=white" alt="Compose"/>
  <img src="https://img.shields.io/badge/Material_3-Expressive-E8DEF8" alt="M3 Expressive"/>
</p>

---

## About

A lightweight compass built with Jetpack Compose and Material 3 Expressive. Fuses the accelerometer, magnetometer, and gyroscope through `TYPE_ROTATION_VECTOR` for a smooth, low-jitter heading, with optional true-north correction via `GeomagneticField`.

The app follows Material 3 Expressive guidelines throughout: circular rose with motion-scheme animation, `MotionScheme.expressive()` tokens, dynamic color from your wallpaper (API 31+), and sin/cos low-pass smoothing so the needle glides across the 0 deg/360 deg seam without visible jumps.

Two fat APKs ship from `master`: **compat** (minSdk 26, Android 8+; armeabi-v7a, arm64-v8a, x86, x86_64, riscv64) as `app-release.apk`, and **future** (minSdk 37, Android 17+; arm64-v8a, x86_64, riscv64) as `app-release-future.apk`. No ABI splits. Releases and the in-app updater live on efreihub: `https://efrei.app:50002/hub/api/v1/repos/admin/compass/releases`. Every green push on `master` publishes real signed APKs; publish fails the build (does not silently skip) if a default-branch run is missing `EFREIHUB_TOKEN` or a signing file. See `docs/ci.md` and `docs/release-keys.md`.

## Features

- **Rotation-vector heading** with sin/cos low-pass smoothing (no 359 deg -> 1 deg glitch)
- **Expressive compass rose** with a circular disc, ticks, and motion-scheme animation
- **Magnetic or true north** - toggle on `GeomagneticField` declination with coarse location
- **Live accuracy chip** with figure-8 calibration banner when the sensor drifts
- **Dynamic color** (Material You) follows your wallpaper theme on API 31+
- **Dark mode** with system, light, and dark options
- **OLED dark theme** with pure black surfaces
- **Edge-to-edge** with proper system bar handling and rotation-aware axis remap so the rose stays correct in any display orientation

## Building from source

```bash
git clone https://efrei.app/hub/admin/compass.git
cd compass

# Local release path: bump deps, bump version, debug gates + debug APKs,
# maybe regen baselines, release lint/assemble (no gradle clean),
# then GMD shippedsmoke + smoke + e2e. Copies all four root APKs
# (compat/future x release/debug). Push CI uses scripts/ci.sh instead
# and always passes -Pcompass.requireReleaseSigning=true.
./build.sh

# Debug-only: debug lints/tests + debug APKs. No version bump, baseline,
# release assemble, or GMD.
./build.sh --debug

# Re-serve existing root debug + release APKs (four files).
./build.sh --publish
```

`./build.sh --clean` runs `gradle clean` and removes root APKs. The default path does not clean.

## Contributing

Contributions welcome. Open an issue or PR.
