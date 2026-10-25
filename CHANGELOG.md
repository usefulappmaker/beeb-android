# Beeb for Android TV 0.1.5

A refreshed Android TV experience, with native search, a dedicated 4K UHD collection, more reliable playback and UHD setup directly on your TV.

This release includes all changes since **0.1.1**, the previous GitHub release.

![Beeb for Android TV — changes since 0.1.1](https://github.com/usefulappmaker/beeb-android/releases/download/v0.1.5/Beeb-Android-0.1.5-changes.png)

### A more native Android TV interface

- Cleaner navigation with text tabs and clear focus indicators.
- Programme artwork with titles underneath.
- More space between navigation and programme artwork.
- Modern episode panels showing artwork, description, runtime, broadcast date and available quality.
- A bookmark icon for saving programmes.
- Remote Back navigation without an extra on-screen button.
- Various UI fixes.

### Browse and Search

- Redesigned Browse with cleaner categories and an A–Z row.
- A dedicated **4K UHD** collection.
- A new **Search** tab covering programme titles and descriptions across the catalogue.
- Native Android TV keyboard and voice search through the device’s installed speech service.
- Automatic catalogue refresh, with a manual refresh option.

### More reliable playback

Updated BBC playback discovery brings across the playback-link fix. Beeb now uses the current BBC episode API for UHD playback, replacing the older playlist lookup that could return an error page instead of playback information.

It also filters unavailable versions and prioritises available UHD versions, including technical replacements.

Playback uses standard Android Media3 controls, with programme metadata, a quality badge and controls that hide promptly.

### UHD setup directly on your TV

Open **Settings → Enable BBC UHD**.

Automatically configures the app for UHD playback. Beeb checks BBC UHD access before saving the configuration on your device. There is no need to run a Mac helper, install Python or transfer a setup URL.

Cancelling setup or encountering a failed check preserves your existing configuration.

### Other improvements

- Landscape live-channel shortcuts on compatible Android TV launchers. Availability and placement depend on your launcher.

### Install or update

Download **Beeb-Android-0.1.5.apk** and sideload it onto an Android TV or Google TV device running Android 8.0 or later.

**Existing users:** install over your current release to retain saved programmes, history and UHD setup. Do not uninstall or clear app storage first. In-place updates require the same signing key.

**New UHD users:** select **Settings → Enable BBC UHD**. Existing connected users do not need to repeat setup.

### Testing and requirements

The release passed Android lint and 13 automated unit tests.

UHD playback depends on programme availability, network eligibility and compatible hardware. Voice input requires a working microphone and installed speech service.

Beeb is an independent player, unaffiliated with the BBC. Source code and licences are included.

**Downloads:** signed APK, source archive, changes graphic and SHA-256 checksums.
