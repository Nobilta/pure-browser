# Project maintenance instructions

- Before editing, check Git status and preserve unrelated user changes.
- Keep the completed source on `master` using normal commits. Do not create or retain
  backup/checkpoint/rollback branches or duplicate source/artifact backups.
- Keep only the latest delivery APK and its necessary validation records locally.
- After code changes, run automated checks and a local Android emulator regression.
  Record actual results and any unverified device/version coverage.
- After validation, update README and deliver a signed, directly installable release APK.

- Keep `README.md` synchronized with every subsequent change to dependencies, build or validation
  steps, and with `README.en.md`; keep `FEATURES.md` synchronized with every user-visible behavior
  or capability-boundary change; keep release steps, test counts, APK sizes and hashes in
  `RELEASING.md`.
- Treat `README.md` as the canonical project entry point, and keep `README.en.md` structurally in
  sync with it (same sections and links, English prose). Do not leave stale requirements, paths,
  test counts, APK sizes, hashes, or capability claims in any of them.
- Never commit `local.properties`, signing credentials, keystores, generated build output,
  or delivery APK files.
