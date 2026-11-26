# Project maintenance instructions

- Read [the documentation index](docs/README.md) and the guide relevant to the task before editing.
  UI work must follow [the design system](docs/design-system.md). Incognito, persistence and
  session work must follow [the incognito contract](docs/incognito-policy.md).
  Before building or starting an emulator, read [the macOS / Windows build guide](docs/building.md)
  and [the testing guide](docs/testing.md).
- Inspect Git status and preserve unrelated edits. User instructions override repository defaults;
  do not commit, push or publish when the user asks to leave changes in the workspace.
- Preserve the product design: no fixed limit on resident background tabs. Incognito shares
  explicitly saved outputs/preferences and isolates session data. Accepted private downloads and
  their records survive session exit; do not replace old test expectations with cancellation/deletion.
- Avoid overdefensive programming. Fix demonstrated failures and real external boundaries; do not add
  broad exception swallowing, repeated validation, speculative fallbacks, retries or duplicate state.
  Prefer existing components and lifecycle owners; only introduce abstraction for concrete reuse.
- Keep Gradle operations serial in one checkout. Match APK ABI to the device. Emulators must run
  headless (`-no-window -no-audio`); stop the task's emulator and QA helper processes after validation,
  including on failure. Do not stop unrelated user processes or silently overwrite an existing AVD.
  Prefer an existing compatible, idle project QA AVD; do not create one for every task. Create a
  temporary AVD only when no suitable device exists or API/ABI coverage or concurrent isolation requires it.
  Track the AVDs and processes created or started by the task. After success, failure or interruption,
  stop and delete task-created temporary AVDs to release memory and disk space. Keep the shared QA AVD
  definition for reuse, clean up the task's test data, and do not stop or delete devices used by others.
- Validate according to [the testing guide](docs/testing.md). Application changes require relevant
  automated and local device checks, then a signed installable release APK. Documentation-only work
  needs content/link checks; tooling-only work needs the affected tooling checks, not an app rebuild.
  Report actual coverage and limitations; never claim Windows or physical-device validation from a Mac emulator run.
- Keep documentation under `docs/` with one owner per topic. Keep both READMEs in sync and update
  only the affected guides. Add a version to [changelog](docs/changelog.md) only after its official
  release is published. Do not put test builds, unreleased work or release plans there; prepare
  public release notes outside Git, then archive the published text in the changelog.
  Keep generated test reports and artifact evidence outside Git; summarize actual coverage and
  failures in the delivery response. Do not add manual verification documents, session reports or old-plan copies.
- The local maintenance branch is `master`; remote `main` is the CI/release branch. When publication
  is explicitly requested, follow [the release guide](docs/releasing.md); pushing `master` does not
  trigger the `main` workflow. Do not create backup/checkpoint branches or duplicate artifact backups.
- Keep only the latest delivery APK and necessary validation records locally. Never commit signing
  credentials, keystores, `local.properties`, generated output, APKs or `validation/results/`.
- Follow the script ownership in `docs/testing.md`. Keep reusable build/release tools and meaningful
  regressions; remove obsolete probes and unused fixtures. When moving scripts, update callers,
  fixture paths and test source hashing together. Do not retain forwarding scripts for old paths
  or change test expectations to make a cleanup pass.
