# Third-party notices

Pure Browser includes the components and data listed below. Each retains its upstream license.
Dependency versions are recorded in [app/gradle.lockfile](app/gradle.lockfile) and
[rust/Cargo.lock](rust/Cargo.lock); Gradle artifact checksums are in
[verification-metadata.xml](gradle/verification-metadata.xml).

## Material Icons

The `ic_lock`, `ic_lock_open`, `ic_copy`, `ic_share`, `ic_edit`, `ic_add`,
`ic_open_in_new`, `ic_more`, `ic_tabs`, `ic_file`, `ic_pip`, `ic_translate`, `ic_ui_reader`
and `ic_ui_print` drawables are adapted from
[Google Material Icons](https://github.com/google/material-design-icons), under the
[Apache License 2.0](third_party/apache-2.0-LICENSE.txt).
Their SVG paths were converted to Android VectorDrawable resources.

## Lucide Icons

The `ic_ui_*` drawables used by the redesigned menu and settings are adapted from
[Lucide](https://lucide.dev/), under the [ISC License](third_party/lucide-ISC.txt).
Their SVG paths were converted to Android VectorDrawable resources and retain the
24dp grid, 2dp round stroke, and outline style.

## Mozilla Readability

Reader mode uses [Readability.js](https://github.com/mozilla/readability), copyright Arc90 Inc
and Mozilla, under the [Apache License 2.0](third_party/apache-2.0-LICENSE.txt).
The unmodified script is shipped as `app/src/main/assets/readability.js`, pinned to upstream
commit `ab4027a8b37669745016869a37a504727992b2ba` (SHA-256
`e9330028c8a5a4aa7d75147be2605d520f7f213c7b28474947dc0e9c984e9bed`); the presentation
script around it is original to this project.

## AndroidX

AndroidX dependencies, including Activity, WebKit, Compose and Material 3, use the
[Apache License 2.0](third_party/apache-2.0-LICENSE.txt).
The Gradle lockfile records the resolved versions.

## ZXing

The QR scanner uses [ZXing Core](https://github.com/zxing/zxing), copyright ZXing authors,
under the [Apache License 2.0](third_party/apache-2.0-LICENSE.txt).
Only the offline QR decoder is used. Camera capture uses Android Camera2; Google Play services
and remote recognition services are not required.

## EasyList, EasyPrivacy and EasyList China

The bundled rule data are unmodified snapshots from 2026-09-09, attributed to
[The EasyList authors](https://easylist.to/) and the
[EasyList China maintainers](https://github.com/easylist/easylistchina/).

The data are redistributed under
[Creative Commons Attribution-ShareAlike 3.0 Unported](third_party/easylist-CC-BY-SA-3.0.txt),
one of the alternatives offered by the [upstream license notice](https://easylist.to/pages/licence.html).
Source URLs, versions and SHA-256 hashes are recorded in the
[bundled notice](app/src/main/assets/filters/NOTICE.txt).
That notice and the full license text are included in the APK assets.

## Public Suffix List and IDNA

The `site_identity` Rust crate uses [psl](https://github.com/addr-rs/psl) 2.1.232
(MIT / Apache-2.0) and its compiled Mozilla Public Suffix List, including the ICANN and PRIVATE sections.
The list data use MPL-2.0 and originate from the [Public Suffix List](https://publicsuffix.org/list/).

`idna` 1.1 (MIT / Apache-2.0) provides UTS #46 normalization using ICU4X (Unicode-3.0).
Exact transitive versions are pinned in Cargo.lock.

## Optional translation services

The translation UI can call services selected by the user. MyMemory is the only built-in endpoint and
is used without an account; its [API terms](https://mymemory.translated.net/doc/spec.php) apply.
Azure Translator, DeepL and LibreTranslate are optional and are called only after the user supplies
their own key or endpoint. No API key or page text is sent to the project's own servers.
