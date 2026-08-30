# Architecture

Sprout is a small orchestration layer over established Scala and JVM tools. The command line maps a
declarative project description into explicit requests to dependency resolution, compilation, and
execution boundaries. It is not a general task engine.

```text
cli ─────> config ──> core <── dependencies
 │          ▲         ▲       (Coursier)
 ├────────> bsp ──────┼────── compiler
 ├────────────────────┼────── runner
 └────────────────────┼────── packager
```

## Modules

- `core` owns immutable domain values, errors, project layout, source discovery, hashing, and cache
  contracts. It has no knowledge of TOML, terminals, or CLI parsing.
- `config` parses `sprout.toml` into the core model and performs narrow, atomic dependency edits.
  Configuration remains data only; edits never execute project code or rewrite unrelated sections.
- `dependencies` implements the resolver boundary with Coursier and retains a resolved graph with
  selected versions, requested versions, parent relations, direct/transitive status, and artifact
  ownership. Compilation, `graph`, and `why` consume the same resolution result.
- `compiler` owns compilation requests and the compiler boundary. The first backend starts Dotty in
  an isolated JVM; callers do not depend on that choice.
- `runner` starts JVM applications and provides a test-framework boundary. MUnit is the first adapter.
- `packager` creates deterministic application directories, JARs, launchers, archives, and checksums.
  It consumes compiled output and an ordered runtime classpath but knows nothing about CLI parsing or
  dependency resolution.
- `bsp` translates the standard Build Server Protocol into project, resolution, and compilation
  operations. It owns no editor-specific build logic.
- `cli` contains command parsing, compact presentation, project generation, and build orchestration.

Dependencies point inward toward `core`; infrastructure modules do not depend on the CLI. A future
multi-module planner can issue multiple compilation requests without changing resolver or compiler
interfaces.

## Build sessions

Every build-oriented CLI invocation loads one immutable `BuildSession`. It retains the project,
resolved main dependencies, optional resolved test dependencies, compiler classpath, and derived
compile/runtime classpaths for that command. `compile`, `run`, and `package` resolve the main graph
once. `test` resolves the distinct main and combined test graphs once each. A session is intentionally
short-lived; it is not global mutable state or a daemon cache.

## Local state and caching

Project outputs live below `.sprout/`: main classes, test classes, metadata, and application packages.
Dependencies use Coursier's established global artifact cache during builds and are copied into an
application distribution only by `sprout package`. Compilation metadata is keyed from source
contents, compiler version and options, JVM target, and ordered resolved classpath content. An output
is reusable only when the input key and the content fingerprint of every generated file both match.

Cache entries carry a metadata format version and use a temporary sibling plus atomic rename. Invalid
versions and malformed files are cache misses rather than build failures. The same atomic metadata
writer is the required boundary for Zinc analysis stores, so a cancelled or failed process cannot
leave a partially written analysis file at its final path.

## Zinc integration

`ScalaCompiler` is the replaceable seam. A Zinc backend will consume the same compilation request plus
an analysis store below `.sprout/metadata`, use Zinc analysis to select affected sources, compile them,
and atomically update analysis. The CLI and project model do not assume whole-project compilation.
Sprout will use Zinc rather than reproduce its dependency analysis.

## BSP and a future daemon

A daemon can sit in front of the same application service and retain resolver/compiler resources in a
warm JVM. Local IPC is a transport concern: normal one-shot CLI execution remains supported. The BSP
adapter already translates editor import and compile requests into the same project, Coursier, and
compiler boundaries. It can later delegate to a daemon without changing the BSP contract.
