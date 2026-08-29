package sprout.bsp

import ch.epfl.scala.bsp4j.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
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

  test("publishes positioned compiler diagnostics and clears them after a successful compile") {
    val root = Files.createTempDirectory("sprout-bsp-diagnostics")
    val sourceDirectory = Files.createDirectories(root.resolve("src/main/scala"))
    val source = sourceDirectory.resolve("Main.scala")
    Files.writeString(
      root.resolve("sprout.toml"),
      """[project]
        |name = "diagnostics-check"
        |scala = "3.3.6"
        |""".stripMargin
    )
    Files.writeString(source, "object Main:\n  val answer: Int = \"wrong\"\n")

    val client = RecordingBuildClient()
    val server = SproutBuildServer(root, "test")
    server.connect(client)
    val mainTarget = server.workspaceBuildTargets().get().getTargets.get(0).getId
    val failed = CompileParams(List(mainTarget).asJava)
    failed.setOriginId("failure-check")

    assertEquals(server.buildTargetCompile(failed).get().getStatusCode, StatusCode.ERROR)
    val publishedError = client.diagnostics.asScala.last
    assertEquals(publishedError.getTextDocument.getUri, source.toUri.toString)
    assertEquals(publishedError.getOriginId, "failure-check")
    assertEquals(publishedError.getDiagnostics.size(), 1)
    assertEquals(publishedError.getDiagnostics.get(0).getRange.getStart.getLine.intValue, 1)
    assert(publishedError.getDiagnostics.get(0).getMessage.contains("Type Mismatch"))

    Files.writeString(source, "object Main:\n  val answer: Int = 42\n")
    val fixed = CompileParams(List(mainTarget).asJava)
    fixed.setOriginId("success-check")

    assertEquals(server.buildTargetCompile(fixed).get().getStatusCode, StatusCode.OK)
    val cleared = client.diagnostics.asScala.last
    assertEquals(cleared.getOriginId, "success-check")
    assertEquals(cleared.getDiagnostics.size(), 0)
    assert(cleared.getReset.booleanValue)
  }

private final class RecordingBuildClient extends BuildClient:
  val diagnostics = CopyOnWriteArrayList[PublishDiagnosticsParams]()

  override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit =
    diagnostics.add(params)
  override def onBuildShowMessage(params: ShowMessageParams): Unit = ()
  override def onBuildLogMessage(params: LogMessageParams): Unit = ()
  override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = ()
  override def onBuildTaskStart(params: TaskStartParams): Unit = ()
  override def onBuildTaskProgress(params: TaskProgressParams): Unit = ()
  override def onBuildTaskFinish(params: TaskFinishParams): Unit = ()
