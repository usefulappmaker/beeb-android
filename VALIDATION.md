# Android 0.1.5 validation

## Playback lookup and on-device UHD setup

Thirteen JVM tests pass, including current episode matching, replacement/UHD version priority, expired/future/accessibility-version filtering, malformed service responses, certificate/key matching, certificate expiry, strict configuration parsing, cancellation and HLG signalling.

On the physical Chromecast with Google TV, the downloaded TwinVine configuration passed certificate/key validation and authenticated to BBC. The bounded probe retrieved a static HEVC 2160p DASH representation and verified BT.2020/HLG signalling in its initialization data. The test did not save the real candidate identity. This verifies service access and stream signalling, not actual video decoding or the television's HDR output mode.

A live HD resolver test also passed on the Chromecast: Vigil resolved through the current episode API to a fresh HLS media playlist. No video playback or output-mode assertion was made by that network test.

A separate physical-device fixture test confirmed that a failed or cancelled probe preserves the original encrypted identity; only a successful verification commits the candidate. All fixture identities were synthetic and removed after testing. The existing release identity was not used or replaced by these tests.

The initial emulator live probe was unsuccessful. The physical Chromecast passed the same flow; no emulator live-stream verification is claimed. Signed release and debug builds and lint pass. Older verification history follows.

# Earlier Android verification

This is an initial Android TV port. The checks below distinguish completed tests from features that still need device testing.

## Hardware feedback

The user confirmed successful 4K HDR HLG playback on their Chromecast with Google TV using the 0.1.0 build. This is user-observed hardware validation; it does not imply Widevine DRM support (this implementation uses unprotected DASH).

## 0.1.3 native player and identity recovery

Playback uses Media3's unmodified default controller with a small quality badge and supplementary programme metadata. The custom Cinema transport is removed. The episode Play button is logo-only with an accessible Play description.

A synthetic identity was imported in the 0.1.2 preview app on the physical Chromecast, then the 0.1.3 APK was installed over it with `adb install -r`. The identity and wrapping key remained usable. The same device test verified automatic migration to a recovery copy, recovery from damaged primary ciphertext, rejection of an incorrect backup password without replacing the working identity, and portable backup restoration after deleting the old device key.

The signed release was then installed in place over the existing release on the Chromecast. Its Settings screen confirmed version 0.1.3 and a connected, readable UHD identity; the original app installation was preserved. This confirms local identity access, not a fresh BBC authorisation/playback test.

Two additional JVM tests cover backup round trips, independently randomised encryption, wrong passwords, tampering, malformed input and short passwords. Two other device tests cover local history and pinned provisioning. Full live playback/rewind UI checks encountered a decoder timeout on this Chromecast; do not interpret the identity tests as verification of every playback state. The user's earlier 4K HLG confirmation applies to the earlier release. File-picker behaviour and Downloads backup UI remain device-dependent.

## Live home cards

On the physical Chromecast, the provider test passed: landscape channel cards publish, a second publish updates them without duplicates, and each stored intent resolves to its corresponding live channel. This verifies the Android TV provider and links; Google TV launcher visibility/placement and full live playback still need user confirmation.

## Completed

- Debug and signed release APK builds; debug/release Android lint, with no errors. Nonfatal lint warnings remain.
- Eight JVM tests (including two portable-backup tests): individual episode artwork, plain banner artwork, BBC IDs, playlist URL/query handling and selection, skip intervals, episode ordering and completion policy.
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
adb shell am instrument -w org.beebplayer.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The live integration test accesses current BBC services and requires an eligible network. The identity test skips if an existing identity is configured, to avoid replacing it. Debug diagnostics contain numeric playback state rather than signed stream URLs and are disabled in release builds.

## Android TV Browse/Search preview

The emulator discovery check verifies the UHD collection, searches across the complete bundled catalogue (including non-UHD programmes), empty queries, case/accent handling and native keyboard presentation. Real BBC refresh completed with 3,647 records and 95 known UHD titles. Debug build and Android lint pass. The installed system speech-recognition intent resolves; actual microphone transcription has not been tested.
