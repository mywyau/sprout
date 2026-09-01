package sprout.config

import cats.effect.IO
import sprout.core.*
import org.tomlj.{Toml, TomlParseResult, TomlTable}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object ProjectConfig:
  val FileName = "sprout.toml"

  def locate(from: Path): IO[Path] = IO.blocking {
    Iterator
      .iterate(from.toAbsolutePath.normalize)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve(FileName))
      .find(Files.isRegularFile(_))
      .getOrElse(throw SproutError.User(s"No $FileName found in this directory or its parents"))
  }

  def load(path: Path): IO[Project] = IO.blocking(parse(path, Toml.parse(path)))

  private def parse(path: Path, toml: TomlParseResult): Project =
    if toml.hasErrors then
      val details = toml.errors.asScala.map(_.toString).mkString("\n")
      throw SproutError.Configuration(path, details)
    val projectTable = Option(toml.getTable("project"))
      .getOrElse(throw SproutError.Configuration(path, "missing [project] table"))
    val name = required(projectTable, "name").flatMap(ProjectName.from).fold(fail(path), identity)
    val version =
      required(projectTable, "scala").flatMap(ScalaVersion.from).fold(fail(path), identity)
    val main = dependencies(Option(toml.getTable("dependencies")), DependencyScope.Main, path)
    val test = dependencies(Option(toml.getTable("test-dependencies")), DependencyScope.Test, path)
    val tools = externalTools(Option(toml.getTable("tools")), path)
    Project(
      name,
      version,
      main ++ test,
      ProjectLayout.conventional(path.toAbsolutePath.normalize.getParent),
      tools
    )

  private def required(table: TomlTable, key: String): Either[String, String] =
    Option(table.getString(key)).toRight(s"missing or invalid project.$key")

  private def dependencies(
      table: Option[TomlTable],
      scope: DependencyScope,
      path: Path
  ): List[Dependency] =
    table.toList.flatMap(_.entrySet.asScala.toList.sortBy(_.getKey).map { entry =>
      entry.getValue match
        case value: String =>
          Dependency
            .parse(value, scope)
            .fold(message => throw SproutError.Configuration(path, message), identity)
        case _ =>
          throw SproutError.Configuration(path, s"dependency '${entry.getKey}' must be a string")
    })

  private def externalTools(table: Option[TomlTable], path: Path): List[ExternalTool] =
    table.toList.flatMap(_.entrySet.asScala.toList.sortBy(_.getKey).map { entry =>
      val dependency = entry.getValue match
        case value: String =>
          Dependency
            .parse(value, DependencyScope.Main)
            .fold(message => throw SproutError.Configuration(path, message), identity)
        case _ => throw SproutError.Configuration(path, s"tool '${entry.getKey}' must be a string")
      entry.getKey match
        case "scalafmt" if dependency.crossVersion == CrossVersion.None =>
          ExternalTool("scalafmt", dependency, "org.scalafmt.cli.Cli")
        case "scalafmt" =>
          throw SproutError
            .Configuration(path, "tool 'scalafmt' must use an ordinary Maven coordinate")
        case name => throw SproutError.Configuration(path, s"unsupported tool '$name'")
    })

  private def fail[A](path: Path)(details: String): A =
    throw SproutError.Configuration(path, details)
