# Architecture

Sprout is a small orchestration layer over established Scala and JVM tools. The command line maps a
declarative project description into explicit requests to dependency resolution, compilation, and
execution boundaries. It is not a general task engine.

```text
cli ─────> config ──> core <── dependencies
 │          ▲         ▲       (Coursier)
 ├────────> bsp ──────┼────── compiler
 └────────────────────┼────── runner
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
- `bsp` translates the standard Build Server Protocol into project, resolution, and compilation
  operations. It owns no editor-specific build logic.
- `cli` contains command parsing, compact presentation, project generation, and build orchestration.

Dependencies point inward toward `core`; infrastructure modules do not depend on the CLI. A future
multi-module planner can issue multiple compilation requests without changing resolver or compiler
interfaces.

## Local state and caching

Project outputs live below `.sprout/`: main classes, test classes, and metadata. Dependencies use
Coursier's established global artifact cache and are never copied into the project. Compilation
metadata is keyed from source contents, compiler version and options, and resolved classpath content.
An output is reusable only when the key matches and the output directory still exists.

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
