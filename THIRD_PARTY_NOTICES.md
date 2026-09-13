# Third-Party Notices

The fullscreen `ic_lock`, `ic_lock_open`, and browser
`ic_copy`, `ic_share`, `ic_edit`, `ic_add`, `ic_open_in_new`, `ic_more`, `ic_tabs`, `ic_file`, `ic_print`, `ic_pip`
drawables are adapted from [Google Material Icons](https://github.com/google/material-design-icons),
licensed under the [Apache License 2.0](third_party/material-icons-LICENSE.txt).
The SVG paths were converted to Android VectorDrawable resources; the replay icon
uses the forward icon's zero glyph. These notices and the license accompany the
source and local APK delivery.

AndroidX and other existing dependencies retain their upstream licenses.

The QR scanner uses [ZXing Core 3.5.3](https://github.com/zxing/zxing),
copyright ZXing authors, under the [Apache License 2.0](third_party/material-icons-LICENSE.txt).
Only its offline QR decoder is used at runtime. Camera capture uses Android Camera2;
Google Play services and a remote recognition service are not required.

The 0.7.0 integration uses AndroidX Activity 1.13.0, WebKit 1.17.0,
Compose UI/Foundation/Runtime 1.9.4 and Material 3 1.4.0 under Apache License 2.0.
Exact runtime and transitive versions are recorded in `app/gradle.lockfile`;
artifact checksums are in `gradle/verification-metadata.xml`.

The bundled **EasyList, EasyPrivacy and EasyList China** rule data are unmodified
2026-09-09 snapshots, attributed to **The EasyList authors (https://easylist.to/)**
and the [EasyList China maintainers](https://github.com/easylist/easylistchina/).
They are redistributed here under
[Creative Commons Attribution-ShareAlike 3.0 Unported](third_party/easylist-CC-BY-SA-3.0.txt),
one of the alternatives offered by the [upstream licence notice](https://easylist.to/pages/licence.html).
This licence covers the rule data. Source URLs, versions and SHA-256 hashes are
recorded in [the bundled notice](app/src/main/assets/filters/NOTICE.txt).
Both the notice and full licence text are included in the APK assets.
# Public Suffix List and IDNA

The `site_identity` Rust module uses `psl` 2.1.232 (MIT/Apache-2.0) and its compiled
Mozilla Public Suffix List, including ICANN and PRIVATE sections. List data is licensed
under MPL-2.0; source: https://publicsuffix.org/list/ and https://github.com/addr-rs/psl.
`idna` 1.1 (MIT/Apache-2.0) provides UTS #46 normalization using ICU4X (Unicode-3.0).
Exact transitive versions are pinned in `rust/Cargo.lock`. Update the pinned PSL version,
run the site-identity/filter/desktop-alias regressions, and rebuild both JNI libraries
when refreshing the list. Permissions continue to use full origins, not PSL domains.
