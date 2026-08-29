# Performance

Performance is a product constraint, but correctness defines whether cached work is reusable.

## Current strategy

- Coursier owns the global download cache and concurrent artifact fetching.
- Compilation outputs and metadata live below `.sprout/`.
- The compile key includes source paths and contents, exact Scala version, compiler options, and
  resolved classpath paths and sizes.
- A compile is skipped only when that key is present and compiled class output still exists.
- Timestamps are not treated as proof that source contents are unchanged.

The current cache is deliberately conservative. Changing any source triggers a correct full
compilation. Test output has a separate request and key. Zinc will eventually replace full recompiles
with analysis-guided affected-source compilation.

## Measurements

`benchmarks/measure.sh` records coarse wall-clock values for CLI startup, cold compilation, and a
no-change compilation. Future scenarios should include warm compilation, a single-file edit, and
cached/uncached dependency resolution. Measurements should run against realistic fixtures and report
JDK, OS, cache state, and hardware.

Microbenchmarks are useful for explaining a suspected cost, not for deciding product behaviour by
themselves. End-to-end latency and diagnostics remain the meaningful user experience.

## Future daemon

A daemon may hold the compiler, resolution metadata, and project model in a warm JVM behind local IPC.
It will be an optional front end to the same application services; build correctness and normal CLI
operation cannot depend on daemon state.
