package sprout.dependencies

import cats.effect.unsafe.implicits.global
import sprout.core.*
import java.util.zip.ZipFile

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

  test("resolves the exact module versions recorded by a lock") {
    val catsEffect = dependency("org.typelevel::cats-effect:3.6.3")
    val selected = resolver.resolve(scalaVersion, List(catsEffect)).unsafeRunSync()
    val locked = selected.graph.modules.map(node => LockedModule(node.module, node.version))

    val resolved = resolver.resolveLocked(scalaVersion, List(catsEffect), locked).unsafeRunSync()

    assert(resolved.classpath.paths.exists(_.getFileName.toString == "cats-effect_3-3.6.3.jar"))
    assertEquals(resolved.graph.matching("cats-effect").head.version, "3.6.3")
  }

  test("resolves the matching precompiled Scala 3 Zinc bridge") {
    val bridge = resolver.compilerBridge(scalaVersion).unsafeRunSync()
    val zip = ZipFile(bridge.toFile)
    try
      val service = zip.getEntry("META-INF/services/xsbti.compile.CompilerInterface2")
      assert(service != null)
      val contents = new String(zip.getInputStream(service).readAllBytes()).trim
      assertEquals(contents, "dotty.tools.xsbt.CompilerBridge")
    finally zip.close()
  }

  private def dependency(value: String): Dependency =
    Dependency.parse(value, DependencyScope.Main).fold(fail(_), identity)
