# Notices

Armature Client Mod and the included shared Armature sources are distributed
under the MIT license, copyright (c) 2026 Cryxow. Preserve `LICENSE` in source and
binary redistributions containing this code.

The shared source snapshot originates in Armature's `armature-client-protocol`,
`armature-core`, `armature-renderer-api` and `armature-renderer-native` modules.
Only the platform-free classes selected by this build and `body-parts-v2.json`
are included. The Paper/Folia plugin and BetterModel backend are not bundled.

Minecraft, its mappings, Fabric Loader, Fabric API, Mod Menu, Gson, JOML and
SLF4J are development/runtime dependencies, not vendored third-party sources in
this repository. Their own licenses and distribution terms continue to apply.
The Gradle wrapper is provided by Gradle under Apache License 2.0, included in
`gradle/wrapper/LICENSE`:
https://github.com/gradle/gradle/blob/v9.4.1/LICENSE

Test fixtures are included for renderer regression tests and do not enter the
runtime JAR. Server model bundles and server resource packs are not distributed
with this mod; their licensing is independent of the renderer's MIT license.
