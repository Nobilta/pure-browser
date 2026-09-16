# Third-party notices

Pure Browser includes the components and data listed below. Each retains its upstream license.
Dependency versions are recorded in [app/gradle.lockfile](app/gradle.lockfile) and
[rust/Cargo.lock](rust/Cargo.lock); Gradle artifact checksums are in
[verification-metadata.xml](gradle/verification-metadata.xml).

## Material Icons

The `ic_lock`, `ic_lock_open`, `ic_copy`, `ic_share`, `ic_edit`, `ic_add`,
`ic_open_in_new`, `ic_more`, `ic_tabs`, `ic_file` and `ic_pip` drawables are adapted from
[Google Material Icons](https://github.com/google/material-design-icons), under the
[Apache License 2.0](third_party/material-icons-LICENSE.txt).
Their SVG paths were converted to Android VectorDrawable resources.

## AndroidX

AndroidX dependencies use the Apache License 2.0. The current integration includes Activity 1.13.0,
WebKit 1.17.0, Compose UI/Foundation/Runtime 1.9.4 and Material 3 1.4.0.
The Gradle lockfile records the complete resolved dependency set.

## ZXing

The QR scanner uses [ZXing Core 3.5.3](https://github.com/zxing/zxing), copyright ZXing authors,
under the [Apache License 2.0](third_party/material-icons-LICENSE.txt).
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

When refreshing the suffix list, update the pinned PSL version, run the site-identity, filter and desktop-alias
regressions, and rebuild both JNI libraries. Website permissions continue to use full origins rather than PSL domains.
