# Mantis 0.2.2 review

Project owner: **PunctualBoat**. Review date: October 8, 2026. Base: `fe38c4cc6a2ac4e248ad468e97e7cab475e31141` (0.2.1).

The supplied hardening patch is useful, but needs corrections before release. The supplied jar review also identifies substantial feature gaps. Mantis is a working scripting foundation with generic serializer-backed recipe edits; it is not yet a drop-in KubeJS replacement, and whole-server performance has not been established.

## Patch assessment

The original patch applied cleanly, and its 66 JUnit tests passed with the real GraalJS, Gson, and JUnit dependencies. Additional probes reproduced timeout overflow and recipe aliasing that those tests missed.

| Change | Assessment and resulting behavior |
| --- | --- |
| Configurable callback/load/statement limits | Kept. Durations that overflow nanoseconds now reject direct construction and warn/fall back during configuration parsing. Engine resources are created only after limits are accepted. |
| Longer budgets for recipe/startup/load/init events | Kept. Payload conversion and the callback share the same outer load budget, so conversion cannot accidentally use the shorter callback budget first. |
| Consecutive event/timer failure budgets | Kept. Success resets the count; default threshold is ten. Strict events still reject preparation. Script timers use the clock's actual threshold, report through their owning session, and release their resource tickets when cancelled. |
| Copy-on-write recipes | Corrected. The ordinary Java constructor still snapshots input, and full `commit` returns detached JSON. Minecraft explicitly borrows its exclusively owned preparation input and commits a validated delta, avoiding copies of untouched recipe JSON. |
| Exact-ID recipe lookup and namespace filtering | Kept. Exact-ID matching also checks any supplied type/namespace constraints. |
| First-load fallback | Kept. It publishes no scripted recipe changes. An empty fallback skips extension registration, so it does not repeat the extension failure that caused preparation to fail. Subsequent failed reloads retain the previous generation. |
| Skip unused server-tick payloads | Kept. Payloads are constructed only when startup or server scripts listen. |
| Execution-limit cleanup | Added. A cancelled running context marks its session failed and immediately releases listeners, timers, pending futures, and bindings. Error history remains available until reload. |
| Clock shutdown | Added. Timer cleanup runs once, continues after an individual cleanup failure, and cannot schedule fresh timers onto a closing clock. |
| Runtime identification | Added. Startup logs the actual Truffle runtime name. This environment's stock OpenJDK 17 reports `Interpreted`. |

The lenient policy applies to server-script preparation when no generation is active. Startup-script failures still fail mod initialization. Extension side effects outside owned resources and recipe transactions cannot be rolled back automatically. A deadline still closes the entire affected generation; this release does not isolate each script into a separate context. Wall-clock limits include GC pauses and host stalls.

## Corrections to the supplied jar review

* **The supplied Rhino fork is interpreter-only.** `dev.latvian.mods.rhino.Context.createCompiler()` selects `Interpreter`; the jar has no optimizer/bytecode-compilation backend. Upstream Rhino compiled-mode measurements are not a KubeJS baseline.
* **Mantis already has an extension API.** `MantisApi.registerExtension` supplies a `ScriptSession.Registrar` with modules, bindings, converters, events, async work, and owned resources. The Forge test extension exercises this path. Declarative recipe-schema registration, plugin-file discovery, and KubeJS's wider plugin lifecycle are missing.
* **Two interpreters can perform differently.** The shared execution model does not prove that one cannot outperform the other. Compare actual implementations and equivalent workloads. Host crossing overhead and recipe architecture also matter.
* **Existing Minecraft wrappers do not depend on reflective Mojmap lookup.** Their method bodies contain compiled Minecraft calls that ForgeGradle reobfuscates. A future allowlisted layer reflecting directly over Minecraft members would need generated bindings or runtime name remapping.
* **Event counts are not a compatibility percentage.** Mantis dispatches seven named server/player/recipe events, plus startup and lifecycle hooks. KubeJS exposes substantially broader groups. Count supported behavior and ported scripts rather than inferring script compatibility from class or handler counts.
* **Clientless and hybrid-server support remain unverified.** Missing `displayTest` metadata does not substitute for a connection test, and required mixins do not prove that every hybrid server fails. Neither path is certified by these tests.

## What the supplied addons require

This comparison inspects the supplied plugin classes, schema registrations, components, and metadata. It does not load the corresponding base mods. Addon jars are reference material, not Mantis plugins; their KubeJS interfaces and dependencies are not supported unchanged.

| Supplied addon | Observed API contribution | Mantis status |
| --- | --- | --- |
| Create 2001.3.0-build.8 | Processing schemas, mechanical crafting, nested sequenced assembly/loops, fluid conversion, special item types and Create events. Metadata requires Create 6 or newer. | Raw serializer JSON can be edited through the generic path. Typed processing/assembly builders, custom items, fluids, and Create events are absent. |
| Mekanism 2001.1.5.1-build.2 | 27 registered schemas, chemical ingredients/stacks, gas/infuse-type/pigment/slurry builders. Metadata requires Mekanism 10.4.5 or newer. | Generic JSON foundation exists; chemical components, typed builders, and registry creation are absent. |
| Botania 1.4.0 | Ten schemas covering mana infusion, runic altar, petal apothecary, terra plate, pure daisy, elven trade, brews and orechid variants; brew registry and rune item builders. | Generic JSON foundation exists; typed helpers, brew registration and custom item builders are absent. |
| Ender IO 0.6.1 | Fire crafting, grinding balls and machine schemas; counted ingredients, conduits, generated assets/language and runtime alloy-smelting injection. | Generic JSON preparation does not automatically intercept recipes injected later at runtime. A dedicated adapter is needed for that path and for conduits/assets. |
| Mystical Agriculture 0.1.0 | Six schemas; crop/mob-soul registry events, custom crop/tier/type/ingredient wrappers and weighted entities. | Generic JSON foundation exists; crop/mob-soul registration and custom typed components are absent. |
| Ars Nouveau 1.2.2 | Enchanting apparatus, enchantment, crush, imbuement, glyph and caster-tome schemas. | Generic JSON foundation exists; typed helpers are absent. |
| Create Addition 1.0 | Charging with energy/charge rate, rolling, and liquid burning with fluid/burn time. | Generic JSON foundation exists; typed energy/fluid helpers are absent. |
| Blood Magic 1.0.2 | Altar, alchemy table, array, ARC and soul-forge schemas. | Generic JSON foundation exists; typed helpers are absent. |

“Generic JSON foundation” means Mantis can add, replace, remove, inspect and patch JSON before installed RecipeManager serializers parse it. It is not an installed-mod compatibility result. Recipe storage outside RecipeManager, late runtime injection, custom registries and event-driven mechanics need explicit integrations. Actual Draconic Evolution and Occultism remain untested.

The supplied KubeJS jar contains 831 classes and 65 mixin classes; Rhino contains 446 classes and a 781,922-byte `mm.jsmappings` resource. Class counts describe implementation size, not quality or completed functionality. Their compressed jar sizes total 3,457,036 bytes, excluding Architectury, addons, and Minecraft. Mantis bundles a different engine/dependency stack; comparing these files alone does not measure retained heap or startup memory.

The [reference inventory](reference-jars.json) records filenames, exact byte sizes, class counts and SHA-256 hashes for all ten supplied jars.

## Verification and measurements

The current local suite passes 77 tests with real dependencies, including matching fixture results against the supplied Minecraft Rhino fork. This includes configuration-file creation/parsing, recipe snapshot/delta isolation, validator failure atomicity, load-budget conversion, execution-limit resource cleanup, custom timer thresholds, and strict-event recovery.

Forge CI runs the full Gradle tests/build, normal GameTests, a separate first-load failure/recovery GameTest run, and benchmark execution smoke checks. The Minecraft fixture uses a real registered non-crafting serializer, not an installed addon mod. The first-load run deliberately breaks a script, checks unchanged recipes and released resources, removes the bad script, and recovers through reload. Both runs also exercise broken extension registration and strict rejection.

The [actual fork comparison](../mantis-benchmarks/results/minecraft-rhino.md) finds Rhino ahead on small calls and loading, with Mantis ahead on the measured loop and JavaScript object-editing fixtures. The [large recipe strategy comparison](../mantis-benchmarks/results/recipe-transactions.md) finds substantial savings for untouched/sparsely edited JSON: patching 100 of 20,000 recipes measures about 49.3 ms/70.4 MB allocated with snapshots versus 4.9 ms/2.0 MB with a borrowed delta. These are different scoped workloads, not a complete KubeJS/Mantis pack comparison.

Raw JMH JSON preserves JVM metadata, forks, uncertainty estimates, allocation measurements and iteration data. These host workloads cannot establish Minecraft tick latency, retained heap, whole-pack reload speed, or results across JVM vendors.

## Next priorities

1. Build an equivalent-script pack harness using actual base mods. Measure cold startup, reload, 10–20k recipe operations, tick overhead, latency and retained heap on at least two JVM distributions.
2. Add ingredient/item/fluid/tag components and declarative recipe schemas above the existing generic transaction API. Extend the existing Java extension API for custom components and schema contributions. Cover input/output filters and native batch replacement before optimizing individual JS calls further.
3. Add startup registry builders at the appropriate registry phase, then broader player/entity/block/item/level events and helpers, tags and loot. Keep the explicit export/allowlist model; generate bindings when handwritten wrappers cease to scale.
4. Port addon schemas as original Mantis declarations and test them against installed versions. Handle non-RecipeManager storage and runtime injection through adapters. Track executable compatibility cases, not speculative percentages.
5. Test server-only client connections and hybrid-server behavior before advertising support. Client scripting, broad legacy globals and unchanged KubeJS scripts require a separate migration scope.

No supplied third-party implementations or addon binaries are copied into Mantis. The optional benchmark adapter calls the supplied Rhino jar's public API and is confined to the development module.
