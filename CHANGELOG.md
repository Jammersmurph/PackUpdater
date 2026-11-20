# PackUpdater changelog

## 1.6.3 — 2026-11-20

This release makes the maintained PackUpdater installer the default and
documents its desktop features as part of the PackUpdater experience. The
installer remains independently updateable and configurable; the capabilities
below do not require changes to the Packwiz tool.

### Default update experience

- Changed the built-in installer endpoint to
  `https://api.github.com/repos/Jammersmurph/packwiz-installer/releases/latest`.
- Updated the bundled and release-attached `packupdater.properties` to use that
  same default. New configurations do not need an installer URL change to get
  the supported PackUpdater interface.
- Standard desktop features now documented together:
  - Purple theme and square PackUpdater branding.
  - Pack name from `pack.toml` in the header and window title.
  - Optional pack artwork in both the header and window icon.
  - Searchable optional-mod selection, including collapsible groups.
  - Expandable activity log and failed-download details.
  - Automatic closing after successful updating, rather than a new completion
    prompt. Original optional-mod reminders and error prompts remain.

### Optional-mod groups

- Added a release-attached and JAR-packaged **`optional-groups.toml`** example.
- Added a full guide for defining VR, visual, or other groups using optional
  `.pw.toml` metadata paths.
- Documented whole-group enable/disable, individual overrides, mixed-state
  indicators, enabled counts, search, ungrouped choices, and Save/Cancel.
- Grouping preserves existing choices and defaults. Required mods cannot be
  made optional by listing them in a group; side restrictions still apply.
- Missing configuration keeps the normal list. Invalid configuration warns and
  falls back safely. Duplicate membership uses the first group.

### Pack artwork

- Documented PNG, WebP, JPG/JPEG, GIF (first frame), and BMP support, including
  files whose actual image format does not match the extension.
- Documented the exactly-one-icon rule: multiple supported `icon.*` candidates
  beside `pack.toml` keep the default logo.
- Documented aspect-ratio preservation, the 1 MiB/2048×2048 limits, and the
  shared three-second remote lookup deadline.

### Documentation and distribution

- Added an authoring example, player guide, optional-group reference, and
  installer/branding/migration guide.
- Updated first-run config comments to describe automatic completion and group
  placement, and corrected the claim that a blank installer URL disables updates.
- Releases retain the version-less `packupdater.jar` asset and now publish:
  `packupdater.jar`, `packupdater.pw.toml`, `packupdater.properties`, and
  `optional-groups.toml`.
- Added regression coverage for default URL consistency, packaged examples,
  omitted/blank settings, and preservation of existing explicit installer URLs.
  All 45 PackUpdater tests passed.

### Migration and compatibility

- **Existing configuration files are not overwritten.** A pack explicitly using
  the original upstream URL keeps it until its author changes the setting in
  the pack repository.
- JVM overrides remain higher priority than file settings.
- A missing or blank installer URL uses the new default; it does not disable
  self-update checks.
- `packupdater.dev` still selects the modpack's development URL, not a separate
  installer development release.
- Android, dedicated-server, and silent-fallback behavior is unchanged.
  Grouping does not opt desktop-only mods into restricted platforms.

See the [migration guide](docs/Installer.md#migrating-an-existing-pack) and
[optional-group guide](docs/Optional-mod-groups.md).
