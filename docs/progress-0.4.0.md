# Mantis 0.4.0 progress

Creator: **PunctualBoat**. Target: Minecraft Forge 1.20.1, Java 17.

This pass adds script-defined commands, startup fluid families, creative tabs and an installed-mod recipe test. It builds on the runtime, internal clock, generic recipes, startup content and server gameplay APIs delivered in 0.3.0.

| Area | Added in 0.4.0 | Remaining |
| --- | --- | --- |
| Commands | Generation-owned handlers; permission levels; integer, double, boolean and string arguments; optional values; suggestions; source/player helpers; collision rejection; removal and cleanup through reloads | Literal subcommand trees, entity/position arguments and aliases |
| Fluids | Fluid type, source/flowing variants, placeable liquid block, bucket, physical properties, textures/tint, atlas declarations and generated names/models | Visual client validation and specialized fluid behavior |
| Creative tabs | New tabs, icons, item lists, ordering hints and additions to existing tabs; references checked after registration | Visual ordering and multiplayer validation |
| Compatibility | Optional Create 6.0.8 test environment using matching Maven build 289; real serializers for seven processing types, including custom-fluid filling/emptying | Other Create versions, Draconic Evolution, Occultism and mixed-mod packs |
| Pack usability | Disabled command and Create examples, expanded startup content example and API documentation | Completion/type declarations and richer inventory/NBT helpers |

Commands install into the dispatcher prepared for the candidate resource reload. Recipe validation and command collision checks finish before scripted recipe JSON is published. A rejected reload retains the previous dispatcher; a successful reload creates fresh nodes and closes the previous callbacks. Invalid declarations never retain a callback. Callback argument conversion, JavaScript execution and return validation share the execution budget; repeated failures release the handler, and execution-limit failures release all work owned by that generation.

Fluid families use suppliers so Forge registry event order does not break their links. Startup resources are frozen after loading and require a restart to change. Creative contents are stored Java declarations, allowing client tab building without entering a script context. Both sides must share startup content. Surface tint and the bucket icon are separate properties.

The Create checks edit installed mixing and crushing recipes, preserve output chances, remove an installed compacting recipe, and add mixing, crushing, milling, pressing, compacting, filling and emptying recipes. Mantis fluids are resolved by Create's fluid input/output APIs. Create and its dependencies are optional development dependencies and are excluded from the bundled Mantis JAR.

Generic recipe editing applies to mods using RecipeManager and registered serializers. Private recipe storage needs an extension adapter. The existing non-crafting fusion/ritual fixture remains useful for nested JSON checks but does not establish compatibility with installed Draconic Evolution or Occultism. Client scripting, runtime loot modifiers, server-script pack regeneration, broader adapters, multiplayer testing and longer-running modpack checks remain release work.

Verified on Forge 47.4.0 / Java 17 in [CI run 37840506145](https://github.com/PunctualBoat203/Mantis/actions/runs/37840506145), implementation commit `0e2a2b9b49ef401d1ebdc40ab31fb333e4ca000d`:

- 90 unit tests passed, with zero failures or skipped tests.
- All three required Minecraft GameTests passed in the normal run.
- All three passed again with deliberate first-load failure and recovery.
- All three passed with Create installed, including rejection of a malformed Create recipe while retaining the previous recipes, commands, timers and futures.
- The Forge build and benchmark smoke checks passed.
- The bundled JAR contains 109 Mantis classes and eight disabled examples; test classes, benchmark classes and reference/installed-mod binaries are excluded. Only the five JavaScript runtime libraries are embedded.

The JAR and report archive checksums match the CI artifacts. Pack metadata, generated resources, declared fluid properties, world placement/collection, creative contents and command dispatch were checked in Minecraft. A visual client and multiplayer test remain pending. Benchmark smoke checks execution only; the earlier scoped benchmark reports remain the performance evidence.

Bundled JAR SHA-256: `b9d7d9b7b58389cbbe088266b7794f78097c4dd2a192b325efd3c47e0a609046`.
