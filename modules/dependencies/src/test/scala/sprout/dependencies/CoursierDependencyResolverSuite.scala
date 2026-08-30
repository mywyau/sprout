package sprout.dependencies

import cats.effect.unsafe.implicits.global
import sprout.core.*

class CoursierDependencyResolverSuite extends munit.FunSuite:
  private val resolver = CoursierDependencyResolver()
  private val scalaVersion = ScalaVersion.from("3.3.6").toOption.get

  test("preserves the Cats Effect graph, provenance, versions, and artifacts") {
    val catsEffect = dependency("org.typelevel::cats-effect:3.6.3")

    val resolved = resolver.resolve(scalaVersion, List(catsEffect)).unsafeRunSync()

    assert(resolved.classpath.paths.exists(_.getFileName.toString == "cats-effect_3-3.6.3.jar"))
    val catsEffectNode = resolved.graph.matching("cats-effect").head
    val catsCore = resolved.graph.matching("cats-core").head
    assertEquals(catsEffectNode.version, "3.6.3")
    assert(catsEffectNode.direct)
    assert(!catsCore.direct)
    assert(catsCore.artifacts.exists(_.getFileName.toString.startsWith("cats-core_3-")))
    assert(resolved.graph.pathsTo(catsCore.module).exists(_.head == catsEffectNode.module))
    assertEquals(resolved.graph.roots.map(_.child), List(catsEffectNode.module))
  }

  test("records requested and selected versions when Coursier evicts a version") {
    val catsEffect = dependency("org.typelevel::cats-effect:3.6.3")
    val olderCatsCore = dependency("org.typelevel::cats-core:2.10.0")

    val graph = resolver
      .resolve(scalaVersion, List(catsEffect, olderCatsCore))
      .unsafeRunSync()
      .graph
    val catsCoreRoot = graph.roots.find(_.child.displayName == "cats-core").get

    assertEquals(catsCoreRoot.requestedVersion, "2.10.0")
    assertEquals(catsCoreRoot.selectedVersion, "2.11.0")
    assert(catsCoreRoot.evicted)
  }

  private def dependency(value: String): Dependency =
    Dependency.parse(value, DependencyScope.Main).fold(fail(_), identity)
