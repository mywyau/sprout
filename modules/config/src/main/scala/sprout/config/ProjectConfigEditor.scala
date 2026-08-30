package sprout.config

import cats.effect.IO
import java.nio.charset.StandardCharsets
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption}
import java.util.regex.Pattern
import org.tomlj.Toml
import scala.jdk.CollectionConverters.*
import sprout.core.{Dependency, DependencyScope, SproutError}

object ProjectConfigEditor:
  def validateAddition(path: Path, dependency: Dependency): IO[String] =
    ProjectConfig.load(path).flatMap { project =>
      IO.blocking {
        val name = dependency.artifact.value
        val scoped = project.dependencies.filter(_.scope == dependency.scope)
        if scoped.exists(_.display == dependency.display) then
          throw SproutError.User(
            s"Dependency already exists in [${tableName(dependency.scope)}]: ${dependency.display}"
          )

        val table = Option(Toml.parse(path).getTable(tableName(dependency.scope)))
        if table.exists(_.entrySet.asScala.exists(_.getKey == name)) then
          throw SproutError.User(
            s"Dependency name '$name' already exists in [${tableName(dependency.scope)}]"
          )
        name
      }
    }

  def add(path: Path, dependency: Dependency): IO[String] =
    validateAddition(path, dependency).flatMap { name =>
      IO.blocking {
        val original = Files.readString(path, StandardCharsets.UTF_8)
        val updated = addEntry(original, tableName(dependency.scope), name, dependency.display)
        replaceAtomically(path, updated)
        name
      }
    }

  def remove(path: Path, name: String, scope: DependencyScope): IO[Unit] =
    ProjectConfig.load(path) *> IO.blocking {
      val table = Option(Toml.parse(path).getTable(tableName(scope)))
      if !table.exists(_.entrySet.asScala.exists(_.getKey == name)) then
        throw SproutError.User(
          s"Dependency '$name' was not found in [${tableName(scope)}]"
        )

      val original = Files.readString(path, StandardCharsets.UTF_8)
      val updated = removeEntry(original, tableName(scope), name).getOrElse {
        throw SproutError.User(
          s"Cannot safely edit dependency '$name'; use a single-line TOML assignment"
        )
      }
      replaceAtomically(path, updated)
    }

  private def addEntry(content: String, table: String, name: String, coordinate: String): String =
    val separator = lineSeparator(content)
    val lines = content.split("\\r?\\n", -1).toVector
    val entry = s"${renderKey(name)} = ${renderString(coordinate)}"
    section(lines, table) match
      case Some((_, end)) =>
        val insertion = Iterator
          .iterate(end)(_ - 1)
          .takeWhile(_ > 0)
          .find(index => lines(index - 1).trim.nonEmpty)
          .getOrElse(end)
        lines.patch(insertion, Vector(entry), 0).mkString(separator)
      case None =>
        val base = content + (if content.endsWith("\n") || content.isEmpty then "" else separator)
        val spacing = if base.isEmpty || base.endsWith(separator + separator) then "" else separator
        s"$base$spacing[$table]$separator$entry$separator"

  private def removeEntry(content: String, table: String, name: String): Option[String] =
    val separator = lineSeparator(content)
    val lines = content.split("\\r?\\n", -1).toVector
    section(lines, table).flatMap { case (start, end) =>
      val key = Pattern.quote(renderKey(name))
      val assignment = s"^\\s*$key\\s*=.*$$".r
      (start + 1 until end).find(index => assignment.matches(lines(index))).map { index =>
        lines.patch(index, Nil, 1).mkString(separator)
      }
    }

  private def section(lines: Vector[String], name: String): Option[(Int, Int)] =
    val heading = s"^\\s*\\[\\s*${Pattern.quote(name)}\\s*\\]\\s*(?:#.*)?$$".r
    lines.indices.find(index => heading.matches(lines(index))).map { start =>
      val end = (start + 1 until lines.size)
        .find(index => lines(index).trim.startsWith("["))
        .getOrElse(lines.size)
      (start, end)
    }

  private def renderKey(value: String): String =
    if value.matches("[A-Za-z0-9_-]+") then value else renderString(value)

  private def renderString(value: String): String =
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    s"\"$escaped\""

  private def lineSeparator(content: String): String =
    if content.contains("\r\n") then "\r\n" else "\n"

  private def tableName(scope: DependencyScope): String = scope match
    case DependencyScope.Main => "dependencies"
    case DependencyScope.Test => "test-dependencies"

  private def replaceAtomically(path: Path, content: String): Unit =
    val temporary = Files.createTempFile(path.getParent, s".${path.getFileName}.", ".tmp")
    try
      Files.writeString(temporary, content, StandardCharsets.UTF_8)
      copyPermissions(path, temporary)
      try
        Files.move(
          temporary,
          path,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
      catch
        case _: AtomicMoveNotSupportedException =>
          Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(temporary)

  private def copyPermissions(source: Path, target: Path): Unit =
    try
      val permissions = Files.getPosixFilePermissions(source)
      Files.setPosixFilePermissions(target, permissions)
    catch case _: UnsupportedOperationException => ()
