package sprout.bsp

import cats.effect.IO
import cats.syntax.all.*
import sprout.compiler.{CachingScalaCompiler, FileCache, ProcessScalaCompiler}
import sprout.config.ProjectConfig
import sprout.core.*
import sprout.dependencies.CoursierDependencyResolver
import java.nio.file.{Files, Path}

private[bsp] enum TargetKind:
  case Main, Test

private[bsp] final case class TargetClasspath(
    project: Project,
    kind: TargetKind,
    dependencies: List[Path],
    compiler: List[Path]
):
  def outputDirectory: Path = kind match
    case TargetKind.Main => project.layout.mainClasses
    case TargetKind.Test => project.layout.testClasses

  def compileClasspath: List[Path] = kind match
    case TargetKind.Main => dependencies
    case TargetKind.Test => project.layout.mainClasses :: dependencies

private[bsp] final class BspWorkspace(root: Path):
  private val resolver = CoursierDependencyResolver()
  private val processCompiler = ProcessScalaCompiler(captureOutput = true)

  def project: IO[Project] = ProjectConfig.locate(root).flatMap(ProjectConfig.load)

  def classpath(kind: TargetKind): IO[TargetClasspath] = project.flatMap { value =>
    val dependencies = kind match
      case TargetKind.Main => value.mainDependencies
      case TargetKind.Test => value.testDependencies
    (
      resolver.resolve(value.scalaVersion, dependencies),
      resolver.compilerClasspath(value.scalaVersion)
    )
      .parMapN((resolved, compiler) =>
        TargetClasspath(value, kind, resolved.classpath.paths, compiler.paths)
      )
  }

  def dependencySources(kind: TargetKind): IO[List[Path]] = project.flatMap { value =>
    val dependencies = kind match
      case TargetKind.Main => value.mainDependencies
      case TargetKind.Test => value.testDependencies
    resolver.resolveSources(value.scalaVersion, dependencies).map(_.paths)
  }

  def compile(kind: TargetKind): IO[CompilationResult] = classpath(kind).flatMap { target =>
    val sourceDirectories = kind match
      case TargetKind.Main => target.project.layout.mainSources
      case TargetKind.Test => target.project.layout.testSources
    SourceDiscovery.scalaSources(sourceDirectories).flatMap { sources =>
      if sources.isEmpty then IO.pure(CompilationResult.UpToDate)
      else
        compiler(target.project, kind).compile(
          CompilationRequest(
            sources,
            target.compileClasspath,
            target.compiler,
            target.outputDirectory,
            target.project.scalaVersion,
            semanticdbOptions(target.project)
          )
        )
    }
  }

  def compileWithDependencies(kind: TargetKind): IO[CompilationResult] = kind match
    case TargetKind.Main => compile(TargetKind.Main)
    case TargetKind.Test => compile(TargetKind.Main) *> compile(TargetKind.Test)

  def clean: IO[Unit] = project.flatMap(value => deleteTree(value.layout.buildDirectory))

  def semanticdbOptions(project: Project): List[String] =
    List("-Xsemanticdb", "-sourceroot", project.layout.root.toString, "-color:never")

  def sourceFiles(kind: TargetKind): IO[List[Path]] = project.flatMap { value =>
    val directories = kind match
      case TargetKind.Main => value.layout.mainSources
      case TargetKind.Test => value.layout.testSources
    SourceDiscovery.scalaSources(directories)
  }

  private def compiler(project: Project, kind: TargetKind): ScalaCompiler[IO] =
    val cacheName = if kind == TargetKind.Main then "compile" else "test-compile"
    CachingScalaCompiler(
      processCompiler,
      FileCache(project.layout.metadataDirectory.resolve(cacheName))
    )

  private def deleteTree(directory: Path): IO[Unit] = IO.blocking {
    if Files.exists(directory) then
      val stream = Files.walk(directory)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      finally stream.close()
  }
