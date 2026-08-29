package sprout.config

import cats.effect.unsafe.implicits.global
import java.nio.file.Files

class ProjectConfigSuite extends munit.FunSuite:
  test("loads a declarative project and both dependency scopes") {
    val root = Files.createTempDirectory("sprout-config")
    val file = root.resolve("sprout.toml")
    Files.writeString(
      file,
      """[project]
        |name = "sample"
        |scala = "3.3.6"
        |
        |[dependencies]
        |cats = "org.typelevel::cats-effect:3.6.3"
        |
        |[test-dependencies]
        |munit = "org.scalameta::munit:1.1.1"
        |""".stripMargin
    )
    val project = ProjectConfig.load(file).unsafeRunSync()
    assertEquals(project.name.value, "sample")
    assertEquals(project.dependencies.size, 2)
    assertEquals(project.testDependencies.size, 2)
    assertEquals(project.layout.mainSources, List(root.resolve("src/main/scala")))
  }

  test("reports malformed TOML as a configuration error") {
    val root = Files.createTempDirectory("sprout-invalid-config")
    val file = root.resolve("sprout.toml")
    Files.writeString(file, "[project\nname = 7")
    intercept[sprout.core.SproutError.Configuration](ProjectConfig.load(file).unsafeRunSync())
  }
