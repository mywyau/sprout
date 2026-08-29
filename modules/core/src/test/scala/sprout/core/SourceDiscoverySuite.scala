package sprout.core

import cats.effect.unsafe.implicits.global
import java.nio.file.Files

class SourceDiscoverySuite extends munit.FunSuite:
  test("discovers Scala files recursively and ignores other files") {
    val root = Files.createTempDirectory("sprout-sources")
    val nested = Files.createDirectories(root.resolve("nested"))
    Files.writeString(root.resolve("A.scala"), "object A")
    Files.writeString(nested.resolve("B.scala"), "object B")
    Files.writeString(nested.resolve("notes.txt"), "ignored")
    val files = SourceDiscovery.scalaSources(List(root)).unsafeRunSync()
    assertEquals(files.map(_.getFileName.toString), List("A.scala", "B.scala"))
  }

  test("a missing conventional directory has no sources") {
    val missing = java.nio.file.Path.of("target", "definitely-missing-sources")
    assertEquals(SourceDiscovery.scalaSources(List(missing)).unsafeRunSync(), Nil)
  }
