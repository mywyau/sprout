package sprout.config

import cats.effect.unsafe.implicits.global
import java.nio.file.Files
import sprout.core.{Dependency, DependencyScope, SproutError}

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

  test("adds and removes dependencies without rewriting unrelated configuration") {
    val root = Files.createTempDirectory("sprout-edit-config")
    val file = root.resolve("sprout.toml")
    Files.writeString(
      file,
      """# Keep this comment
        |[project]
        |name = "sample"
        |scala = "3.3.6"
        |
        |[custom]
        |enabled = true
        |""".stripMargin
    )
    val dependency = Dependency
      .parse("org.typelevel::cats-effect:3.6.3", DependencyScope.Main)
      .fold(fail(_), identity)

    val name = ProjectConfigEditor.add(file, dependency).unsafeRunSync()

    assertEquals(name, "cats-effect")
    val added = Files.readString(file)
    assert(added.startsWith("# Keep this comment"))
    assert(added.contains("[custom]\nenabled = true"))
    assert(added.contains("[dependencies]\ncats-effect = \"org.typelevel::cats-effect:3.6.3\""))
    assertEquals(ProjectConfig.load(file).unsafeRunSync().mainDependencies, List(dependency))

    ProjectConfigEditor.remove(file, "cats-effect", DependencyScope.Main).unsafeRunSync()

    val removed = Files.readString(file)
    assert(!removed.contains("cats-effect"))
    assert(removed.contains("[custom]\nenabled = true"))
  }

  test("rejects duplicate names and leaves the file unchanged") {
    val root = Files.createTempDirectory("sprout-duplicate-config")
    val file = root.resolve("sprout.toml")
    val original =
      """[project]
        |name = "sample"
        |scala = "3.3.6"
        |
        |[dependencies]
        |cats-effect = "example::cats-effect:1.0.0"
        |""".stripMargin
    Files.writeString(file, original)
    val dependency = Dependency
      .parse("org.typelevel::cats-effect:3.6.3", DependencyScope.Main)
      .fold(fail(_), identity)

    intercept[SproutError.User](ProjectConfigEditor.add(file, dependency).unsafeRunSync())

    assertEquals(Files.readString(file), original)
  }
