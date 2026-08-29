package sprout.bsp

import cats.effect.IO
import ch.epfl.scala.bsp4j.BspConnectionDetails
import com.google.gson.{GsonBuilder, JsonParser}
import java.nio.charset.StandardCharsets
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption}
import scala.jdk.CollectionConverters.*

enum BspConnectionChange:
  case Created, Updated, Unchanged

final case class BspConnectionResult(path: Path, change: BspConnectionChange)

object BspConnection:
  val BspVersion = "2.1.0"

  def install(
      projectRoot: Path,
      sproutVersion: String,
      launcher: String
  ): IO[BspConnectionResult] = IO.blocking {
    val directory = projectRoot.toAbsolutePath.normalize.resolve(".bsp")
    val destination = directory.resolve("sprout.json")
    val normalizedLauncher = normalizeLauncher(launcher)
    val details = BspConnectionDetails(
      "Sprout",
      List(normalizedLauncher, "bsp").asJava,
      sproutVersion,
      BspVersion,
      List("scala").asJava
    )
    val gson = GsonBuilder().setPrettyPrinting().create()
    val desired = gson.toJsonTree(details)
    val existing = Option
      .when(Files.isRegularFile(destination)) {
        try Some(JsonParser.parseString(Files.readString(destination, StandardCharsets.UTF_8)))
        catch case _: RuntimeException => None
      }
      .flatten

    Files.createDirectories(directory)
    if existing.contains(desired) then
      BspConnectionResult(destination, BspConnectionChange.Unchanged)
    else
      val change =
        if Files.exists(destination) then BspConnectionChange.Updated
        else BspConnectionChange.Created
      writeAtomically(
        directory,
        destination,
        gson.toJson(desired) + System.lineSeparator()
      )
      BspConnectionResult(destination, change)
  }

  def currentLauncher: String =
    Option(System.getProperty("sprout.launcher")).filter(_.nonEmpty).getOrElse("sprout")

  private def normalizeLauncher(launcher: String): String =
    val path = Path.of(launcher)
    if path.isAbsolute then path.normalize.toString else launcher

  private def writeAtomically(directory: Path, destination: Path, contents: String): Unit =
    val temporary = Files.createTempFile(directory, "sprout-", ".json")
    try
      Files.writeString(temporary, contents, StandardCharsets.UTF_8)
      try
        Files.move(
          temporary,
          destination,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
      catch
        case _: AtomicMoveNotSupportedException =>
          Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(temporary)
