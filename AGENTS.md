# Hytale Plugin Project

This repository is for creating **Hytale plugins** — Java plugins built against the Hytale
server. Plugin code compiles against the locally installed `HytaleServer.jar`, which is
provided by the Hytale runtime and is never bundled into the produced plugin artifact.

## Requirements

- **JDK 25.** The server API is Java 25 bytecode (class file major version 69), so the
  project must be compiled with a JDK 25 toolchain.
- **A local Hytale installation**, so the server jar can be resolved (see below).
- Use the committed Gradle wrapper (Gradle 9.3.0), not a system Gradle installation.

## Build

```powershell
.\gradlew verifyHytaleDependency      # check that the configured server jar exists
.\gradlew build                       # compile and assemble
.\gradlew test                        # run the JUnit Jupiter test suite
.\gradlew generatePluginManifest      # create run/mods/<group>.<name>/manifest.json
.\gradlew copyPluginClasses           # compile and copy bytecode into run/mods/<group>.<name>
.\gradlew syncAssets                  # mirror the pack's assets and manifest into src/main/resources
.\gradlew debugServer                 # run the server with a JDWP debug listener
.\gradlew referencePaths              # show the asset and source-code reference paths
```

`verifyHytaleDependency` fails with an actionable message when the server jar is missing or
`hytale.patchline` is invalid. It is wired into every `JavaCompile` task, so `compileJava`
and `compileTestJava` fail the same way instead of reporting a confusing
"package does not exist" error.

## Running the server

`debugServer` launches `com.hypixel.hytale.Main` from the configured install with a
JDWP listener on `localhost:5005`, then attach a remote debugger to that port.

| Property | Default | Purpose |
| --- | --- | --- |
| `hytale.debug.port` | `5005` | JDWP listen port |
| `hytale.debug.suspend` | `false` | `true` waits for the debugger before any server code runs |
| `hytale.assets` | `Assets.zip` beside the `Server/` folder | Value passed as `--assets` |
| `hytale.serverArgs` | empty | Extra server flags, e.g. `--project-prop hytale.serverArgs="--bare"` |

The server's own state (worlds, configs, logs) is written to `run/` in this project, which
is git-ignored, so the install directory is left alone. The server also maintains
`run/mods/`, the natural place to drop a built plugin for testing. Expect a
"No server tokens configured. Use /auth login to authenticate." warning on a fresh run.

Note that the jar's manifest declares `Enable-Native-Access: ALL-UNNAMED`; that attribute
only applies to a `java -jar` launch, so the task passes `--enable-native-access=ALL-UNNAMED`
explicitly.

## Plugin identity and manifest

`group`, `name` and `version` in `gradle.properties` are the single source of truth: the Gradle
project, the built artifact and the generated `manifest.json` all use them. Override them per
run with `-Pgroup=... -Pname=... -Pversion=...`.

`generatePluginManifest` — which `debugServer` depends on, so it runs on every server start —
writes `run/mods/<group>.<name>/manifest.json` using the same directory convention as the
in-game Asset Editor. The file is a **build output**: it is rewritten from the properties below
on every run, so configure the manifest there rather than editing the generated JSON.

| Property | Default | Manifest field |
| --- | --- | --- |
| `hytale.plugin.description` | empty | `Description` |
| `hytale.plugin.authors` | empty | `Authors` — `Name\|Email\|Url` entries, comma separated |
| `hytale.plugin.website` | empty | `Website` |
| `hytale.plugin.mainClass` | empty | `Main` — omitted when empty, making the folder an asset pack rather than a code plugin |
| `hytale.plugin.serverVersion` | `*` | `ServerVersion` |
| `hytale.plugin.dependencies` | empty | `Dependencies` — `Group:Name=range` entries, comma separated |
| `hytale.plugin.optionalDependencies` | empty | `OptionalDependencies` — same syntax |
| `hytale.plugin.loadBefore` | empty | `LoadBefore` — same syntax |
| `hytale.plugin.subPlugins` | empty | `SubPlugins` — `Name=MainClass` entries, comma separated |
| `hytale.plugin.disabledByDefault` | `false` | `DisabledByDefault` |
| `hytale.plugin.includesAssetPack` | `false` | `IncludesAssetPack` |

`Group`, `Name` and `Version` come from the `group`, `name` and `version` properties above.

The emitted JSON uses the game's schema and key order:

```json
{
  "Group": "org.example",
  "Name": "hytale-plugin-template",
  "Version": "1.0.0-SNAPSHOT",
  "Description": "",
  "Authors": [],
  "Website": "",
  "ServerVersion": "*",
  "Dependencies": {},
  "OptionalDependencies": {},
  "LoadBefore": {},
  "DisabledByDefault": false,
  "IncludesAssetPack": false,
  "SubPlugins": []
}
```

`Version` must be a semver, so a Maven-style project version is padded: `1.0-SNAPSHOT` becomes
`1.0.0-SNAPSHOT`. Set `version=1.0.0` if you want the artifact and the manifest to carry the
same string.

**How plugin code loads:** a directory in `mods/` is loaded by the server's `AssetModule` as an
asset pack. Java plugin *code* is either a jar placed directly in `mods/` with `manifest.json`
inside it, or a `manifest.json` found on the JVM classpath — which is the route `copyPluginClasses`
and `debugServer` use (see below). `PluginManager` skips directories when scanning `mods/`.

## Mirroring the pack's assets into the project

`syncAssets` mirrors the pack's assets and its descriptor out of `run/mods/<group>.<name>`
into `src/main/resources`: `manifest.json`, `Common/**` and `Server/**` — the same top-level
layout as the game's own `Assets.zip`. Everything else in the mod folder, notably the staged
bytecode, stays in `run/`. It is wired as a finalizer of `debugServer`, so it runs when the server
stops, and it can be run by hand at any time.

`src/main/resources` is therefore a **generated** directory: it is the jar root, holding
`manifest.json`, `Common/...` and `Server/...`, and anything else there is deleted on the next
stop — including files you hand-write. Deletions propagate too: remove an asset in-game and it
disappears from the project. The mirror is skipped entirely when the mod folder does not exist,
so it can never empty the project. If the build is cancelled rather than stopped cleanly (Ctrl+C)
the finalizer may not run; the mod files are untouched, so run `.\gradlew syncAssets`
afterwards. Because the task mirrors by deleting then copying, killing a build *while it is
running* can leave `src/main/resources` partly or wholly emptied — re-running
`.\gradlew syncAssets` restores it from the mod folder.

## Loading plugin code in the debug server

The mods scan only ever discovers regular files, and its jar loader reads `manifest.json` from
inside a zip — so a mod folder full of loose `.class` files would be ignored. Plugin code
therefore loads over the **classpath**: the server calls
`ClassLoader.getResources("manifest.json")` and registers every manifest it finds, so
`debugServer` puts the plugin's mod folder on the JVM classpath.

`copyPluginClasses` compiles the project and copies `build/classes/java/main` into the plugin's
mod folder itself, keeping the package directories (`org/example/...`), which is exactly what a
classpath root looks like; `debugServer` depends on it, so `.\gradlew debugServer` compiles,
copies and runs in one step. It uses `Copy`, not `Sync`, because the folder is shared with
`manifest.json`, `Server/...` and whatever the game writes there — nothing is ever deleted. The
trade-off: renaming a class leaves its old `.class` behind until you remove that file (or the
whole mod folder) by hand.

Two settings matter:

- `hytale.plugin.mainClass` **must** be set. Without it the manifest has no `"Main"`, the folder
  is treated as an asset pack, and the staged classes never load — the task warns when it is empty.
- `hytale.plugin.includesAssetPack` decides which copy of the pack wins. With `false` the folder
  stays authoritative. With `true` the classpath plugin registers the pack first and the server
  logs `Asset pack '<Group>:<Name>' already registered (CLASSPATH), skipping MODS` — the classpath
  copy wins and the folder pack is ignored. Both load, so this is a choice rather than a fault;
  `true` is what a packaged jar needs to load its own embedded asset pack.

## Implementation references

Before writing new code or assets, look at the reference material these two properties point at.
Read them from `gradle.properties`; `.\gradlew referencePaths` prints them and warns about a path
that no longer exists.

| Property | What is there |
| --- | --- |
| `hytale.reference.assets` | Real asset JSON **plus the JSON schema for every asset type** — use the schemas to learn an asset's fields, and the existing JSON files as shape examples |
| `hytale.reference.source` | The Hytale server **source tree** — a Maven multi-module project with ~8,100 `.java` files: read the real API instead of decompiling or `javap`-ing the jar |

Where to look inside the source tree:

- `CoreServer/src/main/java/com/hypixel/hytale/server/core/` — the API this plugin compiles
  against: `plugin/` (entry point, lifecycle, registry), `command/system/` (commands,
  `CommandBase`), `Message.java`.
- `builtin/` — the game's own plugins as full working examples; `builtin/HOWTO.md` explains how
  they are wired.
- `Examples/Plugins/ExamplePlugin/` — the official minimal plugin (one class plus its
  `src/main/resources/manifest.json`).
- Ignore `target/`, `dist/`, `run/`, `universe/`, `logs/` and `mods/` — build output and a server
  run directory, not sources.

That tree may not be the same version as the installed jar this project compiles against, so use it
to find where something lives and how it is used, and trust the compiler (or `javap` on the jar) for
whether a symbol exists at the configured `hytale.patchline`.

The asset tree uses the `Common/` + `Server/` layout that `syncAssets` mirrors into
`src/main/resources`, so anything copied from it lands at the same path in the jar.

## Local Hytale dependency

Two properties, set in `gradle.properties`:

| Property | Meaning |
| --- | --- |
| `hytale.installDir` | Folder that directly contains the `release` / `pre-release` folders |
| `hytale.patchline` | `release` or `pre-release` |

The dependency resolves to:

```
<hytale.installDir>/<hytale.patchline>/package/game/latest/Server/HytaleServer.jar
```

When `hytale.installDir` is unset it falls back to `<hytale_home>/install` and then to
`%APPDATA%/Hytale/install`. The committed value is machine-specific: override it in
`~/.gradle/gradle.properties` or on the command line instead of editing it per machine.

> In `.properties` files a backslash is an escape character (`\U`, `\b`, `\a`, …), so write
> these paths with forward slashes.

The jar is declared `compileOnly` for main plus `testCompileOnly` and `testRuntimeOnly` for
tests (the test runtime classpath does not inherit `compileOnly`). Do **not** change it to
`implementation` — the server jar must not end up inside the produced plugin jar.

## Layout

```
src/main/java        plugin sources
src/main/resources   plugin resources packaged into the jar
src/test/java        tests
src/test/resources   test resources
```

The Java source sets use these standard locations. `src/main/java/org/example` holds the starter
plugin: `TestPlugin` registers `/testplugin`, which prints "Plugin template works!" to whoever
runs it. Gradle simply skips any source directory that is absent, and your IDE creates one when
you first add a file to it.

## Hytale API

Verified against the installed `HytaleServer.jar`:

- Entry point: `com.hypixel.hytale.server.core.plugin.JavaPlugin`
  (`public abstract class JavaPlugin extends PluginBase`, constructed with `JavaPluginInit`).
- Related types: `com.hypixel.hytale.server.core.plugin` (`PluginBase`, `PluginInit`) and
  `com.hypixel.hytale.common.plugin` (`PluginManifest`, `PluginIdentifier`).

The plugin descriptor format is documented above. Its schema and key casing were verified
against the manifests the game itself writes
([run/mods/Test.TestPluggin/manifest.json](run/mods/Test.TestPluggin/manifest.json)) and the 64
built-in plugin manifests bundled in `HytaleServer.jar` (`manifests.json` at the jar root).
`PluginManifest` also carries fields the editor's fresh pack omits, notably `Main`.
