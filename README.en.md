# Pure Browser

[中文](README.md) · [English](README.en.md)

[![CI](https://github.com/Nobilta/pure-browser/actions/workflows/ci.yml/badge.svg)](https://github.com/Nobilta/pure-browser/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Nobilta/pure-browser)](https://github.com/Nobilta/pure-browser/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2010%2B%20%C2%B7%20arm64--v8a-3ddc84)](#install)

Pure Browser is an open-source Android browser built on the system WebView, with ad blocking, userscripts,
tabs and video playback. Its Material 3 interface supports dark mode and dynamic colour. No Google services required.

**[Download the latest version](https://github.com/Nobilta/pure-browser/releases/latest)** · [Screenshots](#screenshots) · [Report an issue](https://github.com/Nobilta/pure-browser/issues)

Requires Android 10 or later on an ARM64 device running 64-bit Android.

## Screenshots

| Browsing | Menu | Tabs | Settings | Incognito |
|---|---|---|---|---|
| <img src="docs/screenshots/browsing.png" width="155" alt="Browsing a page"> | <img src="docs/screenshots/menu.png" width="155" alt="Browser menu"> | <img src="docs/screenshots/tabs.png" width="155" alt="Tab panel"> | <img src="docs/screenshots/settings.png" width="155" alt="Settings"> | <img src="docs/screenshots/incognito.png" width="155" alt="Incognito mode"> |

## Features

- **Ad blocking and userscripts**: built-in filter lists, custom filter subscriptions and userscript installation.
- **Tabs and bookmarks**: tab groups, search, find in page, and bookmark import and export; sign-in popups keep their originating page alive to receive callbacks.
- **Video playback**: fullscreen speed controls, brightness and volume gestures, picture-in-picture, background playback and DLNA casting.
- **Downloads**: pause, resume interrupted transfers and delete in bulk, with configurable save locations; every download is confirmed first and repeat requests are merged, and downloading a new copy keeps the old file; long-press image saves confirm a copy without opening Downloads.
- **Loading and recovery**: loading feedback while waiting for a response, with a built-in recovery page for failed loads.
- **Immersive fullscreen**: hide the system bars and browser toolbar so the page fills the screen; a floating button keeps browser actions reachable, and exiting takes effect immediately without reloading the page.
- **Offline QR scanning**: scan with the camera or read a code from an image, without an internet connection.
- **Personalisation**: customise the home page, place the address bar at the top or bottom, and choose English, Simplified Chinese or Traditional Chinese; settings can be exported to a file and imported on another device, with each group confirmed only after it has been saved.
- **Privacy controls**: incognito browsing, per-site permissions, and browsing history and site data cleanup; unreadable website settings can be reset after confirmation to restore saving.

See the [feature guide](FEATURES.md) for details, website compatibility and incognito limitations.
The linked development and reference guides are currently in Chinese.

## Install

1. Open the [latest release](https://github.com/Nobilta/pure-browser/releases/latest) on your phone and download `PureBrowser-v<version>-release.apk` from **Assets**.
2. Open the APK. If prompted, allow app installs from the source you used to download it.
3. Follow the Android installation prompts.

To upgrade, install the new version over the existing app to keep your bookmarks and settings.
**You do not need to uninstall first.** The app also checks for updates at startup; you can turn this off
or check manually in **Settings → About**.

See the [changelog](CHANGELOG.md) for changes in each version.

## Build from source

The project uses Kotlin, Jetpack Compose and Rust. Builds support macOS, Linux and Windows;
on Windows run the scripts from Git Bash.
Follow the [development setup guide](CONTRIBUTING.md#开发环境) to install the JDK, Android SDK/NDK, Rust, Node.js and Python.

```bash
git clone https://github.com/Nobilta/pure-browser.git
cd pure-browser
./build-and-test.sh --quick      # Run automated checks
./gradlew :app:assembleDebug     # Build an installable debug APK
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk` and can be installed alongside the official release.
Builds target ARM64 by default. See the [testing guide](TESTING_GUIDE.md) for emulator setup
and the [release guide](RELEASING.md) for signing and publishing.

The [architecture guide](ARCHITECTURE.md) introduces the codebase.

## Feedback and contributions

Found a bug or have an idea? [Open an issue](https://github.com/Nobilta/pure-browser/issues).
For bugs, include the app version, Android version and steps to reproduce.
Please report security vulnerabilities privately using the [security policy](SECURITY.md).

Pull requests are welcome. Read the [contributing guide](CONTRIBUTING.md) and [code of conduct](CODE_OF_CONDUCT.md) to get started.

## License

The project code is licensed under the [MIT License](LICENSE).
Filter lists and other third-party components have their own licences, listed in [third-party notices](THIRD_PARTY_NOTICES.md).
