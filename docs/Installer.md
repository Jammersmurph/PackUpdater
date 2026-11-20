# Installer source, branding, and migration

## Default installer

PackUpdater now defaults to
[Jammersmurph/packwiz-installer](https://github.com/Jammersmurph/packwiz-installer):

```properties
packupdater.installer-url=https://api.github.com/repos/Jammersmurph/packwiz-installer/releases/latest
packupdater.installer-asset=packwiz-installer.jar
```

This fork provides the themed desktop window, automatic pack names, pack artwork,
and [optional-mod groups](Optional-mod-groups.md). It still installs standard
Packwiz packs; modpack authors do not need a modified Packwiz tool.

PackUpdater extracts its embedded bootstrapper, which checks the installer's
stable GitHub release and downloads `packwiz-installer.jar` when its version
changes. The fork's release tag matches the JAR's `Implementation-Version`, so
an unchanged installer is not downloaded on every launch. An update check can
still happen on every launch; that does not mean another JAR was downloaded.

## Migrating an existing pack

Updating the PackUpdater mod does **not** overwrite an existing
`config/packupdater.properties`. Explicit settings still win over built-in
defaults.

If your pack's configuration explicitly points at
`https://api.github.com/repos/packwiz/packwiz-installer/releases/latest`, replace
that line with the new URL above in **your pack repository**, then distribute it
through your normal Packwiz workflow. A JVM `-Dpackupdater.installer-url=...`
override takes precedence over the config file too.

New configurations and configurations without an explicit installer URL use
the new default. A blank URL also falls back to the default; it does not disable
the installer's self-update check.

The `packupdater.dev` flag selects your **modpack's** development URL, not an
installer development build. Installer releases use the stable channel; test
installer development changes locally.

You can still select another compatible installer by supplying its GitHub
release API URL and asset name. Upstream installers do not support this fork's
custom artwork and optional-group configuration.

## Pack name and window behavior

The header and window title use the `name` from `pack.toml` once it loads.
The installer also supports a `--title` override when launched directly through
its bootstrapper; PackUpdater does not add a separate title property.

The fixed-size updater window closes automatically after a successful update.
Optional-mod choices and confirmation dialogs can pause launching; closing or
cancelling an update is not the same as completing it.

## Optional artwork

Put exactly one supported icon file beside the hosted `pack.toml`:

```text
icon.png
icon.webp
icon.jpg
icon.jpeg
icon.gif
icon.bmp
```

These are alternatives, **not** files to add all at once. If multiple candidates
exist, the installer uses its default square PackUpdater logo.

PNG, WebP, JPEG, GIF (first frame), and BMP contents are supported. Detection is
based on contents, so an existing WebP file named `icon.png` also works. Images
must be no larger than 1 MiB and 2048×2048 pixels. The header preserves aspect
ratio within a square display area; the image also replaces the window icon.

Missing, invalid, oversized, ambiguous, or unavailable artwork leaves the default
logo unchanged. All remote icon checks share a three-second deadline. Headless
installs do not fetch artwork.

Artwork and `optional-groups.toml` are independent of the PackUpdater client
configuration and do not require new `packupdater.*` settings.
