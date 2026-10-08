# Legacy client regression fixtures

These JSON files contain the native compiler's complete client bundle and frozen
server matrix witnesses. They were generated on 2026-10-08 without editing either
source model.

| Fixture | Source | Source SHA-256 | Server poses |
| --- | --- | --- | --- |
| `arm_generic.json` | User's Folia 1.21.8 `plugins/Armature/models/default/arm_generic.bbmodel` | `dc5c6528359c1639acbbd69a72acd27cb6949485bf3e0887fec86c57308268df` | 198 |
| `arm_rifle.json` | `armature-plugin/src/main/resources/models/default/arm_rifle.bbmodel` | `f27d898fb0c94af0da5e005ae18ab0d6eea4b5e1c2dac6676e31ed666ed72e3b` | 78 |

The exporter runs `NativeModelCatalogCompiler` and samples `NativeModelRuntime`
at zero, one quarter and three quarters of every authored/generated clip length,
with zero profile offset and no visibility overrides. Each witness records all
bone matrices and visibility. `LegacyClientModelTest` compares those witnesses
with the client sampler; it also checks that the legacy skin roles are exported.

The rifle marker uses a 2×2 PNG with a 16×16 logical UV grid. The complete textures
remain embedded to exercise the real bundle parser. These fixtures establish
parsing and transform parity, not in-game framing or shaderpack validation.
