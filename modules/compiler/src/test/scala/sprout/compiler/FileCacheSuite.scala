package sprout.compiler

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import sprout.core.*

class FileCacheSuite extends munit.FunSuite:
  test("atomically replaces binary metadata for future Zinc analysis stores") {
    val directory = Files.createTempDirectory("sprout-analysis-metadata")
    val destination = directory.resolve("analysis.bin")

    AtomicFile.writeBytes(destination, Array[Byte](1, 2, 3))
    AtomicFile.writeBytes(destination, Array[Byte](4, 5))

    assertEquals(Files.readAllBytes(destination).toList, List[Byte](4, 5))
    val stream = Files.list(directory)
    try assert(!stream.iterator.asScala.exists(_.getFileName.toString.endsWith(".tmp")))
    finally stream.close()
  }

  test("writes versioned cache metadata atomically") {
    val directory = Files.createTempDirectory("sprout-file-cache")
    val cache = FileCache(directory)
    val key = CacheKey("entry")

    cache.put(key, CachedValue("value")).unsafeRunSync()

    assertEquals(cache.get(key).unsafeRunSync().map(_.value), Some("value"))
    assert(Files.readString(directory.resolve("entry.cache")).contains("metadata-version=1"))
    val stream = Files.list(directory)
    try assert(!stream.iterator.asScala.exists(_.getFileName.toString.endsWith(".tmp")))
    finally stream.close()
  }

  test("ignores incompatible and corrupt cache metadata") {
    val directory = Files.createTempDirectory("sprout-file-cache-version")
    val key = CacheKey("entry")
    FileCache(directory, metadataVersion = 1).put(key, CachedValue("value")).unsafeRunSync()

    assertEquals(FileCache(directory, metadataVersion = 2).get(key).unsafeRunSync(), None)

    Files.write(directory.resolve("entry.cache"), Array[Byte](0xc3.toByte, 0x28.toByte))
    assertEquals(FileCache(directory).get(key).unsafeRunSync(), None)
  }

  test("invalidates changed dependencies and damaged compiled output") {
    val root = Files.createTempDirectory("sprout-caching-compiler")
    val source = Files.writeString(root.resolve("Main.scala"), "object Main")
    val dependency = Files.writeString(root.resolve("dependency.jar"), "same")
    val compilerJar = Files.writeString(root.resolve("compiler.jar"), "compiler")
    val output = root.resolve("classes")
    val request = CompilationRequest(
      List(source),
      List(dependency),
      List(compilerJar),
      output,
      ScalaVersion.from("3.3.6").toOption.get,
      jvmTarget = JvmTarget("21")
    )
    val delegate = RecordingCompiler()
    val compiler = CachingScalaCompiler(delegate, FileCache(root.resolve("cache")))

    assert(compiler.compile(request).unsafeRunSync().isInstanceOf[CompilationResult.Compiled])
    assertEquals(compiler.compile(request).unsafeRunSync(), CompilationResult.UpToDate)
    assertEquals(delegate.invocations.get(), 1)

    Files.writeString(dependency, "size")
    assert(compiler.compile(request).unsafeRunSync().isInstanceOf[CompilationResult.Compiled])
    assertEquals(delegate.invocations.get(), 2)

    Files.writeString(output.resolve("Main.class"), "damaged")
    assert(compiler.compile(request).unsafeRunSync().isInstanceOf[CompilationResult.Compiled])
    assertEquals(delegate.invocations.get(), 3)
  }

  test("recompiles when matching cache metadata is corrupt") {
    val root = Files.createTempDirectory("sprout-corrupt-cache")
    val source = Files.writeString(root.resolve("Main.scala"), "object Main")
    val compilerJar = Files.writeString(root.resolve("compiler.jar"), "compiler")
    val request = CompilationRequest(
      List(source),
      Nil,
      List(compilerJar),
      root.resolve("classes"),
      ScalaVersion.from("3.3.6").toOption.get
    )
    val cacheDirectory = root.resolve("cache")
    val delegate = RecordingCompiler()
    val compiler = CachingScalaCompiler(delegate, FileCache(cacheDirectory))
    compiler.compile(request).unsafeRunSync()
    val key = Hashing.compilationKey(request).unsafeRunSync()
    Files.writeString(cacheDirectory.resolve(s"${key.value}.cache"), "not a cache entry")

    assert(compiler.compile(request).unsafeRunSync().isInstanceOf[CompilationResult.Compiled])
    assertEquals(delegate.invocations.get(), 2)
  }

  private final class RecordingCompiler extends ScalaCompiler[IO]:
    val invocations = AtomicInteger()

    def compile(request: CompilationRequest): IO[CompilationResult] = IO.blocking {
      val invocation = invocations.incrementAndGet()
      Files.createDirectories(request.outputDirectory)
      Files.writeString(request.outputDirectory.resolve("Main.class"), s"compiled-$invocation")
      CompilationResult.Compiled(request.sources.size)
    }
