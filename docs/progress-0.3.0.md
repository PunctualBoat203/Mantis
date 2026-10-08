# Mantis 0.3.0 progress

Creator: **PunctualBoat**. Target: Minecraft Forge 1.20.1, Java 17.

This pass targets roughly 70% of a practical first release: a usable scripting runtime, internal clock, generic recipe editing, convenient recipe builders, basic startup content, server gameplay events, and generated pack data. The percentage is a scope estimate. The original broad mod-support goal also requires installed-mod and modpack testing.

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

Verification is tracked by the build workflow: unit tests, the Forge mod build, generated-resource and gameplay GameTests, rejected and successful reloads, first-load recovery, and benchmark smoke checks. Benchmark smoke verifies execution only. The local unit suite currently passes 86 tests; final server verification is recorded after CI completes.
