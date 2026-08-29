package sprout.core

sealed abstract class SproutError(message: String, cause: Throwable | Null = null)
    extends RuntimeException(message, cause)

object SproutError:
  final case class User(message: String) extends SproutError(message)
  final case class Configuration(path: java.nio.file.Path, details: String)
      extends SproutError(s"Invalid configuration\n\n$path\n\n$details")
  final case class Resolution(
      dependency: String,
      scalaVersion: ScalaVersion,
      attempted: String,
      underlying: Throwable
  ) extends SproutError(
        s"Failed to resolve dependency\n\n$dependency\n\nScala version:\n${scalaVersion.value}\n\nSprout tried:\n$attempted\n\nRepository:\nMaven Central",
        underlying
      )
  final case class Compilation(exitCode: Int, details: Option[String] = None)
      extends SproutError(
        s"Scala compilation failed (compiler exited with status $exitCode)" +
          details.filter(_.nonEmpty).fold("")(value => s"\n\n$value")
      )
  final case class Process(label: String, exitCode: Int)
      extends SproutError(s"$label failed (process exited with status $exitCode)")
