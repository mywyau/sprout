# Performance

Performance is a product constraint, but correctness defines whether cached work is reusable.

## Current strategy

- Coursier owns the global download cache and concurrent artifact fetching.
- Each CLI command creates one immutable build session containing the project, resolved main and
  optional test dependencies, compiler classpath, and derived compile/runtime classpaths.
- Compilation outputs and metadata live below `.sprout/`.
- The compile key includes ordered source and classpath contents, the compiler artifact contents,
  compiler bridge contents, exact Scala version, compiler options, and current JVM target.
- Cache metadata has an explicit format version and is replaced atomically. Corrupt or incompatible
  metadata is ignored and regenerated.
- A compile is skipped only when both its input key and a content fingerprint of all compiled output
  match. Missing or modified output invokes Zinc, which uses its persisted analysis to select the
  affected sources or safely performs a full compilation when analysis is unavailable.
- Timestamps are not treated as proof that source contents are unchanged.

Main and test compilation have separate fingerprints and Zinc analysis stores. A source-only
implementation change recompiles that source without rewriting unrelated class files; API changes
can also recompile dependent sources. Dependency, compiler-option, Scala-version, and JVM-target
changes invalidate the compatible setup conservatively.

## Measurements

`benchmarks/measure.sh` copies a fixture into a temporary project and records monotonic wall-clock
baselines for CLI startup, cold compilation, warm compilation, a no-change compile, and a single-file
change. It also reports the timestamp, OS, Java version, Sprout version, and fixture. The original
fixture is never modified.

Run the baseline against the default hello-world fixture or a larger project:

```bash
benchmarks/measure.sh
SPROUT=/path/to/sprout benchmarks/measure.sh /path/to/project
```

“Cold” means no project-local `.sprout` state. It deliberately does not erase Coursier's global cache
or the operating system's filesystem cache. “Warm” cleans project output and compiles again with
downloaded artifacts available; “no-change” reuses the immediately preceding output. Capture several
runs on the same machine before and after a performance change rather than treating one number as a
portable score.

Microbenchmarks are useful for explaining a suspected cost, not for deciding product behaviour by
themselves. End-to-end latency and diagnostics remain the meaningful user experience.

## Future daemon

A daemon may hold the compiler, resolution metadata, and project model in a warm JVM behind local IPC.
It will be an optional front end to the same application services; build correctness and normal CLI
operation cannot depend on daemon state.
