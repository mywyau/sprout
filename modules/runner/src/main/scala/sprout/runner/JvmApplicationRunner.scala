package sprout.runner

import cats.effect.IO
import sprout.core.*
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object MainClassDiscovery:
  def discover(classes: Path, classpath: List[Path]): IO[String] =
    classNames(classes).flatMap { names =>
      names
        .foldLeft(IO.pure(List.empty[String])) { (found, name) =>
          found
            .flatMap(values => hasMain(name, classpath).map(if _ then name :: values else values))
        }
        .flatMap {
          case main :: Nil => IO.pure(main)
          case Nil         =>
            IO.raiseError(
              SproutError.User(
                "No application main class found. Define an object with def main(args: Array[String])."
              )
            )
          case many =>
            IO.raiseError(
              SproutError
                .User(s"Multiple application main classes found:\n\n${many.sorted.mkString("\n")}")
            )
        }
    }

  private def classNames(root: Path): IO[List[String]] = IO.blocking {
    if !Files.isDirectory(root) then Nil
    else
      val stream = Files.walk(root)
      try
        stream.iterator.asScala
          .filter(path =>
            Files.isRegularFile(path) && path.toString
              .endsWith(".class") && !path.getFileName.toString.contains("$")
          )
          .map(path =>
            root
              .relativize(path)
              .toString
              .stripSuffix(".class")
              .replace(java.io.File.separatorChar, '.')
          )
          .toList
      finally stream.close()
  }

  private def hasMain(name: String, classpath: List[Path]): IO[Boolean] = IO.blocking {
    val javap = Path.of(System.getProperty("java.home"), "bin", "javap").toString
    val process =
      new ProcessBuilder(javap, "-classpath", classpath.mkString(java.io.File.pathSeparator), name)
        .redirectErrorStream(true)
        .start()
    val output = new String(process.getInputStream.readAllBytes())
    process.waitFor()
    output.contains("public static void main(java.lang.String[])")
  }

final class JvmApplicationRunner extends ApplicationRunner[IO]:
  def run(mainClass: String, classpath: List[Path], arguments: List[String]): IO[Int] =
    IO.blocking {
      val javaCommand = Path.of(System.getProperty("java.home"), "bin", "java").toString
      new ProcessBuilder(
        (List(
          javaCommand,
          "-cp",
          classpath.mkString(java.io.File.pathSeparator),
          mainClass
        ) ++ arguments)*
      )
        .inheritIO()
        .start()
        .waitFor()
    }
