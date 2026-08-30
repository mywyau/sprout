package sprout.cli

import cats.effect.IO
import cats.syntax.all.*
import sprout.core.*
import sprout.config.{ProjectConfig, ProjectConfigEditor}
import sprout.compiler.{CachingScalaCompiler, FileCache, ZincScalaCompiler}
import sprout.dependencies.CoursierDependencyResolver
import sprout.packager.{ApplicationPackageRequest, ApplicationPackager, ApplicationPackageResult}
import sprout.runner.{JvmApplicationRunner, MainClassDiscovery, MUnitTestRunner}
import java.nio.file.{Files, Path}

final class BuildService(
    resolver: DependencyResolver[IO] = CoursierDependencyResolver(),
    scalaCompiler: ScalaCompiler[IO] = ZincScalaCompiler(),
    applicationRunner: ApplicationRunner[IO] = JvmApplicationRunner(),
    testRunner: TestRunner[IO] = MUnitTestRunner()
):
  private lazy val applicationPackager = ApplicationPackager()

  def compile(from: Path): IO[CompilationResult] =
    mainSession(from).flatMap(compileMain)

  def run(from: Path, arguments: List[String]): IO[Unit] =
    for
      session <- mainSession(from)
      _ <- compileMain(session).flatMap(reportCompilation)
      mainClass <- MainClassDiscovery.discover(
        session.project.layout.mainClasses,
        session.mainRuntimeClasspath
      )
      exit <- applicationRunner.run(mainClass, session.mainRuntimeClasspath, arguments)
      _ <- IO.raiseWhen(exit != 0)(SproutError.Process("Application", exit))
    yield ()

  def test(from: Path): IO[TestResult] =
    for
      session <- sessionWithTests(from)
      testBuild <- IO.fromOption(session.test)(
        IllegalStateException("test build session was not initialised")
      )
      _ <- compileMain(session).flatMap(reportCompilation)
      sources <- SourceDiscovery.scalaSources(session.project.layout.testSources)
      _ <- IO.raiseWhen(sources.isEmpty)(
        SproutError.User("No Scala test sources found under src/test/scala")
      )
      _ <- compiler(session.project, "test-compile")
        .compile(
          CompilationRequest(
            sources,
            testBuild.compileClasspath,
            session.compilerClasspath.paths,
            session.project.layout.testClasses,
            session.project.scalaVersion,
            incremental = Some(
              IncrementalCompilation(
                session.compilerBridge,
                session.project.layout.zincDirectory("test-compile")
              )
            )
          )
        )
        .flatMap(reportCompilation)
      result <- testRunner.run(
        List(session.project.layout.testClasses),
        testBuild.runtimeClasspath
      )
      _ <- IO.raiseWhen(result.failed > 0)(SproutError.User(s"${result.failed} test(s) failed"))
    yield result

  def packageApplication(from: Path): IO[ApplicationPackageResult] =
    for
      session <- mainSession(from)
      _ <- compileMain(session).flatMap(reportCompilation)
      mainClass <- MainClassDiscovery.discover(
        session.project.layout.mainClasses,
        session.mainRuntimeClasspath
      )
      result <- applicationPackager.create(
        ApplicationPackageRequest(
          session.project.name.value,
          session.project.scalaVersion.value,
          mainClass,
          session.project.layout.mainClasses,
          session.project.layout.mainResources,
          session.mainDependencies.classpath.paths,
          session.project.layout.packageDirectory
        )
      )
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

  private def mainSession(from: Path): IO[BuildSession] =
    load(from).flatMap { project =>
      resolving(
        (
          resolver.resolve(project.scalaVersion, project.mainDependencies),
          resolver.compilerClasspath(project.scalaVersion),
          resolver.compilerBridge(project.scalaVersion)
        ).parMapN((dependencies, compiler, bridge) =>
          BuildSession.main(project, dependencies, compiler, bridge)
        )
      )
    }

  private def sessionWithTests(from: Path): IO[BuildSession] =
    load(from).flatMap { project =>
      resolving(
        (
          resolver.resolve(project.scalaVersion, project.mainDependencies),
          resolver.resolve(project.scalaVersion, project.testDependencies),
          resolver.compilerClasspath(project.scalaVersion),
          resolver.compilerBridge(project.scalaVersion)
        ).parMapN((main, test, compiler, bridge) =>
          BuildSession.withTests(project, main, test, compiler, bridge)
        )
      )
    }

  private def compileMain(session: BuildSession): IO[CompilationResult] =
    for
      sources <- SourceDiscovery.scalaSources(session.project.layout.mainSources)
      _ <- IO.raiseWhen(sources.isEmpty)(
        SproutError.User("No Scala sources found under src/main/scala")
      )
      result <- compiler(session.project, "compile").compile(
        CompilationRequest(
          sources,
          session.mainCompileClasspath,
          session.compilerClasspath.paths,
          session.project.layout.mainClasses,
          session.project.scalaVersion,
          incremental = Some(
            IncrementalCompilation(
              session.compilerBridge,
              session.project.layout.zincDirectory("compile")
            )
          )
        )
      )
    yield result

  private def compiler(project: Project, cacheName: String): ScalaCompiler[IO] =
    CachingScalaCompiler(
      scalaCompiler,
      FileCache(project.layout.metadataDirectory.resolve(cacheName))
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
