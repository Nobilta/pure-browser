# Project maintenance instructions

- Before editing, check Git status and preserve unrelated user changes.
- Keep the completed source on the local `master` branch using normal commits. It publishes to
  the remote `main` branch, which is the one CI watches, so push with `git push origin HEAD:main`
  as RELEASING.md describes — pushing a branch literally named `master` would trigger no CI.
  Do not create or retain backup/checkpoint/rollback branches or duplicate source/artifact backups.
- Keep only the latest delivery APK and its necessary validation records locally.
- After code changes, run automated checks and a local Android emulator regression.
  Record actual results and any unverified device/version coverage.
- After validation, update README and deliver a signed, directly installable release APK.

- Keep the Chinese and English READMEs in sync. Update `FEATURES.md` for user-visible changes,
  the contributing/testing guides for development changes, and `RELEASING.md` for release steps.
- Put each release's concise validation summary in `release/notes.md`: identify the tested artifact,
  device, actual coverage and unverified areas. Keep detailed results outside Git; use generated
  release attachments for APK sizes and checksums instead of duplicating them across documents.
- Keep documentation focused on current usage and maintenance. Do not add standalone session
  reports, task-completion logs or lists of features excluded from future development.
- Treat `README.md` as the canonical project entry point, and keep `README.en.md` structurally in
  sync with it (same sections and links, English prose). Keep requirements, paths, version details
  and capability claims accurate.
- Never commit `local.properties`, signing credentials, keystores, generated build output,
  or delivery APK files.
