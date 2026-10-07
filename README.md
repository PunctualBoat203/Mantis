# Mantis

Minecraft 1.20.1 scripting by **PunctualBoat**. Java 17, Forge 47.4.0 or newer in the 47.x line.

Mantis owns script loading, modules, events, reload cleanup, an internal clock, and recipe edits. This first implementation uses bundled GraalJS 23.0.12 for JavaScript execution. It has no Rhino, KubeJS, or HeroClock dependency. Performance against Rhino has not been established.

Build with `./gradlew test :mantis-minecraft:build`. The bundled mod is `mantis-minecraft/build/libs/mantis-0.1.0.jar`. Launch development Minecraft with `:mantis-minecraft:runClient` or `:mantis-minecraft:runServer`.

On first launch Mantis creates:

```
config/mantis/
  startup_scripts/
  server_scripts/
  examples/
    startup_scripts/
    server_scripts/
```

Startup scripts run once during common setup and require a game restart to change. Server scripts run once per datapack load. `.js` and `.mjs` files load in filename order as ES modules; relative imports work within their script directory. Examples end in `.js.example` and never run automatically. Copy an example into its matching script directory and remove `.example` to enable it. Existing files are preserved.

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

Script edits are staged on copies. Changed recipes are validated by their installed serializers before the original JSON map changes. Script errors reject the new generation. A successful `/reload` or `/mantis reload` replaces the server scripts and cleans up previous listeners and timers. On failure, the previous generation stays active. External side effects performed by an extension during script evaluation cannot be rolled back automatically.

## Modules and events

| Module | Exports |
| --- | --- |
| `mantis:events` | `events.on(name, callback)`, `events.once(name, callback)`; returned handles have `unsubscribe()` |
| `mantis:console` | `console.log`, `warn`, `error` |
| `mantis:clock` | `clock.ticks`, `after`, `every`, `cooldown`, `remaining`; timer handles have `cancel()` and `active()` |
| `minecraft:recipes` | `recipes` |
| `minecraft:mods` | `mods.isLoaded(modId)` |
| `minecraft:server` | `server.broadcast(message)`, `server.runCommand(command)` |

Server events are `recipes`, `server.started`, `server.reloaded`, `server.tick`, `server.stopping`, `player.logged_in`, and `player.logged_out`. Tick/lifecycle payloads carry `ticks` where available. Player payloads carry `player.uuid()`, `player.name()`, and `player.tell(message)`. Startup scripts receive `startup` and server lifecycle/player events; clock, recipes, and server-command modules are server-script APIs.

Operator commands: `/mantis clock`, `/mantis status`, `/mantis reload`. Status includes active script/listener/timer counts and accumulated script time.

## Java integration

`mantis-core`, `mantis-runtime`, and `mantis-recipes` have no Minecraft or loader dependency. Java applications can create a `MantisEngine`, supply source snapshots and host modules, then evaluate modules and invoke exported functions.

Mods register extensions with `MantisApi.registerExtension(modId, registrar -> ...)` during construction or common setup. Use `registrar.module("modid:api", Map.of("api", object))` to provide a virtual module. Annotate callable host methods with `@MantisExport`. `registrar.resources()` owns generation resources; `registrar.events()` exposes dispatch; `registrar.context()` supplies the initialized script context. Extensions may use the standalone recipe transaction classes to integrate storage outside RecipeManager.

Java class lookup, unexported methods, native access, processes, threads, environment access, arbitrary files, and sockets are unavailable to scripts. Module reads use an in-memory source snapshot. Calls have statement and wall-time limits; exceeding a limit closes the affected context. This is a permissions foundation for pack-authored scripts, not a hardened untrusted-code sandbox. Host methods and extension APIs must enforce their own permissions and cancellation. CompletableFuture/Promise bridging, custom type-converter registration, Rhino migration, client scripting, and comparative benchmarks remain future work.
