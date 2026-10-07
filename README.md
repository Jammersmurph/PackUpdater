<div align="center">

# PackUpdater

**A NeoForge mod that updates a PackWiz modpack before Minecraft starts.**

Configured through a file in the instance, so players never need JVM arguments.

[Releases](https://github.com/Jammersmurph/PackUpdater/releases) · [Wiki](https://github.com/Jammersmurph/PackUpdater/wiki) · [Report an issue](https://github.com/Jammersmurph/PackUpdater/issues) · [PackWiz](https://packwiz.infra.link)

</div>

---

## What it does

Drop the jar in a modpack and it takes care of itself. On every launch, before mods load,
PackUpdater syncs the instance against your pack's `pack.toml`: new mods arrive, changed files are
replaced, removed files are cleaned up, and everything is verified against PackWiz's content
hashes. If your pack uses optional mods, players get the PackWiz installer's window to pick them.

## For modpack authors

Two files, nothing else:

**1. `mods/packupdater.pw.toml`** and **2. `config/packupdater.properties`** — both are
attached to the [release assets](https://github.com/Jammersmurph/PackUpdater/releases). Drop each
one in as-is; the only edit you need is the pack URL, which PackUpdater cannot discover on its own:

```properties
packupdater.url=https://raw.githubusercontent.com/you/pack/main/pack.toml
```

> On GitHub the URL must use the `raw.githubusercontent.com` host with the branch in the path. A
> `github.com` blob link returns HTML, and the update fails with a 404 that looks like a missing
> pack. See [Configuration](https://github.com/Jammersmurph/PackUpdater/wiki/Configuration#hosting-the-pack-on-github).

**Optional mods on Android.** Restricted platforms such as Android launchers get no optional mods at
all, because there is no window there to decline them and an optional mod is often desktop-only. A
pack author opts one in, in that mod's own metadata:

```toml
[option]
optional = true
packupdater-allow-restricted = true
```

Desktop is unaffected — the installer's window still appears and the player chooses.

**Dedicated servers.** Set `packupdater.server-side=true` in the server's config. It changes
nothing about how the update runs; it only tells the installer to install server-side mods and to
skip the window, since a server has neither.

Then:

```
packwiz refresh
```

That override is universal: releases publish the jar as a version-less `packupdater.jar` and the
file carries an `[update.github]` block, so one copy keeps working forever and `packwiz update`
handles new releases. **No CI changes needed** — if your pack already runs `packwiz update --all`,
PackUpdater is picked up automatically.

## Documentation

Full reference, including every configuration key and what happens on restricted platforms:

**[github.com/Jammersmurph/PackUpdater/wiki](https://github.com/Jammersmurph/PackUpdater/wiki)**

- [Configuration](https://github.com/Jammersmurph/PackUpdater/wiki/Configuration) — every key, grouped by intent
- [Restricted platforms](https://github.com/Jammersmurph/PackUpdater/wiki/Restricted-platforms) — Android launchers and headless servers
- [How it works](https://github.com/Jammersmurph/PackUpdater/wiki/How-it-works) — architecture and design notes
- [Building](https://github.com/Jammersmurph/PackUpdater/wiki/Building) — development setup

## Credits

PackUpdater is a fork of **[BrassUpdater](https://github.com/Brassworks-smp/BrassUpdater)** by
**[swzo](https://github.com/salem-5)**, the auto-updater for the Brassworks SMP. That mod is where
the transformation-service approach, the bootstrap-then-installer hand-off, and the loading-screen
progress reporting all come from.

The fork rebranded it to a neutral identity, replaced JVM-argument-only configuration with a config
file, made every URL configurable instead of pinned to a fork, fixed a build that could not
compile, turned the embedded bootstrap from an opaque binary into readable source, and added
install paths that work on platforms that cannot start a second JVM or show a window.

Thanks to swzo for the original work, and to the
[PackWiz](https://github.com/packwiz/packwiz-installer) project that does the actual installing.

## License

MIT — see [LICENSE](LICENSE).