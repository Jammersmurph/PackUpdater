## PackUpdater

A NeoForge mod that updates a [PackWiz](https://packwiz.dev) modpack before Minecraft starts.
Everything is configured through a file in the instance, so players never need JVM arguments.

## How it works

PackUpdater hooks into ModLauncher as an `ITransformationService`, which runs early enough to
hand off to the PackWiz installer before mods load. On launch it:

1. Reads its configuration (see below).
2. Extracts an embedded bootstrap jar to a temp directory.
3. Runs that bootstrap in a separate JVM against your game directory.
4. The bootstrap self-updates the PackWiz installer from a GitHub release, then runs it to sync
   your pack.

The bootstrap source lives in [`bootstrap/`](bootstrap/) and can be rebuilt with
[`bootstrap/build.sh`](bootstrap/build.sh).

## Configuration

Settings are read from `<gameDir>/config/packupdater.properties`, which is created with
documented defaults on first launch. Any key can be overridden with a JVM argument, which takes
precedence over the file:

| Key | Default | Purpose |
| --- | --- | --- |
| `packupdater.url` | *(empty)* | URL of the `pack.toml` to sync against. Required. |
| `packupdater.dev-url` | *(empty)* | Pack URL to use when `dev` is true. Takes precedence over `url` while `dev` is on. |
| `packupdater.dev` | `false` | Sync `dev-url` instead of `url`. |
| `packupdater.installer-url` | upstream PackWiz | GitHub "latest release" API URL for the PackWiz installer. Blank disables the self-update. |
| `packupdater.installer-asset` | `packwiz-installer.jar` | Release asset to download. Passed to the bootstrap as a JVM system property, not a CLI flag, because the installer rejects unknown arguments. |
| `packupdater.installer-token` | *(empty)* | GitHub token, only needed for private repositories. |
| `packupdater.gui` | `true` | Show the PackWiz installer's window on launch, which is where optional mods are chosen. Forced off when headless. |
| `packupdater.skip` | `false` | Skip the updater entirely. |

Example as JVM arguments:

```
-Dpackupdater.url=https://raw.githubusercontent.com/you/pack/main/pack.toml
```

`packupdater.url` must be a URL to the raw file, not a `github.com` page URL. A `github.com`
blob or tree link returns HTML and the installer will fail with a 404.

JVM arguments are easier to set per-instance in launchers such as Prism Launcher, HMCL, or
ATLauncher; the config file is easier to share with a whole team.

### Optional mods

Optional mods come from the `[optional-mods]` section of your pack's `index.toml`. When they are
present, the PackWiz installer shows an **"Optional mods..."** button in its window and asks what to
install on each run where the selection has changed.

That window is only shown when `packupdater.gui` is on, which is the default. PackUpdater passes
`-g` only when you turn it off, or when there is no display at all, so a dedicated server or CI job
falls back to a silent update instead of failing on a missing window.

Two consequences worth knowing:

- Startup blocks. Minecraft waits on the installer until the window closes, and the loading screen
  sits behind it.
- With the window shown you get two progress indicators: the installer's own window, and the
  Minecraft loading screen that PackUpdater drives from the installer's output.

### Mobile and other restricted platforms

Some platforms cannot open the installer's window from a child process. Android launchers such as
PojavLauncher, Pojav Glow·Worm, Fold Craft and Zalith supply AWT from an external jar instead of
from the JDK, so the game has a working display but a forked JVM does not.

PackUpdater asks a short-lived child JVM whether it can actually open a window, and uses that
answer. When the answer is no, or when a windowed run fails with a windowing error, it retries
with no window against a copy of the pack index.

Guessing does not work here, which is why it is a real probe. `GraphicsEnvironment.isHeadless()`
reports only whether `java.awt.headless` was set, and launchers set it to `false` while pointing
`DISPLAY` at a display that does not exist, so it happily returns `false`. Asking the game JVM
for its AWT toolkit's code source is no better: launchers supply AWT through `-Xbootclasspath`,
and a bootclasspath-loaded class reports a `null` code source exactly like a stock JDK toolkit,
so the two are indistinguishable. Only building a window exercises the thing that fails.

That retry honours each optional mod's declared `default`, which passing `-g` alone would not:
the installer's CLI path force-enables every optional mod it finds. The fallback omits optional
mods the pack explicitly disables, and installs the rest.

If you would rather it did not do this at all:

| Key | Default | Effect |
| --- | --- | --- |
| `packupdater.compat` | `auto` | Set to `off` for exactly the previous behaviour, with no detection and no fallback. |
| `packupdater.fallback` | `auto` | `never` disables the retry. `always` uses it unconditionally, which is how you exercise this path on a desktop. |

### Installer releases

Out of the box PackUpdater bootstraps
[packwiz/packwiz-installer](https://github.com/packwiz/packwiz-installer), so no installer
configuration is needed. `packupdater.installer-url` only needs changing if you maintain your own
build of the installer, and it must point at a GitHub release whose assets include a file named
`packupdater.installer-asset`. If that asset is missing, the bootstrap reports the mismatch and
falls back to whatever installer it already has, if any.

## Building

```
./bootstrap/build.sh   # rebuilds the embedded bootstrap jar
./gradlew build
```

CI runs both steps on every push and pull request, and publishes the mod jar as a release asset
when a `v*` tag is pushed.

## Using it in a modpack

Every release ships a ready-to-use `packupdater.pw.toml` next to the jar. Grab it from the
[releases page](https://github.com/Jammersmurph/PackUpdater/releases), drop it into your pack's
`mods/`, and run `packwiz refresh` once.

That file is universal. Releases publish the jar as a version-less `packupdater.jar` and the
override carries an `[update.github]` block, so a single copy keeps working forever -
`packwiz update` rewrites the url, hash and tag as newer releases appear. Do not hand-edit the
url or hash; if you do, `packwiz update` will correct it back.

Two files are needed in total:

1. `mods/packupdater.pw.toml` — from the release assets
2. `config/packupdater.properties` — the pack url, which the mod cannot discover on its own

```toml
# config/packupdater.properties, shipped in your pack
packupdater.url=https://raw.githubusercontent.com/you/pack/main/pack.toml
```

Then run `packwiz refresh`. No CI changes are needed: if your pack already runs
`packwiz update --all`, PackUpdater is picked up automatically as another external file.

On a brand new instance the config file does not exist for the first launch, so that launch
skips the update and it starts working from the second one. Launchers that pre-install pack
files avoid this.

## License

MIT — see [LICENSE](LICENSE).