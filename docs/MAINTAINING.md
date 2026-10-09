# Maintaining the standalone client repository

## Source export from the plugin checkout

The Fabric build in the original checkout uses the live shared Armature sources.
The separate repository instead includes their selected snapshot under `shared/`.
Do not copy the entire plugin repository or its generated server pack.

From the plugin checkout's `armature-client-fabric` directory:

```powershell
& ..\gradlew.bat exportStandalone -PstandaloneDir=E:/exports/ArmatureClientMod
```

The destination must be absent or empty. Export never resets an existing Git
checkout. It includes the mod, tests, docs, workflows, license, wrapper and
exact shared classes selected by `sourceSets.main`; local logs, game downloads,
build outputs, plugin runtime code and server packs are excluded.

Compare the exported tree with the standalone repository and merge deliberately.
Changes made to forks are not overwritten or automatically sent back to Armature.
Protocol/shared behavior changes should be tested on both sides before release.

## Versioning and GitHub Releases

The build defaults to 0.9.2-preview; `-PmodVersion=...` overrides it. The release
workflow builds all eight Minecraft targets from its `v...` tag, runs headless
tests and bytecode hook checks, compiles gametests, and uploads one runtime JAR
per target with SHA256SUMS.txt. Source JARs remain available from local builds
and Maven publishing but are excluded from CI artifacts and GitHub Releases;
developers can clone the repository or use GitHub's automatic source archives.
Targets through 1.21.11 use
Loom's remapping plugin; 26.x uses unobfuscated Minecraft and Java 25. Tags containing
a hyphen produce GitHub prereleases. A published release does not imply that
every shaderpack has been tested.

Before pushing a release tag, test and review the intended commit. The workflow
uses the repository's standard `GITHUB_TOKEN`; it requires no custom publishing
secret. Its release job has permission to write repository contents; PR/build CI
has read-only permissions. Public Maven publishing is not configured.

Do not put Minecraft binaries or Gradle caches in Git. The wrapper JAR is the
only build tool binary committed. Preserve the test fixtures and MIT notices.

## Version adapters

The authored source is in `src/main` and `shared`. `prepareMinecraftSources`
selects replacements from `src/versioned` and applies narrow API renames. Output
is generated under the target's build directory; never edit or commit it.

- `submit`: Minecraft 1.21.9+ submits geometry, equipment and neutral held items
  to the native render collector. Movement samples interpolate `moveDist`.
- `calendar`: 26.x applies camera animation before frustum/projection extraction
  and reads world/hand FOV separately; networking and GUI use the new API names.
  Tests initialize registry components after creating the vanilla lookup.
- `next`: 26.2 hand submission and frame update hooks and GUI ownership changes.

Resource IDs use `Identifier` and render types move to `rendertype` from 1.21.11.
All adapters use Minecraft's
rendering abstractions; no raw OpenGL calls are used. This supports the 26.2
backend API without claiming live Vulkan or Iris shaderpack validation.

To add a target, update `gradle/minecraft-versions.properties`, both workflow
matrices and the README table, then compile/test against that exact game JAR.
Run `MinecraftHooksTest` and connected-client checks before claiming runtime parity.
Export retains authored sources and all adapters, rather than a single target's
generated sources.

Armor uses `ClientArmorMesh`, which overrides `ModelPart.Cube.compile` to emit
the authored body-local faces through Minecraft's equipment consumers. Do not
replace it with a constructor placeholder and later polygon edits: Sodium
caches the constructor cuboid, so those edits leave armor at the placeholder's
size and position. Equipment still owns textures, dye, trims and glint. Headless
tests check dispatch, emitted vertices and equipment attributes; visual Sodium
and Iris checks require a connected client.

`ClientModelSessions` associates mutable animation and the last viewport sample
with the protocol session UUID, rather than its current channel or asset hash.
Clearing a channel retires the presentation without resetting playback; an
outgoing channel or a reclaimed current channel takes that exact session.
A different session using the same asset receives fresh playback and shares
reference-counted textures. Do not reset playback during channel transfers or
give two sessions the same mutable animation. Options are applied with their
following ownership frame, and stale uploads cannot replace a resolved session.
