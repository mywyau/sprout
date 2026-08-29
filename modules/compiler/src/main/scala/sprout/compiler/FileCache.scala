package sprout.compiler

import cats.effect.IO
import sprout.core.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

final class FileCache(directory: Path) extends Cache[IO]:
  def get(key: CacheKey): IO[Option[CachedValue]] = IO.blocking {
    val path = entry(key)
    if Files.isRegularFile(path) then Some(CachedValue(Files.readString(path))) else None
  }

  def put(key: CacheKey, value: CachedValue): IO[Unit] = IO.blocking {
    Files.createDirectories(directory)
    Files.writeString(
      entry(key),
      value.value,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING
    )
    ()
  }

  private def entry(key: CacheKey): Path = directory.resolve(key.value + ".cache")
