## PackUpdater

A NeoForge mod that updates a [PackWiz](https://packwiz.dev) modpack before Minecraft starts,
reporting progress on the loading screen.

## How it works

PackUpdater hooks into ModLauncher as an `ITransformationService`, which runs early enough that
the NeoForge loading screen is still available to display progress. On launch it:

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
| `packupdater.dev-url` | *(empty)* | Fallback pack URL, used only when `url` is blank and `dev` is true. |
| `packupdater.dev` | `false` | Use `dev-url` instead of `url`. |
| `packupdater.installer-url` | upstream PackWiz | GitHub "latest release" API URL for the PackWiz installer. Blank disables the self-update. |
| `packupdater.installer-asset` | `packwiz-installer.jar` | Release asset to download from that release. |
| `packupdater.installer-token` | *(empty)* | GitHub token, only needed for private repositories. |
| `packupdater.gui` | `false` | Let the bootstrapper show its own window instead of driving the loading screen. |
| `packupdater.skip` | `false` | Skip the updater entirely. |

Example as JVM arguments:

```
-Dpackupdater.url=https://example.com/pack/pack.toml
-Dpackupdater.installer-url=https://api.github.com/your-org/packwiz-installer/releases/latest
```

JVM arguments are easier to set per-instance in launchers such as Prism Launcher, HMCL, or
ATLauncher; the config file is easier to share with a whole team.

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

Ship the jar from the [releases page](https://github.com/Jammersmurph/PackUpdater/releases) and
commit a `pack.toml` for it in your PackWiz index. End users then only need the config file -
they never have to touch JVM arguments.

The one required setting is `packupdater.url`. Everything else has a working default, including
the installer, so a stock install needs nothing but that.

## License

MIT — see [LICENSE](LICENSE).