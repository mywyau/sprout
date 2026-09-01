package sprout.core

import cats.effect.{IO, Resource}
import java.nio.channels.{FileChannel, OverlappingFileLockException}
import java.nio.file.{Files, Path, StandardOpenOption}

/** Serializes commands that mutate a project's generated state across processes. */
object ProjectLock:
  def apply[A](root: Path)(action: IO[A]): IO[A] =
    Resource.make(acquire(root))(release).use(_ => action)

  private def acquire(root: Path): IO[FileChannel] = IO.blocking {
    val path = root.resolve(".sprout-build.lock")
    val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    try
      Option(channel.tryLock()).fold {
        channel.close()
        throw SproutError.User("Another Sprout command is already running in this project")
      }(_ => channel)
    catch
      case _: OverlappingFileLockException =>
        channel.close()
        throw SproutError.User("Another Sprout command is already running in this project")
  }

  private def release(channel: FileChannel): IO[Unit] = IO.blocking(channel.close()).handleError(_ => ())
