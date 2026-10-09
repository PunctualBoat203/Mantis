# Mantis 0.4.1 review follow-up

Creator: **PunctualBoat**. Target: Minecraft Forge 1.20.1, Java 17.

This patch release addresses concrete 0.4.0 defects and adds checks for Windows and the bundled production JAR. The proposed malformed-recipe patch was accepted and its test expanded to cover numeric, boolean and object serializer types as well as missing, null and array types. Untouched malformed recipes remain for Minecraft to handle; filters no longer reject unrelated valid recipe edits.

| Review item | Result |
| --- | --- |
| A1: malformed serializer types | Type and semantic role filters skip unusable types; replacements leave those recipes unchanged. |
| A2: failed startup session | Activation skips failed/disposed startup sessions, preserving startup registration data and allowing later server generations to run. Status/errors show startup failures. |
| A3: console arguments and levels | Guest values and multiple arguments work; arrays stay single console arguments; Minecraft receives actual INFO/WARN/ERROR levels. Circular/unprintable objects have safe formatting fallbacks. |
| A4: quoted filenames | Entry URI apostrophes are escaped; relative modules with spaces, apostrophes and Unicode have a regression test. |
| A5: first-load error disappears | The empty fallback retains the original error, reports degraded status and warns operators on login. Successful recovery clears degradation. The existing configuration default remains available. |
| A6: accidental chunk generation | Block reads/writes reject unloaded chunks. `level.isLoaded` allows scripts to probe safely. |
| A7: empty filter selects all | Retained as the documented API contract. The README now explicitly calls out destructive edits and computed filters; `all` already means an array of AND filters. |
| B1: Windows paths | Virtual paths and entry URIs share a normalized absolute root. CI runs the unit suite on Windows. |
| B2: production packaging | CI installs a real Forge server, boots the bundled JAR and exercises startup items/fluids, recipes, console levels, commands and three resource reloads. |
| B3/B4: pending generations and activation gap | Candidates belong to their resource manager. Failure cleanup uses that load's game executor and cannot discard unrelated candidates. Adoption runs in vanilla's completion stage instead of a later queued task. Forge coverage includes back-to-back reloads. |
| B5: script-issued reload | Resource reloads reject before changing resources when any Mantis context is executing on that thread, including `execute run reload`. Ordinary operator reloads remain available. |
| B8: liquid placement | Liquid blocks are replaceable, use liquid piston behavior and empty sound. A GameTest places a solid block into the fluid. |
| B10: extreme numeric exponents | JSON conversion checks precision/scale before integer expansion, preserving ordinary large integer fields while rejecting oversized representations. |
| C2/C6: tag replacement and command permission | Existing behavior is stated explicitly in the README. |
| Third-party notices | Release resources include UPL, GraalJS component and ICU4J license texts, plus an inventory of the five embedded libraries. |

Local verification passed 96 Java unit tests using the real GraalJS/Gson dependencies and Java 17, with no failures or skips. Forge, Windows and production-server checks are run in CI; results must be checked before treating this as a verified release.

The following review concerns remain separate work: a long-running heap/metaspace soak and interface-proxy retention; large guest allocations and regex interruption; interface annotation/export diagnostics; resource-scope cleanup complexity; creative ordering and optional content references; server-only mode and client content hashes; KubeJS coexistence; more installed-mod schemas; block loot/tool/render defaults; event payload allocation profiling; completion declarations and slow-handler diagnostics; build plugin pinning and multiplayer/client checks. None is claimed resolved by the added short reload test.

Generated resource pack priority, deadline failure policy, generation-owned future cancellation and first-load configuration retain their documented behavior. No broad formatter pass, dependency replacement or scaffolding was added.
