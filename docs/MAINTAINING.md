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

The build defaults to 0.8.5-preview; `-PmodVersion=...` overrides it. The release
workflow builds the version from its `v...` tag, runs headless tests, compiles
gametests and uploads the remapped runtime JAR plus source JAR. Tags containing
a hyphen produce GitHub prereleases. A published release does not imply that
every shaderpack has been tested.

Before pushing a release tag, test and review the intended commit. The workflow
uses the repository's standard `GITHUB_TOKEN`; it requires no custom publishing
secret. Its release job has permission to write repository contents; PR/build CI
has read-only permissions. Public Maven publishing is not configured.

Do not put Minecraft binaries or Gradle caches in Git. The wrapper JAR is the
only build tool binary committed. Preserve the test fixtures and MIT notices.
