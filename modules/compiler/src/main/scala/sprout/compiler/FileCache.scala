package sprout.compiler

import cats.effect.IO
import sprout.core.*
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

final class FileCache(
    directory: Path,
    metadataVersion: Int = FileCache.CurrentMetadataVersion
) extends Cache[IO]:
  def get(key: CacheKey): IO[Option[CachedValue]] = IO.blocking {
    val path = entry(key)
    if !Files.isRegularFile(path) then None
    else
      try decode(Files.readString(path))
      catch case NonFatal(_) => None
  }

  def put(key: CacheKey, value: CachedValue): IO[Unit] = IO.blocking {
    AtomicFile.writeString(entry(key), encode(value))
  }

  private def entry(key: CacheKey): Path = directory.resolve(key.value + ".cache")

  private def encode(value: CachedValue): String = s"${header}${value.value}"

  private def decode(content: String): Option[CachedValue] =
    Option.when(content.startsWith(header))(CachedValue(content.drop(header.length)))

  private def header: String =
    s"sprout-file-cache\nmetadata-version=$metadataVersion\n\n"

object FileCache:
  val CurrentMetadataVersion = 1
