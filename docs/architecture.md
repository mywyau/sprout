# Architecture

Sprout is a small orchestration layer over established Scala and JVM tools. The command line maps a
declarative project description into explicit requests to dependency resolution, compilation, and
execution boundaries. It is not a general task engine.

```text
cli ──> config ──> core <── dependencies
 │                  ▲       (Coursier)
 └──────────────────┼────── compiler
                    └────── runner
```

## Modules

- `core` owns immutable domain values, errors, project layout, source discovery, hashing, and cache
  contracts. It has no knowledge of TOML, terminals, or CLI parsing.
- `config` parses `sprout.toml` into the core model. Configuration is data only.
- `dependencies` implements the resolver boundary with Coursier and retains resolved artifacts as a
  model that can later expose dependency provenance to `graph` and `why`.
- `compiler` owns compilation requests and the compiler boundary. The first backend starts Dotty in
  an isolated JVM; callers do not depend on that choice.
- `runner` starts JVM applications and provides a test-framework boundary. MUnit is the first adapter.
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

## Future daemon and BSP

A daemon can sit in front of the same application service and retain resolver/compiler resources in a
warm JVM. Local IPC is a transport concern: normal one-shot CLI execution remains supported. A BSP
adapter likewise translates protocol requests into build plans and compilation requests rather than
embedding build logic in the protocol layer.
