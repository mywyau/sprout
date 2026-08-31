package sprout.cli

import cats.effect.IO
import cats.syntax.all.*
import sprout.core.*
import sprout.config.{Lockfile, ProjectConfig, ProjectConfigEditor}
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
      request <- mainCompilationRequest(session)
      _ <- compileMain(session, request).flatMap(reportCompilation)
      mainClass <- cachedMainClass(session, request)
      exit <- applicationRunner.run(mainClass, session.mainRuntimeClasspath, arguments)
      _ <- IO.raiseWhen(exit != 0)(SproutError.Process("Application", exit))
    yield ()

  def test(
      from: Path,
      selector: Option[String] = None,
      verbose: Boolean = true
  ): IO[TestResult] =
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
      selection <- testSelection(selector, session.project)
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
            ),
            resourceDirectories = session.project.layout.testResources,
            resourceStateDirectory =
              Some(session.project.layout.resourceStateDirectory("test-compile"))
          )
        )
        .flatMap(reportCompilation)
      result <- testRunner.run(
        List(session.project.layout.testClasses),
        testBuild.runtimeClasspath,
        selection,
        if verbose then TestOutput.Verbose else TestOutput.Compact
      )
      _ <- IO.raiseWhen(result.failed > 0)(SproutError.User(s"${result.failed} test(s) failed"))
    yield result

  def packageApplication(from: Path): IO[ApplicationPackageResult] =
    for
      session <- mainSession(from)
      request <- mainCompilationRequest(session)
      _ <- compileMain(session, request).flatMap(reportCompilation)
      mainClass <- cachedMainClass(session, request)
      result <- applicationPackager.create(
        ApplicationPackageRequest(
          session.project.name.value,
          session.project.scalaVersion.value,
          mainClass,
          session.project.layout.mainClasses,
          Nil,
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

  def lock(from: Path): IO[Unit] =
    for
      project <- load(from)
      main <- resolving(resolver.resolve(project.scalaVersion, project.mainDependencies))
      test <- resolver.resolve(project.scalaVersion, project.testDependencies)
      _ <- Lockfile.write(project, main, test)
    yield ()

  private def load(from: Path): IO[Project] = ProjectConfig.locate(from).flatMap(ProjectConfig.load)

  private def resolvedMainDependencies(from: Path): IO[(Project, ResolvedDependencies)] =
    for
      project <- load(from)
      cached <- metadata(project).dependencies(project.scalaVersion, project.mainDependencies)(
        resolver.resolve(project.scalaVersion, project.mainDependencies)
      )
      _ <- reportResolution(cached.cached)
      dependencies = cached.value
    yield (project, dependencies)

  private def mainSession(from: Path): IO[BuildSession] =
    load(from).flatMap { project =>
      val cache = metadata(project)
      Lockfile.require(project).flatTap(Lockfile.verifyInput(project, _)).flatMap { lock =>
        (
        cache.dependencies(project.scalaVersion, project.mainDependencies)(
          resolver.resolveLocked(project.scalaVersion, project.mainDependencies, Lockfile.mainModules(lock))
        ),
        cache.compilerClasspath(project.scalaVersion)(
          resolver.compilerClasspath(project.scalaVersion)
        ),
        cache.compilerBridge(project.scalaVersion)(resolver.compilerBridge(project.scalaVersion))
      ).parMapN { (dependencies, compiler, bridge) =>
        (
          BuildSession.main(project, dependencies.value, compiler.value, bridge.value),
          List(dependencies.cached, compiler.cached, bridge.cached).forall(identity)
        )
        ).flatMap { case (session, cached) =>
        Lockfile
          .require(project)
          .flatMap(Lockfile.verifyMain(project, session.mainDependencies, _)) *>
          reportResolution(cached).as(session)
        }
      }
    }

  private def sessionWithTests(from: Path): IO[BuildSession] =
    load(from).flatMap { project =>
      val cache = metadata(project)
      Lockfile.require(project).flatTap(Lockfile.verifyInput(project, _)).flatMap { lock =>
        (
        cache.dependencies(project.scalaVersion, project.mainDependencies)(
          resolver.resolveLocked(project.scalaVersion, project.mainDependencies, Lockfile.mainModules(lock))
        ),
        cache.dependencies(project.scalaVersion, project.testDependencies)(
          resolver.resolveLocked(project.scalaVersion, project.testDependencies, Lockfile.testModules(lock))
        ),
        cache.compilerClasspath(project.scalaVersion)(
          resolver.compilerClasspath(project.scalaVersion)
        ),
        cache.compilerBridge(project.scalaVersion)(resolver.compilerBridge(project.scalaVersion))
      ).parMapN { (main, test, compiler, bridge) =>
        (
          BuildSession.withTests(project, main.value, test.value, compiler.value, bridge.value),
          List(main.cached, test.cached, compiler.cached, bridge.cached).forall(identity),
          main.value,
          test.value
        )
        ).flatMap { case (session, cached, mainDependencies, testDependencies) =>
        Lockfile
          .require(project)
          .flatMap(Lockfile.verify(project, mainDependencies, testDependencies, _)) *>
          reportResolution(cached).as(session)
        }
      }
    }

  private def compileMain(session: BuildSession): IO[CompilationResult] =
    mainCompilationRequest(session).flatMap(compileMain(session, _))

  private def mainCompilationRequest(session: BuildSession): IO[CompilationRequest] =
    for
      sources <- SourceDiscovery.scalaSources(session.project.layout.mainSources)
      _ <- IO.raiseWhen(sources.isEmpty)(
        SproutError.User("No Scala sources found under src/main/scala")
      )
    yield CompilationRequest(
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
      ),
      resourceDirectories = session.project.layout.mainResources,
      resourceStateDirectory = Some(session.project.layout.resourceStateDirectory("compile"))
    )

  private def compileMain(
      session: BuildSession,
      request: CompilationRequest
  ): IO[CompilationResult] =
    compiler(session.project, "compile").compile(request)

  private def cachedMainClass(session: BuildSession, request: CompilationRequest): IO[String] =
    Hashing.compilationKey(request).flatMap { key =>
      metadata(session.project)
        .mainClass(key, session.project.layout.mainClasses)(
          MainClassDiscovery.discover(
            session.project.layout.mainClasses,
            session.mainRuntimeClasspath
          )
        )
        .map(_.value)
    }

  private def compiler(project: Project, cacheName: String): ScalaCompiler[IO] =
    CachingScalaCompiler(
      scalaCompiler,
      FileCache(project.layout.metadataDirectory.resolve(cacheName))
    )

  private def testSelection(selector: Option[String], project: Project): IO[TestSelection] =
    selector match
      case None                              => IO.pure(TestSelection.All)
      case Some(value) if value.trim.isEmpty =>
        IO.raiseError(SproutError.User("Test suite or source file must not be empty"))
      case Some(value) if value.endsWith(".scala") =>
        IO.blocking {
          val requested = Path.of(value)
          val source =
            if requested.isAbsolute then requested.normalize
            else project.layout.root.resolve(requested).normalize
          val sourceRoot = project.layout.testSources
            .map(_.toAbsolutePath.normalize)
            .find(source.startsWith)
            .getOrElse(
              throw SproutError.User(
                s"Test source '$value' must be under src/test/scala"
              )
            )
          if !Files.isRegularFile(source) then
            throw SproutError.User(s"Test source file not found: $value")
          val relative = sourceRoot.relativize(source).toString.stripSuffix(".scala")
          TestSelection.Suite(relative.replace(java.io.File.separatorChar, '.'))
        }
      case Some(value) => IO.pure(TestSelection.Suite(value.trim))

  private def resolving[A](action: IO[A]): IO[A] =
    IO.println("Resolving dependencies...") *> action

  private def metadata(project: Project): BuildMetadataCache =
    BuildMetadataCache(project.layout.metadataDirectory)

  private def reportResolution(cached: Boolean): IO[Unit] =
    IO.println(if cached then "Dependencies cached" else "Resolving dependencies...")

  private def reportCompilation(result: CompilationResult): IO[Unit] = result match
    case CompilationResult.Compiled(count)         => IO.println(s"Compiled $count Scala source(s)")
    case CompilationResult.UpToDate                => IO.println("Sources unchanged")
    case CompilationResult.ResourcesUpdated(count) => IO.println(s"Updated $count resource(s)")

  private def deleteTree(root: Path): IO[Unit] = IO.blocking {
    if Files.exists(root) then
      val stream = Files.walk(root)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      finally stream.close()
  }

final case class AddedDependency(name: String, dependency: Dependency, artifactCount: Int)
