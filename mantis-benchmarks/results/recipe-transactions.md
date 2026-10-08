# Recipe transaction strategies

Measured October 8, 2026 using the 0.2.2 implementation in commit `e040f3d67812eafe974bc199e6a9305fb55f8d54`. Linux, OpenJDK 17.0.20, Gson 2.10.1, JMH 1.37, one worker thread, a 1 GiB heap, two forks, three 1-second warmups and five 1-second measurements per fork, with the GC profiler. [Raw results](java17-recipe-transactions.json) retain iteration data, JVM metadata, uncertainty and allocation measurements.

Each collection contains 10,000 or 20,000 synthetic recipe JSON objects with ingredient/output arrays and nested energy, ritual and metadata objects. Setup constructs these inputs outside the timed operation. Each measured operation creates a transaction, applies the selected edits and commits. Inputs remain unchanged between operations.

`snapshot` uses the isolated Java constructor and full-map `commit`. `borrowed-delta` uses Minecraft's selected `borrowing`/`commitChanges` path, which borrows exclusively owned input until commit and publishes detached replacements/removals. Both strategies use the same current filter/edit implementation; this is a strategy comparison, not a paired measurement of two releases. Validators are no-ops. No Minecraft serializer, JavaScript, JSON text conversion, resource loading or final RecipeManager map application is measured.

Times are mean milliseconds per operation ± JMH's error estimate. Allocation is decimal MB per operation, not retained memory.

| Recipes | Operation | Snapshot, ms/op | Borrowed delta, ms/op | Snapshot, MB/op | Borrowed delta, MB/op |
| ---: | --- | ---: | ---: | ---: | ---: |
| 10,000 | Unchanged | 17.242 ± 1.158 | 2.168 ± 0.078 | 34.720 | 0.401 |
| 20,000 | Unchanged | 42.093 ± 3.863 | 4.309 ± 0.093 | 69.441 | 0.801 |
| 10,000 | Patch 100 exact IDs | 18.304 ± 0.566 | 2.472 ± 0.059 | 35.710 | 1.559 |
| 20,000 | Patch 100 exact IDs | 49.258 ± 12.442 | 4.888 ± 0.196 | 70.432 | 1.959 |
| 10,000 | Patch every recipe by type | 57.738 ± 1.702 | 52.559 ± 3.906 | 130.690 | 113.650 |
| 20,000 | Patch every recipe by type | 164.937 ± 23.219 | 110.610 ± 4.131 | 261.378 | 227.298 |

Borrowing and publishing only changes reduce work substantially when recipes are untouched or sparsely edited. For 100 edits among 20,000 recipes, the measured mean decreases from about 49.3 to 4.9 ms, and allocation from 70.4 to 2.0 MB. Changing every recipe still copies each edited JSON object for edit/validation/publication isolation; it also allocates substantially more than sparse edits. The 10,000-recipe all-edits timing intervals overlap, so that timing result needs more measurement.

The production path preserves serializer validation and atomic publication. Synthetic host timings do not establish pack reload speed, KubeJS performance or script-handler latency. JavaScript `recipes.patch` text round trips remain an optimization target; this benchmark does not measure them.

Reproduce with:

```sh
./gradlew :mantis-benchmarks:benchmark -PbenchmarkArgs='RecipeBenchmarks -prof gc -jvmArgs -Xmx1g'
```
