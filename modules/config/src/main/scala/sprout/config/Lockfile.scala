package sprout.config

import cats.effect.IO
import sprout.core.*
import java.nio.charset.StandardCharsets
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption}
import java.security.MessageDigest
import java.util.Base64
import scala.util.control.NonFatal

/** The project-local record of the dependency graph selected for a configuration.
  *
  * The payload is deliberately a small, deterministic text format. It records every selected
  * module, every edge in the resolved graph, and a SHA-256 digest for each classpath artifact.
  */
final case class DependencyLock private[config] (
    input: String,
    main: String,
    test: String
)

object Lockfile:
  val FileName = "sprout.lock"
  private val Header = "sprout-lock-v1"

  def path(project: Project): Path = project.layout.root.resolve(FileName)

  def load(project: Project): IO[Option[DependencyLock]] = IO.blocking {
    val file = path(project)
    if !Files.isRegularFile(file) then None
    else
      decode(Files.readAllLines(file, StandardCharsets.UTF_8).toArray(new Array[String](0)).toList)
  }

  def require(project: Project): IO[DependencyLock] =
    load(project).flatMap(
      IO.fromOption(_)(
        SproutError.User("No sprout.lock found; run 'sprout lock' before building")
      )
    )

  def write(
      project: Project,
      main: ResolvedDependencies,
      test: ResolvedDependencies
  ): IO[Unit] = IO.blocking {
    val content = List(
      Header,
      "input=" + encode(input(project)),
      "main=" + encode(snapshot(main)),
      "test=" + encode(snapshot(test)),
      ""
    ).mkString("\n")
    replaceAtomically(path(project), content)
  }

  def verify(
      project: Project,
      main: ResolvedDependencies,
      test: ResolvedDependencies,
      lock: DependencyLock
  ): IO[Unit] = IO.blocking {
    if lock.input != input(project) then stale()
    if lock.main != snapshot(main) || lock.test != snapshot(test) then stale()
  }

  /** Verify the part of the lock needed by a main-only command without resolving test artifacts. */
  def verifyMain(project: Project, main: ResolvedDependencies, lock: DependencyLock): IO[Unit] =
    IO.blocking {
      if lock.input != input(project) || lock.main != snapshot(main) then stale()
    }

  def verifyTest(project: Project, test: ResolvedDependencies, lock: DependencyLock): IO[Unit] =
    IO.blocking {
      if lock.input != input(project) || lock.test != snapshot(test) then stale()
    }

  def verifyInput(project: Project, lock: DependencyLock): IO[Unit] = IO.blocking {
    if lock.input != input(project) then stale()
  }

  def mainModules(lock: DependencyLock): List[LockedModule] = modules(lock.main)
  def testModules(lock: DependencyLock): List[LockedModule] = modules(lock.test)

  private def stale(): Nothing =
    throw SproutError.User(
      "sprout.lock does not match the resolved dependencies; run 'sprout lock' to refresh it"
    )

  private def input(project: Project): String =
    val values = project.dependencies
      .sortBy(dependency => (dependency.scope.toString, dependency.display))
      .map(dependency =>
        List(
          dependency.scope.toString,
          dependency.organisation.value,
          dependency.artifact.value,
          dependency.version.value,
          dependency.crossVersion.toString
        ).mkString("\u0000")
      )
    (("scala=" + project.scalaVersion.value) :: values.map(value => "dependency=" + value))
      .mkString("\n")

  private def snapshot(dependencies: ResolvedDependencies): String =
    val modules = dependencies.graph.modules.sortBy(_.module.id).flatMap { dependency =>
      if dependency.artifacts.isEmpty then
        List(s"module\t${dependency.module.id}\t${dependency.version}\t")
      else
        dependency.artifacts.sortBy(_.toString).map { artifact =>
          s"module\t${dependency.module.id}\t${dependency.version}\t${sha256(artifact)}"
        }
    }
    val relations = dependencies.graph.relations
      .sortBy(relation =>
        (relation.parent.map(_.id).getOrElse(""), relation.child.id, relation.requestedVersion)
      )
      .map(relation =>
        s"relation\t${relation.parent.map(_.id).getOrElse("")}\t${relation.child.id}\t${relation.requestedVersion}\t${relation.selectedVersion}"
      )
    (modules ++ relations).mkString("\n")

  private def modules(snapshot: String): List[LockedModule] =
    snapshot.linesIterator
      .collect {
        case line if line.startsWith("module\t") =>
          line.split("\\t", -1).toList match
            case _ :: identifier :: version :: _ =>
              identifier.split(":", 2).toList match
                case organisation :: name :: Nil =>
                  Some(LockedModule(ResolvedModule(organisation, name), version))
                case _ => None
            case _ => None
      }
      .flatten
      .toList
      .distinct

  private def sha256(path: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val input = Files.newInputStream(path)
    try
      val buffer = Array.ofDim[Byte](64 * 1024)
      Iterator
        .continually(input.read(buffer))
        .takeWhile(_ != -1)
        .foreach(count => digest.update(buffer, 0, count))
    finally input.close()
    digest.digest().map(byte => f"${byte & 0xff}%02x").mkString

  private def decode(lines: List[String]): Option[DependencyLock] =
    lines match
      case header :: input :: main :: test :: _ if header == Header =>
        for
          inputValue <- input.stripPrefix("input=") match
            case value if value != input => decodeValue(value)
            case _                       => None
          mainValue <- main.stripPrefix("main=") match
            case value if value != main => decodeValue(value)
            case _                      => None
          testValue <- test.stripPrefix("test=") match
            case value if value != test => decodeValue(value)
            case _                      => None
        yield DependencyLock(inputValue, mainValue, testValue)
      case _ => None

  private def encode(value: String): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(value.getBytes(StandardCharsets.UTF_8))

  private def decodeValue(value: String): Option[String] =
    try Some(new String(Base64.getUrlDecoder.decode(value), StandardCharsets.UTF_8))
    catch case NonFatal(_) => None

  private def replaceAtomically(path: Path, content: String): Unit =
    Files.createDirectories(path.getParent)
    val temporary = Files.createTempFile(path.getParent, s".${path.getFileName}.", ".tmp")
    try
      Files.writeString(temporary, content, StandardCharsets.UTF_8)
      try
        Files.move(
          temporary,
          path,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
      catch
        case _: AtomicMoveNotSupportedException =>
          Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(temporary)
