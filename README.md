<div align="center">

# PackUpdater

**A NeoForge mod that updates a PackWiz modpack before Minecraft starts.**

Configured through a file in the instance, so players never need JVM arguments.

[Releases](https://github.com/Jammersmurph/PackUpdater/releases) · [Report an issue](https://github.com/Jammersmurph/PackUpdater/issues) · [PackWiz](https://packwiz.infra.link)

</div>

---

## What it does

Drop the jar in a modpack and it takes care of itself. On every launch, before mods load,
PackUpdater syncs the instance against your pack's `pack.toml`: new mods arrive, changed files are
replaced, removed files are cleaned up, and everything is verified against PackWiz's content
hashes. If your pack uses optional mods, players get the PackWiz installer's window to pick them.

Because configuration lives in the instance rather than in launch arguments, it works the same in
Prism Launcher, HMCL, ATLauncher, MultiMC, or a bare `java -jar` invocation — and a pack author
ships it by committing two files.

## Credits

PackUpdater is a fork of **[BrassUpdater](https://github.com/Brassworks-smp/BrassUpdater)** by
**[swzo](https://github.com/salem-5)**, the auto-updater for the Brassworks SMP. That mod was the
original inspiration, and the transformation-service approach, the bootstrap-then-installer
hand-off, and the loading-screen progress reporting all descend from it.

The fork changed substantially:

| | |
| --- | --- |
| **Branding** | Renamed to a neutral identity. Mod id, package, coordinates, log prefixes and the strings inside the embedded bootstrap were all replaced. |
| **Configuration** | A config file with per-key JVM overrides, replacing the original's JVM-argument-only setup. |
| **URLs** | Every upstream URL is now configurable. The installer release is no longer pinned to a fork. |
| **Build** | The committed build did not compile: `build.gradle` used Kotlin's `var` in a Groovy script, and `Resource` is a nested `ITransformationService` type rather than a top-level class in that package. |
| **Bootstrap** | The embedded bootstrap is now readable source in [`bootstrap/`](bootstrap/), rebuilt reproducibly by [`bootstrap/build.sh`](bootstrap/build.sh), instead of an opaque binary blob. |
| **Portable install** | Adds a silent filtered install and an in-process install engine, so packs update on Android launchers and other platforms that cannot start a second JVM or show a window. |

Thanks to swzo for the original work, and to the
[PackWiz](https://github.com/packwiz/packwiz-installer) project that does the actual installing.

## Using it in a modpack

Two files. Every release ships a ready-to-use override, so nothing needs hand-writing.

**1. `mods/packupdater.pw.toml`** — grab it from the
[release assets](https://github.com/Jammersmurph/PackUpdater/releases) and drop it in.

**2. `config/packupdater.properties`** — the one thing PackUpdater cannot discover on its own:

```properties
packupdater.url=https://raw.githubusercontent.com/you/pack/main/pack.toml
```

Then:

```
packwiz refresh
```

That override is universal. Releases publish the jar as a version-less `packupdater.jar` and the
file carries an `[update.github]` block, so one copy keeps working forever: `packwiz update`
rewrites the url, hash and tag as new releases appear. Don't hand-edit the url or hash — if you
do, the next `packwiz update` corrects it back.

**No CI changes are needed.** If your pack already runs `packwiz update --all`, PackUpdater is
picked up automatically as another external file.

> On a brand-new instance the config file doesn't exist for the first launch, so that launch skips
> the update and it works from the second. Launchers that pre-install pack files avoid this.

## Configuration

Settings are read from `<gameDir>/config/packupdater.properties`, which is created with documented
defaults on first launch. Any key can be overridden with a JVM argument, which takes precedence
over the file:

```
-Dpackupdater.url=https://raw.githubusercontent.com/you/pack/main/pack.toml
```

JVM arguments are convenient for a single instance; the config file is better for sharing a setup
across a team.

### Pack

| Key | Default | Purpose |
| --- | --- | --- |
| `packupdater.url` | *(empty)* | URL of the `pack.toml` to sync against. **Required.** |
| `packupdater.dev-url` | *(empty)* | Pack URL used while `dev` is true. Takes precedence over `url`. |
| `packupdater.dev` | `false` | Sync `dev-url` instead of `url`. |
| `packupdater.skip` | `false` | Skip the updater entirely. |

`packupdater.url` must be the **raw** file URL. A `github.com` blob or tree link returns HTML and
the installer will fail with a 404 — use `raw.githubusercontent.com` or your own CDN.

With `packupdater.dev=true`, `dev-url` wins over `url`, so testing a branch is a one-flag change
even when the pack ships a production URL.

### Installer

| Key | Default | Purpose |
| --- | --- | --- |
| `packupdater.installer-url` | upstream PackWiz | GitHub "latest release" API URL for the PackWiz installer. Blank disables the self-update. |
| `packupdater.installer-asset` | `packwiz-installer.jar` | Release asset to download. |
| `packupdater.installer-token` | *(empty)* | GitHub token; only needed for private repositories. |

Out of the box this bootstraps [packwiz/packwiz-installer](https://github.com/packwiz/packwiz-installer),
so no configuration is needed. `installer-url` only needs changing if you maintain your own build,
and it must point at a release containing an asset named `installer-asset`.

### Window and optional mods

| Key | Default | Purpose |
| --- | --- | --- |
| `packupdater.gui` | `true` | Show the installer's window, which is where optional mods are chosen. |

Optional mods come from the `[optional-mods]` section of your pack's `index.toml`. With the window
shown, the installer offers an **"Optional mods..."** button and remembers the choice between runs.

Two consequences worth knowing:

- **Startup blocks.** Minecraft waits for the installer until the window closes, with the loading
  screen behind it.
- **Two progress indicators.** The installer's own window, and the loading screen PackUpdater
  drives from the installer's output.

Set `gui=false` for a fully unattended update.

### Restricted platforms

Some platforms can't show a window or can't start a second JVM at all. PackUpdater handles both,
and each fallback still installs the *same files* the windowed path would have, by honouring each
optional mod's declared `default`.

The installer's own CLI path force-enables every optional mod it finds, so simply passing `-g`
would install the union of all of them. PackUpdater avoids that: when it cannot use the window, it
rebuilds the index without the optional mods the pack explicitly disables, and installs the rest.

| Key | Default | Values | Purpose |
| --- | --- | --- | --- |
| `packupdater.compat` | `auto` | `auto`, `off` | `off` disables detection and fallback for exactly the previous behaviour. |
| `packupdater.fallback` | `auto` | `auto`, `never`, `always` | When to use the silent filtered install. `always` forces it, which is how you exercise this path on a desktop. |
| `packupdater.engine` | `auto` | `auto`, `fork`, `direct` | `auto` forks a separate JVM and installs in-process when it cannot. `fork` always forks, `direct` never does. |

**Android launchers** (PojavLauncher, Pojav Glow·Worm, Fold Craft, Zalith) run Minecraft inside
the launcher and sandbox it, so `bin/java` cannot be executed at all — it fails with
`Exec failed, error: 13 (Permission denied)`. PackUpdater detects the refused spawn and installs
inside the game process instead. It cannot call the PackWiz installer there, because that invokes
`System.exit` on nearly every path including success, which would end the running game, and Java 21
does not allow `System.exit` to be trapped. So PackUpdater performs the download and verification
loop itself.

What you give up on that path is the window: optional mods cannot be chosen interactively and
follow the pack's declared defaults.

If PackUpdater ever misjudges a platform, `packupdater.compat=off` restores the original
behaviour without replacing the jar.

## Building

```
./bootstrap/build.sh   # rebuilds the embedded bootstrap jar
./gradlew build
```

CI runs both on every push and pull request, and publishes the mod jar plus a fresh
`packupdater.pw.toml` when a `v*` tag is pushed.

The bootstrap jar is built with `--release 21` and reproducible timestamps. Both matter: Minecraft
1.21.1 runs on Java 21, so a jar built on a newer JDK carries a class file version the game cannot
load, and CI checks for that on every build.

## How it works

PackUpdater registers as a ModLauncher `ITransformationService`, which runs early enough to hand
off to the PackWiz installer before any mod is loaded:

1. Read the configuration.
2. Extract the embedded bootstrap jar to a temp directory.
3. Start a child JVM, unless the platform forbids it.
4. The bootstrap self-updates the PackWiz installer from its GitHub release, then runs it against
   the game directory.

When a window is available, PackUpdater reports progress on the NeoForge loading screen by reading
the installer's output. Progress text is matched by pattern, so installer wording changes can
silently degrade that display without affecting the update itself.

## License

MIT — see [LICENSE](LICENSE).