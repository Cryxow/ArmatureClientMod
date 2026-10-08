# Armature Client Mod

An optional, open-source **Fabric client mod for Minecraft 1.21.8** that renders
Armature first-person models and animations at client frame rate. Supports strict
`.armature` v2 and legacy `.bbmodel` models, skin and armor, dynamic held items,
viewport attachment, camera animation, procedural motion and bone physics.

The Armature server plugin still selects profiles, actions, visibility and
held-item policies. This mod handles presentation; it does not implement weapon
gameplay. Installing it on a server without Armature does not enable models.

**Preview software:** rendering compatibility, including Iris shaderpacks, still
needs in-game verification. Minecraft versions other than 1.21.8 are unsupported.

## Install

1. Install Fabric Loader **0.19.5 or later** for Minecraft **1.21.8**, using Java **21+**.
2. Install [Fabric API](https://modrinth.com/mod/fabric-api), targeting 1.21.8.
3. Download the mod JAR from [Releases](https://github.com/Cryxow/ArmatureClientMod/releases)
   and put it in the instance's `mods` directory. Do not install the `-sources.jar`.
4. Optionally install [Mod Menu](https://modrinth.com/mod/modmenu) **15.x**.
5. Connect to a server using the compatible Armature plugin with client rendering
   enabled and the native renderer selected. The current client speaks protocol **9**.

With Mod Menu: **Mods → Armature Client → Configure** toggles client rendering.
Without Mod Menu, set `renderingEnabled` in `config/armature-client.json` before
starting Minecraft. The preference defaults to `true`.

The plugin takes over rendering when client rendering is disabled. The renderer
switch coordinates the server fallback pack; it can wait for a resource reload.
Native model bundles are supplied separately by the plugin. A cold model load
can take time; equip waits for upload so its intro is not skipped. Custom items,
sounds and other common assets may still require the server's common pack.

## Build from source

This repository is self-contained: no plugin checkout, Paper server, private
Maven repository or server files are needed to compile it. Install **JDK 25**;
the compiled mod targets **Java 21**. The Gradle wrapper downloads Gradle 9.4.1.
Loom downloads the Minecraft development dependencies during the build; those
downloads are not included in this source repository or distributed mod.

```sh
./gradlew test build compileGametestJava
```

Windows PowerShell:

```powershell
& .\gradlew.bat test build compileGametestJava
```

Installable and source JARs are in `build/libs/`. `compileGametestJava` compiles
the optional interactive tests without launching Minecraft or a server.
See [Contributing](CONTRIBUTING.md) for runtime verification.

## Reuse in another client mod

You can fork, modify and redistribute the source, including as part of a
server-specific client mod, under the [MIT license](LICENSE). Preserve the
copyright and license notice. No source disclosure is required by this license.

Prefer using this mod as a dependency or bundling it as a nested Fabric mod when
you need its current renderer unchanged. See [Integration](docs/INTEGRATION.md)
for Gradle examples, settings access and the rules for a source-based fork.

Internal renderer classes and the preview protocol may change. Server-specific
gameplay stays in your own mod/plugin; retain the Armature profile and held-item
contract when reusing its renderer.

## Layout

- `src/main`: Fabric entrypoint, renderer, networking, mixins and configuration UI.
- `src/test`: headless unit and regression tests; fixtures are test data, not mod assets.
- `src/gametest`: optional client tests for a running compatible server.
- `shared`: the exact platform-free Armature protocol, animation, pose and motion
  sources used by this version, with protocol codec tests. They are included here
  so forks compile independently.
- `.github/workflows`: build/test CI and tagged GitHub Releases with JARs.

The original plugin checkout exports this tree with `exportStandalone`. Export
does not synchronize independent forks automatically. See [Maintaining](docs/MAINTAINING.md).

## License and asset boundary

Code is [MIT licensed](LICENSE), copyright 2026 Cryxow. See [NOTICE](NOTICE.md).
Minecraft, Fabric API and optional Mod Menu remain external dependencies under
their own licenses. Models and textures received from a server retain their
owners' rights; this mod's license does not grant rights to those server assets.
