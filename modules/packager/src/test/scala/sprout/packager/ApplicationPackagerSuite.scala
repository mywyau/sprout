package sprout.packager

import cats.effect.unsafe.implicits.global
import java.io.BufferedInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

class ApplicationPackagerSuite extends munit.FunSuite:
  test("creates deterministic application distributions, archives, and checksums") {
    val root = Files.createTempDirectory("sprout-packager")
    val classes = Files.createDirectories(root.resolve("classes/example"))
    val resources = Files.createDirectories(root.resolve("resources"))
    val dependencies = Files.createDirectories(root.resolve("dependencies"))
    Files.write(classes.resolve("Main.class"), Array[Byte](1, 2, 3))
    Files.writeString(resources.resolve("application.conf"), "message = sprout\n")
    val firstDependency = Files.write(dependencies.resolve("alpha.jar"), Array[Byte](4, 5))
    val secondDependency = Files.write(dependencies.resolve("beta.jar"), Array[Byte](6, 7))
    val request = ApplicationPackageRequest(
      "sample",
      "3.3.6",
      "example.Main",
      root.resolve("classes"),
      List(resources),
      List(firstDependency, secondDependency),
      root.resolve("output")
    )

    val first = ApplicationPackager().create(request).unsafeRunSync()
    val firstTarHash = sha256(first.tarArchive)
    val firstZipHash = sha256(first.zipArchive)

    val applicationJar = first.applicationDirectory.resolve("lib/sample.jar")
    val jar = new JarFile(applicationJar.toFile)
    try
      assertEquals(jar.getManifest.getMainAttributes.getValue("Main-Class"), "example.Main")
      assertEquals(
        jar.getManifest.getMainAttributes.getValue("Class-Path"),
        "0001-alpha.jar 0002-beta.jar"
      )
      assert(jar.getEntry("example/Main.class") != null)
      assert(jar.getEntry("application.conf") != null)
    finally jar.close()

    assert(Files.isRegularFile(first.applicationDirectory.resolve("bin/sample")))
    assert(Files.isRegularFile(first.applicationDirectory.resolve("bin/sample.cmd")))
    assertEquals(first.dependencyCount, 2)
    assertChecksums(
      first.applicationDirectory.resolve("metadata/checksums.txt"),
      first.applicationDirectory
    )
    assertChecksums(first.archiveChecksums, request.outputDirectory)
    assertEquals(
      tarEntries(first.tarArchive).toSet,
      expectedArchiveEntries
    )
    val zip = new ZipFile(first.zipArchive.toFile)
    try assertEquals(zip.entries.asScala.map(_.getName).toSet, expectedArchiveEntries)
    finally zip.close()

    val second = ApplicationPackager().create(request).unsafeRunSync()
    assertEquals(sha256(second.tarArchive), firstTarHash)
    assertEquals(sha256(second.zipArchive), firstZipHash)
  }

  private val expectedArchiveEntries =
    Set(
      "sample/",
      "sample/bin/",
      "sample/bin/sample",
      "sample/bin/sample.cmd",
      "sample/lib/",
      "sample/lib/sample.jar",
      "sample/lib/0001-alpha.jar",
      "sample/lib/0002-beta.jar",
      "sample/metadata/",
      "sample/metadata/application.properties",
      "sample/metadata/checksums.txt"
    )

  test("rejects duplicate entries from classes and resources") {
    val root = Files.createTempDirectory("sprout-packager-duplicates")
    val classes = Files.createDirectories(root.resolve("classes"))
    val resources = Files.createDirectories(root.resolve("resources"))
    Files.writeString(classes.resolve("duplicate.txt"), "classes")
    Files.writeString(resources.resolve("duplicate.txt"), "resources")

    intercept[sprout.core.SproutError.User](
      ApplicationPackager()
        .create(
          ApplicationPackageRequest(
            "sample",
            "3.3.6",
            "example.Main",
            classes,
            List(resources),
            Nil,
            root.resolve("output")
          )
        )
        .unsafeRunSync()
    )
  }

  private def assertChecksums(checksumFile: Path, root: Path): Unit =
    Files.readAllLines(checksumFile, StandardCharsets.UTF_8).forEach { line =>
      val parts = line.split("  ", 2)
      assertEquals(parts.length, 2)
      assertEquals(sha256(root.resolve(parts(1))), parts(0))
    }

  private def tarEntries(path: Path): List[String] =
    val gzip = new GzipCompressorInputStream(
      new BufferedInputStream(Files.newInputStream(path))
    )
    val tar = new TarArchiveInputStream(gzip)
    val entries = ListBuffer.empty[String]
    try
      var entry = tar.getNextEntry
      while entry != null do
        entries += entry.getName
        entry = tar.getNextEntry
    finally tar.close()
    entries.toList

  private def sha256(path: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(Files.readAllBytes(path)).map("%02x".format(_)).mkString
