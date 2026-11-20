# Using PackUpdater

PackUpdater updates your modpack before Minecraft loads its mods. On a desktop,
the normal PackUpdater experience includes a themed progress window, your
pack's name and artwork, optional-mod selection, and an activity log.

Pack authors configure this behavior. Players normally do not need to edit
files or add JVM arguments.

## On launch

1. Launch the instance normally.
2. PackUpdater checks its installer version and your pack's `pack.toml`.
3. The window displays progress while it checks or updates the pack.
4. When the update completes successfully, the window closes automatically and
   Minecraft startup continues. There is no routine completion button to click.

The header initially says **Modpack Updater**, then changes to the name from
`pack.toml` once it loads. The pack's own icon replaces the default square
PackUpdater logo when suitable artwork is available.

The updater and Minecraft are separate windows during this stage; the main
update window uses fixed-size hints intended to suit tiling window managers.
The optional-mod selector and error dialogs are separate dialogs.

## Optional mods

Click **Optional mods** when offered. Newly introduced optional choices can also
open the selector automatically. The normal optional-mod reminder can show a
Continue/countdown prompt before proceeding; this is not a post-update
completion requirement.

If the author supplied groups, such as **VR Support**:

- Click its arrow to expand or collapse the group.
- Check the group to enable all its members, or uncheck it to disable them.
- Expand it to change individual mods.
- A mixed checkbox and enabled count indicate that only some members are on.
- Unlisted mods appear under **Other optional mods**.
- Search finds group names and individual mods, automatically expanding matches.
  A group checkbox still affects **all** its members, not just search results.

Click **Save selection** to apply your choices. **Cancel** or closing the selector
discards unsaved changes and may interrupt the current update/launch flow.
Previously saved choices remain available on later launches; group configuration
does not reset them to a new preset.

If the pack author has not supplied group configuration, the normal searchable
flat list is shown instead.

## Activity and errors

**Show activity** expands the recent progress messages. **Hide activity** hides
the log again; it does not stop the update.

If downloads fail, PackUpdater can show the failed files and their technical
details. Depending on the failure, controls may let you stop launching, continue
without failed downloads, ignore the update, or open missing mod pages. Skipping
files or updates can leave a pack incomplete; use those choices deliberately.

Cancelling the main updater is not successful completion and may stop the game
from launching.

## Common questions

### Why are the groups or custom icon missing?

These are author-configured features. Groups need a valid
`optional-groups.toml` beside the hosted `pack.toml`. Artwork needs exactly one
supported `icon.*` file there. Missing or invalid configuration falls back to the
normal list or default logo without making the update fail.

Multiple supported `icon.*` files also use the default logo. See
[branding and installer configuration](Installer.md) for the format and size
limits, and [optional-mod groups](Optional-mod-groups.md) for the full schema.

An existing configuration that explicitly selects the original upstream
installer also keeps its original interface. The pack author can migrate that
setting as described in the [migration guide](Installer.md#migrating-an-existing-pack).

### Why does it check for updates every time?

Checking for updates is normal. Downloading the same installer repeatedly is
not expected with the standard installer releases: the installed JAR's version
matches its release tag, and an unchanged release reports **Already up to date**.

### What about Android and dedicated servers?

The desktop interface is not required for restricted/headless installations.
PackUpdater retains its silent fallback behavior there. Group configuration does
not automatically enable optional desktop mods on Android; authors must still
use the existing restricted-platform opt-in rules.

### Can I replace the installer?

Yes. The backend remains configurable through `packupdater.installer-url` and
`packupdater.installer-asset`. Another installer may not provide the standard
PackUpdater interface, branding, or groups documented here.
