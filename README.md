# Mantis

Minecraft 1.20.1 scripting by **PunctualBoat**. Java 17, Forge 47.4.0 or newer in the 47.x line.

Mantis owns script loading, modules, events, reload cleanup, Java bindings, type conversion, async scheduling, an internal clock, and recipe edits. It uses bundled GraalJS 23.0.12 for JavaScript execution. The mod has no Rhino, KubeJS, or HeroClock dependency. Comparative benchmarks live in a separate development module; performance depends on the workload and JVM.

Build with `./gradlew test :mantis-minecraft:build`. The bundled mod is `mantis-minecraft/build/libs/mantis-0.2.2.jar`. Launch development Minecraft with `:mantis-minecraft:runClient` or `:mantis-minecraft:runServer`.

On first launch Mantis creates:

```
config/mantis/
  mantis.properties
  startup_scripts/
  server_scripts/
  examples/
    startup_scripts/
    server_scripts/
```

Startup scripts run once at `FMLLoadCompleteEvent` and require a game restart to change. They currently provide initialization hooks; registering new items, blocks, or fluids is not implemented. Server scripts run once per datapack load. `.js` and `.mjs` files load in filename order as ES modules; relative imports work within their script directory. Examples end in `.js.example` and never run automatically. Copy an example into its matching script directory and remove `.example` to enable it. Existing files are preserved.

## Scripts

```js
import { events } from 'mantis:events';
import { clock } from 'mantis:clock';
import { console } from 'mantis:console';

clock.every(1200, () => console.log('Ticks: ' + clock.ticks()));

events.on('player.logged_in', event => {
  if (clock.cooldown('welcome:' + event.player.uuid(), 1200)) {
    event.player.tell('Welcome back!');
  }
});
```

The clock advances once at the end of each server tick across all dimensions. It is unaffected by `/time`, sleeping, daylight rules, or scoreboards. Lag stretches tick time; offline time does not count. Its counter and named cooldown deadlines save with the world's overworld data. Callback timers belong to a script generation and are cancelled on reload or shutdown; JavaScript callbacks are not saved across restarts. A zero-delay timer runs on the next tick. Repeating timers reschedule from execution time without catch-up bursts. Pending timers begin when the prepared scripts activate. Read clock state inside running server events or callbacks, after the server has started.

```js
import { events } from 'mantis:events';
import { recipes } from 'minecraft:recipes';

events.on('recipes', () => {
  recipes.custom('mantis:sticks', {
    type: 'minecraft:crafting_shapeless',
    ingredients: [{ tag: 'minecraft:planks' }],
    result: { item: 'minecraft:stick', count: 2 }
  });
});
```

## Recipe API

Edits run inside the `recipes` event, before registered serializers load JSON. Filters combine exact `id`, serializer `type`, and recipe namespace `mod`. An empty filter matches every recipe; unknown filter keys produce an error.

| Operation | Behavior |
| --- | --- |
| `recipes.custom(id, json)` | Add or overwrite a recipe by ID |
| `recipes.replace(id, json)` | Overwrite an existing recipe; fail if missing |
| `recipes.remove(filter)` | Remove matches and return the count |
| `recipes.get(id)` | Read a detached JSON copy |
| `recipes.ids(filter)` | List matching IDs in order |
| `recipes.set(filter, '/path/to/field', value)` | Set an exact JSON field, including array indices |
| `recipes.patch(filter, (json, id) => json)` | Transform complete JSON copies and return the count |

The same API handles crafting, cooking, smithing, machine, fusion, and ritual JSON **when that mod uses Minecraft's RecipeManager and registered recipe serializers**. Its own serializer determines the accepted schema. Forge conditions and all unedited fields are preserved. A ritual activation item, fluid amount, energy cost, catalyst, or output can be changed through its JSON path without assuming it is a crafting-table recipe. Use `get` to inspect the installed recipe's actual structure.

Mods that keep recipes in separate private storage require an integration through the extension API. Generic JSON support does not establish compatibility with every mod/version. Actual Draconic Evolution and Occultism installations have not yet been tested.

Script edits are staged copy-on-write: only recipes that an edit touches are copied, and unchanged recipes are shared with the loaded data. Changed recipes are validated by their installed serializers before the original JSON map changes. Script errors reject the new generation. A successful `/reload` or `/mantis reload` replaces the server scripts and cleans up previous listeners and timers. On failure, the previous generation stays active. The one exception is the very first load, when there is no previous generation to keep: by default (`lenient_first_load=true`) Mantis logs the error, applies no scripted recipe changes, and lets the server finish starting instead of aborting the datapack load. Fix the script and run `/mantis reload`. Set `lenient_first_load=false` to make a first-load failure fatal. External side effects performed by an extension during script evaluation cannot be rolled back automatically.

## Modules and events

| Module | Exports |
| --- | --- |
| `mantis:events` | `events.on(name, callback)`, `events.once(name, callback)`; returned handles have `unsubscribe()` |
| `mantis:console` | `console.log`, `warn`, `error` |
| `mantis:lifecycle` | `lifecycle.on(hook, callback)`, `lifecycle.state()` |
| `mantis:rhino` | Explicit migration helpers: `Java.type`, `Java.to`, `Java.from` |
| `mantis:clock` | `clock.ticks`, `after`, `every`, `cooldown`, `remaining`; timer handles have `cancel()` and `active()` |
| `minecraft:recipes` | `recipes` |
| `minecraft:mods` | `mods.isLoaded(modId)` |
| `minecraft:server` | `server.broadcast(message)`, `server.runCommand(command)` |

Server events are `recipes`, `server.started`, `server.reloaded`, `server.tick`, `server.stopping`, `player.logged_in`, and `player.logged_out`. Tick/lifecycle payloads carry `ticks` where available. Player payloads carry `player.uuid()`, `player.name()`, and `player.tell(message)`. Startup scripts receive `startup` and server lifecycle/player events; clock, recipes, and server-command modules are server-script APIs.

Operator commands: `/mantis clock`, `/mantis status`, `/mantis reload`, `/mantis scripts`, `/mantis modules`, `/mantis profile`, `/mantis errors`. Status includes lifecycle state, scripts, listeners, timers, pending async operations, and accumulated script time. Profile shows operation totals, maximum duration, failures, and source-cache counts. Timings are inclusive: a host bridge operation can be part of a function or module timing. Each generation retains the last 32 error descriptions; internal Java traces use debug logging.

Lifecycle hooks are `load`, `init`, `start`, `reload`, `stop`, `unload`, and `error`. A generation moves through created, loading, loaded, running, stopping, and disposed; failed initialization or exhausted execution limits also record a failed state. Load/init run during preparation. Start runs after activation; reload follows start for a replacement generation. Stop/unload run before owned resources close. An error in a stop hook is reported and cleanup continues. Await pending host work inside functions or event handlers; pending top-level await rejects preparation.

## Java integration

`mantis-core`, `mantis-interop`, `mantis-runtime`, `mantis-recipes`, and `mantis-rhino-compat` have no Minecraft or loader dependency. Java applications can create a `MantisEngine`, supply source snapshots and host modules, then evaluate modules and invoke exported functions.

Mods register extensions with `MantisApi.registerExtension(modId, registrar -> ...)` during construction or common setup. Use `registrar.module("modid:api", Map.of("api", object))` to provide a virtual module. Annotate callable host methods/fields/constructors with `@MantisExport`; use `@MantisProperty("name")` on a zero-argument getter and optional one-argument void setter. Session modules bind these members explicitly through cached MethodHandles. `registrar.bindings().type(MyClass.class)` exposes its annotated static members and constructors. Unannotated members stay hidden.

Overloads prefer exact strings/booleans and lossless integers (`int`, then `long`, `short`, `byte`), followed by fractional `double`, `float`, structured conversions, and the `Object` fallback. Fixed arity precedes varargs. Equally suitable unrelated overloads fail with an ambiguity error. Generic callback parameters support the standard functional interfaces and interfaces annotated with `@MantisExport`; callbacks must run on the host scheduler thread. Use `registrar.bindings().function(Function.class, javaFunction)` to export a Java lambda as a callable JS function.

`registrar.conversions()` registers custom bidirectional converters before loading. Arrays and collections become native JS arrays; string-keyed maps and public records become native JS objects; enums become names; Optional becomes value/null. BigInteger and longs outside the JS safe-integer range become BigInt. Reverse conversion supports generic List/Set/Map/Optional, arrays, records, enums, functional interfaces, and exact numbers. Cycles, excessive nesting, and lossy numeric conversion fail. Minecraft adds ResourceLocation/string and Component/text+JSON converters. Host arrays and record data are copied during ordinary conversion.

```java
MantisApi.registerExtension("example", registrar -> {
    registrar.conversions().register(MyId.class, MyId::toString,
        value -> MyId.parse(value.asString()));
    registrar.module("example:api", Map.of("api", new MyApi()));
});
```

An exported Java method returning `CompletionStage<T>` or `CompletableFuture<T>` becomes a native Promise. Scripts can `await api.loadData()`. `registrar.async().future(promise, Target.class)` converts a Promise back to a CompletableFuture. Java work completes on the host's chosen worker/executor; script continuations are delivered through the generation's scheduler. Compose Promise-derived futures asynchronously; blocking on them inside a host call prevents delivery. Minecraft activates the server scheduler after a successful reload and also drains bounded work each tick. Prepared generations do not deliver pending completions. Closing a generation cancels its pending futures and discards queued callbacks. Use `promise(stage, false)` when the stage is shared and must outlive the generation.

Standalone hosts start a prepared session with `session.start(ScriptScheduler.of(executor, onOwnerThread), false)`. The executor must serialize callbacks on its declared owner. Alternatively use `ScriptScheduler.pumped()` and call `session.async().drain()` on the owner thread. The queue drains up to 256 deliveries or 10 ms per pass. Hosts should pump regularly even when using an executor. `registrar.resources()` owns other generation resources; `registrar.events()` exposes dispatch; `registrar.context()` supplies the initialized context. Extensions may use the standalone recipe transaction classes to integrate storage outside RecipeManager.

## Migration and benchmarks

Import `Java` from `mantis:rhino` to migrate `Java.type`, `Java.to(array, 'int[]')`, and `Java.from(array)` usage. Mods register aliases with `MantisApi.registerRhinoType(alias, MyClass.class)` before initialization; only that class's annotated members are visible. Standalone hosts use `RhinoCompatibility.register(registrar, aliases)`. This does not enable arbitrary packages, class lookup, E4X, legacy globals, or KubeJS APIs. Scripts still use ES modules and Mantis events. The compatibility module contains no Rhino engine dependency.

`./gradlew :mantis-benchmarks:benchmark` runs JMH against upstream Rhino 1.9.1 in interpreted and compiled modes. `-PrhinoVersion=1.7.15.1` selects the older baseline. Results go to `mantis-benchmarks/build/results/benchmarks.json`. Use `-PbenchmarkArgs='functionCall -prof gc'` to select a case and measure allocations. The benchmark workflow runs full measurements on demand; normal builds run short smoke checks that verify cases execute and are not performance evidence.

Cases cover creation/close, fresh parsing/evaluation, cached evaluation, function calls, loops, instance/static methods, fields/properties, constructors, overloads, array/map conversion, event dispatch, 256-recipe JSON edits, and loading 100 scripts. Future/Promise round trips and owned-resource reloads are Mantis-only cases because upstream Rhino does not supply the same framework. The comparative cases assert matching results. Mantis includes its limits, diagnostics, and conversion overhead; Rhino uses its normal upstream embedding API. These are upstream baselines, not measurements of a particular Minecraft Rhino fork or a complete KubeJS pack.

The [measured Java 17 baseline](mantis-benchmarks/results/README.md) compares upstream Rhino; its compiled mode does not describe the interpreter-only Rhino fork supplied with KubeJS 1.20.1. A [paired 0.2.1 comparison](mantis-benchmarks/results/hotcalls.md) records about 4× faster Java → JS calls and 2.4× faster JS → Java calls than 0.2.0 on the measured host, with lower allocation. These focused results do not establish whole-server performance. Mantis logs the actual Truffle runtime name at startup; stock OpenJDK 17 reports `Interpreted`.

The [0.2.2 comparison against the supplied Minecraft Rhino fork](mantis-benchmarks/results/minecraft-rhino.md) finds Rhino ahead on small calls and loading, with Mantis ahead on the measured loop and JavaScript object-editing fixtures. It includes raw results, allocation measurements and differences between the embedding APIs. Use `-PrhinoModJar=/path/to/rhino-forge.jar -PbenchmarkArgs='EngineBenchmarks.functionCall -p backend=mantis,rhino-minecraft -prof gc'` to include that optional reference backend. Neither engine's host results establish complete KubeJS-pack performance.

The [10,000/20,000-recipe transaction measurements](mantis-benchmarks/results/recipe-transactions.md) compare isolated snapshots with the borrowed delta used by Minecraft. Sparse edits and unchanged recipes avoid most JSON copying; edits touching every recipe still allocate private JSON copies. This comparison covers Java staging/commit work, not script conversion or installed serializer time.

The [0.2.2 review](docs/review-0.2.2.md) records patch corrections and feature gaps found by inspecting the supplied KubeJS/addon jars. Typed recipe schemas, startup registries, wider Minecraft events, loot/tags and installed-mod tests remain priorities. Mantis extensions already contribute modules, bindings, converters and owned resources; the supplied KubeJS plugins do not run unchanged.

Java class lookup, unexported methods, native access, processes, threads, environment access, arbitrary files, and sockets are unavailable to scripts. Module reads use an in-memory source snapshot. Calls have statement and wall-time limits; exceeding a limit closes the affected context. This is a permissions foundation for pack-authored scripts, not a hardened untrusted-code sandbox. Host methods and extension APIs must enforce their own permissions and cancellation. The shared source cache is bounded by entry count and bytes and keys source content. Its hit/miss/eviction counts cover explicit evaluations, generated entry stubs, and bridge helpers; main module bodies load through the in-memory filesystem and are not counted by that cache. Module instances are confined to a generation; full reloads rebuild dependencies from the new source snapshot. Per-file dependency reloads, client scripting, a custom JavaScript engine, and installed-mod compatibility testing remain future work.

Each outer operation resets the statement budget (default 1,000,000). Callbacks have a 250 ms deadline by default. Initialization, module/evaluation loading, and handlers of load-phase events (`startup`, `recipes`, and the `load`/`init` lifecycle hooks) get the longer load budget, 10 seconds by default, including payload conversion. All three values are set in `config/mantis/mantis.properties` (`callback_timeout_ms`, `load_timeout_ms`, `statement_limit`). Restart after changing settings. Nested bridge and script calls share their outer budget. Exceeding a deadline or statement budget closes the whole script context for that generation, records a failed state, and cancels its listeners, timers, and pending futures; `/mantis reload` starts a fresh one. Wall-clock deadlines also include GC pauses and server stalls.

An exception in an event handler or repeating timer is logged and the handler stays registered; one that fails ten times in a row is disabled so it cannot flood the log, and a success resets its count. `server.tick` payloads are only built when a script listens for that event. A shared watchdog checks deadlines every 5 ms, and completion also checks elapsed time. Host work must cooperate with cancellation; Java code blocked in a host method cannot be forcibly stopped by a script deadline.

`./gradlew :mantis-minecraft:runGameTestServer` runs an isolated Minecraft test of recipe edits, rejected reloads, timer/future cleanup, server-thread async continuations, Minecraft type conversion, lifecycle hooks, and saved clock data. A registered custom non-crafting serializer verifies nested fusion energy, catalyst/output arrays, ritual fields, and preservation of opaque metadata across reloads. This fixture verifies the generic serializer path; installed Draconic Evolution/Occultism compatibility remains untested. Test scripts, serializers, and structures stay out of the release JAR.

`./gradlew :mantis-minecraft:runGameTestServer -PtestFirstLoad` also starts with a deliberately broken server script, verifies unchanged recipes and an empty fallback generation, then removes the broken script and recovers through reload. Both runs check that an extension-registration failure is skipped only by the first-load fallback and rejected by strict reloads.
