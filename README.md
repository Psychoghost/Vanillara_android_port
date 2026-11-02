<img width="2560" height="1600" alt="image" src="https://github.com/user-attachments/assets/a3e37932-4628-4ae8-a4e3-47f755d08a62" />







# Vanillara — Red Alert on Android

A native Android source port of **Command & Conquer: Red Alert** (1996), built from
Electronic Arts' GPLv3 source release with the Android platform layer from
[`dk8827/ra-port`](https://github.com/dk8827/ra-port).

**No game data is included.** You must own Red Alert and supply your own Allied
and Soviet disc/install files. Vanillara creates the folders for you and never
uploads or modifies your files.

[Download the latest APK](https://github.com/Psychoghost/Vanillara_android_port/releases/latest)

![Android](https://img.shields.io/badge/Android-6.0%2B%20arm64-3ddc84)
![Engine](https://img.shields.io/badge/engine-native%20C%2B%2B-00599c)
![License](https://img.shields.io/badge/license-GPLv3%20%2B%20EA%20terms-blue)

## What works

- Allied and Soviet campaigns
- Local skirmish
- Movies with sound
- Touch controls designed for a phone/tablet
- Game data imported from folders you control

Not included yet: expansion packs, and a signed release build and won't ever be a signed release. The downloadable APK is a debug-signed build that
can be installed directly.

## Requirements

- An Android 6.0+ (API 23) **arm64-v8a** phone or tablet
- Your own copy of Red Alert: the Allied and Soviet discs, an existing full
  installation, or the original freeware release. Buy game here: https://store.steampowered.com/app/2229840/Command_Conquer_Red_Alert_Counterstrike_and_The_Aftermath/

- Free space for both discs' data plus saves
- A USB cable or file manager to copy the files once

## Install and import your game files

1. Install `Vanillara-debug.apk` from the
   [releases page](https://github.com/Psychoghost/Vanillara_android_port/releases/latest).
2. Open **Vanillara**. On Android 12+ it asks you to **Grant file access**
   (Settings → Apps → Vanillara → allow files and media). It then creates two
   drop folders on **internal storage**, never on a removable SD card:
   - `/sdcard/Vanillara/redalert-root/assets/redalert/allies`
   - `/sdcard/Vanillara/redalert-root/assets/redalert/soviet`

   The exact path is shown on the setup screen, with a **Copy path** button.
3. Copy the contents of your **Allied** disc/install into `allies/` and the
   contents of your **Soviet** disc/install into `soviet/`.
   - Each side should contain `INSTALL/REDALERT.INI` and the `.MIX` files
     (`REDALERT.MIX`, `CONQUER.MIX`, `GENERAL.MIX`, and so on).
   - Mount or extract `.ISO`, `.BIN`, and `.CUE` images first. The game does not
     read disc images directly, and the importer ignores them.
4. Return to Vanillara and tap **Rescan**. The status turns **Ready** when
   `REDALERT.MIX` is found.
5. Tap **Play**.

### If the folder is not visible

Android 11+ hides some storage locations unless the app has file access:

- Tap **Grant file access** and allow access to files and media. The folder is
  then at `/sdcard/Vanillara/...` in any file manager, or over USB/MTP.
- If you prefer not to grant access, use the in-app **Import Allied folder** /
  **Import Soviet folder** buttons. The app copies the data into its private
  internal folder instead and never uploads or modifies the source.

The app always uses internal storage. It does not write to a removable SD card,
even when one is inserted.

> Upgrading from v0.1? That build used `Android/data/com.vanillara.redalert/`.
> Move those `redalert-root` folders to `/sdcard/Vanillara/` (or tap **Clear
> imported data** and import again), then tap **Rescan**.

## Touch controls

| Gesture | Action |
| --- | --- |
| Tap | Left click |
| Drag | Left-button drag (selection box) |
| Long press, or quick two-finger tap | Right click / deselect |
| Two-finger drag | Scroll the map, or pan the magnified view |
| Pinch | Zoom the screen up to 3× |
| Quick two-finger double tap | Show the whole screen again |
| Back button / gesture | Escape |
| Tap during a movie | Skip the movie |

The game is locked to landscape. Bluetooth/USB gamepads supported by SDL are
also usable.

## Build the APK yourself

Prerequisites:

- JDK 17
- Android SDK Platform 36
- Android SDK Build-Tools 36
- Android NDK `28.2.13676358`
- Android CMake `3.22.1`
- `adb` for installing (optional)

The Gradle wrapper is included. The first build downloads SDL 2.32.10 into
`android/third_party/` (ignored by git). No game data is needed to build.

```sh
./scripts/build_android_debug.sh
```

Output:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Install and launch on a connected device:

```sh
./scripts/run_android_debug.sh --no-build
```

Run the unit tests for the importer/validator:

```sh
cd android
./gradlew :app:testDebugUnitTest
```

The build intentionally packages **zero** game files. The GitHub Actions
workflow also checks that no `.MIX` files or game assets are present in the APK.

## How the data folders work

The setup screen and the native engine use **internal storage**, never a
removable SD card:

```text
/sdcard/Vanillara/redalert-root/
  assets/redalert/
    allies/
      INSTALL/REDALERT.INI
      *.MIX
    soviet/
      INSTALL/REDALERT.INI
      *.MIX
```

`/sdcard` is Android's internal shared storage (`/storage/emulated/0`). Android
11+ requires **All files access** to write there; Android 6-10 uses the storage
runtime permission. If access is not granted, the app falls back to its private
internal directory (`/data/user/0/com.vanillara.redalert/files`) and the import
buttons copy data there.

The app records the active `redalert-root` in a small pointer file, so the
native engine starts in the right place and the engine's original
`assets/redalert/...` search paths work unchanged. Saves, `OPTIONS.INI`, and
other runtime files are written there too.

## Project layout

| Path | Purpose |
| --- | --- |
| `CODE/` | Red Alert game code from EA's GPL release |
| `PORT/MAC/` | Shared SDL2 platform layer and touch input |
| `PORT/ANDROID/` | Android native entry point |
| `android/` | Gradle app, setup UI, importer, APK packaging |
| `cmake/` | Shared CMake source lists and build configuration |
| `scripts/` | Android build and install helpers |

## Credits and license

This is an unofficial, modified source port. It is based on:

- [Electronic Arts' `CnC_Red_Alert` source release](https://github.com/electronicarts/CnC_Red_Alert),
  GPLv3 with additional Electronic Arts terms.
- [`dk8827/ra-port`](https://github.com/dk8827/ra-port), the Android/iOS/macOS
  platform layer this Android build derives from.
- SDL 2, zlib, and the other dependencies listed in their own notices.

The Android setup screen, folder creation, game-data importer, and packaging
changes were added in 2026. See [`LICENSE.md`](LICENSE.md) and
[`NOTICE.md`](NOTICE.md).

**No game assets are included.** The repository contains only source code and
build tooling. You are responsible for supplying legally obtained Red Alert
files. “Command & Conquer” and “Red Alert” are trademarks of Electronic Arts
Inc. This project is not affiliated with, endorsed by, or sponsored by
Electronic Arts.

<!-- omgithub:readme:start -->
## 🚀 Build, play, and remix with OMGithub

**Remixed using [OMGithub.com](https://omgithub.com).**

[![OMGithub](https://img.shields.io/badge/OMGithub-Open%20project-orange?style=for-the-badge)](https://omgithub.com/Psychoghost/Vanillara_android_port)
[![GitHub](https://img.shields.io/badge/GitHub-Source-181717?logo=github&style=for-the-badge)](https://github.com/Psychoghost/Vanillara_android_port)

- 🎮 [Open the project](https://omgithub.com/Psychoghost/Vanillara_android_port).
- ✨ [Remix this project](https://omgithub.com/?remix=Psychoghost%2FVanillara_android_port).
- 💻 [Explore the source](https://github.com/Psychoghost/Vanillara_android_port).
- 🛠️ [Check build runs](https://github.com/Psychoghost/Vanillara_android_port/actions).
- 🐛 [Report an issue](https://github.com/Psychoghost/Vanillara_android_port/issues).
- 👤 [Explore the creator's projects](https://omgithub.com/Psychoghost).
- 🌍 [Create with OMGithub](https://omgithub.com).
- 🧬 [Explore the remix source](https://github.com/Psychoghost/Vanillara_android_port).
<!-- omgithub:readme:end -->
