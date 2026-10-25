# Enable BBC UHD on Android TV

Install Beeb 0.1.5 or later and open **Settings → Enable BBC UHD**. No Mac application, Python installation, helper or copied setup URL is needed.

1. Select **Enable BBC UHD**. Beeb automatically retrieves the current UHD configuration over HTTPS.
2. The TV validates the configuration, certificate dates and matching RSA private key. It creates the import container in memory and checks BBC access with the candidate identity.
3. Beeb checks at most three known UHD programmes, two episodes each. It requires an unprotected on-demand HEVC 2160p DASH representation and BT.2020/HLG signalling in its initialization data. This checks service access and stream signalling, not the TV’s actual output mode.
4. Only after the check succeeds does Beeb save the identity in its existing AES-GCM encrypted private storage. Play a UHD episode to check playback and the television’s HDR mode.

Cancelling or a failed validation/probe does not replace your existing setup. Expired certificates, unsupported key formats, offline content or a changed upstream service can prevent setup. Beeb does not execute upstream code, save a plaintext key file, or bundle a certificate/private key in the APK or source archive.

## Updates and encrypted backups

Install signed updates over the existing application. Keep the same application ID and signing key. Beeb retains its private encrypted identity and recovery copy across normal updates. Clearing app storage or uninstalling removes the device wrapping key.

Use **Settings → Export encrypted UHD backup**, choosing a password of at least 12 characters. On Android 10+, the backup is saved in **Downloads/Beeb**; copy it somewhere you control and keep the password separately. Older Android versions use the system file picker.

Use **Settings → Restore encrypted UHD backup** to choose an app-owned backup or another file. File-picker availability varies on TV devices. If the file is inaccessible, use **Enable BBC UHD** to set up the TV again. The portable backup can restore after reinstalling or changing devices; the internal recovery copy cannot recover a deleted Android Keystore key. Existing identities imported through the older Mac helper remain compatible.

## Requirements

An eligible network, BBC accepting the supplied identity, compatible UHD content, a HEVC-capable TV box and an HLG display path are required for physical 4K HLG output. Protected/Widevine presentations are not implemented by this setup flow. The received-quality label is not independent proof of HDR output.
