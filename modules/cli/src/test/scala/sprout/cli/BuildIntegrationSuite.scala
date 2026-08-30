package sprout.cli

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import sprout.core.*
import sprout.dependencies.CoursierDependencyResolver
import java.nio.file.{Files, Path, StandardCopyOption}
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

class BuildIntegrationSuite extends munit.FunSuite:
  private val service = BuildService()

  test("compiles a hello-world fixture and reuses unchanged output") {
    val project = copyFixture("hello-world")
    val first = service.compile(project).unsafeRunSync()
    assert(first.isInstanceOf[CompilationResult.Compiled])
    assert(Files.isRegularFile(project.resolve(".sprout/classes/Main.class")))
    assertEquals(service.compile(project).unsafeRunSync(), CompilationResult.UpToDate)
  }

  test("Zinc recompiles a changed source without rebuilding unrelated sources") {
    val project = copyFixture("incremental-app")
    val first = service.compile(project).unsafeRunSync()
    assertEquals(first, CompilationResult.Compiled(3))

    val unrelatedClass = project.resolve(".sprout/classes/Unrelated$.class")
    val preservedTimestamp = FileTime.fromMillis(1234567890000L)
    Files.setLastModifiedTime(unrelatedClass, preservedTimestamp)
    Files.writeString(
      project.resolve("src/main/scala/Greeting.scala"),
      """object Greeting:
        |  def message: String = "Hello after a small change"
        |""".stripMargin
    )

    assertEquals(service.compile(project).unsafeRunSync(), CompilationResult.Compiled(1))
    assertEquals(Files.getLastModifiedTime(unrelatedClass), preservedTimestamp)

    Files.delete(project.resolve("src/main/scala/Unrelated.scala"))
    service.compile(project).unsafeRunSync()
    assert(!Files.exists(unrelatedClass), "Zinc should remove products for deleted sources")
  }

  test("recovers from corrupt Zinc analysis with a safe full compilation") {
    val project = copyFixture("incremental-app")
    service.compile(project).unsafeRunSync()
    val analysis = project.resolve(".sprout/metadata/zinc/v1/compile/analysis.zip")
    Files.writeString(analysis, "corrupt analysis")
    Files.writeString(
      project.resolve("src/main/scala/Greeting.scala"),
      """object Greeting:
        |  def message: String = "Recovered"
        |""".stripMargin
    )

    assertEquals(service.compile(project).unsafeRunSync(), CompilationResult.Compiled(3))
    assertEquals(Files.readAllBytes(analysis).take(2).toList, List[Byte](0x50, 0x4b))
    val stream = Files.list(analysis.getParent)
    try assert(!stream.iterator.asScala.exists(_.getFileName.toString.endsWith(".tmp")))
    finally stream.close()

    val analysisBeforeFailure = Files.readAllBytes(analysis).toList
    Files.writeString(
      project.resolve("src/main/scala/Greeting.scala"),
      """object Greeting:
        |  val message: Int = "broken"
        |""".stripMargin
    )
    intercept[SproutError.Compilation](service.compile(project).unsafeRunSync())
    assertEquals(Files.readAllBytes(analysis).toList, analysisBeforeFailure)
  }

  test("reports a real Scala compile failure") {
    val project = copyFixture("compile-error")
    intercept[SproutError.Compilation](service.compile(project).unsafeRunSync())
  }

  test("resolves and compiles a Scala cross-version dependency") {
    val project = copyFixture("cats-effect-app")
    assert(service.compile(project).unsafeRunSync().isInstanceOf[CompilationResult.Compiled])
  }

  test("runs an application through the JVM boundary") {
    val project = copyFixture("hello-world")
    val resolver = CountingResolver()
    BuildService(resolver = resolver).run(project, Nil).unsafeRunSync()
    assertEquals(resolver.resolutions.asScala.toList, List(Nil))
    assertEquals(resolver.compilerClasspathInvocations.get(), 1)
  }

  test("packages and executes an application with resolved runtime dependencies") {
    val project = copyFixture("cats-effect-app")
    val resolver = CountingResolver()
    val result = BuildService(resolver = resolver).packageApplication(project).unsafeRunSync()

    assert(Files.isDirectory(result.applicationDirectory))
    assert(Files.isRegularFile(result.tarArchive))
    assert(Files.isRegularFile(result.zipArchive))
    assert(Files.isRegularFile(result.archiveChecksums))
    assert(result.dependencyCount > 0)

    val launcher = result.applicationDirectory.resolve("bin/cats-effect-app")
    val process = new ProcessBuilder(launcher.toString).redirectErrorStream(true).start()
    val output =
      new String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    val exitCode = process.waitFor()
    assertEquals(exitCode, 0, output)
    assert(output.contains("Cats Effect resolved by Sprout"), output)
    assertEquals(resolver.resolutions.size(), 1)
    assertEquals(resolver.compilerClasspathInvocations.get(), 1)
  }

  test("runs MUnit through the framework boundary") {
    val project = copyFixture("munit-project")
    val resolver = CountingResolver()
    val result = BuildService(resolver = resolver).test(project).unsafeRunSync()
    assertEquals(result.failed, 0)
    assertEquals(result.total, 1)
    assertEquals(
      resolver.resolutions.asScala.toList.map(_.map(_.display)).sortBy(_.mkString),
      List(Nil, List("org.scalameta::munit:1.1.1"))
    )
    assertEquals(resolver.compilerClasspathInvocations.get(), 1)
  }

  test("adds, resolves, and removes main and test dependencies") {
    val project = copyFixture("hello-world")

    val cats = service
      .add(project, "org.typelevel::cats-effect:3.6.3", DependencyScope.Main)
      .unsafeRunSync()
    val munit = service
      .add(project, "org.scalameta::munit:1.1.1", DependencyScope.Test)
      .unsafeRunSync()

    assertEquals(cats.name, "cats-effect")
    assert(cats.artifactCount > 0)
    assertEquals(munit.name, "munit")
    val configured = sprout.config.ProjectConfig
      .load(project.resolve("sprout.toml"))
      .unsafeRunSync()
    assertEquals(configured.mainDependencies.map(_.display), List(cats.dependency.display))
    assertEquals(
      configured.dependencies.filter(_.scope == DependencyScope.Test).map(_.display),
      List(munit.dependency.display)
    )

    service.remove(project, "cats-effect", DependencyScope.Main).unsafeRunSync()
    service.remove(project, "munit", DependencyScope.Test).unsafeRunSync()

    assertEquals(
      sprout.config.ProjectConfig.load(project.resolve("sprout.toml")).unsafeRunSync().dependencies,
      Nil
    )
  }

  test("does not update configuration when an added dependency cannot resolve") {
    val project = copyFixture("hello-world")
    val config = project.resolve("sprout.toml")
    val original = Files.readString(config)

    intercept[SproutError.Resolution](
      service
        .add(
          project,
          "org.typelevel::cats-effect:0.0.0-sprout-missing",
          DependencyScope.Main
        )
        .unsafeRunSync()
    )

    assertEquals(Files.readString(config), original)
  }

  test("renders graph and why reports from the resolved Cats Effect model") {
    val project = copyFixture("cats-effect-app")

    val firstGraph = service.graph(project).unsafeRunSync()
    val secondGraph = service.graph(project).unsafeRunSync()
    val why = service.why(project, "cats-core").unsafeRunSync()

    assertEquals(firstGraph, secondGraph)
    assert(firstGraph.startsWith("cats-effect-app\n└── cats-effect 3.6.3"))
    assert(firstGraph.contains("cats-core 2.11.0"))
    assert(why.contains("cats-core 2.11.0"))
    assert(why.contains("cats-effect 3.6.3"))
    intercept[SproutError.User](service.why(project, "does-not-exist").unsafeRunSync())
  }

  private def copyFixture(name: String): Path =
    val repository = Iterator
      .iterate(Path.of(".").toAbsolutePath.normalize)(_.getParent)
      .takeWhile(_ != null)
      .find(path => Files.isRegularFile(path.resolve("build.sbt")))
      .getOrElse(fail("could not locate repository root"))
    val source = repository.resolve("fixtures").resolve(name)
    val target = Files.createTempDirectory(s"sprout-$name")
    val stream = Files.walk(source)
    try
      stream.iterator.asScala.foreach { path =>
        val destination = target.resolve(source.relativize(path).toString)
        if Files.isDirectory(path) then Files.createDirectories(destination)
        else Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
      }
    finally stream.close()
    target

  private final class CountingResolver(delegate: DependencyResolver[IO])
      extends DependencyResolver[IO]:
    val resolutions = new ConcurrentLinkedQueue[List[Dependency]]()
    val compilerClasspathInvocations = AtomicInteger()

    def resolve(
        scalaVersion: ScalaVersion,
        dependencies: List[Dependency]
    ): IO[ResolvedDependencies] =
      IO(resolutions.add(dependencies)).flatMap(_ => delegate.resolve(scalaVersion, dependencies))

    def compilerClasspath(scalaVersion: ScalaVersion): IO[ResolvedClasspath] =
      IO(compilerClasspathInvocations.incrementAndGet()).flatMap(_ =>
        delegate.compilerClasspath(scalaVersion)
      )

    def compilerBridge(scalaVersion: ScalaVersion): IO[Path] =
      delegate.compilerBridge(scalaVersion)

  private object CountingResolver:
    def apply(): CountingResolver = new CountingResolver(CoursierDependencyResolver())
