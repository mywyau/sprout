package sprout.dependencies

import cats.effect.IO
import coursierapi.{Dependency as CoursierDependency, Fetch}
import sprout.core.*
import scala.jdk.CollectionConverters.*

final class CoursierDependencyResolver extends DependencyResolver[IO]:
  def resolve(scalaVersion: ScalaVersion, dependencies: List[Dependency]): IO[ResolvedClasspath] =
    val scalaLibrary = Dependency
      .parse(s"org.scala-lang:scala3-library_3:${scalaVersion.value}", DependencyScope.Main)
      .fold(message => throw IllegalArgumentException(message), identity)
    fetch(scalaVersion, scalaLibrary :: dependencies)

  def compilerClasspath(scalaVersion: ScalaVersion): IO[ResolvedClasspath] =
    val compiler = Dependency
      .parse(s"org.scala-lang:scala3-compiler_3:${scalaVersion.value}", DependencyScope.Main)
      .fold(message => throw IllegalArgumentException(message), identity)
    fetch(scalaVersion, List(compiler))

  private def fetch(
      scalaVersion: ScalaVersion,
      dependencies: List[Dependency]
  ): IO[ResolvedClasspath] =
    IO.blocking {
      val fetch = Fetch.create()
      dependencies.foreach { dependency =>
        fetch.addDependencies(
          CoursierDependency.of(
            dependency.organisation.value,
            dependency.resolvedArtifact(scalaVersion),
            dependency.version.value
          )
        )
      }
      val files = fetch.fetch().asScala.toList.map(_.toPath)
      ResolvedClasspath(files.map(path => ResolvedArtifact(path.getFileName.toString, "", path)))
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
