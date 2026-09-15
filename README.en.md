# Pure Browser

[中文](README.md) · [English](README.en.md)

[![CI](https://github.com/Nobilta/pure-browser/actions/workflows/ci.yml/badge.svg)](https://github.com/Nobilta/pure-browser/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Nobilta/pure-browser)](https://github.com/Nobilta/pure-browser/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2010%2B%20%C2%B7%20arm64--v8a-3ddc84)](#install)

**An open-source lightweight browser for Android**: a Jetpack Compose interface on top of the system WebView,
with ad blocking written in Rust and no Google services involved — sign-in helpers, QR scanning, filtering and
downloads all happen locally.

The goal is to make the basics solid rather than broad: tabs and Back behave the way you expect, page state
survives navigation, updates install in place, and every release proves itself with reproducible checks and
emulator regressions (see the [verification report](EMULATOR_TEST_REPORT.md)). Android 10 (API 29) and later,
`arm64-v8a` only, applicationId `com.mybrowser`.

> Most detailed documents in this repository are currently written in Chinese. This file is a full English
> introduction; the behaviour spec, testing guide and design notes link to the Chinese originals.

## Screenshots

| Browsing | Menu | Tabs | Settings | Incognito |
|---|---|---|---|---|
| <img src="docs/screenshots/browsing.png" width="155" alt="Browsing a page"> | <img src="docs/screenshots/menu.png" width="155" alt="Browser menu"> | <img src="docs/screenshots/tabs.png" width="155" alt="Tab panel"> | <img src="docs/screenshots/settings.png" width="155" alt="Settings"> | <img src="docs/screenshots/incognito.png" width="155" alt="Incognito mode"> |

## Features

- **Tabs and navigation**: multiple tabs, groups and search, opener-tab Back handling, web popups, find in page,
  long-press history. Background pages are kept per opened tab, so form input, SPA state and scroll position
  survive a switch.
- **Address bar**: top or bottom, URL/search detection, history and bookmark suggestions, hides while scrolling.
- **Home, bookmarks, history**: customisable shortcuts; paged search; Netscape HTML import and export.
- **Offline QR scanning**: Camera2 + ZXing, including torch and image decoding — no Google services, no upload.
- **Downloads**: HTTP Range segments, pause/resume, restart recovery; tapping a finished item hands it to Android.
- **App updates**: checks GitHub Releases on launch, downloads and verifies the package, then opens the system
  installer.
- **Video and casting**: fullscreen enhanced player (speed, brightness/volume gestures, PiP), background media,
  DLNA casting.
- **Privacy and security**: incognito mode (its own profile when the provider supports `MULTI_PROFILE`),
  per-site permissions, browsing-data cleanup, Android Autofill/WebAuthn.
- **Ad blocking and userscripts**: bundled EasyList/EasyPrivacy/EasyList China snapshots, custom subscriptions,
  user scripts.
- **Developer tools**: bounded console and network log, rule-hit explanations, source highlighting, page info.
- **Interface**: Material 3, dynamic colour, dark mode, English/Simplified Chinese/Traditional Chinese, one
  consistent motion vocabulary for every panel.

The complete capability list and its boundaries (including known limits) live in
[FEATURES.md](FEATURES.md) (Chinese).

## Install

Download `PureBrowser-v<version>-release.apk` from
[Releases](https://github.com/Nobilta/pure-browser/releases) and install it. To upgrade, install over the
existing app (same signing key, data preserved) — do not uninstall first.

```bash
shasum -a 256 PureBrowser-v<version>-release.apk                     # verify the download
apksigner verify --verbose --print-certs PureBrowser-v<version>-release.apk
./install_and_test.sh PureBrowser-v<version>-release.apk             # install to a connected device
```

## Build from source

Requirements: macOS, JDK 17+, Android SDK Platform/Build Tools 37, NDK, Rust stable (with the
`aarch64-linux-android` target), Node.js 18+, Python 3.9+. The SDK comes from `local.properties` or the
environment, the NDK is resolved by `rust/resolve-android-ndk.sh`, and no global shell or Cargo configuration
is touched.

```bash
./diagnose.sh                    # environment self-check
./build-and-test.sh --quick      # localisation, Node protocol tests, Rust fmt/test, Android unit tests
./build-and-test.sh --release    # adds clippy, lint, R8, signing and zipalign checks; produces a signed APK
```

Release builds need a local `keystore.properties`. **Signing keys, passwords, `local.properties`, build output,
APKs and verification results are never committed** — see [RELEASING.md](RELEASING.md) for the reasoning and for
the CI trade-offs.

Emulator regressions (they seed bookmarks, downloads and site data; run one emulator and one script at a time,
never alongside a Gradle build):

```bash
python3 validation/qa-server.py --apk PureBrowser-v<version>-release.apk
# in another terminal:
python3 validation/setup-ui-probe.py emulator-5554
python3 validation/run-regressions.py --serial emulator-5554 --apk PureBrowser-v<version>-release.apk \
  --label <label> --profile tabs
```

`--profile smoke` (default) covers basic browsing, `tabs` covers tab and media lifecycle, `full` is the whole
matrix; stage layout, retries and runtime notes are in the [testing guide](TESTING_GUIDE.md) (Chinese).

## Architecture

Platform semantics stay in Kotlin, pure computation is pushed into Rust through JNI, and in-page control is
injected JavaScript:

```text
app/src/main/java/com/mybrowser/
  core/ data/ home/ tabs/ search/  WebView and navigation, SQLite, shortcuts, tabs, search engines
  ui/                              Compose UI, split into feature sub-packages
    shell/ menu/ settings/ library/ devtools/ player/ home/ download/ qr/
  download/ filter/ userscript/    Downloads, filter subscriptions, user scripts
  privacy/ site/ security/         Profiles, site permissions, security
  media/ dlna/ qr/                 Video, system media, DLNA, offline QR
  update/                          GitHub Releases check, download and verification
app/src/main/assets/               Playback control, script runtime, bundled filter rules
rust/                             adblock, site_identity, url_utils
validation/                        Reproducible pages, automated checks, emulator tooling
```

`core` holds the vocabulary shared across layers and the platform pipeline; feature packages depend on `core`
and lower packages and never the other way round. Dependency versions are pinned by `app/gradle.lockfile`,
`gradle/verification-metadata.xml` and `rust/Cargo.lock`, and a normal build never refreshes the locks or their
verification values. See [ARCHITECTURE_REVIEW.md](ARCHITECTURE_REVIEW.md) (Chinese) for details.

## Contributing

Issues and pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md) (Chinese). In short: run
`./build-and-test.sh --quick` before submitting, run the relevant emulator stages for UI or lifecycle changes,
keep [FEATURES.md](FEATURES.md) in sync when user-visible behaviour changes, and never commit signing material,
`local.properties`, build output or APKs. Please follow the [code of conduct](CODE_OF_CONDUCT.md).

## Support

- **Bugs and feature requests**: open an [issue](https://github.com/Nobilta/pure-browser/issues); the templates
  ask for the app version, Android and WebView versions, reproduction steps and browsing mode.
- **Security problems**: please do not open a public issue — report them privately as described in
  [SECURITY.md](SECURITY.md).
- **Known limits**: [FEATURES.md](FEATURES.md) and the [verification report](EMULATOR_TEST_REPORT.md) list
  capability boundaries and what has and has not been verified.

## Roadmap

Explicitly **not planned** at the moment: translation and cross-device sync; full uBlock/AdGuard compatibility
(the current engine covers a network-rule subset plus basic element hiding); full Tampermonkey compatibility
(no `GM_xmlhttpRequest`, no arbitrary native network or file access); non-arm64 devices, non-Android platforms,
and store distribution.

Recent changes are in [CHANGELOG.md](CHANGELOG.md); version plans follow
[Releases](https://github.com/Nobilta/pure-browser/releases).

## License and third parties

Licensed under the [MIT License](LICENSE). Sources and licences of filter lists, Rust dependencies and other
third-party components are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Documentation

- Capabilities and boundaries: [FEATURES.md](FEATURES.md) · [ARCHITECTURE_REVIEW.md](ARCHITECTURE_REVIEW.md)
- Verification: [TESTING_GUIDE.md](TESTING_GUIDE.md) · [EMULATOR_TEST_REPORT.md](EMULATOR_TEST_REPORT.md)
- Design and maintenance: [system boundaries](design/system-integration.md) ·
  [video and casting](design/video-playback-and-casting.md) · [menu return paths](design/menu-navigation-20260910.md) ·
  [sheet motion](design/sheet-motion-consistency.md) · [back/forward cache](design/back-navigation-without-reload.md) ·
  [RELEASING.md](RELEASING.md) · [CHANGELOG.md](CHANGELOG.md)

All of the above are currently written in Chinese.
