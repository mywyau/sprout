package sprout.cli

import cats.effect.IO
import sprout.core.*
import sprout.config.{ProjectConfig, ProjectConfigEditor}
import sprout.compiler.{CachingScalaCompiler, FileCache, ProcessScalaCompiler}
import sprout.dependencies.CoursierDependencyResolver
import sprout.runner.{JvmApplicationRunner, MainClassDiscovery, MUnitTestRunner}
import java.nio.file.{Files, Path}

final class BuildService:
  private val resolver: DependencyResolver[IO] = CoursierDependencyResolver()
  private val processCompiler: ScalaCompiler[IO] = ProcessScalaCompiler()
  private val applicationRunner: ApplicationRunner[IO] = JvmApplicationRunner()
  private val testRunner: TestRunner[IO] = MUnitTestRunner()

  def compile(from: Path): IO[CompilationResult] =
    load(from).flatMap { project =>
      for
        sources <- SourceDiscovery.scalaSources(project.layout.mainSources)
        _ <- IO.raiseWhen(sources.isEmpty)(
          SproutError.User("No Scala sources found under src/main/scala")
        )
        dependencies <- resolving(resolver.resolve(project.scalaVersion, project.mainDependencies))
        compilerClasspath <- resolver.compilerClasspath(project.scalaVersion)
        result <- compiler(project).compile(
          CompilationRequest(
            sources,
            dependencies.classpath.paths,
            compilerClasspath.paths,
            project.layout.mainClasses,
            project.scalaVersion
          )
        )
      yield result
    }

  def run(from: Path, arguments: List[String]): IO[Unit] =
    for
      project <- load(from)
      _ <- compile(from).flatMap(reportCompilation)
      dependencies <- resolver.resolve(project.scalaVersion, project.mainDependencies)
      classpath = project.layout.mainClasses ::
        (project.layout.mainResources ++ dependencies.classpath.paths)
      mainClass <- MainClassDiscovery.discover(project.layout.mainClasses, classpath)
      exit <- applicationRunner.run(mainClass, classpath, arguments)
      _ <- IO.raiseWhen(exit != 0)(SproutError.Process("Application", exit))
    yield ()

  def test(from: Path): IO[TestResult] =
    for
      project <- load(from)
      _ <- compile(from).flatMap(reportCompilation)
      sources <- SourceDiscovery.scalaSources(project.layout.testSources)
      _ <- IO.raiseWhen(sources.isEmpty)(
        SproutError.User("No Scala test sources found under src/test/scala")
      )
      dependencies <- resolving(resolver.resolve(project.scalaVersion, project.testDependencies))
      compilerClasspath <- resolver.compilerClasspath(project.scalaVersion)
      testClasspath = project.layout.mainClasses :: dependencies.classpath.paths
      _ <- compiler(project)
        .compile(
          CompilationRequest(
            sources,
            testClasspath,
            compilerClasspath.paths,
            project.layout.testClasses,
            project.scalaVersion
          )
        )
        .flatMap(reportCompilation)
      runtimeClasspath = project.layout.testClasses :: project.layout.mainClasses ::
        (project.layout.testResources ++ project.layout.mainResources ++ dependencies.classpath.paths)
      result <- testRunner.run(List(project.layout.testClasses), runtimeClasspath)
      _ <- IO.raiseWhen(result.failed > 0)(SproutError.User(s"${result.failed} test(s) failed"))
    yield result

  def clean(from: Path): IO[Unit] =
    load(from).flatMap(project => deleteTree(project.layout.buildDirectory))

  def add(from: Path, coordinate: String, scope: DependencyScope): IO[AddedDependency] =
    for
      config <- ProjectConfig.locate(from)
      project <- ProjectConfig.load(config)
      dependency <- IO.fromEither(
        Dependency.parse(coordinate, scope).left.map(SproutError.User.apply)
      )
      _ <- ProjectConfigEditor.validateAddition(config, dependency)
      candidateDependencies = scope match
        case DependencyScope.Main => project.mainDependencies :+ dependency
        case DependencyScope.Test => project.testDependencies :+ dependency
      classpath <- resolving(resolver.resolve(project.scalaVersion, candidateDependencies))
      name <- ProjectConfigEditor.add(config, dependency)
    yield AddedDependency(name, dependency, classpath.classpath.artifacts.size)

  def remove(from: Path, name: String, scope: DependencyScope): IO[Unit] =
    ProjectConfig.locate(from).flatMap(ProjectConfigEditor.remove(_, name, scope))

  def graph(from: Path): IO[String] =
    resolvedMainDependencies(from).map { case (project, dependencies) =>
      DependencyReport.graph(project.name.value, dependencies.graph)
    }

  def why(from: Path, name: String): IO[String] =
    resolvedMainDependencies(from).flatMap { case (project, dependencies) =>
      IO.fromEither(
        DependencyReport
          .why(project.name.value, dependencies.graph, name)
          .left
          .map(SproutError.User.apply)
      )
    }

  private def load(from: Path): IO[Project] = ProjectConfig.locate(from).flatMap(ProjectConfig.load)

  private def resolvedMainDependencies(from: Path): IO[(Project, ResolvedDependencies)] =
    for
      project <- load(from)
      dependencies <- resolving(resolver.resolve(project.scalaVersion, project.mainDependencies))
    yield (project, dependencies)

  private def compiler(project: Project): ScalaCompiler[IO] =
    CachingScalaCompiler(
      processCompiler,
      FileCache(project.layout.metadataDirectory.resolve("compile"))
    )

  private def resolving[A](action: IO[A]): IO[A] =
    IO.println("Resolving dependencies...") *> action

  private def reportCompilation(result: CompilationResult): IO[Unit] = result match
    case CompilationResult.Compiled(count) => IO.println(s"Compiled $count Scala source(s)")
    case CompilationResult.UpToDate        => IO.println("Sources unchanged")

  private def deleteTree(root: Path): IO[Unit] = IO.blocking {
    if Files.exists(root) then
      val stream = Files.walk(root)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      finally stream.close()
  }

final case class AddedDependency(name: String, dependency: Dependency, artifactCount: Int)
