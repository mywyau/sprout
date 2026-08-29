# Sprout

Sprout is a fast, simple, opinionated build tool for ordinary Scala projects. Its interface is meant
to feel closer to Cargo, npm, or uv than to a programmable build definition: put project data in
`sprout.toml`, use conventional directories, and run commands whose names are easy to guess.

Sprout currently targets Scala 3 JVM, Maven Central, and single-module application and library
projects. It delegates dependency resolution to Coursier and compilation to the Scala 3 compiler.

## What Sprout is not

Sprout is not an sbt replacement for every build, a task DSL, a compiler, a dependency resolver, or a
universal JVM build system. The initial release deliberately excludes Scala 2, Scala.js, Scala Native,
plugins, custom tasks, multi-module builds, cross-building, publishing, and remote caches.

## Install

Sprout requires JDK 17 or newer, but users do not need Scala, Coursier, or sbt. After the first GitHub
release is published, install the latest version with:

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

Each release also includes a generated `sprout.rb` formula. Until a dedicated Homebrew tap repository
is created, it can be installed directly after downloading it:

```bash
brew install ./sprout.rb
```

To uninstall the script-based installation, remove `~/.local/bin/sprout` and
`~/.local/share/sprout`. Coursier's dependency cache is independent and is deliberately retained.

## Build from source

Contributors need JDK 17 or newer and sbt 1.11.6 or newer:

```bash
sbt check cli/assembly
export PATH="$PWD/bin:$PATH"
sprout --help
```

Create the same archives published by CI with:

```bash
scripts/package-release.sh 0.1.0-SNAPSHOT
scripts/test-distribution.sh 0.1.0-SNAPSHOT target/release
```

## Hello world

```bash
sprout new hello
cd hello
sprout run
# Hello from Sprout!

sprout test
sprout clean
```

`new` creates a conventional application and one MUnit test. Generated state stays under `.sprout/`;
`clean` removes only that directory and leaves Coursier's global artifact cache intact.

## Configuration

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

## Commands

| Command | Behaviour |
| --- | --- |
| `sprout --help` | Show concise command help |
| `sprout new NAME` | Generate an application and MUnit test |
| `sprout compile` | Resolve and compile main sources |
| `sprout run [ARGS]` | Compile, detect one main class, and run it |
| `sprout test` | Compile and run MUnit suites |
| `sprout clean` | Delete project-local `.sprout/` state |

Pass `--debug` with a command to include stack traces for unexpected failures. Normal configuration,
resolution, and compilation failures remain compact and actionable.

## Development

```bash
sbt test
sbt check
```

Real integration fixtures cover a basic app, Coursier dependency resolution, a compiler error, and an
MUnit project. CI additionally installs a packaged archive into a temporary location and exercises
`new`, `run`, `test`, and `clean`. `benchmarks/measure.sh` provides coarse startup/cold/no-change
measurements intended for tracking trends, not claims.

See [architecture](docs/architecture.md), [roadmap](docs/roadmap.md),
[performance](docs/performance.md), and [release process](docs/releasing.md) for design details and
current direction.

## Current limitations

Compilation caching currently skips an unchanged compilation by hashing source contents, Scala
version, compiler options, and classpath artifact identities. It is not incremental within a changed
compilation; Zinc is the planned backend for that. There is no lockfile, BSP server, daemon, package
command, publishing, or multi-module support yet.
