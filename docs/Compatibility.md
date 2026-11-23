# NeoForge compatibility

PackUpdater uses **one jar**, installed in `mods/`, with no Java agent or launcher
arguments. It does not depend on Minecraft gameplay classes.

## Support range

The current source supports **NeoForge for Minecraft 1.20.1 through 26.3** with
one jar. Both generations of mod metadata allow the intermediate releases and
their corresponding loader versions. Stable releases in this range are listed
in `supported_minecraft_versions` for Modrinth publication.

If an installation fails, [open a GitHub issue](https://github.com/Jammersmurph/PackUpdater/issues/new)
with the Minecraft version, NeoForge version, Java version, and full launch log.

These changes are unreleased; the published 1.6.3 jar does not contain the modern
loader adapter or expanded version ranges.

## Desktop regression checks

| Minecraft | NeoForge | Live checks |
|---|---|---|
| 1.20.1 | 47.1.106 | Java 17, GUI/optional selection, same-launch add |
| 1.21 | 21.0.167 | GUI/optional selection, same-launch add and replacement; Java 17-core regression |
| 1.21.1 | 21.1.216 | Same-launch add, replacement, removal, GUI/optional selection |
| 26.3 | 26.3.0.115 | GUI/optional selection and same-launch add |

There is one distributed jar, not separate artifacts per game version. The first
three targets (1.21, 1.21.1, 26.3) used the identical Java 21-core build. Adding
1.20.1 lowered the shared core and embedded bootstrap to Java 17; that build was
then checked on 1.20.1 and rechecked on 1.21. The modern adapter also remains
covered by its loader-library tests.

The 1.21.1 baseline downloaded the companion installer from the normal GitHub
endpoint. Later tests used a local snapshot of that same installer
(`build-44828358880-1`) after a 1.21 attempt hit HTTP 403 from GitHub. That failed
attempt did not verify updating; the subsequent local-snapshot attempts did.

## Startup integrations

- **ModLauncher-based NeoForge:** the existing transformation service still runs
  the updater before mod discovery. Original-namespace NeoForge 1.20.1 uses its
  public `net.minecraftforge` loader APIs. Subsequent versions use `net.neoforged`.
  Earlier releases read `META-INF/mods.toml`, while newer ones read
  `META-INF/neoforge.mods.toml`. Both descriptors are in
  the same jar. The old descriptor uses LowCodeFML because startup is handled by
  the transformation service, not a version-specific mod constructor.
- **Newer NeoForge without ModLauncher:** a highest-priority mod-file candidate
  locator runs the same updater before the built-in mods-folder locator takes
  its directory snapshot. This hook also runs on dedicated servers; it is not a
  graphics-only bootstrap hook.
- A shared startup guard prevents both integrations from updating twice.

The updater engine, config, companion installer, optional selections, GUI probe,
and restricted-platform fallback are shared. Loading-screen progress uses a bar
owned by PackUpdater, not reflection into another loader's private fields.

Like other early services, PackUpdater itself and other already-loaded early
plugins cannot be reliably replaced in memory. Their updates may need another
launch. The same-launch checks below concern ordinary mod jars.

## Verification

Loader-library unit tests are not full game compatibility tests. `./gradlew build`
runs the updater tests in isolated JVMs against original-namespace FML 47 and
renamed-namespace FML 3, 4, 9, 10, and 12. Building/testing needs JDK 17, JDK 21,
and JDK 25; the bundled adapter, core, and bootstrap target Java 17 bytecode.
The game still needs the Java runtime required by its own version.

Loader-library checks complement the live desktop regression checks above.

Live launch evidence is collected separately using dedicated Prism instances,
one visible instance at a time:

The initial same-launch mod tests used a temporary ordinary test mod, which was
subsequently removed from the test setup. It was never bundled into PackUpdater
or required for normal installation.

The current fixture installs only harmless config files and an optional text
file. It checks downloads, optional selection, game-resource loading, and exactly
one update per launch. Optional explicit phases can check replacement and cleanup.
Only `packupdater.jar` is installed as a mod.

### Reproducing the desktop smoke tests

Use disposable instances named `PackUpdater Testing <version>`, containing the
same built jar in `minecraft/mods/packupdater.jar`.

```sh
./gradlew build
python scripts/compat-test-pack.py /tmp/opencode/packupdater-compat/pack \
  --phase 1 --installer /path/to/known-good/packwiz-installer.jar
python -m http.server 18761 --bind 127.0.0.1 \
  --directory /tmp/opencode/packupdater-compat/pack
```

Configure each test instance:

```properties
packupdater.url=http://127.0.0.1:18761/pack.toml
packupdater.gui=true
packupdater.installer-url=http://127.0.0.1:18761/installer-release.json
```

Then, in another terminal:

```sh
python scripts/compat-prism-test.py "PackUpdater Testing 1.20.1"
python scripts/compat-prism-test.py "PackUpdater Testing 1.21"
```

Leave the harmless optional test file checked when prompted. The runner waits
for game resources and verifies filesystem results, leaves the game visible for
ten seconds, then terminates only that test game. One launch is the default;
multiple phases require an explicit `--phases 1 2 3`. Evidence
is saved under `/tmp/opencode/packupdater-compat/results/`.

The local installer snapshot isolates loader compatibility from GitHub API
availability. A successful test with it does **not** verify GitHub downloading.
Do not ship these localhost URLs in a real pack.

Dedicated-server, other operating-system, and restricted-launcher testing must be
recorded separately; a Prism desktop launch does not verify those environments.
