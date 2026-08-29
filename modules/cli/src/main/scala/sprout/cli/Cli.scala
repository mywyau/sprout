package sprout.cli

enum CliCommand:
  case Help
  case Version
  case New(name: String)
  case Compile
  case Run(arguments: List[String])
  case Test
  case Clean
  case SetupIde
  case Bsp

final case class CliInvocation(command: CliCommand, debug: Boolean)

object Cli:
  def parse(arguments: List[String]): Either[String, CliInvocation] =
    val debug = arguments.contains("--debug")
    arguments.filterNot(_ == "--debug") match
      case Nil | "--help" :: Nil | "-h" :: Nil => Right(CliInvocation(CliCommand.Help, debug))
      case "--version" :: Nil                  => Right(CliInvocation(CliCommand.Version, debug))
      case "new" :: name :: Nil                => Right(CliInvocation(CliCommand.New(name), debug))
      case "new" :: Nil                        => Left("Command 'new' requires a project name")
      case "compile" :: Nil                    => Right(CliInvocation(CliCommand.Compile, debug))
      case "run" :: tail                       => Right(CliInvocation(CliCommand.Run(tail), debug))
      case "test" :: Nil                       => Right(CliInvocation(CliCommand.Test, debug))
      case "clean" :: Nil                      => Right(CliInvocation(CliCommand.Clean, debug))
      case "setup-ide" :: Nil                  => Right(CliInvocation(CliCommand.SetupIde, debug))
      case "bsp" :: Nil                        => Right(CliInvocation(CliCommand.Bsp, debug))
      case command :: _ => Left(s"Unknown command '$command'. Run sprout --help.")
