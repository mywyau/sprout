package sprout.cli

import cats.effect.IO
import sprout.core.{ProjectName, SproutError}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

object ProjectGenerator:
  private val ScalaVersion = "3.3.6"
  private val MUnitVersion = "1.1.1"

  def create(parent: Path, rawName: String): IO[Path] =
    ProjectName.from(rawName) match
      case Left(message) =>
        IO.raiseError(SproutError.User(s"Invalid project name '$rawName': $message"))
      case Right(name) =>
        IO.blocking {
          val root = parent.toAbsolutePath.normalize.resolve(name.value)
          if Files.exists(root) then
            throw SproutError.User(s"Cannot create project: $root already exists")
          val main = root.resolve("src/main/scala")
          val test = root.resolve("src/test/scala")
          Files.createDirectories(main)
          Files.createDirectories(test)
          write(root.resolve("sprout.toml"), config(name.value))
          write(main.resolve("Main.scala"), mainSource)
          write(test.resolve("MainSuite.scala"), testSource)
          root
        }

  private def write(path: Path, content: String): Unit =
    Files.writeString(
      path,
      content,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE_NEW,
      StandardOpenOption.WRITE
    )
    ()

  private def config(name: String): String =
    s"""[project]
       |name = "$name"
       |scala = "$ScalaVersion"
       |
       |[test-dependencies]
       |munit = "org.scalameta::munit:$MUnitVersion"
       |""".stripMargin

  private val mainSource =
    """object Main:
      |  def message: String = "Hello from Sprout!"
      |
      |  def main(args: Array[String]): Unit =
      |    println(message)
      |""".stripMargin

  private val testSource =
    """class MainSuite extends munit.FunSuite:
      |  test("the generated application has a greeting"):
      |    assertEquals(Main.message, "Hello from Sprout!")
      |""".stripMargin
