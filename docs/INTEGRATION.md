# Integration into a server-specific Fabric mod

Choose a supported Minecraft release and use Fabric Loom with the same mapping namespace as
your host project. Fabric API must be installed. Keep the Armature server plugin
compatible with the client protocol; the current version speaks protocol 9.

## Depend on the existing mod

For development, clone this repository and publish its remapped artifacts locally:

```sh
./gradlew publishToMavenLocal -PminecraftVersion=1.21.8
```

In your own Fabric project's `build.gradle.kts`:

```kotlin
repositories { mavenLocal() }
dependencies {
    modImplementation("com.armaturemc:armature-client-fabric-1.21.8:0.9.2-preview")
}
```

Distribute both mods to players. For a single installation JAR, Fabric Loom can
nest Armature Client instead:

```kotlin
dependencies {
    modImplementation("com.armaturemc:armature-client-fabric-1.21.8:0.9.2-preview")
    include("com.armaturemc:armature-client-fabric-1.21.8:0.9.2-preview")
}
```

`mavenLocal()` is a local development setup, not a public download service. For
your CI, publish to your own Maven repository or include this source build as a
separate project. GitHub Releases provide JARs; this project does not currently
operate a public Maven repository. Preserve the nested JAR's license and metadata.

Fabric initializes the nested `armature_client` mod automatically. Do not call
`ArmatureClient.onInitializeClient()` yourself or register its payload/mixins a
second time. The mod ID stays `armature_client` when nesting the original mod.

For Minecraft 26.x, build the exact target and use ordinary `implementation`
instead of `modImplementation`, with the `net.fabricmc.fabric-loom` plugin.
These versions are unobfuscated and require Java 25; do not reuse a 1.21.x JAR.
For example the 26.2 coordinate is
`com.armaturemc:armature-client-fabric-26.2:0.9.2-preview`.

## Use your own settings screen

The existing public client controls are:

```java
import com.armaturemc.client.ArmatureClient;

boolean requested = ArmatureClient.isRenderingEnabled();
boolean active = ArmatureClient.isRendererActive();
boolean pending = ArmatureClient.isRendererSwitchPending();

// After Armature Client initialization, on the Minecraft client thread:
ArmatureClient.setRenderingEnabled(false); // throws IOException on persistence failure
```

Changing the preference coordinates the plugin's renderer and pack handoff.
`isRendererActive()` describes the selected renderer, not a server connection
or a promise that a model is currently visible. Requested and active states can
differ while `isRendererSwitchPending()` is true. Handle `IOException` in your UI;
do not write the config file to bypass the handoff while Minecraft is running.
These controls and renderer internals are preview interfaces, not a frozen API.

## Modify or merge the sources

The repository contains all shared animation/protocol sources and resources.
You can compile a fork without checking out the server plugin. For a standalone
replacement, keep the original mod ID and install only that replacement.

If merging the code directly into your own mod:

1. Include the selected shared sources and `armature/body-parts-v2.json`.
2. Carry over client initialization, mixin declarations and required resources.
   Initialize the renderer exactly once; do not install the original alongside it.
3. If renaming Java packages, update entrypoints, mixin package/class references,
   accessor targets, resources and reflective/string references consistently.
4. Preserve `armature:client`, the protocol bounds, hashes/session identities and
   message semantics for compatibility with the unmodified Armature plugin.
5. Preserve profile selection, `held-item`, deferred dynamic item snapshots,
   viewport attachment and server renderer suppression/handoff behavior.
6. Keep copyright and MIT license notices, and identify your distribution as a fork.

Gameplay or a private custom protocol can be added under your own namespace.
Changes to the Armature protocol require a matching plugin implementation; a
client-only change cannot make the existing plugin send new data.
