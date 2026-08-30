package sprout.core

import cats.effect.unsafe.implicits.global
import java.nio.file.{Files, Path}

class HashingSuite extends munit.FunSuite:
  test("compilation fingerprints include dependency bytes rather than only paths and sizes") {
    val fixture = requestFixture()
    val before = Hashing.compilationKey(fixture.request).unsafeRunSync()
    Files.writeString(fixture.dependency, "same")
    val first = Hashing.compilationKey(fixture.request).unsafeRunSync()
    Files.writeString(fixture.dependency, "size")
    val second = Hashing.compilationKey(fixture.request).unsafeRunSync()

    assertNotEquals(before, first)
    assertNotEquals(first, second)
    assertEquals(Files.size(fixture.dependency), 4L)
  }

  test("compilation fingerprints preserve classpath order and include compiler artifacts") {
    val fixture = requestFixture()
    val other = Files.writeString(fixture.root.resolve("other.jar"), "other")
    val original = Hashing
      .compilationKey(fixture.request.copy(classpath = List(fixture.dependency, other)))
      .unsafeRunSync()
    val reordered = Hashing
      .compilationKey(fixture.request.copy(classpath = List(other, fixture.dependency)))
      .unsafeRunSync()
    Files.writeString(fixture.compiler, "changed compiler")
    val changedCompiler = Hashing
      .compilationKey(fixture.request.copy(classpath = List(fixture.dependency, other)))
      .unsafeRunSync()

    assertNotEquals(original, reordered)
    assertNotEquals(original, changedCompiler)
  }

  test(
    "compilation fingerprints include Scala version, options, JVM target, and class directories"
  ) {
    val fixture = requestFixture()
    val classes = Files.createDirectories(fixture.root.resolve("upstream"))
    val upstreamClass = Files.write(classes.resolve("Upstream.class"), Array[Byte](1, 2, 3))
    val request = fixture.request.copy(classpath = List(classes))
    val original = Hashing.compilationKey(request).unsafeRunSync()
    val scalaVersion = ScalaVersion.from("3.3.7").toOption.get

    assertNotEquals(
      original,
      Hashing.compilationKey(request.copy(scalaVersion = scalaVersion)).unsafeRunSync()
    )
    assertNotEquals(
      original,
      Hashing.compilationKey(request.copy(compilerOptions = List("-deprecation"))).unsafeRunSync()
    )
    assertNotEquals(
      original,
      Hashing.compilationKey(request.copy(jvmTarget = JvmTarget("17"))).unsafeRunSync()
    )
    Files.write(upstreamClass, Array[Byte](1, 2, 4))
    assertNotEquals(original, Hashing.compilationKey(request).unsafeRunSync())
  }

  private def requestFixture(): RequestFixture =
    val root = Files.createTempDirectory("sprout-hashing")
    val source = Files.writeString(root.resolve("Main.scala"), "object Main")
    val dependency = Files.writeString(root.resolve("dependency.jar"), "dependency")
    val compiler = Files.writeString(root.resolve("compiler.jar"), "compiler")
    val version = ScalaVersion.from("3.3.6").toOption.get
    RequestFixture(
      root,
      dependency,
      compiler,
      CompilationRequest(
        List(source),
        List(dependency),
        List(compiler),
        root.resolve("classes"),
        version,
        jvmTarget = JvmTarget("21")
      )
    )

  private final case class RequestFixture(
      root: Path,
      dependency: Path,
      compiler: Path,
      request: CompilationRequest
  )
