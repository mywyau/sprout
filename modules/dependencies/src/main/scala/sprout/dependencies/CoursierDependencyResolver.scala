package sprout.dependencies

import cats.effect.IO
import coursier.{Dependency as CoursierDependency, Fetch}
import coursier.core.{
  Classifier,
  Dependency as CoreDependency,
  Module as CoursierModule,
  ModuleName,
  Organization
}
import coursier.version.VersionConstraint
import sprout.core.*

final class CoursierDependencyResolver extends DependencyResolver[IO]:
  def resolve(
      scalaVersion: ScalaVersion,
      dependencies: List[Dependency]
  ): IO[ResolvedDependencies] =
    val scalaLibrary = Dependency
      .parse(s"org.scala-lang:scala3-library_3:${scalaVersion.value}", DependencyScope.Main)
      .fold(message => throw IllegalArgumentException(message), identity)
    fetch(scalaVersion, scalaLibrary :: dependencies, dependencies).map { result =>
      ResolvedDependencies(result.classpath, graph(result, dependencies, scalaVersion))
    }

  def compilerClasspath(scalaVersion: ScalaVersion): IO[ResolvedClasspath] =
    val compiler = Dependency
      .parse(s"org.scala-lang:scala3-compiler_3:${scalaVersion.value}", DependencyScope.Main)
      .fold(message => throw IllegalArgumentException(message), identity)
    fetch(scalaVersion, List(compiler), Nil).map(_.classpath)

  def compilerBridge(scalaVersion: ScalaVersion): IO[java.nio.file.Path] =
    val bridge = Dependency
      .parse(s"org.scala-lang:scala3-sbt-bridge:${scalaVersion.value}", DependencyScope.Main)
      .fold(message => throw IllegalArgumentException(message), identity)
    fetch(scalaVersion, List(bridge), Nil).flatMap { result =>
      IO.fromOption(
        result.classpath.paths.find(_.getFileName.toString.startsWith("scala3-sbt-bridge-"))
      )(
        SproutError.User(s"Scala ${scalaVersion.value} compiler bridge was not downloaded")
      )
    }

  def resolveSources(
      scalaVersion: ScalaVersion,
      dependencies: List[Dependency]
  ): IO[ResolvedClasspath] =
    val scalaLibrary = Dependency
      .parse(s"org.scala-lang:scala3-library_3:${scalaVersion.value}", DependencyScope.Main)
      .fold(message => throw IllegalArgumentException(message), identity)
    fetch(scalaVersion, scalaLibrary :: dependencies, Nil, sources = true).map(_.classpath)

  private def fetch(
      scalaVersion: ScalaVersion,
      dependencies: List[Dependency],
      directDependencies: List[Dependency],
      sources: Boolean = false
  ): IO[FetchResult] =
    IO.blocking {
      val coursierDependencies = dependencies.map(toCoursier(_, scalaVersion))
      val base = Fetch().withDependencies(coursierDependencies)
      val configured =
        if sources then base.withMainArtifacts(false).withClassifiers(Set(Classifier.sources))
        else base
      val result = configured.runResult()
      val artifacts = result.detailedArtifacts0.toList.map { case (dependency, _, _, file) =>
        ResolvedArtifact(module(dependency.module).id, version(dependency), file.toPath)
      }
      FetchResult(
        ResolvedClasspath(artifacts),
        result.resolution,
        directDependencies.map(toCoursier(_, scalaVersion)),
        result.detailedArtifacts0.toList.map { case (dependency, _, _, file) =>
          (module(dependency.module), file.toPath)
        }
      )
    }.handleErrorWith { error =>
      val dependency = dependencies.last
      IO.raiseError(
        SproutError.Resolution(
          dependency.display,
          scalaVersion,
          s"${dependency.organisation.value}:${dependency.resolvedArtifact(scalaVersion)}:${dependency.version.value}",
          error
        )
      )
    }

  private def graph(
      result: FetchResult,
      directDependencies: List[Dependency],
      scalaVersion: ScalaVersion
  ): ResolvedDependencyGraph =
    val selected = result.resolution.orderedDependencies.toList
      .map(dependency => module(dependency.module) -> dependency)
      .toMap
    val roots = result.direct.map { requested =>
      val selectedDependency = selected.getOrElse(module(requested.module), requested)
      DependencyRelation(
        None,
        module(requested.module),
        version(requested),
        version(selectedDependency)
      )
    }
    val visited = scala.collection.mutable.Set.empty[ResolvedModule]
    val relations = scala.collection.mutable.ListBuffer.empty[DependencyRelation]

    def visit(parent: CoreDependency): Unit =
      val parentModule = module(parent.module)
      if visited.add(parentModule) then
        val dependencies = result.resolution
          .dependenciesOf0(parent)
          .fold(error => throw error, identity)
        dependencies.toList.foreach { requestedChild =>
          val childModule = module(requestedChild.module)
          val selectedChild = selected.getOrElse(childModule, requestedChild)
          relations += DependencyRelation(
            Some(parentModule),
            childModule,
            version(requestedChild),
            version(selectedChild)
          )
          visit(selectedChild)
        }

    roots.foreach(root => selected.get(root.child).foreach(visit))
    val directModules = directDependencies
      .map(dependency =>
        ResolvedModule(dependency.organisation.value, dependency.resolvedArtifact(scalaVersion))
      )
      .toSet
    val allRelations = (roots ++ relations).distinct
    val reachable = allRelations.map(_.child).toSet
    val artifacts = result.artifacts.groupMap(_._1)(_._2)
    val modules = reachable.toList.sorted(using Ordering.by(_.id)).map { resolvedModule =>
      val dependency = selected(resolvedModule)
      ResolvedDependency(
        resolvedModule,
        version(dependency),
        directModules.contains(resolvedModule),
        artifacts.getOrElse(resolvedModule, Nil).distinct
      )
    }
    ResolvedDependencyGraph(modules, allRelations)

  private def toCoursier(dependency: Dependency, scalaVersion: ScalaVersion): CoreDependency =
    CoursierDependency(
      CoursierModule(
        Organization(dependency.organisation.value),
        ModuleName(dependency.resolvedArtifact(scalaVersion)),
        Map.empty
      ),
      VersionConstraint(dependency.version.value)
    )

  private def module(value: CoursierModule): ResolvedModule =
    ResolvedModule(value.organization.value, value.name.value)

  private def version(dependency: CoreDependency): String =
    dependency.versionConstraint.asString

  private final case class FetchResult(
      classpath: ResolvedClasspath,
      resolution: coursier.core.Resolution,
      direct: List[CoreDependency],
      artifacts: List[(ResolvedModule, java.nio.file.Path)]
  )
