package sprout.cli

import cats.effect.unsafe.implicits.global
import sprout.core.{CompilationResult, SproutError}
import java.nio.file.{Files, Path, StandardCopyOption}
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
    service.run(project, Nil).unsafeRunSync()
  }

  test("runs MUnit through the framework boundary") {
    val project = copyFixture("munit-project")
    val result = service.test(project).unsafeRunSync()
    assertEquals(result.failed, 0)
    assertEquals(result.total, 1)
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
