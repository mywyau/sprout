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
  def from(value: String): Either[String, Organisation] = coordinatePart("organisation", value)
  extension (value: Organisation) def value: String = value

opaque type ArtifactName = String
object ArtifactName:
  def from(value: String): Either[String, ArtifactName] = coordinatePart("artifact", value)
  extension (value: ArtifactName) def value: String = value

opaque type DependencyVersion = String
object DependencyVersion:
  def from(value: String): Either[String, DependencyVersion] =
    coordinatePart("dependency version", value)
  extension (value: DependencyVersion) def value: String = value

private def coordinatePart[A](label: String, value: String): Either[String, A] =
  val trimmed = value.trim
  Either.cond(
    trimmed.nonEmpty && !trimmed.exists(character => character.isWhitespace || character == ':'),
    trimmed.asInstanceOf[A],
    s"$label must be non-empty and contain no whitespace or ':'"
  )

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
  def packageDirectory: Path = buildDirectory.resolve("package")

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

final case class ResolvedModule(organisation: String, name: String):
  def id: String = s"$organisation:$name"
  def displayName: String = name.replaceFirst("_(?:2\\.\\d+|3)$", "")

final case class ResolvedDependency(
    module: ResolvedModule,
    version: String,
    direct: Boolean,
    artifacts: List[Path]
)

final case class DependencyRelation(
    parent: Option[ResolvedModule],
    child: ResolvedModule,
    requestedVersion: String,
    selectedVersion: String
):
  def evicted: Boolean = requestedVersion != selectedVersion

final case class ResolvedDependencyGraph(
    modules: List[ResolvedDependency],
    relations: List[DependencyRelation]
):
  private lazy val byModule = modules.map(module => module.module -> module).toMap

  def dependency(module: ResolvedModule): Option[ResolvedDependency] = byModule.get(module)

  def roots: List[DependencyRelation] =
    relations.filter(_.parent.isEmpty).sortBy(relation => relation.child.id)

  def children(module: ResolvedModule): List[DependencyRelation] =
    relations
      .filter(_.parent.contains(module))
      .sortBy(relation => (relation.child.id, relation.requestedVersion))

  def parents(module: ResolvedModule): List[ResolvedModule] =
    relations
      .flatMap(relation => Option.when(relation.child == module)(relation.parent).flatten)
      .distinct
      .sortBy(_.id)

  def matching(query: String): List[ResolvedDependency] =
    val normalized = query.trim
    modules
      .filter { dependency =>
        normalized.split(":", 2).toList match
          case organisation :: name :: Nil =>
            dependency.module.organisation == organisation &&
            (dependency.module.name == name || dependency.module.displayName == name)
          case _ =>
            dependency.module.name == normalized || dependency.module.displayName == normalized
      }
      .sortBy(_.module.id)

  def pathsTo(target: ResolvedModule): List[List[ResolvedModule]] =
    def loop(
        current: ResolvedModule,
        path: List[ResolvedModule]
    ): List[List[ResolvedModule]] =
      if current == target then List(path :+ current)
      else if path.contains(current) then Nil
      else children(current).flatMap(relation => loop(relation.child, path :+ current))

    roots.flatMap(relation => loop(relation.child, Nil)).distinct.sortBy(_.map(_.id).mkString("/"))

final case class ResolvedDependencies(
    classpath: ResolvedClasspath,
    graph: ResolvedDependencyGraph
)

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
