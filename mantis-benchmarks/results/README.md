# Java 17 baseline

Measured October 7, 2026 on Linux with OpenJDK 17.0.20, a 1 GiB heap, GraalJS 23.0.12, and upstream Rhino 1.9.1. These are host benchmarks, not an installed Minecraft modpack. Each result uses two forks, three 1-second warmups, five 1-second measurements per fork, and JMH's GC profiler. Call inputs vary between invocations. Loading and recipe edits were measured in a separate run with the same settings. The complete scores, error bounds, allocations, and JVM metadata are in [java17-baseline.json](java17-baseline.json).

Lower time is better. Values are mean microseconds per operation; the JSON retains JMH's uncertainty estimates.

| Workload | Mantis | Rhino interpreted | Rhino compiled |
| --- | ---: | ---: | ---: |
| Java → JS function call | 2.065 | 0.216 | 0.019 |
| JS → Java method call | 3.497 | 0.347 | 0.187 |
| Load 100 scripts into a fresh context/scope | 2,387.299 | 259.755 | 27,552.355 |
| Create and edit 256 recipe JSON objects | 160.557 | 257.890 | 88.792 |

Mantis has not met the overall speed goal. Its safety checks, profiling, conversion, and interpreter backend have substantial overhead on small calls. Compiled Rhino leads those cases. Mantis has a lower mean than compiled Rhino for fresh collection loading; interpreted Rhino leads that case. Recipe-edit results vary considerably between forks, and compiled Rhino has the lowest mean. These measurements do not prove performance for Draconic Evolution, Occultism, another Rhino fork, or an entire server.

Reproduce with:

```sh
./gradlew :mantis-benchmarks:benchmark -PrhinoVersion=1.9.1 -PbenchmarkArgs='EngineBenchmarks.(functionCall|javaMethod|load100Scripts|recipeEdits) -prof gc -jvmArgs -Xmx1g'
```

The source for call benchmarks corresponds to commit `93d554de3f9b38106d296b84755bc590dc525efe`; the loading/recipe benchmark implementations were identical in the preceding feature commit. Results are a starting baseline for optimization and should be remeasured when the runtime or benchmark workload changes.
