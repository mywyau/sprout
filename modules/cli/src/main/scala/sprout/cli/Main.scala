package sprout.cli

import cats.effect.{ExitCode, IO, IOApp}
import sprout.core.{CompilationResult, SproutError}
import sprout.bsp.{BspConnection, BspConnectionChange, BspServer}
import java.nio.file.Path

object Main extends IOApp:
  private val Version = Option(getClass.getPackage.getImplementationVersion)
    .getOrElse("0.1.0-dev")
  private val service = BuildService()

  def run(arguments: List[String]): IO[ExitCode] =
    val parsed = Cli.parse(arguments)
    val debug = parsed.exists(_.debug)
    parsed
      .fold(
        message => IO.raiseError(SproutError.User(message)),
        invocation => execute(invocation.command)
      )
      .as(ExitCode.Success)
      .handleErrorWith { error =>
        val message = error match
          case expected: SproutError => expected.getMessage
          case other                 => s"Unexpected internal error: ${other.getMessage}"
        IO(System.err.println(s"error: $message")) *>
          IO.whenA(debug)(IO(error.printStackTrace())) *>
          IO.pure(ExitCode.Error)
      }

  private def execute(command: CliCommand): IO[Unit] =
    command match
      case CliCommand.Help      => IO.println(help)
      case CliCommand.Version   => IO.println(s"Sprout $Version")
      case CliCommand.New(name) =>
        ProjectGenerator
          .create(Path.of("."), name)
          .flatMap(path =>
            IO.println(s"Created ${path.getFileName}\n\n  cd ${path.getFileName}\n  sprout run")
          )
      case CliCommand.Compile =>
        service.compile(Path.of(".")).flatMap {
          case CompilationResult.Compiled(_) => IO.println("\n✓ Build succeeded")
          case CompilationResult.UpToDate    => IO.println("\n✓ Nothing to build")
        }
      case CliCommand.Run(arguments) => service.run(Path.of("."), arguments)
      case CliCommand.Test           =>
        service
          .test(Path.of("."))
          .flatMap(result => IO.println(s"\n✓ ${result.total} test(s) passed"))
      case CliCommand.Package =>
        service.packageApplication(Path.of(".")).flatMap { result =>
          IO.println(
            s"""\n✓ Package created
               |
               |Application   ${result.applicationDirectory}
               |Archive       ${result.tarArchive}
               |ZIP           ${result.zipArchive}
               |Checksums     ${result.archiveChecksums}
               |Main class    ${result.mainClass}
               |Dependencies  ${result.dependencyCount}""".stripMargin
          )
        }
      case CliCommand.Clean =>
        service.clean(Path.of(".")).flatMap(_ => IO.println("✓ Cleaned .sprout"))
      case CliCommand.Add(coordinate, scope) =>
        service.add(Path.of("."), coordinate, scope).flatMap { added =>
          IO.println(
            s"Added ${added.name}\n\n${added.dependency.display}\nResolved ${added.artifactCount} artifact(s)\nUpdated sprout.toml"
          )
        }
      case CliCommand.Remove(name, scope) =>
        service
          .remove(Path.of("."), name, scope)
          .flatMap(_ => IO.println(s"Removed $name\n\nUpdated sprout.toml"))
      case CliCommand.Graph     => service.graph(Path.of(".")).flatMap(IO.println)
      case CliCommand.Why(name) => service.why(Path.of("."), name).flatMap(IO.println)
      case CliCommand.SetupIde  =>
        for
          config <- sprout.config.ProjectConfig.locate(Path.of("."))
          root = config.toAbsolutePath.normalize.getParent
          result <- BspConnection.install(root, Version, BspConnection.currentLauncher)
          action = result.change match
            case BspConnectionChange.Created   => "Installed"
            case BspConnectionChange.Updated   => "Updated stale"
            case BspConnectionChange.Unchanged => "Already configured"
          _ <- IO.println(
            s"✓ $action ${root.relativize(result.path)}\n\nIn VS Code, run: Metals: Restart build server"
          )
        yield ()
      case CliCommand.Bsp => BspServer.run(Path.of("."), Version)

  private val help =
    s"""Sprout $Version
       |
       |A fast, simple, opinionated build tool for ordinary Scala projects.
       |
       |Usage: sprout [--debug] COMMAND
       |
       |Commands:
       |  new NAME   Create a Scala project
       |  compile    Compile main sources
       |  run [ARGS] Compile and run the application
       |  test       Compile and run MUnit tests
       |  package    Create a runnable application directory and archives
       |  clean      Delete project-local build state
       |  add [--test] COORDINATE
       |              Add and resolve a dependency
       |  remove [--test] NAME
       |              Remove a dependency from sprout.toml
       |  graph       Show the resolved dependency tree
       |  why NAME    Show every path introducing a dependency
       |  setup-ide  Configure Metals and other BSP-compatible editors
       |
       |Options:
       |  -h, --help     Show this help
       |  --version      Show the Sprout version
       |  --debug        Include stack traces for errors
       |""".stripMargin
