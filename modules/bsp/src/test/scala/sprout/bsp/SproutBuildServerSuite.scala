package sprout.bsp

import ch.epfl.scala.bsp4j.{DependencySourcesParams, ScalacOptionsParams, SourcesParams}
import java.nio.file.Files
import scala.jdk.CollectionConverters.*

class SproutBuildServerSuite extends munit.FunSuite:
  test("exposes main and test targets with resolved Scala classpaths") {
    val root = Files.createTempDirectory("sprout-bsp-workspace")
    Files.createDirectories(root.resolve("src/main/scala"))
    Files.createDirectories(root.resolve("src/test/scala"))
    Files.writeString(
      root.resolve("sprout.toml"),
      """[project]
        |name = "bsp-check"
        |scala = "3.3.6"
        |
        |[dependencies]
        |cats-effect = "org.typelevel::cats-effect:3.6.3"
        |""".stripMargin
    )
    val server = SproutBuildServer(root, "test")
    val targets = server.workspaceBuildTargets().get().getTargets.asScala.toList

    assertEquals(targets.map(_.getDisplayName), List("bsp-check (main)", "bsp-check (test)"))
    assertEquals(targets(1).getDependencies.asScala.toList, List(targets(0).getId))

    val options = server
      .buildTargetScalacOptions(ScalacOptionsParams(targets.map(_.getId).asJava))
      .get()
      .getItems
      .asScala
      .toList
    assert(options.head.getClasspath.asScala.exists(_.contains("cats-effect_3-3.6.3.jar")))
    assert(options.head.getOptions.asScala.contains("-Xsemanticdb"))
    assert(options(1).getClasspath.asScala.contains(options.head.getClassDirectory))

    val sources = server
      .buildTargetSources(SourcesParams(targets.map(_.getId).asJava))
      .get()
      .getItems
      .asScala
      .toList
    assert(sources.head.getSources.asScala.exists(_.getUri.endsWith("src/main/scala/")))
    assert(sources(1).getSources.asScala.exists(_.getUri.endsWith("src/test/scala/")))

    val dependencySources = server
      .buildTargetDependencySources(DependencySourcesParams(List(targets.head.getId).asJava))
      .get()
      .getItems
      .asScala
      .head
      .getSources
      .asScala
    assert(dependencySources.exists(_.contains("cats-effect_3-3.6.3-sources.jar")))
  }
