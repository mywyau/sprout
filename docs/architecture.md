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
- `compiler` owns compilation requests and the compiler boundary. The default backend integrates
  Zinc with the matching precompiled Scala 3 compiler bridge; the original isolated-process backend
  remains available behind the same interface.
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
resolved main dependencies, optional resolved test dependencies, compiler classpath, compiler
bridge, and derived
compile/runtime classpaths for that command. `compile`, `run`, and `package` resolve the main graph
once. `test` resolves the distinct main and combined test graphs once each. A session is intentionally
short-lived; it is not global mutable state or a daemon cache.

## Local state and caching

Project outputs live below `.sprout/`: main classes, test classes, metadata, and application packages.
Main and test resources are synchronised into their corresponding class directories after compilation.
The synchroniser tracks its own versioned manifest, removes stale copied resources, and rejects a
resource path that would overwrite compiled output.
The metadata directory also contains the versioned build-session cache: resolved dependency graphs,
compiler artifacts, compiler bridges, and main-class selections. It is an optimisation boundary only;
missing or malformed metadata causes normal resolution or discovery rather than a build failure.
Dependencies use Coursier's established global artifact cache during builds and are copied into an
application distribution only by `sprout package`. Compilation metadata is keyed from source
contents, compiler version and options, JVM target, and ordered resolved classpath content. An output
is reusable only when the input key and the content fingerprint of every generated file both match.

Cache entries carry a metadata format version and use a temporary sibling plus atomic rename. Invalid
versions and malformed files are cache misses rather than build failures. Zinc analysis is stored
separately for main and test compilation below `.sprout/metadata/zinc/v1/`. Analysis updates are
staged beside the destination and atomically renamed, so a cancelled or failed process cannot leave
a partially written analysis file at its final path. Corrupt analysis is treated as absent and
recovered with a safe full compilation.

## Zinc integration

`ScalaCompiler` remains the replaceable seam. `ZincScalaCompiler` consumes the ordinary compilation
request plus its versioned state location, uses Zinc analysis to select affected sources, and updates
analysis only after successful compilation. Sprout resolves the official Scala 3 bridge matching the
project's exact Scala version through Coursier. The CLI and project model do not assume whole-project
compilation, and Sprout does not reproduce Zinc's dependency analysis.

## BSP and a future daemon

A daemon can sit in front of the same application service and retain resolver/compiler resources in a
warm JVM. Local IPC is a transport concern: normal one-shot CLI execution remains supported. The BSP
adapter already translates editor import and compile requests into the same project, Coursier, and
compiler boundaries. It can later delegate to a daemon without changing the BSP contract.
