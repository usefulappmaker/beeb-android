# Optional TwinVine setup on Android TV

HD/live playback does not need TwinVine. UHD needs your own working iP configuration and an Android TV/Google TV device with HEVC Main 10 decoding and HLG display support. The emulator is suitable for app testing, not physical HDR validation.

## Prepare the computer helper

Install/configure your own [TwinVine](https://github.com/vinefeeder/TwinVine) iP service according to its upstream instructions. This app does not distribute its certificate/private key. Its existing iP YAML configuration must contain `certificate` (base64 PEM certificate plus matching unencrypted private key) and `user_agent`. Do not paste the configuration into public issues.

From this Beeb source folder on your computer, with Python 3.10+:

```sh
python3 -m venv .venv-setup
.venv-setup/bin/python -m pip install -r scripts/requirements-setup.txt
.venv-setup/bin/python scripts/setup-uhd.py --twinvine "$HOME/TwinVine" --check
```

Replace the example TwinVine location with your own. Automatic discovery supports `packages/envied/src/envied/services/iP/config.yaml` and the `_repos/<checkout>/iP/config.yaml` layout. Alternatively pass `--config` with the exact iP YAML file. Multiple candidate configurations require an explicit choice. The helper validates the certificate, key and validity period, but cannot guarantee that BBC will authorise UHD.

## Transfer once

Keep computer and Android TV on the same private local network. Run the check command again **without `--check`**. On macOS you can also use `./setup-uhd.command` after creating `.venv-setup`.

If automatic network-interface selection is wrong, add `--host` with the computer's private IPv4 address. Do not use the emulator's or TV's address. Do not expose the helper through router port forwarding. Allow the helper through your computer's firewall on the local network if necessary.

The helper displays a complete HTTPS URL containing a fingerprint and token. In **Beeb → Settings → 4K HLG · TwinVine**, enter that URL and choose **Connect TwinVine**. Use your TV's keyboard/remote companion app as available. The link expires after 15 minutes by default, and can be consumed only once. Do not open it in a browser or include it in a screenshot: anyone with both network access and the link could retrieve that setup package.

The Android client verifies the pinned certificate fingerprint, disallows redirects and imports the password-protected PKCS#12 package. It stores the package encrypted with a device-bound Android Keystore key in its no-backup app directory. The normal media HTTP client never receives the identity. The separate client-certificate client is limited to the BBC secure selector host.

When Settings says **UHD identity connected**, you can close the computer helper. Subsequent playback goes directly from Android TV to BBC/CDN services; the computer is not a playback server.

## Use and renew

Choose a currently available UHD episode and press the logo Play button. Beeb attempts UHD only when the device reports an HLG-capable display and a suitable HEVC decoder, with HD fallback if UHD lookup or playback fails. Verify the player's received-quality label and the TV's actual HLG/HDR status. No conversion can add HDR to a display or decoder that lacks support.

Expired/consumed link: start the helper again. Expired/unaccepted identity: update your own upstream configuration and reconnect. **Forget UHD identity** removes the encrypted package and wrapping key from this Android app; it does not revoke the upstream certificate. Uninstalling/clearing app storage also requires setup again. Never publish your configuration, certificates, passwords, complete setup URL or signed media URLs.
