# Mantis

Minecraft 1.20.1 scripting by **PunctualBoat**. Java 17, Forge 47.4.0 or newer in the 47.x line.

Mantis owns script loading, modules, events, reload cleanup, Java bindings, type conversion, async scheduling, an internal clock, and recipe edits. It uses bundled GraalJS 23.0.12 for JavaScript execution. The mod has no Rhino, KubeJS, or HeroClock dependency. Comparative benchmarks live in a separate development module; performance depends on the workload and JVM.

Build with `./gradlew test :mantis-minecraft:build`. The bundled mod is `mantis-minecraft/build/libs/mantis-0.4.1.jar`. Launch development Minecraft with `:mantis-minecraft:runClient` or `:mantis-minecraft:runServer`. See the [0.4.1 review follow-up](docs/review-follow-up-0.4.1.md) for fixes, verified checks and remaining review work.

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

Startup scripts run once before Forge fills its registries and require a game restart to change. They declare items, blocks, fluids, creative tabs, recipe schemas, generated tags/loot, and JSON assets. Registry declarations must match between clients and servers. Startup errors fail mod loading; the lenient first-load fallback applies only to server scripts. Server scripts run once per datapack load. `.js` and `.mjs` files load in filename order as ES modules; relative imports work within their script directory. Examples end in `.js.example` and never run automatically. Copy an example into its matching script directory and remove `.example` to enable it. Existing files are preserved.

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

Edits run inside the `recipes` event, before registered serializers load JSON. Filters combine exact `id`, serializer `type`, recipe namespace `mod`, and semantic `input`/`output` selectors. Item selectors are IDs, tags use `#namespace:path`, and fluid selectors use `{fluid: "namespace:path"}`. `any: [filters]`, `all: [filters]`, and `not: filter` compose filters; other fields on the same filter still apply. An empty filter intentionally matches every recipe, including for removal and replacement; check computed filters before passing them to destructive edits. Unknown filter keys produce an error. Recipes without a string serializer type are skipped by semantic input/output and type filters, allowing unrelated valid recipes to be edited. Item/tag membership comes from the current reload, including newly generated tags.

| Operation | Behavior |
| --- | --- |
| `recipes.custom(id, json)` | Add or overwrite a recipe by ID |
| `recipes.replace(id, json)` | Overwrite an existing recipe; fail if missing |
| `recipes.remove(filter)` | Remove matches and return the count |
| `recipes.get(id)` | Read a detached JSON copy |
| `recipes.ids(filter)` | List matching IDs in order |
| `recipes.set(filter, '/path/to/field', value)` | Set an exact JSON field, including array indices |
| `recipes.patch(filter, (json, id) => json)` | Transform native JS copies and return the count |
| `recipes.count(filter)` / `contains(filter)` | Count matches / test whether any match exists |
| `recipes.replaceInput(filter, from, to)` / `replaceOutput(...)` | Replace values in input/output fields and return the number of changed recipes |
| `recipes.item(id, count = 1)` / `tag(id)` / `fluid(id, amount)` | Create normalized item, tag, or fluid JSON |
| `recipes.ingredient(value)` | Normalize an item, tag, alternatives, or custom ingredient |
| `recipes.schema(type, definition)` / `types()` | Register a schema for this generation / list available schemas |
| `recipes.create(id, type, fields)` | Build through a registered schema |

The same API handles crafting, cooking, smithing, machine, fusion, and ritual JSON **when that mod uses Minecraft's RecipeManager and registered recipe serializers**. Its own serializer determines the accepted schema. Forge conditions and all unedited fields are preserved. A ritual activation item, fluid amount, energy cost, catalyst, or output can be changed through its JSON path without assuming it is a crafting-table recipe. Use `get` to inspect the installed recipe's actual structure. Schemas mark input/output paths for semantic filters; unknown serializers use common field names. Register a schema for unusual field names. `set` and `patch` work on arbitrary paths regardless of a schema. Replacements preserve count and opaque metadata when the target is an object; an explicit replacement count overrides it. A matching tag is replaced as a whole ingredient. String outputs cannot represent a multi-item stack; use `set` for serializers that keep count in a separate field. Integer JSON fields outside JavaScript's safe range become BigInt in native copies, preserving large energy values.

Mods that keep recipes in separate private storage require an integration through the extension API. Generic JSON support does not establish compatibility with every mod/version. Actual Draconic Evolution and Occultism installations have not yet been tested.

Script edits are staged copy-on-write: only recipes that an edit touches are copied, and unchanged recipes are shared with the loaded data. Changed recipes are validated by their installed serializers before the original JSON map changes. Script errors reject the new generation. A successful `/reload` or `/mantis reload` replaces the server scripts and cleans up previous listeners and timers. On failure, the previous generation stays active. The one exception is the very first load, when there is no previous generation to keep: by default (`lenient_first_load=true`) Mantis logs the error, applies no scripted recipe changes, and lets the server finish starting instead of aborting the datapack load. The fallback retains the original error: `/mantis status` reports `degraded`, `/mantis errors` shows the failure, and operators receive a warning on login. A successful reload clears the degraded state. Fix the script and run `/mantis reload`. Set `lenient_first_load=false` to make a first-load failure fatal. External side effects performed by an extension during script evaluation cannot be rolled back automatically.

## Recipe builders and schemas

Vanilla helpers take an explicit recipe ID: `shapeless(id, output, ingredients)`, `shaped(id, output, pattern, key)`, `smelting` / `blasting` / `smoking` / `campfire(id, output, ingredient)`, `stonecutting(id, output, ingredient)`, and `smithing(id, output, template, base, addition)`. Cooking returns a builder with `experience(value)` and `cookingTime(ticks)`; builders also support `group(name)`, `set(pointer, value)`, and `id()`. Mutation handles expire at the end of their recipe event. Cooking outputs contain one item; stonecutting supports counted output.

```js
events.on('recipes', () => {
  recipes.shaped('pack:sticks', recipes.item('minecraft:stick', 4),
    ['P', 'P'], {P: '#minecraft:planks'});
  recipes.smelting('pack:recycle', 'minecraft:iron_nugget', 'minecraft:iron_sword')
    .experience(0.2).cookingTime(100);
});
```

A schema targets an installed serializer and declares fields as `{kind, path, role?, optional?, default?}`. `path` is a JSON pointer through object fields; overlapping paths and `/type` edits are rejected. `template` preserves constant serializer metadata. Built-in kinds are `json`, `ingredient`, `ingredients`, `item`, `items`, `fluid`, `positive_int`, `number`, and `string`. `role: 'input'` / `'output'` controls semantic matching. Java extensions can add component converters and schemas through `MantisApi.registerRecipeComponent` / `registerRecipeSchema`. Server `recipes.schema` declarations belong to that generation; startup `schemas.register` declarations are copied into each generation.

```js
// The serializer must exist and accept this JSON layout.
events.on('recipes', () => {
  recipes.schema('example:machine', {
    fields: {
      feed: {kind: 'ingredient', path: '/processing/feed', role: 'input'},
      product: {kind: 'item', path: '/processing/product', role: 'output'},
      energy: {kind: 'positive_int', path: '/energy', default: 1000}
    }
  });
  recipes.create('pack:machine_recipe', 'example:machine', {
    feed: '#forge:ingots/iron', product: recipes.item('minecraft:diamond', 2)
  });
});
```

## Startup content and generated resources

```js
import { registries } from 'minecraft:registries';
import { data } from 'minecraft:data';

registries.item('pack:token', {
  displayName: 'Pack Token', texture: 'minecraft:item/emerald', maxStackSize: 16
});
registries.block('pack:stone', {
  displayName: 'Pack Stone', texture: 'minecraft:block/stone', hardness: 2, resistance: 6
});
data.tag('items', 'pack:tokens', ['pack:token']);
data.lootTable('pack:chests/token', {
  type: 'minecraft:chest',
  pools: [{rolls: 1, entries: [{type: 'minecraft:item', name: 'pack:token'}]}]
});
```

Item properties are `maxStackSize`, `durability`, `fireResistant`, `food: {nutrition, saturation, alwaysEat}`, `texture`, and `displayName`. Durable items stack to one. Block properties are `hardness`, `resistance`, `light`, `noOcclusion`, `requiresTool`, `item` (automatic block item, default true), `texture`, and `displayName`. Unknown properties and duplicate IDs fail. Use a custom namespace for new entries. Items get generated models using their texture; blocks get cube models, blockstates and optional block-item models. Default textures are paper/stone. `displayName` creates English translations. Custom block/item behavioral subclasses are not yet provided.

```js
const sap = registries.fluid('pack:sap', {
  displayName: 'Sap', tint: '#CC88BB44', viscosity: 1500, tickRate: 8
});
data.tag('fluids', 'pack:sap', [sap.source, sap.flowing]);
registries.creativeTab('pack:content', {
  displayName: 'Pack Content', icon: sap.bucket,
  items: ['pack:token', 'pack:stone', sap.bucket], after: ['minecraft:ingredients']
});
registries.tabItems('minecraft:ingredients', ['pack:token', sap.bucket]);
```

`fluid` returns IDs for `source`, `flowing`, `block`, and `bucket`. `pack:sap` creates a fluid type/source/liquid block at that ID, `pack:flowing_sap`, and `pack:sap_bucket`. Families reserve their generated IDs and connect through suppliers, independent of registry event order. Properties include `density` (default 1000), `viscosity` (1000), `temperature` (300 kelvin), `light` (0–15), `tickRate` (5), `slopeFindDistance` (4), `levelDecreasePerBlock` (1), `resistance` (100), and booleans `canConvertToSource`, `canExtinguish`, `canHydrate`, `supportsBoating` (all default false). Fluids use Forge's flowing-fluid behavior. `stillTexture` / `flowingTexture` default to vanilla water sprites; `tint` is an ARGB string, default `#FFFFFFFF`. Sprite declarations are added to the block atlas and fluids use the translucent render layer. `bucketTexture` defaults to the vanilla water-bucket icon and is independent of the fluid tint. Custom textures must come from a resource pack or installed mod.

`creativeTab` accepts `displayName`, `icon` (item ID), `items` (item IDs), and optional `before` / `after` arrays of tab IDs. `tabItems` appends IDs to an existing or scripted tab, removing repeated IDs. Item and tab references are checked after registry loading. Tab callbacks use stored Java declarations rather than retaining script callbacks. Registry declarations are capped at 4096 entries; a fluid family counts as four declarations. Lists of creative item IDs are capped at 4096.

Generated resources are in-memory packs. `data.tag` accepts a relative tag folder (`items`, `blocks`, `fluids`, `entity_types`, `game_events`, or e.g. `worldgen/biome`), a tag ID, and values consisting of IDs, `#tag` references, or `{id, required}` entries. Repeated declarations append; `replace: true` replaces earlier values and lower-priority pack values. `data.lootTable` writes a complete loot table. `data.json('pack:relative/path.json', object)` supplies other server JSON, including predicates, advancements or mod data, subject to that loader's schema. `data.assetJson` supplies or overrides client JSON such as models and translations. Textures must already exist in Minecraft or an installed resource pack/mod. Data and assets are limited to 4096 resources total, 1 MiB per resource and 16 MiB total.

Startup resources are frozen and reused during `/reload`; changing them requires a restart. Their installed loaders validate the JSON. The generated packs default to the top position; operator pack ordering can affect overriding. Runtime loot-drop modifiers and server-script tag generation are still pending.

## Script commands

Declare commands at the top level of a server script, or during its load/init/recipe events. Declarations freeze when that reload finishes preparing. Each command belongs to that script generation; removed declarations disappear after a successful reload. Name collisions with vanilla/mod commands reject the reload. A rejected reload retains the previous dispatcher and handlers.

```js
import { commands } from 'minecraft:commands';

commands.register('pack:welcome', {
  permission: 0,
  arguments: [{name: 'message', type: 'string', optional: true}]
}, event => {
  event.source.reply(event.args.message ?? 'Welcome!');
  return 1;
});
```

Use `/pack:welcome "Hello world"`. Permission defaults to 2 and accepts integers 0–4. Argument types are `integer`, `double`, `boolean`, `word`, `string` (quoted or one word), and `greedy` (remaining text). Numeric arguments accept `min` / `max`; string types accept static `suggestions`. Optional arguments must follow required arguments and a greedy argument must be last. Missing optional values are `null`. Names are lowercase literals, optionally namespaced, and `mantis` is reserved. There are at most 512 commands, 16 arguments per command and 256 suggestions per argument. Nested literal subcommands and Minecraft entity/position arguments are not yet provided.

Handlers receive `args`, the original command `input`, and `source`. Source helpers are `name()`, `reply(message)`, `error(message)`, `hasPermission(level)`, `player()` (null for console/command blocks), and `dimension()`. Operations execute on the server thread. Return an integer result, or omit the return for result 1. Errors report result 0; ten consecutive callback/return-validation failures disable and release that handler. A success resets the counter. Command callbacks include argument conversion and result validation in the callback execution budget; exceeding an execution limit closes all work owned by that generation. Reload after fixing the error.

## Modules and events

| Module | Exports |
| --- | --- |
| `mantis:events` | `events.on(name, callback)`, `once(name, callback)`, or either with `(name, filter, callback)`; handles have `unsubscribe()` |
| `mantis:console` | `console.log`, `warn`, `error` |
| `mantis:lifecycle` | `lifecycle.on(hook, callback)`, `lifecycle.state()` |
| `mantis:rhino` | Explicit migration helpers: `Java.type`, `Java.to`, `Java.from` |
| `mantis:clock` | `clock.ticks`, `after`, `every`, `cooldown`, `remaining`; timer handles have `cancel()` and `active()` |
| `minecraft:recipes` | `recipes` |
| `minecraft:mods` | `mods.isLoaded(modId)` |
| `minecraft:server` | `server.broadcast(message)`, `runCommand(command)`, `players()`, `level(dimension)` |
| `minecraft:registries` | Startup-only `registries.item`, `block`, `fluid`, `creativeTab`, `tabItems` |
| `minecraft:commands` | Server-only `commands.register(name, options, handler)` |
| `minecraft:schemas` | Startup-only `schemas.register(type, definition)` |
| `minecraft:data` | Startup-only `data.tag(folder, id, values, replace = false)`, `lootTable(id, json)`, `json(path, json)`, `assetJson(path, json)` |

Server events include `recipes`, `server.started`, `server.reloaded`, `server.tick`, `server.stopping`, `player.logged_in`, and `player.logged_out`. Tick/lifecycle payloads carry `ticks` where available. Startup scripts receive `startup` and later gameplay/lifecycle events; they have no clock or recipe-edit module. Server operations require a running server on its main thread.

| Event group | Names and payload additions |
| --- | --- |
| Players | `player.chat` (`message`), `player.respawned`, `player.changed_dimension` (`from`, `to`), `player.tick` |
| Items | `item.crafted`, `item.smelted`, `item.picked_up`, `item.dropped`, `item.used`, `item.right_clicked` (`item`, `stack`) |
| Blocks | `block.right_clicked`, `block.left_clicked`, `block.broken`, `block.placed` (`block`, `position`, `level`) |
| Entities | `entity.spawned`, `entity.death` (`source`), `entity.hurt` (`damage`, `source`) |
| Levels | `level.loaded`, `level.unloaded`, `level.tick`, `level.before_explosion`, `level.after_explosion` (affected `blocks` / `entities` counts) |

Gameplay entity payloads carry `entity`, `entityType`, `dimension`, and `player` when applicable. Level payloads carry `level` and `dimension`. Filtered subscriptions accept exact `item`, `block`, `entityType`, and `dimension` strings, checked in Java before payload conversion or entering JavaScript. Multiple fields combine with AND. Filters are captured at registration; skipped events do not consume a `once` listener.

Every gameplay payload has `control.cancellable()`, `cancelled()`, and `cancel()`; `cancel()` requires a cancellable Forge event. `entity.hurt` also allows `control.damage(amount)`. Controls expire when synchronous event dispatch ends. Read current damage from the initial payload; another listener may have changed the underlying Forge event. Default Forge dispatch skips already cancelled events. Lifecycle/login/logout payloads have no control.

`player` supports `uuid()`, `name()`, `tell(message)`, `position()`, `level()`, `heldItem()`, `give(id, count)`, `teleport(x, y, z)`, and string `data(key)` / `data(key, value)`. `entity` supports `uuid()`, `type()`, `position()`, `health()` for living entities, and the same data methods. Mantis entity data saves under its own persistent NBT compound; player data is copied across player cloning/respawns. `stack` is a snapshot with `id()`, `count()`, and `nbt()` (SNBT). `level` supports `dimension()`, `isLoaded(x, y, z)`, `block(x, y, z)`, `setBlock(x, y, z, id)`, and `dayTime()`. Block reads and writes reject unloaded chunks; check `isLoaded` before probing remote positions. Inventory, world and entity operations require the server thread.

```js
import { events } from 'mantis:events';
events.on('block.broken', { block: 'minecraft:diamond_block' }, event => {
  event.control.cancel();
  event.player.tell('This block is protected.');
});
```

`console.log`, `warn` and `error` accept zero or more JavaScript values. Arrays and ordinary objects use JSON when possible; circular or otherwise unserializable values fall back to a printable string. Minecraft logs preserve INFO, WARN and ERROR levels. `server.runCommand` uses the server console permission source. Resource reloads reject while a script callback is executing, including nested `execute` commands; request `/mantis reload` from outside scripts. Item selectors can match tag membership; replacing an item also replaces matching tag ingredients with the specified item.

Operator commands: `/mantis clock`, `/mantis status`, `/mantis reload`, `/mantis scripts`, `/mantis modules`, `/mantis profile`, `/mantis errors`. Status includes lifecycle state, first-load degradation, startup session state, scripts, listeners, timers, pending async operations, and accumulated script time. Errors include retained first-load failures and startup errors. A failed startup gameplay session stays failed until restart but does not block server-script reloads. Profile shows operation totals, maximum duration, failures, and source-cache counts. Timings are inclusive: a host bridge operation can be part of a function or module timing. Each generation retains the last 32 error descriptions; internal Java traces use debug logging.

Lifecycle hooks are `load`, `init`, `start`, `reload`, `stop`, `unload`, and `error`. A generation moves through created, loading, loaded, running, stopping, and disposed; failed initialization or exhausted execution limits also record a failed state. Load/init run during preparation. Start runs after activation; reload follows start for a replacement generation. Stop/unload run before owned resources close. An error in a stop hook is reported and cleanup continues. Await pending host work inside functions or event handlers; pending top-level await rejects preparation.

## Java integration

`mantis-core`, `mantis-interop`, `mantis-runtime`, `mantis-recipes`, and `mantis-rhino-compat` have no Minecraft or loader dependency. Java applications can create a `MantisEngine`, supply source snapshots and host modules, then evaluate modules and invoke exported functions.

Mods register extensions with `MantisApi.registerExtension(modId, registrar -> ...)` during construction or common setup. Register during construction if startup scripts import the extension: startup runs before common setup. Use `registrar.module("modid:api", Map.of("api", object))` to provide a virtual module. Annotate callable host methods/fields/constructors with `@MantisExport`; use `@MantisProperty("name")` on a zero-argument getter and optional one-argument void setter. Session modules bind these members explicitly through cached MethodHandles. `registrar.bindings().type(MyClass.class)` exposes its annotated static members and constructors. Unannotated members stay hidden.

Overloads prefer exact strings/booleans and lossless integers (`int`, then `long`, `short`, `byte`), followed by fractional `double`, `float`, structured conversions, and the `Object` fallback. Fixed arity precedes varargs. Equally suitable unrelated overloads fail with an ambiguity error. Generic callback parameters support the standard functional interfaces and interfaces annotated with `@MantisExport`; callbacks must run on the host scheduler thread. Use `registrar.bindings().function(Function.class, javaFunction)` to export a Java lambda as a callable JS function.

`registrar.conversions()` registers custom bidirectional converters before loading. Arrays and collections become native JS arrays; string-keyed maps and public records become native JS objects; enums become names; Optional becomes value/null. BigInteger and longs outside the JS safe-integer range become BigInt. Reverse conversion supports generic List/Set/Map/Optional, arrays, records, enums, functional interfaces, and exact numbers. Cycles, excessive nesting, and lossy numeric conversion fail. Minecraft adds ResourceLocation/string and Component/text+JSON converters. Host arrays and record data are copied during ordinary conversion. Annotated objects nested inside collections/maps also receive cached bindings, including their method return values.

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

The [0.2.2 review](docs/review-0.2.2.md) records patch corrections and feature gaps found by inspecting the supplied KubeJS/addon jars. The [0.4.0 progress report](docs/progress-0.4.0.md) covers commands, fluids, creative tabs, installed-mod checks and remaining release work; the [0.3.0 report](docs/progress-0.3.0.md) records the earlier milestone. Mantis extensions already contribute modules, bindings, converters and owned resources; the supplied KubeJS plugins do not run unchanged.

Java class lookup, unexported methods, native access, processes, threads, environment access, arbitrary files, and sockets are unavailable to scripts. Module reads use an in-memory source snapshot. Calls have statement and wall-time limits; exceeding a limit closes the affected context. This is a permissions foundation for pack-authored scripts, not a hardened untrusted-code sandbox. Host methods and extension APIs must enforce their own permissions and cancellation. The shared source cache is bounded by entry count and bytes and keys source content. Its hit/miss/eviction counts cover explicit evaluations, generated entry stubs, and bridge helpers; main module bodies load through the in-memory filesystem and are not counted by that cache. Module instances are confined to a generation; full reloads rebuild dependencies from the new source snapshot. Per-file dependency reloads, client scripting, a custom JavaScript engine, and broader installed-mod compatibility testing remain future work.

Each outer operation resets the statement budget (default 1,000,000). Callbacks have a 250 ms deadline by default. Initialization, module/evaluation loading, and handlers of load-phase events (`startup`, `recipes`, and the `load`/`init` lifecycle hooks) get the longer load budget, 10 seconds by default, including payload conversion. All three values are set in `config/mantis/mantis.properties` (`callback_timeout_ms`, `load_timeout_ms`, `statement_limit`). Restart after changing settings. Nested bridge and script calls share their outer budget. Exceeding a deadline or statement budget closes the whole script context for that generation, records a failed state, and cancels its listeners, timers, and pending futures; `/mantis reload` starts a fresh one. Wall-clock deadlines also include GC pauses and server stalls.

An exception in an event handler or repeating timer is logged and the handler stays registered; one that fails ten times in a row is disabled so it cannot flood the log, and a success resets its count. `server.tick` payloads are only built when a script listens for that event. A shared watchdog checks deadlines every 5 ms, and completion also checks elapsed time. Host work must cooperate with cancellation; Java code blocked in a host method cannot be forcibly stopped by a script deadline.

`./gradlew :mantis-minecraft:runGameTestServer` runs isolated Minecraft tests of startup items/blocks/food, generated tags/loot/assets and pack metadata, vanilla/schema recipe builders, tag-aware replacements, event filtering/cancellation/damage changes, player data cloning, recipe edits, rejected reloads, timer/future cleanup, server-thread async continuations, Minecraft type conversion, lifecycle hooks, and saved clock data. A registered custom non-crafting serializer verifies nested fusion energy, catalyst/output arrays, ritual fields, and preservation of opaque metadata across reloads. This fixture verifies the generic serializer path; installed Draconic Evolution/Occultism compatibility remains untested. Test scripts, serializers, and structures stay out of the release JAR.

`./gradlew :mantis-minecraft:runGameTestServer -PtestFirstLoad` also starts with a deliberately broken server script, verifies unchanged recipes and an empty fallback generation, then removes the broken script and recovers through reload. Both runs check that an extension-registration failure is skipped only by the first-load fallback and rejected by strict reloads.

The same GameTests exercise command permissions, all supported argument types, optional values, suggestions, return validation, collisions, removed declarations, and callback cleanup through reloads. They register fluid families and creative tabs, place and collect fluids, inspect bucket remainders and generated fluid assets, and build tab contents including additions to existing tabs. A visual client test is still needed.

`./gradlew :mantis-minecraft:runGameTestServer -PtestCreate` installs Create 6.0.8 (matching Maven build 289), Ponder, Flywheel, Registrate and MixinExtras only for development tests. It checks edits to existing mixing/crushing recipes, chance preservation, removals, and seven installed processing serializers, including filling and emptying with a Mantis fluid. It also rejects a malformed Create recipe through reload and verifies that the previous recipes, commands and owned work survive. These mods are not bundled with Mantis. This version-specific check does not establish compatibility with all Create releases or other recipe systems. The generic serializer fixture remains the only fusion/ritual test; installed Draconic Evolution and Occultism remain untested.

`python3 scripts/production_smoke.py` boots the built bundled JAR in an installed Forge 47.4.0 server. It checks startup items/fluids, recipes, native console levels, command callbacks and three reloads. It uses an isolated temporary server under `build/production-smoke`; remove that test directory before repeating the command. CI also runs the Java unit suite on Windows to verify virtual module path and URI handling.
