# Attribution and licence

Beeb Android is an independent Android TV client derived from the GPL-3.0-or-later Beeb tvOS public source edition. It is not produced or endorsed by the BBC, Google or Apple.

Programme discovery, catalogue interpretation, HLS selection, watch-history policy and skip-marker handling are ported from Beeb. Beeb's stream discovery adapts get_iplayer 3.36, copyright (C) 2008–2010 Phil Lewis and 2010– get_iplayer contributors: https://github.com/get-iplayer/get_iplayer/tree/v3.36. The Perl runtime is not embedded.

The client-certificate UHD selector flow follows TwinVine's iP service, author stabbedbybrick: https://github.com/vinefeeder/TwinVine. The one-time Python provisioning helper is from Beeb's public edition. No TwinVine certificate, private key, account credential, configuration or signed playback URL is bundled.

Android-specific UI, Media3 integration, local persistence and Android Keystore-backed provisioning are implemented in this project. AndroidX/Media3 and OkHttp are distributed under Apache-2.0. Their notices are retained in the dependency artefacts; the Apache-2.0 licence is included alongside the GPL licence. Test-only JUnit, AndroidX test and JSON dependencies are not shipped as app runtime functionality.

The pink Beeb artwork is reused from the public tvOS app. BBC artwork, captions and video are fetched from BBC services at runtime and are not included as bundled programme media. Downloaded caption files are cached locally for native rendering.

Beeb Android source is GPL-3.0-or-later; see LICENSE.txt. This source package excludes local signing keys and build environments. The distributed APK is locally signed with a dedicated generic app certificate, not an Apple development identity. Its signing key is not part of the source download.
