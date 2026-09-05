# Project maintenance instructions

- Before editing, check Git status and create a named rollback branch or commit preserving
  the current work. Do not overwrite unrelated user changes.
- After code changes, run automated checks and a local Android emulator regression.
  Record actual results and any unverified device/version coverage.
- After validation, update README and deliver a signed, directly installable release APK.

- Keep `README.md` synchronized with every subsequent change to user-visible behavior,
  architecture, dependencies, build or validation steps, and release artifacts.
- Treat `README.md` as the canonical project entry point. Do not leave stale test counts,
  requirements, paths, APK sizes, hashes, or capability claims in it.
- Never commit `local.properties`, signing credentials, keystores, generated build output,
  or delivery APK files.
