# Android 0.1.1 validation

This is an initial Android TV port. The checks below distinguish completed tests from features that still need device testing.

## Hardware feedback

The user confirmed successful 4K HDR HLG playback on their Chromecast with Google TV using the 0.1.0 build. This is user-observed hardware validation; it does not imply Widevine DRM support (this implementation uses unprotected DASH).

## 0.1.1 interface update

Controls now use a 1.5-second timeout while playing. The homepage banner handles left/right in place and uses a taller frame capped to the TV screen. The launcher has a padded adaptive icon on a dark background. Episode metadata uses an unboxed gradient header alongside standard Media3 controls. These changes do not alter stream selection or identity storage.

## Completed

- Debug and signed release APK builds; debug/release Android lint, with no errors. Nonfatal lint warnings remain.
- Six JVM tests: individual episode artwork, plain banner artwork, BBC IDs, playlist URL/query handling and selection, skip intervals, episode ordering and completion policy.
- Android TV emulator: real BBC homepage/catalogue discovery and native BBC video decoding at 1920×1080, with playback position advancing and subtitle tracks discovered. This was a successful initial run, not a long-duration reliability test.
- Device tests for saved programmes, persistent local progress, completion/next-episode policy and rejecting invalid provisioning links.
- Synthetic HTTPS provisioning: incorrect certificate pin rejected, correct pin imported, encrypted private storage survives a new vault instance, selector client constructed, and Forget removes the identity. No personal TwinVine identity was used.
- Public source packaging excludes local build output, account paths, credentials and signing keys. The release signing certificate uses a generic Beeb identity.

## Still to verify

- Sustained playback, physical remote navigation and suspend/resume on actual Android TV/Google TV hardware.
- Full native subtitle rendering and automatic restoration after a ten-second rewind. The integration test is included, but later emulator runs stalled in the display/audio codec pipeline or failed DNS; that extended test has not passed. The tvOS behaviour was separately confirmed by the user, which does not establish Android behaviour.
- Real TwinVine helper-to-device interoperability and BBC authorisation with an accepted personal UHD identity. The synthetic pinned-transfer/encryption check does not prove UHD access.
- Broader physical 2160p HLG/HEVC device compatibility and automatic HD fallback. The app's received-format label does not independently establish the TV's output mode.
- Live-channel playback, all programme types, and skip markers across the changing BBC catalogue.

The app uses native Media3 HLS/DASH and subtitle rendering; the optional computer helper only transfers an identity. API/CDN availability and device codec support can change. Do not interpret a catalogue 4K badge as a successful HDR playback test.

## Reproduce

From the source folder with the documented SDK/JDK installed:

```sh
./build.sh assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w org.beebplayer.android.test/androidx.test.runner.AndroidJUnitRunner
```

The live integration test accesses current BBC services and requires an eligible network. The identity test skips if an existing identity is configured, to avoid replacing it. Debug diagnostics contain numeric playback state rather than signed stream URLs and are disabled in release builds.
