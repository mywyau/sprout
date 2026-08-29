package sprout.bsp

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import ch.epfl.scala.bsp4j.*
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters.*

private[bsp] final class SproutBuildServer(root: Path, version: String)
    extends BuildServer,
      ScalaBuildServer:
  private val projectRoot = root.toAbsolutePath.normalize
  private val workspace = BspWorkspace(projectRoot)
  private val mainId = identifier(TargetKind.Main)
  private val testId = identifier(TargetKind.Test)
  @volatile private var client: Option[BuildClient] = None

  def connect(value: BuildClient): Unit = client = Some(value)

  override def buildInitialize(
      params: InitializeBuildParams
  ): CompletableFuture[InitializeBuildResult] = complete {
    IO {
      val capabilities = BuildServerCapabilities()
      capabilities.setCompileProvider(CompileProvider(List("scala").asJava))
      capabilities.setTestProvider(TestProvider(List("scala").asJava))
      capabilities.setInverseSourcesProvider(true)
      capabilities.setDependencySourcesProvider(true)
      capabilities.setResourcesProvider(true)
      capabilities.setOutputPathsProvider(true)
      capabilities.setCanReload(true)
      InitializeBuildResult("Sprout", version, BspConnection.BspVersion, capabilities)
    }
  }

  override def onBuildInitialized(): Unit = ()
  override def buildShutdown(): CompletableFuture[Object] =
    CompletableFuture.completedFuture(null)
  override def onBuildExit(): Unit = ()
  override def workspaceReload(): CompletableFuture[Object] =
    CompletableFuture.completedFuture(null)

  override def workspaceBuildTargets(): CompletableFuture[WorkspaceBuildTargetsResult] = complete {
    workspace.classpath(TargetKind.Main).map { target =>
      val project = target.project
      val main = buildTarget(
        mainId,
        s"${project.name.value} (main)",
        List(BuildTargetTag.APPLICATION),
        Nil,
        canTest = false,
        project,
        target.compiler
      )
      val test = buildTarget(
        testId,
        s"${project.name.value} (test)",
        List(BuildTargetTag.TEST),
        List(mainId),
        canTest = true,
        project,
        target.compiler
      )
      WorkspaceBuildTargetsResult(List(main, test).asJava)
    }
  }

  override def buildTargetSources(params: SourcesParams): CompletableFuture[SourcesResult] =
    complete {
      workspace.project.map { project =>
        val items = selected(params.getTargets).map { kind =>
          val directories = kind match
            case TargetKind.Main => project.layout.mainSources
            case TargetKind.Test => project.layout.testSources
          val sources =
            directories.map(path => SourceItem(uri(path), SourceItemKind.DIRECTORY, false))
          SourcesItem(identifier(kind), sources.asJava)
        }
        SourcesResult(items.asJava)
      }
    }

  override def buildTargetScalacOptions(
      params: ScalacOptionsParams
  ): CompletableFuture[ScalacOptionsResult] = complete {
    selected(params.getTargets)
      .traverse(workspace.classpath)
      .map { targets =>
        val items = targets.map { target =>
          ScalacOptionsItem(
            identifier(target.kind),
            workspace.semanticdbOptions(target.project).asJava,
            target.compileClasspath.map(uri).asJava,
            uri(target.outputDirectory)
          )
        }
        ScalacOptionsResult(items.asJava)
      }
  }

  override def buildTargetDependencySources(
      params: DependencySourcesParams
  ): CompletableFuture[DependencySourcesResult] = complete {
    selected(params.getTargets)
      .traverse(kind => workspace.dependencySources(kind).tupleLeft(kind))
      .map { values =>
        DependencySourcesResult(values.map { case (kind, paths) =>
          DependencySourcesItem(identifier(kind), paths.map(uri).asJava)
        }.asJava)
      }
  }

  override def buildTargetResources(
      params: ResourcesParams
  ): CompletableFuture[ResourcesResult] = complete {
    workspace.project.map { project =>
      val items = selected(params.getTargets).map { kind =>
        val resources = kind match
          case TargetKind.Main => project.layout.mainResources
          case TargetKind.Test => project.layout.testResources
        ResourcesItem(identifier(kind), resources.map(uri).asJava)
      }
      ResourcesResult(items.asJava)
    }
  }

  override def buildTargetOutputPaths(
      params: OutputPathsParams
  ): CompletableFuture[OutputPathsResult] = complete {
    workspace.project.map { project =>
      val items = selected(params.getTargets).map { kind =>
        val output = kind match
          case TargetKind.Main => project.layout.mainClasses
          case TargetKind.Test => project.layout.testClasses
        OutputPathsItem(
          identifier(kind),
          List(OutputPathItem(uri(output), OutputPathItemKind.DIRECTORY)).asJava
        )
      }
      OutputPathsResult(items.asJava)
    }
  }

  override def buildTargetCompile(params: CompileParams): CompletableFuture[CompileResult] =
    val kinds = selected(params.getTargets)
    val compiledKinds =
      if kinds.contains(TargetKind.Test) then List(TargetKind.Main, TargetKind.Test) else kinds
    val action =
      if kinds.contains(TargetKind.Test) then workspace.compileWithDependencies(TargetKind.Test)
      else if kinds.contains(TargetKind.Main) then workspace.compile(TargetKind.Main)
      else IO.pure(sprout.core.CompilationResult.UpToDate)
    complete(
      action.attempt.flatMap {
        case Right(_) =>
          clearDiagnostics(compiledKinds, params.getOriginId).as {
            val result = CompileResult(StatusCode.OK)
            result.setOriginId(params.getOriginId)
            result
          }
        case Left(error) =>
          publishFailure(error, params.getOriginId).as {
            val result = CompileResult(StatusCode.ERROR)
            result.setOriginId(params.getOriginId)
            result
          }
      }
    )

  override def buildTargetCleanCache(
      params: CleanCacheParams
  ): CompletableFuture[CleanCacheResult] = complete {
    workspace.clean.as(CleanCacheResult(true))
  }

  override def buildTargetInverseSources(
      params: InverseSourcesParams
  ): CompletableFuture[InverseSourcesResult] = complete {
    workspace.project.map { project =>
      val source = Path.of(java.net.URI.create(params.getTextDocument.getUri)).normalize
      val targets =
        if project.layout.testSources.exists(source.startsWith) then List(testId)
        else if project.layout.mainSources.exists(source.startsWith) then List(mainId)
        else Nil
      InverseSourcesResult(targets.asJava)
    }
  }

  override def buildTargetDependencyModules(
      params: DependencyModulesParams
  ): CompletableFuture[DependencyModulesResult] =
    CompletableFuture.completedFuture(DependencyModulesResult(List.empty.asJava))

  override def buildTargetRun(params: RunParams): CompletableFuture[RunResult] =
    CompletableFuture.completedFuture(RunResult(StatusCode.ERROR))
  override def buildTargetTest(
      params: TestParams
  ): CompletableFuture[ch.epfl.scala.bsp4j.TestResult] =
    CompletableFuture.completedFuture(ch.epfl.scala.bsp4j.TestResult(StatusCode.ERROR))
  override def debugSessionStart(
      params: DebugSessionParams
  ): CompletableFuture[DebugSessionAddress] =
    CompletableFuture.failedFuture(
      UnsupportedOperationException("BSP debugging is not supported yet")
    )
  override def buildTargetScalaTestClasses(
      params: ScalaTestClassesParams
  ): CompletableFuture[ScalaTestClassesResult] =
    CompletableFuture.completedFuture(ScalaTestClassesResult(List.empty.asJava))
  override def buildTargetScalaMainClasses(
      params: ScalaMainClassesParams
  ): CompletableFuture[ScalaMainClassesResult] =
    CompletableFuture.completedFuture(ScalaMainClassesResult(List.empty.asJava))

  private def buildTarget(
      id: BuildTargetIdentifier,
      displayName: String,
      tags: List[String],
      dependencies: List[BuildTargetIdentifier],
      canTest: Boolean,
      project: sprout.core.Project,
      compilerJars: List[Path]
  ): BuildTarget =
    val capabilities = BuildTargetCapabilities()
    capabilities.setCanCompile(true)
    capabilities.setCanTest(canTest)
    capabilities.setCanRun(false)
    capabilities.setCanDebug(false)
    val target = BuildTarget(
      id,
      tags.asJava,
      List("scala").asJava,
      dependencies.asJava,
      capabilities
    )
    target.setDisplayName(displayName)
    target.setBaseDirectory(uri(project.layout.root))
    target.setDataKind(BuildTargetDataKind.SCALA)
    target.setData(
      ScalaBuildTarget(
        "org.scala-lang",
        project.scalaVersion.value,
        project.scalaVersion.binaryVersion,
        ScalaPlatform.JVM,
        compilerJars.map(uri).asJava
      )
    )
    target

  private def selected(ids: java.util.List[BuildTargetIdentifier]): List[TargetKind] =
    Option(ids).toList
      .flatMap(_.asScala)
      .flatMap { id =>
        if id == mainId then Some(TargetKind.Main)
        else if id == testId then Some(TargetKind.Test)
        else None
      }
      .distinct

  private def identifier(kind: TargetKind): BuildTargetIdentifier =
    val name = if kind == TargetKind.Main then "main" else "test"
    BuildTargetIdentifier(projectRoot.resolve("sprout.toml").toUri.toString + s"?target=$name")

  private def uri(path: Path): String = path.toAbsolutePath.normalize.toUri.toString

  private val compilerLocation = "(?m)^-- .*?: (.+\\.scala):(\\d+):(\\d+)\\s*$".r

  private def clearDiagnostics(kinds: List[TargetKind], originId: String): IO[Unit] =
    kinds.traverse_ { kind =>
      workspace.sourceFiles(kind).flatMap(_.traverse_(path => publish(path, kind, Nil, originId)))
    }

  private def publishFailure(error: Throwable, originId: String): IO[Unit] =
    val message = error.getMessage
    compilerLocation.findFirstMatchIn(message) match
      case Some(location) =>
        val path = Path.of(location.group(1)).toAbsolutePath.normalize
        workspace.project.flatMap { project =>
          val kind =
            if project.layout.testSources.exists(path.startsWith) then TargetKind.Test
            else TargetKind.Main
          val start = Position(location.group(2).toInt - 1, location.group(3).toInt - 1)
          val end = Position(start.getLine, start.getCharacter + 1)
          val diagnostic = Diagnostic(Range(start, end), message)
          diagnostic.setSeverity(DiagnosticSeverity.ERROR)
          diagnostic.setSource("sprout")
          publish(path, kind, List(diagnostic), originId)
        }
      case None =>
        IO(client.foreach(_.onBuildLogMessage(LogMessageParams(MessageType.ERROR, message))))

  private def publish(
      path: Path,
      kind: TargetKind,
      diagnostics: List[Diagnostic],
      originId: String
  ): IO[Unit] = IO {
    client.foreach { value =>
      val params = PublishDiagnosticsParams(
        TextDocumentIdentifier(uri(path)),
        identifier(kind),
        diagnostics.asJava,
        true
      )
      params.setOriginId(originId)
      value.onBuildPublishDiagnostics(params)
    }
  }

  private def complete[A](action: IO[A]): CompletableFuture[A] =
    action.unsafeToCompletableFuture()
