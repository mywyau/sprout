package sprout.cli

import cats.effect.IO
import sprout.core.{ExternalTool, SproutError}
import java.nio.file.Path

object ToolRunner:
  def run(
      tool: ExternalTool,
      classpath: List[Path],
      root: Path,
      arguments: List[String]
  ): IO[Unit] =
    IO.blocking {
      val javaCommand = Path.of(System.getProperty("java.home"), "bin", "java").toString
      val command = List(
        javaCommand,
        "-cp",
        classpath.mkString(java.io.File.pathSeparator),
        tool.mainClass
      ) ++ arguments
      val exit = new ProcessBuilder(command*).directory(root.toFile).inheritIO().start().waitFor()
      if exit != 0 then throw SproutError.Process(tool.name.capitalize, exit)
    }
