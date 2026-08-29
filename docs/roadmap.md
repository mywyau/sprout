# Roadmap

The roadmap describes ordering and scope, not release dates or promises. Each stage should preserve a
working ordinary Scala build before expanding the surface area.

## v0.1

- Scala 3 on the JVM
- Declarative single-module projects
- `new`, `compile`, `run`, `test`, and `clean`
- Maven Central dependencies through Coursier
- MUnit test execution

## v0.2

- Initial BSP and Metals project import

## v0.2.1

- Stable Homebrew launcher paths for BSP
- Stale BSP connection detection and atomic repair
- Installed-distribution BSP and SemanticDB release tests
- Positioned compiler diagnostics through BSP

## Next

- Stronger local metadata caching
- Dependency `add` and `remove`
- Zinc incremental compilation

## v0.3

- Richer BSP run, test, and debug integration
- Multi-module builds represented as a DAG

## v0.4

- Packaging and publishing
- Dependency graph, provenance, and conflict diagnostics

## v1.0

- Stable configuration format
- Dependency lockfile
- Reproducible builds

Candidate work such as a local daemon, `doctor`, `update`, `outdated`, and external scripts will be
evaluated against the product principle: ordinary builds should remain boring, predictable, and fast.
