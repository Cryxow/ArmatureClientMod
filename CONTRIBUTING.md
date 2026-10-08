# Contributing

Bug reports, fixes and server-specific integrations are welcome. Changes to the
shared source snapshot should preserve its platform-free design; do not add
Bukkit/Paper, BetterModel or plugin implementation dependencies to the client.

## Development

Use JDK 25 and the checked-in wrapper. Run:

```sh
./gradlew test build compileGametestJava
```

Tests cover animation clocks, sparse bone resets, transitions, legacy transforms,
scale, armor, held items, surface separation, cache and renderer handoff. Passing
them does not establish in-game visual compatibility.

For renderer changes, test a connected Minecraft 1.21.8 client against a compatible
Armature server. Check first equip and cached re-equip, rapid swaps, client
rendering toggles, armor, legacy and modern rigs, held-item policy and camera
motion. Check the shaderpack you claim to support. Keep shaderpack results separate
from vanilla results. The optional client gametests require an explicit test
server and may launch a Minecraft window; CI only compiles them.

```sh
./gradlew runClientGametest -ParmatureTestServer=127.0.0.1:25565
```

## Issues and pull requests

Include Minecraft, mod and server plugin versions, Fabric Loader/API versions,
whether Iris/Sodium and a shaderpack are present, steps to reproduce and the
relevant logs. Remove tokens, private server addresses and other private data
before posting logs. Attach a minimal model only if you have permission to share it.

Explain the problem, the resulting behavior and what you tested in a pull request.
Protocol changes need matching plugin changes, bounded decoding and a protocol
version change; do not silently reinterpret protocol 9 for existing clients.

By contributing, you agree that your contribution is licensed under this
repository's MIT license. No separate contributor agreement is required.
