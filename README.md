# Sprout

Sprout is a fast, simple, opinionated build tool for ordinary Scala projects. Its interface is meant
to feel closer to Cargo, npm, or uv than to a programmable build definition: put project data in
`sprout.toml`, use conventional directories, and run commands whose names are easy to guess.

Sprout currently targets Scala 3 JVM, Maven Central, and single-module application and library
projects. It delegates dependency resolution to Coursier and compilation to the Scala 3 compiler.

## Install

Sprout requires JDK 17 or newer, but users do not need Scala, Coursier, or sbt. Install with Homebrew:

```bash
brew install mywyau/tap/sprout
```

Alternatively, use the checksum-verifying installer:

```bash
curl -fsSL https://github.com/mywyau/sprout/releases/latest/download/install.sh | sh
```

The installer verifies the release archive's SHA-256 checksum, installs it below
`~/.local/share/sprout/versions/`, and points `~/.local/bin/sprout` at the selected version. If that
directory is not already on `PATH`, follow the command printed by the installer.

Install a specific version or custom destination with:

```bash
./install.sh --version 0.1.0
./install.sh --install-root /opt/sprout --bin-dir /usr/local/bin
```

Windows users can download the release ZIP and add its `bin` directory to `PATH`; `sprout.cmd` uses
`JAVA_HOME`, `SPROUT_JAVA_HOME`, or `java` from `PATH`.

## Build from source

Contributors need JDK 17 or newer and sbt 1.11.6 or newer:

```bash
sbt check cli/assembly
export PATH="$PWD/bin:$PATH"
sprout --help
```

## Hello world

```bash
sprout new hello
cd hello
sprout lock
sprout run
# Hello from Sprout!

sprout test
# Prints each test name; use `sprout test --quiet` for a summary only.
sprout package
sprout clean
```

`new` creates a conventional application and one MUnit test. `sprout lock` writes the dependency lock;
run it again after intentionally changing `sprout.toml`. Generated state stays under `.sprout/`.

## Package an application

Create a self-contained application distribution after choosing a single main class:

```bash
sprout package
.sprout/package/hello/bin/hello
```

The command writes a runnable directory, `.tar.gz` and `.zip` archives, and SHA-256 checksums below
`.sprout/package/`. The directory contains small Unix and Windows launchers, the application JAR,
ordered runtime dependency JARs, resources, and metadata. Test dependencies are excluded. A target
machine needs a compatible JRE, but it does not need Sprout, Scala, Coursier, or sbt.

See [application packaging](docs/packaging.md) for the layout and checksum commands.

## VS Code and Metals

Install the Metals extension, then configure the current Sprout project once:

```bash
sprout lock
sprout setup-ide
```

This writes `.bsp/sprout.json`, which tells Metals how to start Sprout's build server. In VS Code,
run **Metals: Restart build server** from the command palette (or reload the window). Metals then
uses the Scala version, source roots, compiler options, and resolved dependency classpath from
`sprout.toml`; no `build.sbt` is needed. Running `sprout setup-ide` again is safe: Sprout leaves a
current connection unchanged and atomically repairs one that is malformed or points to a different
Sprout version or launcher. Re-run it after moving, upgrading, or reinstalling Sprout.

## Dependencies

Add or remove dependencies without editing TOML by hand, then refresh the lock:

```bash
sprout add org.typelevel::cats-effect:3.6.3
sprout lock
sprout add --test org.scalameta::munit:1.1.1
sprout lock
```

`add` accepts ordinary Maven coordinates and Scala-aware `organisation::artifact:version`
coordinates. Sprout resolves an addition before atomically updating `sprout.toml`, so an unavailable
version leaves the configuration unchanged. Dependency names default to the artifact name.

Inspect the selected dependency graph or find every path to a transitive dependency:

```bash
sprout graph
sprout why cats-core
```

`graph` is deterministic, marks dependencies shared by multiple branches as repeated, and shows both
requested and selected versions when Coursier resolves a conflict. `why` prints every path from a
direct dependency to the requested module. Use `organisation:artifact` if a short artifact name is
ambiguous.

## Configuration and layout

Sprout projects use declarative TOML and never execute build code:

```toml
[project]
name = "hello"
scala = "3.3.6"

[dependencies]
cats-effect = "org.typelevel::cats-effect:3.6.3"

[test-dependencies]
munit = "org.scalameta::munit:1.1.1"
```

`organisation::artifact:version` is Scala-aware and resolves an artifact such as `cats-effect_3`.
Use one colon for an ordinary Maven artifact. Exact Scala versions are required so builds cannot
silently change underneath the project.

The default layout is:

```text
src/main/scala       src/main/resources
src/test/scala       src/test/resources
```

Resources are copied into `.sprout/classes` and `.sprout/test-classes` with the compiled output.
They are available to `run` and `test`, and main resources are included in application packages.

## Commands

| Command | Behaviour |
| --- | --- |
| `sprout --help` | Show concise command help |
| `sprout new NAME` | Generate an application and MUnit test |
| `sprout compile` | Resolve and compile main sources |
| `sprout run [ARGS]` | Compile, detect one main class, and run it |
| `sprout test [--quiet] [SUITE_OR_FILE]` | Compile and run MUnit or ScalaTest suites; use `--quiet` for summary-only output |
| `sprout package` | Create a runnable application directory, archives, and checksums |
| `sprout clean` | Delete project-local `.sprout/` state |
| `sprout add [--test] COORDINATE` | Resolve and add a main or test dependency |
| `sprout remove [--test] NAME` | Remove a main or test dependency |
| `sprout graph` | Show the resolved main dependency tree |
| `sprout why NAME` | Show every path introducing a main dependency |
| `sprout lock` | Resolve dependencies and update `sprout.lock` |
| `sprout doctor` | Diagnose JDK, lockfile, cache, permissions, project layout, and BSP setup |
| `sprout setup-ide` | Install the BSP connection used by Metals-compatible editors |

Pass `--debug` with a command to include stack traces for unexpected failures. Normal configuration,
resolution, and compilation failures remain compact and actionable.

## Limits and details

Compilation caching skips an unchanged compilation using content fingerprints for sources, ordered
dependency and compiler classpaths, compiler bridge, Scala version, compiler options, JVM target, and
compiled output. Changed builds use Zinc analysis to recompile affected sources. Analysis is
versioned, written atomically, and safely rebuilt when missing or corrupt.
`sprout lock` creates a deterministic `sprout.lock`, recording the selected main and test dependency
graphs and SHA-256 digests of their artifacts. Builds and BSP compilation require that lock and verify
the resolved graph and artifact bytes against it; rerun `sprout lock` after intentionally changing
`sprout.toml`.
Dependency diagnostics currently cover the main scope; test-scope graph queries are not yet exposed.
BSP currently covers editor import, dependency sources, and compilation but not editor run, test, or
debug requests. There is no daemon, library publishing, container-image creation, or multi-module
support yet.

Sprout deliberately does not support Scala 2, Scala.js, Scala Native, custom tasks, plugins,
cross-building, publishing, or remote caches.

See the [architecture](docs/architecture.md), [roadmap](docs/roadmap.md),
[performance notes](docs/performance.md), [packaging guide](docs/packaging.md), and
[release guide](docs/releasing.md) for contributor and maintainer details.
