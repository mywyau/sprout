package sprout.bsp

import cats.effect.IO
import ch.epfl.scala.bsp4j.BspConnectionDetails
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object BspConnection:
  val BspVersion = "2.1.0"

  def install(projectRoot: Path, sproutVersion: String, launcher: String): IO[Path] = IO.blocking {
    val directory = projectRoot.toAbsolutePath.normalize.resolve(".bsp")
    val destination = directory.resolve("sprout.json")
    val details = BspConnectionDetails(
      "Sprout",
      List(launcher, "bsp").asJava,
      sproutVersion,
      BspVersion,
      List("scala").asJava
    )
    val json = GsonBuilder().setPrettyPrinting().create().toJson(details) + System.lineSeparator()
    Files.createDirectories(directory)
    Files.writeString(destination, json, StandardCharsets.UTF_8)
    destination
  }

  def currentLauncher: String =
    Option(System.getProperty("sprout.launcher")).filter(_.nonEmpty).getOrElse("sprout")
