package sprout.cli

import cats.effect.unsafe.implicits.global
import java.nio.file.Files

class ProjectGeneratorSuite extends munit.FunSuite:
  test("generates a complete conventional project") {
    val parent = Files.createTempDirectory("sprout-new")
    val project = ProjectGenerator.create(parent, "hello").unsafeRunSync()
    assert(Files.isRegularFile(project.resolve("sprout.toml")))
    assert(Files.isRegularFile(project.resolve("src/main/scala/Main.scala")))
    assert(Files.isRegularFile(project.resolve("src/test/scala/MainSuite.scala")))
    assert(
      Files.readString(project.resolve("src/main/scala/Main.scala")).contains("Hello from Sprout!")
    )
  }

  test("does not overwrite an existing directory") {
    val parent = Files.createTempDirectory("sprout-existing")
    Files.createDirectory(parent.resolve("hello"))
    intercept[sprout.core.SproutError.User](
      ProjectGenerator.create(parent, "hello").unsafeRunSync()
    )
  }
