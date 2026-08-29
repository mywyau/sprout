package sprout.core

import cats.effect.IO
import cats.effect.Resource
import scala.jdk.CollectionConverters.*
import java.nio.file.{Files, Path}

object SourceDiscovery:
  def scalaSources(directories: List[Path]): IO[List[Path]] =
    directories.traverse(discover).map(_.flatten.sortBy(_.toString))

  private def discover(directory: Path): IO[List[Path]] =
    IO.blocking(Files.isDirectory(directory)).flatMap {
      case false => IO.pure(Nil)
      case true  =>
        Resource.fromAutoCloseable(IO.blocking(Files.walk(directory))).use { stream =>
          IO.blocking(
            stream.iterator.asScala
              .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".scala"))
              .toList
          )
        }
    }

  extension [A](values: List[A])
    private def traverse[B](f: A => IO[B]): IO[List[B]] =
      values.foldRight(IO.pure(List.empty[B]))((a, acc) =>
        f(a).flatMap(value => acc.map(value :: _))
      )
