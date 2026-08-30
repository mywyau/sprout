package sprout.compiler

import cats.effect.IO
import sprout.core.{CompilationRequest, SproutError}
import java.nio.file.{Files, Path, StandardCopyOption}
import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

private[compiler] object ResourceSynchronizer:
  private val ManifestHeader = "sprout-resources-v1"

  def sync(request: CompilationRequest): IO[Int] = IO.blocking {
    request.resourceStateDirectory match
      case None        => 0
      case Some(state) => sync(request.resourceDirectories, request.outputDirectory, state)
  }

  private def sync(resourceDirectories: List[Path], output: Path, state: Path): Int =
    val previous = readManifest(state.resolve("manifest"))
    val resources = collect(resourceDirectories)
    val current = resources.map(_._1).toSet
    val stale = previous.diff(current)
    val removed = stale.count { relative =>
      val destination = output.resolve(relative)
      Files.deleteIfExists(destination)
    }
    val copied = resources.count { case (relative, source) =>
      val destination = output.resolve(relative)
      if Files.exists(destination) && !previous.contains(relative) && !sameContent(
          source,
          destination
        )
      then
        throw SproutError.User(
          s"Resource '$relative' conflicts with compiled output at $destination"
        )
      if sameContent(source, destination) then false
      else
        Files.createDirectories(destination.getParent)
        copyAtomically(source, destination)
        true
    }
    writeManifest(state.resolve("manifest"), current)
    copied + removed

  private def collect(resourceDirectories: List[Path]): List[(String, Path)] =
    val resources = resourceDirectories
      .flatMap { directory =>
        files(directory).map { source =>
          val relative =
            directory.relativize(source).toString.replace(java.io.File.separatorChar, '/')
          relative -> source
        }
      }
      .sortBy(_._1)
    resources
      .groupBy(_._1)
      .collectFirst { case (relative, values) if values.size > 1 => relative }
      .foreach(relative =>
        throw SproutError.User(s"Resource '$relative' is defined more than once")
      )
    resources

  private def files(root: Path): List[Path] =
    if !Files.isDirectory(root) then Nil
    else
      val stream = Files.walk(root)
      try stream.iterator.asScala.filter(Files.isRegularFile(_)).toList
      finally stream.close()

  private def readManifest(path: Path): Set[String] =
    if !Files.isRegularFile(path) then Set.empty
    else
      try
        Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toList match
          case header :: entries if header == ManifestHeader =>
            entries
              .map(encoded =>
                new String(Base64.getUrlDecoder.decode(encoded), StandardCharsets.UTF_8)
              )
              .toSet
          case _ => Set.empty
      catch case NonFatal(_) => Set.empty

  private def writeManifest(path: Path, resources: Set[String]): Unit =
    val content =
      (ManifestHeader :: resources.toList.sorted.map(path =>
        Base64.getUrlEncoder.withoutPadding.encodeToString(path.getBytes(StandardCharsets.UTF_8))
      )).mkString("", "\n", "\n")
    AtomicFile.writeString(path, content)

  private def sameContent(left: Path, right: Path): Boolean =
    Files.isRegularFile(right) && Files.size(left) == Files.size(right) &&
      java.util.Arrays.equals(Files.readAllBytes(left), Files.readAllBytes(right))

  private def copyAtomically(source: Path, destination: Path): Unit =
    val temporary =
      Files.createTempFile(destination.getParent, s".${destination.getFileName}.", ".tmp")
    try
      Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
      AtomicFile.moveReplacing(temporary, destination)
    finally Files.deleteIfExists(temporary)
