package sprout.core

import cats.effect.{IO, Resource}
import java.nio.channels.{FileChannel, OverlappingFileLockException}
import java.nio.file.{Path, StandardOpenOption}
import java.util.concurrent.ConcurrentHashMap

/** Serializes commands that mutate a project's generated state across processes. */
object ProjectLock:
  private final case class HeldLock(path: Path, channel: FileChannel)
  private val localLocks = ConcurrentHashMap.newKeySet[Path]()

  def apply[A](root: Path)(action: IO[A]): IO[A] =
    Resource.make(acquire(root))(release).use(_ => action)

  private def acquire(root: Path): IO[HeldLock] = IO.blocking {
    val path = root.resolve(".sprout-build.lock").toAbsolutePath.normalize
    if !localLocks.add(path) then busy()
    try
      val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      try
        Option(channel.tryLock()).fold {
          channel.close()
          busy()
        }(_ => HeldLock(path, channel))
      catch
        case error: OverlappingFileLockException =>
          channel.close()
          throw error
    catch
      case _: OverlappingFileLockException =>
        localLocks.remove(path)
        busy()
      case error: Throwable =>
        localLocks.remove(path)
        throw error
  }

  private def release(lock: HeldLock): IO[Unit] = IO
    .blocking {
      try lock.channel.close()
      finally localLocks.remove(lock.path)
    }
    .handleError(_ => localLocks.remove(lock.path))

  private def busy(): Nothing =
    throw SproutError.User("Another Sprout command is already running in this project")
