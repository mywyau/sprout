package sprout.core

import java.nio.file.Path

opaque type ProjectName = String
object ProjectName:
  def from(value: String): Either[String, ProjectName] =
    val trimmed = value.trim
    Either.cond(
      trimmed.matches("[A-Za-z][A-Za-z0-9_-]*"),
      trimmed,
      "project name must start with a letter and contain only letters, digits, '-' or '_'"
    )
  extension (value: ProjectName) def value: String = value

opaque type ScalaVersion = String
object ScalaVersion:
  def from(value: String): Either[String, ScalaVersion] =
    Either.cond(
      value.matches("3\\.\\d+\\.\\d+(?:[-+].+)?"),
      value,
      s"unsupported Scala version '$value' (Sprout currently requires an exact Scala 3 version)"
    )
  extension (value: ScalaVersion)
    def value: String = value
    def binaryVersion: String = "3"

opaque type Organisation = String
object Organisation:
  def from(value: String): Either[String, Organisation] = nonEmpty("organisation", value)
  extension (value: Organisation) def value: String = value

opaque type ArtifactName = String
object ArtifactName:
  def from(value: String): Either[String, ArtifactName] = nonEmpty("artifact", value)
  extension (value: ArtifactName) def value: String = value

opaque type DependencyVersion = String
object DependencyVersion:
  def from(value: String): Either[String, DependencyVersion] = nonEmpty("dependency version", value)
  extension (value: DependencyVersion) def value: String = value

private def nonEmpty[A](label: String, value: String): Either[String, A] =
  Either.cond(value.trim.nonEmpty, value.trim.asInstanceOf[A], s"$label must not be empty")

enum DependencyScope:
  case Main, Test

enum CrossVersion:
  case None, ScalaBinary

final case class Dependency(
    organisation: Organisation,
    artifact: ArtifactName,
    version: DependencyVersion,
    scope: DependencyScope,
    crossVersion: CrossVersion
):
  def resolvedArtifact(scalaVersion: ScalaVersion): String = crossVersion match
    case CrossVersion.None        => artifact.value
    case CrossVersion.ScalaBinary => s"${artifact.value}_${scalaVersion.binaryVersion}"

  def display: String =
    val separator = if crossVersion == CrossVersion.ScalaBinary then "::" else ":"
    s"${organisation.value}$separator${artifact.value}:${version.value}"

object Dependency:
  def parse(value: String, scope: DependencyScope): Either[String, Dependency] =
    val cross = "^([^:]+)::([^:]+):([^:]+)$".r
    val plain = "^([^:]+):([^:]+):([^:]+)$".r
    value.trim match
      case cross(org, artifact, version) =>
        create(org, artifact, version, scope, CrossVersion.ScalaBinary)
      case plain(org, artifact, version) => create(org, artifact, version, scope, CrossVersion.None)
      case _                             =>
        Left(
          s"invalid dependency '$value' (expected organisation:artifact:version or organisation::artifact:version)"
        )

  private def create(
      org: String,
      artifact: String,
      version: String,
      scope: DependencyScope,
      cross: CrossVersion
  ): Either[String, Dependency] =
    for
      o <- Organisation.from(org)
      a <- ArtifactName.from(artifact)
      v <- DependencyVersion.from(version)
    yield Dependency(o, a, v, scope, cross)

final case class ProjectLayout(
    root: Path,
    mainSources: List[Path],
    mainResources: List[Path],
    testSources: List[Path],
    testResources: List[Path],
    buildDirectory: Path
):
  def mainClasses: Path = buildDirectory.resolve("classes")
  def testClasses: Path = buildDirectory.resolve("test-classes")
  def cacheDirectory: Path = buildDirectory.resolve("cache")
  def metadataDirectory: Path = buildDirectory.resolve("metadata")

object ProjectLayout:
  def conventional(root: Path): ProjectLayout = ProjectLayout(
    root,
    List(root.resolve("src/main/scala")),
    List(root.resolve("src/main/resources")),
    List(root.resolve("src/test/scala")),
    List(root.resolve("src/test/resources")),
    root.resolve(".sprout")
  )

final case class Project(
    name: ProjectName,
    scalaVersion: ScalaVersion,
    dependencies: List[Dependency],
    layout: ProjectLayout
):
  def mainDependencies: List[Dependency] = dependencies.filter(_.scope == DependencyScope.Main)
  def testDependencies: List[Dependency] = dependencies

final case class ResolvedArtifact(module: String, version: String, file: Path)
final case class ResolvedClasspath(artifacts: List[ResolvedArtifact]):
  def paths: List[Path] = artifacts.map(_.file).distinct
  def render: String = paths.mkString(java.io.File.pathSeparator)

final case class CompilationRequest(
    sources: List[Path],
    classpath: List[Path],
    compilerClasspath: List[Path],
    outputDirectory: Path,
    scalaVersion: ScalaVersion,
    compilerOptions: List[String] = Nil
)

enum CompilationResult:
  case Compiled(sourceCount: Int)
  case UpToDate
