package sprout.cli

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import sprout.core.*
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

class BuildMetadataCacheSuite extends munit.FunSuite:
  test("reuses resolved dependency metadata and safely discards corrupt entries") {
    val root = Files.createTempDirectory("sprout-build-metadata")
    val cache = BuildMetadataCache(root)
    val artifact = Files.writeString(root.resolve("library.jar"), "library")
    val dependency = Dependency
      .parse("org.example::library:1.0.0", DependencyScope.Main)
      .toOption
      .get
    val module = ResolvedModule("org.example", "library_3")
    val resolved = ResolvedDependencies(
      ResolvedClasspath(List(ResolvedArtifact(module.id, "1.0.0", artifact))),
      ResolvedDependencyGraph(
        List(ResolvedDependency(module, "1.0.0", direct = true, List(artifact))),
        List(DependencyRelation(None, module, "1.0.0", "1.0.0"))
      )
    )
    val loads = AtomicInteger()
    val load = IO(loads.incrementAndGet()).as(resolved)
    val scalaVersion = ScalaVersion.from("3.3.6").toOption.get

    assert(!cache.dependencies(scalaVersion, List(dependency))(load).unsafeRunSync().cached)
    val hit = cache.dependencies(scalaVersion, List(dependency))(load).unsafeRunSync()
    assert(hit.cached)
    assertEquals(hit.value, resolved)
    assertEquals(loads.get(), 1)

    val changedDependency = Dependency
      .parse("org.example::library:1.0.1", DependencyScope.Main)
      .toOption
      .get
    assert(!cache.dependencies(scalaVersion, List(changedDependency))(load).unsafeRunSync().cached)
    assertEquals(loads.get(), 2)

    corruptEntries(root)
    assert(!cache.dependencies(scalaVersion, List(dependency))(load).unsafeRunSync().cached)
    assertEquals(loads.get(), 3)
  }

  test("reuses a main class only while its compiled class remains present") {
    val root = Files.createTempDirectory("sprout-main-class-metadata")
    val classes = Files.createDirectories(root.resolve("classes"))
    Files.createDirectories(classes.resolve("example"))
    val classFile = Files.write(classes.resolve("example/Main.class"), Array[Byte](1))
    val cache = BuildMetadataCache(root)
    val discoveries = AtomicInteger()
    val discover = IO(discoveries.incrementAndGet()).as("example.Main")
    val key = CacheKey("compile-key")

    assert(!cache.mainClass(key, classes)(discover).unsafeRunSync().cached)
    assert(cache.mainClass(key, classes)(discover).unsafeRunSync().cached)
    assertEquals(discoveries.get(), 1)

    Files.delete(classFile)
    assert(!cache.mainClass(key, classes)(discover).unsafeRunSync().cached)
    assertEquals(discoveries.get(), 2)
  }

  private def corruptEntries(root: Path): Unit =
    val stream = Files.walk(root)
    try
      stream.iterator.asScala
        .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".cache"))
        .foreach(path => Files.writeString(path, "corrupt"))
    finally stream.close()
