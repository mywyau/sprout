package sprout.cli

import cats.effect.IO
import com.google.gson.JsonParser
import sprout.config.{Lockfile, ProjectConfig}
import sprout.core.Project
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

enum DoctorStatus:
  case Ok, Warning, Error

final case class DoctorCheck(status: DoctorStatus, label: String, detail: String)

final case class DoctorReport(checks: List[DoctorCheck]):
  def healthy: Boolean = !checks.exists(_.status == DoctorStatus.Error)

  def render: String =
    val body = checks
      .map { check =>
        val marker = check.status match
          case DoctorStatus.Ok      => "✓"
          case DoctorStatus.Warning => "!"
          case DoctorStatus.Error   => "✗"
        s"$marker ${check.label}: ${check.detail}"
      }
      .mkString("\n")
    val summary =
      if healthy then "\n\nSprout is ready to build this project."
      else "\n\nFix the blocking checks above, then run sprout doctor again."
    s"Sprout doctor\n\n$body$summary"

object Doctor:
  private enum LockStatus:
    case Missing, Current, Stale, Malformed, NotChecked

  def inspect(from: Path): IO[DoctorReport] =
    for
      configuration <- ProjectConfig.locate(from).attempt
      root = configuration.toOption
        .map(_.toAbsolutePath.normalize.getParent)
        .getOrElse(
          from.toAbsolutePath.normalize
        )
      project <- configuration match
        case Right(path) => ProjectConfig.load(path).attempt
        case Left(error) => IO.pure(Left(error))
      lock <- project match
        case Right(value) => lockStatus(value)
        case Left(_)      => IO.pure(LockStatus.NotChecked)
      report <- IO.blocking(check(root, project, lock))
    yield report

  private def check(
      root: Path,
      project: Either[Throwable, Project],
      lockStatus: LockStatus
  ): DoctorReport =
    val configuration = project match
      case Right(value) =>
        DoctorCheck(
          DoctorStatus.Ok,
          "Configuration",
          s"${value.name.value} (Scala ${value.scalaVersion.value})"
        )
      case Left(error) =>
        DoctorCheck(DoctorStatus.Error, "Configuration", concise(error))
    DoctorReport(
      List(jdk, configuration, projectLayout(project), lockfile(project, lockStatus)) ++
        permissions(root) ++ List(cache, bsp(root))
    )

  private def jdk: DoctorCheck =
    val version = Option(System.getProperty("java.version")).getOrElse("unknown")
    val runtime = Option(System.getProperty("java.runtime.name")).getOrElse("Java")
    if javaMajor(version).exists(_ >= 17) then
      DoctorCheck(DoctorStatus.Ok, "JDK", s"$runtime $version")
    else
      DoctorCheck(
        DoctorStatus.Error,
        "JDK",
        s"Java $version found; Sprout requires JDK 17 or newer"
      )

  private def projectLayout(project: Either[Throwable, Project]): DoctorCheck =
    project match
      case Left(_) =>
        DoctorCheck(
          DoctorStatus.Warning,
          "Project layout",
          "not checked until sprout.toml is valid"
        )
      case Right(value) =>
        val missing = List(
          "src/main/scala" -> value.layout.mainSources,
          "src/test/scala" -> value.layout.testSources
        ).collect { case (name, paths) if !paths.exists(Files.isDirectory(_)) => name }
        if missing.isEmpty then
          DoctorCheck(DoctorStatus.Ok, "Project layout", "conventional source roots found")
        else
          DoctorCheck(
            DoctorStatus.Warning,
            "Project layout",
            s"missing ${missing.mkString(" and ")}; create it before compiling those sources"
          )

  private def lockfile(project: Either[Throwable, Project], status: LockStatus): DoctorCheck =
    project match
      case Left(_) =>
        DoctorCheck(DoctorStatus.Warning, "Lockfile", "not checked until sprout.toml is valid")
      case Right(_) =>
        status match
          case LockStatus.Missing =>
            DoctorCheck(DoctorStatus.Error, "Lockfile", "missing sprout.lock; run sprout lock")
          case LockStatus.Current =>
            DoctorCheck(DoctorStatus.Ok, "Lockfile", "sprout.lock matches sprout.toml")
          case LockStatus.Stale =>
            DoctorCheck(
              DoctorStatus.Error,
              "Lockfile",
              "does not match sprout.toml; run sprout lock"
            )
          case LockStatus.Malformed =>
            DoctorCheck(DoctorStatus.Error, "Lockfile", "sprout.lock is malformed; run sprout lock")
          case LockStatus.NotChecked =>
            DoctorCheck(DoctorStatus.Warning, "Lockfile", "not checked until sprout.toml is valid")

  private def lockStatus(project: Project): IO[LockStatus] =
    IO.blocking(Files.exists(Lockfile.path(project))).flatMap {
      case false => IO.pure(LockStatus.Missing)
      case true  =>
        Lockfile.load(project).flatMap {
          case None       => IO.pure(LockStatus.Malformed)
          case Some(lock) =>
            Lockfile.verifyInput(project, lock).attempt.map {
              case Right(_) => LockStatus.Current
              case Left(_)  => LockStatus.Stale
            }
        }
    }

  private def permissions(root: Path): List[DoctorCheck] =
    val rootStatus =
      if Files.isReadable(root) && Files.isWritable(root) then
        DoctorCheck(
          DoctorStatus.Ok,
          "Project permissions",
          "project directory is readable and writable"
        )
      else
        DoctorCheck(
          DoctorStatus.Error,
          "Project permissions",
          "project directory must be readable and writable"
        )
    val state = root.resolve(".sprout")
    val stateStatus =
      if !Files.exists(state) then
        DoctorCheck(
          DoctorStatus.Ok,
          "Build-state permissions",
          ".sprout will be created on first build"
        )
      else if Files.isDirectory(state) && Files.isReadable(state) && Files.isWritable(state) then
        DoctorCheck(DoctorStatus.Ok, "Build-state permissions", ".sprout is readable and writable")
      else
        DoctorCheck(
          DoctorStatus.Error,
          "Build-state permissions",
          ".sprout must be a readable, writable directory"
        )
    List(rootStatus, stateStatus)

  private def cache: DoctorCheck =
    val path = coursierCache
    if Files.isDirectory(path) && Files.isReadable(path) && Files.isWritable(path) then
      DoctorCheck(DoctorStatus.Ok, "Dependency cache", path.toString)
    else if Files.exists(path) then
      DoctorCheck(
        DoctorStatus.Warning,
        "Dependency cache",
        s"$path is not a readable, writable directory"
      )
    else
      DoctorCheck(
        DoctorStatus.Warning,
        "Dependency cache",
        s"not present at $path; it will be populated when dependencies are resolved"
      )

  private def bsp(root: Path): DoctorCheck =
    val connection = root.resolve(".bsp").resolve("sprout.json")
    if !Files.isRegularFile(connection) then
      DoctorCheck(DoctorStatus.Warning, "BSP", "not configured; run sprout setup-ide to use Metals")
    else
      try
        val json = JsonParser.parseString(Files.readString(connection, StandardCharsets.UTF_8))
        val name = Option(json.getAsJsonObject.get("name")).map(_.getAsString)
        val argv = Option(json.getAsJsonObject.get("argv")).filter(_.isJsonArray)
        if name.contains("Sprout") && argv.nonEmpty then
          DoctorCheck(DoctorStatus.Ok, "BSP", ".bsp/sprout.json is configured")
        else DoctorCheck(DoctorStatus.Warning, "BSP", "connection is invalid; run sprout setup-ide")
      catch
        case NonFatal(_) =>
          DoctorCheck(DoctorStatus.Warning, "BSP", "connection is invalid; run sprout setup-ide")

  private def coursierCache: Path =
    Option(System.getenv("COURSIER_CACHE")).filter(_.nonEmpty).map(Path.of(_)).getOrElse {
      val home = Path.of(Option(System.getProperty("user.home")).getOrElse("."))
      val operatingSystem = Option(System.getProperty("os.name")).getOrElse("").toLowerCase
      if operatingSystem.contains("mac") then home.resolve("Library/Caches/Coursier/v1")
      else if operatingSystem.contains("win") then home.resolve("AppData/Local/Coursier/Cache/v1")
      else home.resolve(".cache/coursier/v1")
    }

  private def javaMajor(version: String): Option[Int] =
    val digits = "\\d+".r.findFirstIn(version)
    digits.flatMap(_.toIntOption)

  private def concise(error: Throwable): String =
    Option(error.getMessage)
      .filter(_.nonEmpty)
      .getOrElse(error.getClass.getSimpleName)
      .replace('\n', ' ')
