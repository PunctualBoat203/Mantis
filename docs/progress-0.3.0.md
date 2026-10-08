# Mantis 0.3.0 progress

Creator: **PunctualBoat**. Target: Minecraft Forge 1.20.1, Java 17.

This pass brings Mantis to roughly 70% of a practical first release: a usable scripting runtime, internal clock, generic recipe editing, convenient recipe builders, basic startup content, server gameplay events, and generated pack data. The percentage is a scope estimate. The original broad mod-support goal also requires installed-mod and modpack testing.

| Area | Delivered in 0.3.0 | Remaining |
| --- | --- | --- |
| Runtime | ES modules, snapshots, lifecycle, owned-resource cleanup, reload recovery, execution limits, async scheduling, diagnostics | Dependency-aware per-file reloads and broader pack stress testing |
| Internal clock | World-saved tick counter and named cooldowns; generation-owned timers; no scoreboards | Larger save/restart and long-running pack tests |
| Java integration | Cached annotated bindings, typed conversion, nested bound objects, callbacks, Promises, extension modules | More mod-author integration fixtures and migration tooling |
| Recipes | Generic JSON/array paths, atomic edits, changed-only commits, installed-serializer validation; vanilla builders; schemas/components; tag-aware input/output filters and replacements; native JSON conversion | Serializer-specific adapters and builders for real mods, custom matching components, broader performance tests |
| Startup content | Basic items, durability/food, cube blocks, block items, vanilla-texture models, blockstates, English names | Fluid creation, creative tabs, custom block/item behavior, visual client testing |
| Gameplay | Filtered player/item/block/entity/level events; cancellation and damage changes; player/entity/world helpers and persistent data copied across player cloning | Client events, script-defined commands, richer inventory/NBT/interaction APIs |
| Pack data | Startup-generated tags, loot tables, arbitrary server JSON and client JSON assets, frozen resource packs | Server-script pack regeneration, runtime loot/drop modifiers, specialized data builders |
| Pack usability | Generated disabled examples, operator diagnostics, API/reference documentation | Completion/type declarations and more complete recipes/pack examples |
| Compatibility | Forge GameTest serializer fixture for non-crafting fusion/ritual-style JSON | Actual installed-mod matrix, mixed-mod packs, multiplayer/client validation |

The generic recipe path applies to mods that use RecipeManager and registered serializers. A schema adds ergonomic fields and identifies input/output locations; an installed serializer still decides whether the resulting JSON is valid. Mods with private recipe storage need an extension adapter. No Draconic Evolution, Occultism, Create, or other installed-mod compatibility claim is made by the fixture.

Startup declarations run before Forge registry events and freeze after loading. Both sides must share content declarations. Startup errors stop mod loading. Server script failures keep the previous generation; the first server load can fall back to an empty generation according to configuration. Startup resources require a restart to change.

Verified on Forge 47.4.0 / Java 17 in [CI run 37766955492](https://github.com/PunctualBoat203/Mantis/actions/runs/37766955492), code commit `eaa061c05596ea4d857874e0aed321284ec6a25a`:

- 86 unit tests passed, with zero failures or skipped tests.
- Both required Minecraft GameTests passed in the normal run.
- Both required Minecraft GameTests passed again with deliberate first-load failure and recovery.
- The Forge mod build and benchmark smoke checks passed.
- The bundled JAR contains 99 Mantis classes and six disabled examples, with no GameTest classes or supplied reference-mod binaries.

The GameTests exercise generated-resource validation/metadata, startup content and tags/loot, vanilla/custom recipe builders, semantic replacements, gameplay bindings/cancellation, player data cloning, async ownership, rejected and successful reloads, and persisted clock data. Client JSON resources were inspected through PackResources; a visual client and multiplayer test remain pending. Benchmark smoke verifies execution only.

Bundled JAR SHA-256: `36e910f8e0672be68fbb0a0ea3c422d8a45dfd1f47075b04e1eeca97c20b4ce2`.
