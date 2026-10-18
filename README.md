# Beeb for Android TV — 0.1.1

An independent native Android TV/Google TV BBC player, ported from Beeb's public tvOS edition. Android 8.0 (API 26) or later. This is an initial Android release, not a BBC product or a guarantee of compatibility with every TV.

## Features

- Remote-friendly Home, Browse, Live, Saved and Settings screens, using native Android views with visible focus outlines.
- Homepage features and popularity rows; banners prefer artwork without show logos. Episode cards prefer their individual stills.
- Categories, A–Z pagination, series selection and ordered episode pages.
- Programme bookmarks and local Continue Watching, Resume, Start over and Up next. Long-press a history card to remove it.
- Automatic HD selection and optional direct UHD DASH playback on HLG-capable devices. No tvOS DASH-to-HLS server is used.
- Media3 playback, native subtitle selection/rendering, Android caption styling and optional temporary captions on 10-second rewind.
- Version-matched BBC Skip intro / Skip recap actions, when metadata is supplied.
- Received-quality label: 4K HDR requires 2160p video and HDR transfer signalling from the decoder format. A catalogue badge is only an availability hint, not proof of output quality.
- One-time TwinVine identity transfer with pinned TLS. The retained setup package is encrypted with an Android Keystore AES-GCM key and stored in app-private no-backup storage. No personal credentials are bundled.

## Download and install

**[Download the latest APK](https://github.com/usefulappmaker/beeb-android/releases/latest)** · [All releases](https://github.com/usefulappmaker/beeb-android/releases)

For the initial pre-release, use [version 0.1.1](https://github.com/usefulappmaker/beeb-android/releases/tag/v0.1.1).

Use the supplied `Beeb-Android-0.1.1.apk`. You do not need Android Studio or a Google Play developer account to install an APK on a device that permits sideloading.

1. Transfer the APK to your Android TV/Google TV using your chosen local file-transfer method, or use ADB below.
2. If using a file manager, enable “Install unknown apps” for that installer when Android asks. Only approve an APK you intend to install. Device/vendor restrictions vary.
3. Install and open **Beeb** from the TV apps launcher. It includes a TV launcher banner and requires no touch screen.
4. Try HD playback first. Optional UHD setup is described in [SETUP-UHD.md](SETUP-UHD.md).

ADB installation, after enabling and authorising your device's USB/network debugging connection:

```sh
adb devices
adb install -r Beeb-Android-0.1.1.apk
adb shell am start -n org.beebplayer.android/.MainActivity
```

Pairing/connection commands depend on the TV OS and manufacturer. Use Android Studio's device pairing flow or the device's displayed wireless debugging address and pairing code. Keep debugging on a trusted local network.

This is not a Play Store release. Updates must be signed with the same Android key to install over the existing app. A differently signed self-build requires a different application ID or uninstalling the previous build, which removes its local app data. Unlike free Apple provisioning, Android APK signing does not need weekly renewal.

## Controls

- On the focused homepage banner, Left/Right changes the featured programme; OK opens it. Artwork uses a taller frame, capped to fit the TV screen.
- Playback controls fade after 1.5 seconds during playback.
- D-pad: move focus. Centre/OK: select. Back: return to the previous page or leave playback.
- During playback use Media3's controls for pause/play, scrubbing and subtitle selection.
- The **↶ 10s** button rewinds and temporarily enables English captions if they were Off and a supported track is present. Dedicated remote Rewind does the same. Left also rewinds when the native controls are hidden; when controls are visible it remains available for normal focus/scrubbing navigation.
- Temporary captions restore the prior selection after playback reaches the pre-rewind position. Changing the native subtitle selection manually cancels that automatic restoration.
- Settings lets you disable captions on rewind and open Android's caption appearance settings.
- Skip intro/recap appears only during a supplied interval for the actual playing BBC version.

## Build from source

Install Android Studio with the Android SDK Platform 37, Build Tools 36/37 and Platform Tools. Use Android Studio's bundled Java runtime (tested with Java 25); Gradle Wrapper 9.1.0 and Android Gradle Plugin 9.0.1 are pinned. Compile SDK is 37; target SDK is 36. Dependencies come from Google Maven and Maven Central.

Open this folder in Android Studio and let Gradle sync. Or on a Mac with the default Android Studio/SDK locations:

```sh
./build.sh assembleDebug testDebugUnitTest lintDebug
```

Override `JAVA_HOME` and `ANDROID_HOME` for a different installation. On other systems use the Gradle Wrapper directly after setting these variables. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. No signing account is required for a debug build.

For your own signed release, create and securely retain an Android signing key, then set these environment variables privately:

```text
BEEB_KEYSTORE_PATH       absolute path to your keystore
BEEB_KEYSTORE_PASSWORD   your keystore/key password
BEEB_KEY_ALIAS           alias (default: beeb)
```

Run `./build.sh assembleRelease`. Without those variables, `assembleRelease` produces an unsigned APK. Do not commit the key, password or a machine-specific `local.properties` file. Beeb does not include any maintainer signing key.

## Limits and verification

See [VALIDATION.md](VALIDATION.md) for completed tests and unresolved device checks. An emulator cannot establish physical 4K HLG output. Optional UHD requires your own accepted TwinVine iP identity, a capable HEVC decoder, HLG display path and currently available BBC content. A fresh TwinVine installation alone is not a guarantee of UHD access.

No BBC account/profile sync, offline programme downloads, Chromecast integration or Android TV system-wide watch-next integration is implemented. Continue Watching is inside Beeb. No telemetry or account login is added. The app accesses BBC/CDN services and a local setup helper you explicitly connect to. BBC availability and network restrictions still apply.

The source and APK are separate deliverables. Public source packaging excludes Gradle caches, test reports, device captures, local paths, setup credentials and signing keys. Reused branding and your publishing account can still connect projects; this is not an anonymity guarantee.

GPL-3.0-or-later, with upstream attribution and dependency licensing in [NOTICE.md](NOTICE.md).
