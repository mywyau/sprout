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
sprout clean
```

`new` creates a conventional application and one MUnit test. Generated state stays under `.sprout/`;
`clean` removes only that directory and leaves Coursier's global artifact cache intact.

### VS Code and Metals

Install the Metals extension, then configure the current Sprout project once:

```bash
sprout setup-ide
```

This writes `.bsp/sprout.json`, which tells Metals how to start Sprout's build server. In VS Code,
run **Metals: Restart build server** from the command palette (or reload the window). Metals then
uses the Scala version, source roots, compiler options, and resolved dependency classpath from
`sprout.toml`; no `build.sbt` is needed. Re-run `sprout setup-ide` after moving or reinstalling the
Sprout executable because the connection file records its absolute launcher path.

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
| `sprout setup-ide` | Install the BSP connection used by Metals-compatible editors |

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
3. Installs the packaged archive and exercises `new`, `run`, `test`, and `clean`.
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
[performance](docs/performance.md), and [release process](docs/releasing.md) for design details and
current direction.

## Current limitations

Compilation caching currently skips an unchanged compilation by hashing source contents, Scala
version, compiler options, and classpath artifact identities. It is not incremental within a changed
compilation; Zinc is the planned backend for that. BSP currently covers editor import, dependency
sources, and compilation but not editor run, test, or debug requests. There is no lockfile, daemon,
package command, publishing, or multi-module support yet.
