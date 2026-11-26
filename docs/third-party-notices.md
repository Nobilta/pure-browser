# Third-party notices

Pure Browser includes the components and data listed below. Each retains its upstream license.
Dependency versions are recorded in [app/gradle.lockfile](../app/gradle.lockfile) and
[rust/Cargo.lock](../rust/Cargo.lock); Gradle artifact checksums are in
[verification-metadata.xml](../gradle/verification-metadata.xml).

## Material Symbols

The `ic_ui_*` drawables used by the menu and settings, except `ic_ui_incognito`, are adapted
from [Google Material Symbols](https://github.com/google/material-design-icons) (Rounded,
24dp, outlined unless a filled state is named), under the
[Apache License 2.0](../third_party/apache-2.0-LICENSE.txt). Their 960-unit SVG paths were
converted to Android VectorDrawable resources.

## Lucide Icons

The `ic_ui_incognito` drawable is adapted from [Lucide](https://lucide.dev/), under the
[ISC License](../third_party/lucide-ISC.txt). Its SVG path was converted to an Android
VectorDrawable resource and retains the 24dp grid, 2dp round stroke, and outline style.

## Mozilla Readability

Reader mode uses [Readability.js](https://github.com/mozilla/readability), copyright Arc90 Inc
and Mozilla, under the [Apache License 2.0](../third_party/apache-2.0-LICENSE.txt).
The unmodified script is shipped as `app/src/main/assets/readability.js`, pinned to upstream
commit `ab4027a8b37669745016869a37a504727992b2ba` (SHA-256
`e9330028c8a5a4aa7d75147be2605d520f7f213c7b28474947dc0e9c984e9bed`); the presentation
script around it is original to this project.

## AndroidX

AndroidX dependencies, including Activity, WebKit, Compose and Material 3, use the
[Apache License 2.0](../third_party/apache-2.0-LICENSE.txt).
The Gradle lockfile records the resolved versions.

## ZXing

QR scanning and page-address QR generation use [ZXing Core](https://github.com/zxing/zxing), copyright ZXing authors,
under the [Apache License 2.0](../third_party/apache-2.0-LICENSE.txt).
Encoding and decoding run offline. Camera capture uses Android Camera2; Google Play services
and remote recognition services are not required.

## CommonMark

Local Markdown previews use [commonmark-java](https://github.com/commonmark/commonmark-java)
and its GFM tables extension under the [BSD 2-Clause license](../app/src/main/assets/commonmark-LICENSE.txt).
The full upstream license is bundled in the APK; dependency versions are pinned in the Gradle catalog and lockfile.

## EasyList, EasyPrivacy and EasyList China

The bundled rule data are unmodified snapshots from 2026-09-09, attributed to
[The EasyList authors](https://easylist.to/) and the
[EasyList China maintainers](https://github.com/easylist/easylistchina/).

The data are redistributed under
[Creative Commons Attribution-ShareAlike 3.0 Unported](../third_party/easylist-CC-BY-SA-3.0.txt),
one of the alternatives offered by the [upstream license notice](https://easylist.to/pages/licence.html).
Source URLs, versions and SHA-256 hashes are recorded in the
[bundled notice](../app/src/main/assets/filters/NOTICE.txt).
That notice and the full license text are included in the APK assets.

## Additional filter lists

Unmodified snapshots retrieved on 2026-11-14 are bundled for offline use:

- [CJX Annoyance](https://github.com/cjx82630/cjxlist), copyright cjx82630,
  under [LGPL 3.0](../app/src/main/assets/filters/LICENSE-LGPL-3.0.txt).
- [AdGuard Mobile Ads and Mobile App Banners](https://github.com/AdguardTeam/AdGuardFilters),
  copyright AdGuard Software Ltd. and contributors, under
  [GPL 3.0](../app/src/main/assets/filters/LICENSE-GPL-3.0.txt). The official Chromium
  subscription builds are used so upstream platform conditions are already resolved.
- [EasyList Cookie](https://easylist.to/), copyright the EasyList authors,
  under [Creative Commons Attribution 3.0 Unported](../app/src/main/assets/filters/LICENSE-CC-BY-3.0.txt),
  as specified in the snapshot header.

Original rule headers, license texts and the [source notice](../app/src/main/assets/filters/NOTICE.txt)
are included in the APK. Only syntax supported by the filtering engine is applied.

## Public Suffix List and IDNA

The `site_identity` Rust crate uses [psl](https://github.com/addr-rs/psl) 2.1.232
(MIT / Apache-2.0) and its compiled Mozilla Public Suffix List, including the ICANN and PRIVATE sections.
The list data use MPL-2.0 and originate from the [Public Suffix List](https://publicsuffix.org/list/).

`idna` 1.1 (MIT / Apache-2.0) provides UTS #46 normalization using ICU4X (Unicode-3.0).
Exact transitive versions are pinned in Cargo.lock.

## Optional translation services

The translation UI offers MyMemory and Google as built-in endpoints without account configuration.
MyMemory's [API terms](https://mymemory.translated.net/doc/spec.php) apply to its service.
Azure Translator, DeepL and LibreTranslate are optional and are called only after the user supplies
their own key or endpoint. No API key or page text is sent to the project's own servers.
