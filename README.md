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

Upgrade a Homebrew installation after a new release with:

```bash
brew update
brew upgrade sprout
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
# Prints a compact summary; use `sprout test --verbose` for each test name.
sprout package
sprout clean
```

`new` creates a conventional application and one MUnit test. Generated state stays under `.sprout/`;
`clean` removes only that directory and leaves Coursier's global artifact cache intact.

### Package for production

Create a self-contained application distribution after choosing a single main class:

```bash
sprout package
.sprout/package/hello/bin/hello
```

The command writes a runnable directory, `.tar.gz` and `.zip` archives, and SHA-256 checksums below
`.sprout/package/`. The directory contains small Unix and Windows launchers, the application JAR,
ordered runtime dependency JARs, resources, and metadata. Test dependencies are excluded. A target
machine needs a compatible JRE, but it does not need Sprout, Scala, Coursier, or sbt.

For example, after running `sprout package`, a minimal container image can copy the unpacked
distribution directly:

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /opt/hello
COPY .sprout/package/hello/ ./
ENTRYPOINT ["./bin/hello"]
```

Sprout deliberately produces deployment inputs rather than building or publishing container images.
See [application packaging](docs/packaging.md) for the layout and checksum commands.

### VS Code and Metals

Install the Metals extension, then configure the current Sprout project once:

```bash
sprout setup-ide
```

This writes `.bsp/sprout.json`, which tells Metals how to start Sprout's build server. In VS Code,
run **Metals: Restart build server** from the command palette (or reload the window). Metals then
uses the Scala version, source roots, compiler options, and resolved dependency classpath from
`sprout.toml`; no `build.sbt` is needed. Running `sprout setup-ide` again is safe: Sprout leaves a
current connection unchanged and atomically repairs one that is malformed or points to a different
Sprout version or launcher. Re-run it after moving, upgrading, or reinstalling Sprout.

## Try Sprout locally

Create a disposable project outside the Sprout repository and exercise the installed command exactly
as a user would:

```bash
workdir="$(mktemp -d)"
cd "$workdir"

sprout --version
sprout new hello
cd hello

sprout compile  # cold compilation
sprout compile  # should report nothing to build
sprout run
sprout test
sprout test --verbose
sprout test MainSuite
sprout test src/test/scala/MainSuite.scala
sprout clean
test ! -e .sprout
```

Next, test real dependency resolution. Add this to `sprout.toml`:

```toml
[dependencies]
cats-effect = "org.typelevel::cats-effect:3.6.3"
```

Replace `src/main/scala/Main.scala` with:

```scala
import cats.effect.{IO, IOApp}

object Main extends IOApp.Simple:
  def run: IO[Unit] = IO.println("Cats Effect resolved by Sprout")
```

Run `sprout run` twice. The first run should resolve and compile; the second should reuse downloaded
artifacts and unchanged classes. Also introduce a deliberate type error and confirm that Sprout keeps
the compiler's file and line information without printing an internal stack trace. Use `--debug` to
verify that deeper diagnostics remain available when requested.

Add dependencies without editing TOML by hand:

```bash
sprout add org.typelevel::cats-effect:3.6.3
sprout remove cats-effect
sprout remove --test munit
sprout add --test org.scalameta::munit:1.1.1
```

`add` accepts ordinary Maven coordinates and Scala-aware `organisation::artifact:version`
coordinates. Sprout resolves an addition before atomically updating `sprout.toml`, so an unavailable
version leaves the configuration unchanged. Dependency names default to the artifact name.

Inspect the selected dependency graph and find everything that introduces a transitive dependency:

```bash
sprout graph
sprout why cats-core
```

`graph` is deterministic, marks dependencies shared by multiple branches as repeated, and shows both
requested and selected versions when Coursier resolves a conflict. `why` prints every path from a
direct dependency to the requested module. Use `organisation:artifact` if a short artifact name is
ambiguous.

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

Resources are copied into `.sprout/classes` and `.sprout/test-classes` with the compiled output.
They are therefore available through the normal JVM classpath to `run` and `test`, and main resources
are included in the application JAR produced by `sprout package`.

## Commands

| Command | Behaviour |
| --- | --- |
| `sprout --help` | Show concise command help |
| `sprout new NAME` | Generate an application and MUnit test |
| `sprout compile` | Resolve and compile main sources |
| `sprout run [ARGS]` | Compile, detect one main class, and run it |
| `sprout test [--verbose] [SUITE_OR_FILE]` | Compile tests and run all or one MUnit suite; use `--verbose` for per-test output |
| `sprout package` | Create a runnable application directory, archives, and checksums |
| `sprout clean` | Delete project-local `.sprout/` state |
| `sprout add [--test] COORDINATE` | Resolve and add a main or test dependency |
| `sprout remove [--test] NAME` | Remove a main or test dependency |
| `sprout graph` | Show the resolved main dependency tree |
| `sprout why NAME` | Show every path introducing a main dependency |
| `sprout setup-ide` | Install the BSP connection used by Metals-compatible editors |

Pass `--debug` with a command to include stack traces for unexpected failures. Normal configuration,
resolution, and compilation failures remain compact and actionable.

## Development

```bash
sbt test
sbt check
```

Real integration fixtures cover a basic app, Coursier dependency resolution, Zinc single-source
recompilation and corrupt-analysis recovery, a compiler error, and an MUnit project. CI additionally
installs a packaged Sprout archive into a temporary location and
exercises `new`, dependency editing and diagnostics, BSP setup and compilation, SemanticDB
generation, `run`, `test`, application packaging, packaged execution, checksum verification, and
`clean`.
`benchmarks/measure.sh` provides coarse startup, cold, warm, no-change, and single-file-change
measurements intended for tracking trends, not claims.

## Maintainer release process

Normal releases are driven by semantic version tags. The version is injected into the assembled jar
from the tag, so `build.sbt` does not need a manual release-version edit.

Before tagging, test the intended commit and push it to `main`:

```bash
sbt check
git push origin main
```

Wait for the `CI` workflow to pass, then create a new tag. Never reuse or move a published tag:

```bash
git tag v0.1.2
git push origin v0.1.2
```

The `Release` workflow then:

1. Tests and assembles Sprout with the tag-derived version.
2. Creates archives, checksums, the installer, and a Homebrew formula.
3. Installs the packaged archive and exercises BSP setup/import/compilation, `new`, `run`, `test`,
   application packaging, packaged execution, checksum verification, and `clean`.
4. Publishes or refreshes the GitHub release assets.
5. Commits the formula to `mywyau/homebrew-tap` when it changed.

Watch and verify the release with:

```bash
gh run watch --repo mywyau/sprout
brew update
brew upgrade sprout
sprout --version
brew info mywyau/tap/sprout
```

Homebrew publication requires the `HOMEBREW_TAP_TOKEN` Actions secret. It must be a fine-grained token
limited to `mywyau/homebrew-tap` with `Contents: read and write` and `Metadata: read`. Rotate it before
expiry and update the secret without committing its value:

```bash
gh secret set HOMEBREW_TAP_TOKEN --repo mywyau/sprout
```

The release workflow is safe to rerun after a partial failure: existing assets are replaced and an
unchanged Homebrew formula does not produce an empty commit. See the full
[release guide](docs/releasing.md) for details.

See [architecture](docs/architecture.md), [roadmap](docs/roadmap.md),
[performance](docs/performance.md), [application packaging](docs/packaging.md), and
[release process](docs/releasing.md) for design details and current direction.

## Current limitations

Compilation caching skips an unchanged compilation using content fingerprints for sources, ordered
dependency and compiler classpaths, compiler bridge, Scala version, compiler options, JVM target, and
compiled output. Changed builds use Zinc analysis to recompile affected sources. Analysis is
versioned, written atomically, and safely rebuilt when missing or corrupt.
Dependency diagnostics currently cover the main scope; test-scope graph queries are not yet exposed.
BSP currently covers editor import, dependency sources, and compilation but not editor run, test, or
debug requests. There is no lockfile, daemon, library publishing, container-image creation, or
multi-module support yet.
