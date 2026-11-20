# Optional-mod groups

PackUpdater's optional-mod window supports collapsible groups and group-wide
selection. Authors configure these as part of their pack using the standard
PackUpdater installer. **No changes to the Packwiz tool are required**.

## 1. Add the example beside your hosted pack.toml

Download **`optional-groups.toml`** from the PackUpdater release assets, or copy
[`examples/optional-groups.toml`](../examples/optional-groups.toml).

Place it in the same directory as the `pack.toml` PackUpdater downloads:

```text
your-pack/
├── pack.toml
├── index.toml
├── optional-groups.toml
├── icon.png                       # Optional artwork
├── config/
│   └── packupdater.properties     # This one is installed into the instance
└── mods/
    ├── vivecraft.pw.toml
    └── vivecraft-compat.pw.toml
```

If your pack URL is
`https://raw.githubusercontent.com/you/pack/dev/pack.toml`, the group file must be
available at
`https://raw.githubusercontent.com/you/pack/dev/optional-groups.toml`.
Use the corresponding directory on every pack branch you want to support.

The installer fetches the group file directly when opening optional choices,
even when the pack itself is already up to date. It does **not** belong in the
client's `config/` directory and does not need a Packwiz index entry.

## 2. Define groups using metadata paths

```toml
version = 1

[[groups]]
id = "vr"
name = "VR Support"
description = "Enable these mods when playing with a VR headset."
mods = [
    "mods/vivecraft.pw.toml",
    "mods/vivecraft-compat.pw.toml",
]

[[groups]]
id = "visuals"
name = "Visual Enhancements"
description = "Optional shader support."
mods = ["mods/iris.pw.toml"]
```

Replace these example paths with files that actually exist in your pack.

| Field | Meaning |
| --- | --- |
| `version` | Config format version, not modpack version. Optional; defaults to `1`. |
| `[[groups]]` | Starts another group. Groups appear in file order. |
| `id` | Unique, non-empty identifier, such as `vr`. |
| `name` | The title players see. Required. |
| `description` | Optional explanation shown in the details pane. |
| `mods` | Paths to optional `.pw.toml` files, relative to `pack.toml`. Required. |

Use **metadata paths**, not display names or downloaded JAR filenames.
Paths must stay inside the pack; absolute paths and `..` traversal are rejected.

## 3. Ensure the listed mods are actually optional

Grouping only organizes choices that already exist. A required mod will **not**
become optional because it appears in this file.

For example, an optional mod's `.pw.toml` can contain:

```toml
[option]
optional = true
default = false
description = "Enable when playing with a VR headset."
```

Use your normal Packwiz workflow to maintain that metadata. If you edit a
`.pw.toml` manually, run `packwiz refresh` afterward so its indexed hash stays
correct. Editing only `optional-groups.toml` does not require an index change.

## What players see

- Expand a group to see its individual mods.
- Check a group to enable all its members; uncheck it to disable them all.
- Mixed selections show an intermediate checkbox state and an enabled count.
- Search finds group names or mod names and expands matching results.
- Group checkboxes affect the entire group, including search-hidden members.
- **Save selection** applies changes. **Cancel** or closing the window discards them.

Existing selections and the individual mods' defaults remain unchanged until
the player edits and saves them. Unlisted optional mods appear under
**Other optional mods**.

## Fallbacks and limitations

- Only existing optional mods for the current client/server side can match.
- A mod in multiple groups belongs to the first group. Unknown members produce
  warnings and are skipped; groups with no matching members are hidden.
- Missing configuration preserves the original flat list. Invalid configuration,
  unsupported versions, and download failures warn and fall back without
  preventing installation.
- Files are limited to 128 KiB, 100 groups, and 2,000 members per group.
  Remote fetches have a three-second deadline.
- Groups do not resolve mod dependencies or alter which mods are required.
- Grouping is a desktop GUI feature. Headless/restricted-platform behavior is
  unchanged; it does not automatically enable mods on Android.
- Pointing `packupdater.installer-url` at the original upstream installer disables
  these fork-specific features.

See [Installer source, branding, and migration](Installer.md) if an existing
configuration still points at the original installer.
