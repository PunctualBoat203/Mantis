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
| B3/B4: pending generations and activation gap | Candidates belong to their resource manager. Failure cleanup uses that load's game executor and cannot discard unrelated candidates. Adoption runs in vanilla's completion stage instead of a later queued task. Forge coverage includes back-to-back loads, nested overlapping loads and a listener failure after recipe preparation. |
| B5: script-issued reload | Resource reloads reject before changing resources when any Mantis context is executing on that thread, including `execute run reload`. Ordinary operator reloads remain available. |
| B8: liquid placement | Liquid blocks are replaceable, use liquid piston behavior and empty sound. A GameTest places a solid block into the fluid. |
| B10: extreme numeric exponents | JSON conversion checks precision/scale before integer expansion, preserving ordinary large integer fields while rejecting oversized representations. |
| C2/C6: tag replacement and command permission | Existing behavior is stated explicitly in the README. |
| Third-party notices | Release resources include UPL, GraalJS component and ICU4J license texts, plus an inventory of the five embedded libraries. |

Verified on Forge 47.4.0 / Java 17 in [CI run 37888898318](https://github.com/PunctualBoat203/Mantis/actions/runs/37888898318), implementation commit `3c9afb7ca605e0332356fb1dff2f46e07fda1dcf`:

- All 96 Java unit tests passed on Ubuntu and Windows, with zero failures or skips. The local Java 17 run passed the same 96 tests.
- All three required Forge GameTests passed in each of the normal, first-load failure/recovery and installed Create runs (nine passing executions).
- Those runs include failed startup gameplay sessions, degraded operator commands, unloaded chunk rejection, block placement into fluids, script-issued reload rejection, late reload-listener failure, back-to-back loads and nested overlapping loads.
- The bundled reobfuscated JAR booted in a separately installed Forge server, registered startup items/fluids, edited recipes, retained native console levels and completed three reloads with usable command callbacks and one timer.
- The benchmark smoke checks passed; they check execution and do not establish new performance results.
- The release JAR contains 113 Mantis classes, eight disabled examples, four notice/license resources and five embedded JavaScript runtime libraries. Test classes, benchmark classes and reference/installed-mod binaries are excluded. Artifact SHA-256 digests match GitHub's metadata.

Bundled JAR SHA-256: `9891561b8bf60d6c6512da2f6705beee16ddecfcb516040448d2a548130c2940`.

The following review concerns remain separate work: a long-running heap/metaspace soak and interface-proxy retention; large guest allocations and regex interruption; interface annotation/export diagnostics; resource-scope cleanup complexity; creative ordering and optional content references; server-only mode and client content hashes; KubeJS coexistence; more installed-mod schemas; block loot/tool/render defaults; event payload allocation profiling; completion declarations and slow-handler diagnostics; build plugin pinning and multiplayer/client checks. None is claimed resolved by the added short reload test.

Generated resource pack priority, deadline failure policy, generation-owned future cancellation and first-load configuration retain their documented behavior. No broad formatter pass, dependency replacement or scaffolding was added.
