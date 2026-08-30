package sprout.runner

import cats.effect.IO
import sprout.core.*
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object MainClassDiscovery:
  def discover(classes: Path, classpath: List[Path]): IO[String] = IO.blocking {
    val urls = (classes :: classpath).distinct.map(_.toUri.toURL).toArray
    val loader = new URLClassLoader(urls, getClass.getClassLoader)
    try
      classNames(classes).filter(hasMain(_, loader)) match
        case main :: Nil => main
        case Nil         =>
          throw SproutError.User(
            "No application main class found. Define an object with def main(args: Array[String])."
          )
        case many =>
          throw SproutError.User(
            s"Multiple application main classes found:\n\n${many.sorted.mkString("\n")}"
          )
    finally loader.close()
  }

  private def classNames(root: Path): List[String] =
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

  private def hasMain(name: String, loader: ClassLoader): Boolean =
    try
      val main = Class.forName(name, false, loader).getMethod("main", classOf[Array[String]])
      Modifier.isStatic(main.getModifiers) && main.getReturnType == java.lang.Void.TYPE
    catch
      case _: ClassNotFoundException | _: LinkageError | _: NoSuchMethodException |
          _: SecurityException =>
        false

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
