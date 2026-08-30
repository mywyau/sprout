package sprout.compiler

import java.nio.charset.StandardCharsets
import java.nio.file.{
  AtomicMoveNotSupportedException,
  Files,
  Path,
  StandardCopyOption,
  StandardOpenOption
}

private[compiler] object AtomicFile:
  def writeString(destination: Path, content: String): Unit =
    writeBytes(destination, content.getBytes(StandardCharsets.UTF_8))

  def writeBytes(destination: Path, content: Array[Byte]): Unit =
    Files.createDirectories(destination.getParent)
    val temporary = Files.createTempFile(
      destination.getParent,
      s".${destination.getFileName}.",
      ".tmp"
    )
    try
      Files.write(
        temporary,
        content,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING
      )
      moveReplacing(temporary, destination)
    finally Files.deleteIfExists(temporary)

  private def moveReplacing(source: Path, destination: Path): Unit =
    try
      Files.move(
        source,
        destination,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
    catch
      case _: AtomicMoveNotSupportedException =>
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
