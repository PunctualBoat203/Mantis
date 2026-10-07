# Mantis 0.2.1 hot calls

Measured October 7, 2026 on the same Linux/OpenJDK 17.0.20 host before and after optimization. Both runs use GraalJS 23.0.12, JMH 1.37, a 1 GiB heap, two forks, three 1-second warmups, five 1-second measurements per fork, and the GC profiler. Inputs vary between calls. The benchmark implementations are unchanged. Before is Mantis commit `4c86ab0f635a6116b8d83abcb2f175e291426640`; after uses the 0.2.1 runtime in this repository.

Times are mean microseconds per operation, with JMH's error estimate. Allocations are bytes per operation. Raw results: [before](java17-hotcalls-before.json), [after](java17-hotcalls-after.json).

| Workload | Before, µs/op | After, µs/op | Before, B/op | After, B/op |
| --- | ---: | ---: | ---: | ---: |
| Cached evaluation | 1.100 ± 0.095 | 0.573 ± 0.044 | 1,374.1 | 776.0 |
| Java → JS function | 1.831 ± 0.233 | 0.462 ± 0.017 | 696.5 | 580.0 |
| JS → Java method | 2.744 ± 0.391 | 1.124 ± 0.066 | 2,348.5 | 1,500.1 |
| JS → overloaded Java method | 2.734 ± 0.209 | 1.099 ± 0.068 | 2,336.6 | 1,476.9 |

Allocation samples identified unused cycle-tracking maps for primitive returns, overload lookup streams, and per-call scheduled tasks. The runtime now creates cycle tracking only for structured values, exports primitive bridge returns directly, reuses the last overload shape, and monitors reusable context deadlines. Source-cache hits compare complete source strings without recalculating SHA-256. Statement limits, timeout cancellation, profiling, type checks, and reload ownership remain enabled. Nested calls share the outer execution budget.

This comparison measures these four host workloads. It does not establish a speed advantage over Rhino or a complete Minecraft server. The earlier [Rhino comparison](README.md) remains the baseline for those separate measurements; server/modpack benchmarks remain future work.

Reproduce against each revision with:

```sh
./gradlew :mantis-benchmarks:benchmark -PbenchmarkArgs='EngineBenchmarks.(functionCall|javaMethod|overloadedMethod|cachedEvaluate) -p backend=mantis -prof gc -jvmArgs -Xmx1g'
```
