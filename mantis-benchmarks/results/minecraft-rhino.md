# Supplied Minecraft Rhino fork comparison

Measured October 8, 2026 against the 0.2.2 implementation in commit `e040f3d67812eafe974bc199e6a9305fb55f8d54`. Linux, stock OpenJDK 17.0.20, GraalJS 23.0.12, JMH 1.37, one worker thread, a 1 GiB heap, two forks, three 1-second warmups and five 1-second measurements per fork, with the GC profiler. The Truffle runtime reports `Interpreted`.

The reference is the supplied `rhino-forge-2001.2.3-build.10` jar, SHA-256 `fed2211429301bf043864183cab9ab8e92d4cc4dbb9e488ce6c75217c54584a6`. Its `Context.createCompiler` selects an interpreter. Upstream Rhino's compiled mode is not the KubeJS baseline.

Times are mean microseconds per operation ± JMH's error estimate; allocation is mean bytes per operation. Lower is better. [Raw results](java17-minecraft-rhino.json) include every iteration, JVM arguments and allocation uncertainty.

| Workload | Mantis, µs/op | Minecraft Rhino, µs/op | Mantis, B/op | Minecraft Rhino, B/op |
| --- | ---: | ---: | ---: | ---: |
| Java → JS function | 0.547 ± 0.016 | 0.186 ± 0.005 | 556.0 | 264.0 |
| JS → Java method | 1.346 ± 0.040 | 0.343 ± 0.009 | 1,392.1 | 284.0 |
| 128-iteration arithmetic loop | 9.510 ± 3.672 | 20.216 ± 1.266 | 1,969.6 | 304.1 |
| Load 100 scripts into a fresh context/scope | 1,828.862 ± 258.562 | 140.686 ± 15.871 | 1,838,424.0 | 630,464.8 |
| Create and edit 256 JavaScript recipe-shaped objects | 93.781 ± 5.722 | 149.928 ± 5.554 | 99,597.2 | 183,272.9 |

Rhino leads the small call and loading fixtures. Mantis has lower means for the loop and object-editing fixtures, although its loop allocation is higher and its timing varies considerably. Sharing an interpreter model does not fix the outcome of every workload. These results do not establish a whole-server speed or memory advantage.

The adapter calls the fork's public API directly, with no per-call reflective adapter overhead. Mantis includes its execution limits, diagnostics and annotated host bridge; Rhino uses ordinary Java wrapping without equivalent execution limits. Function bodies and returned results match, but embedding capabilities differ. The fork supplies neither KubeJS itself nor its configured Minecraft remapper, plugin bindings, event system or recipe pipeline in this run.

For loading, Mantis loads ES modules through its snapshot filesystem; Rhino evaluates global scripts in a fresh standard scope. The adapter installs a `globalThis` scope alias for that fixture because the supplied fork lacks that built-in. Both workloads populate the same global values, but their module semantics differ. The 256-object case does not call Gson, RecipeManager, serializer validation or Mantis's recipe transaction API. Allocated bytes are not retained heap. JMH iteration percentiles are not per-handler latency percentiles.

Reproduce with a local copy of the same reference jar:

```sh
./gradlew :mantis-benchmarks:test :mantis-benchmarks:benchmark \
  -PrhinoModJar=/path/to/rhino-forge-2001.2.3-build.10.jar \
  -PbenchmarkArgs='EngineBenchmarks.(functionCall|javaMethod|hotLoop|recipeEdits|load100Scripts) -p backend=mantis,rhino-minecraft -prof gc -jvmArgs -Xmx1g'
```

The optional adapter compiles only when `rhinoModJar` is provided. No reference jar is committed, and this development dependency is not included in the Minecraft release mod. Normal upstream benchmarks remain available for historical comparisons.
