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

The app follows Material 3 Expressive guidelines throughout: expressive cookie-shape rose backing, `MotionScheme.expressive()` tokens, dynamic color from your wallpaper (API 31+), and sin/cos low-pass smoothing so the needle glides across the 0 deg/360 deg seam without visible jumps.

Two product flavors ship from `master`: **compat** (minSdk 26, Android 8+) as `app-release.apk`, and **future** (minSdk 37, Android 17+) as `app-release-future.apk`.

## Features

- **Rotation-vector heading** with sin/cos low-pass smoothing (no 359 deg -> 1 deg glitch)
- **Expressive compass rose** using `RoundedPolygon` cookie shape and motion-scheme animation
- **Magnetic or true north** - toggle on `GeomagneticField` declination with coarse location
- **Live accuracy chip** with figure-8 calibration banner when the sensor drifts
- **Dynamic color** (Material You) follows your wallpaper theme on API 31+
- **Dark mode** with system, light, and dark options
- **OLED dark theme** with pure black surfaces
- **Edge-to-edge** with proper system bar handling and rotation-aware axis remap so the rose stays correct in any display orientation

## Building from source

```bash
git clone https://github.com/Pingasmaster/compass.git
cd compass

# Build (runs lint for both flavors, assembles compat + future debug/release,
# copies app-release.apk and app-release-future.apk to the project root)
./build.sh
```

## Contributing

Contributions welcome. Open an issue or PR.
