package sprout.cli

import sprout.core.DependencyScope

enum CliCommand:
  case Help
  case Version
  case New(name: String)
  case Compile
  case Run(arguments: List[String])
  case Test(selection: Option[String], verbose: Boolean)
  case Package
  case Clean
  case Add(coordinate: String, scope: DependencyScope)
  case Remove(name: String, scope: DependencyScope)
  case Graph
  case Why(name: String)
  case Lock
  case Format(check: Boolean)
  case Doctor
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
      case "test" :: tail                      => testCommand(tail, debug)
      case "package" :: Nil                    => Right(CliInvocation(CliCommand.Package, debug))
      case "package" :: _                      => Left("Usage: sprout package")
      case "clean" :: Nil                      => Right(CliInvocation(CliCommand.Clean, debug))
      case "add" :: tail    => scopedArgument("add", tail, CliCommand.Add.apply, debug)
      case "remove" :: tail =>
        scopedArgument("remove", tail, CliCommand.Remove.apply, debug)
      case "graph" :: Nil       => Right(CliInvocation(CliCommand.Graph, debug))
      case "graph" :: _         => Left("Usage: sprout graph")
      case "why" :: name :: Nil => Right(CliInvocation(CliCommand.Why(name), debug))
      case "why" :: Nil         => Left("Command 'why' requires a dependency name")
      case "why" :: _           => Left("Usage: sprout why NAME")
      case "lock" :: Nil        => Right(CliInvocation(CliCommand.Lock, debug))
      case "lock" :: _          => Left("Usage: sprout lock")
      case "fmt" :: Nil         => Right(CliInvocation(CliCommand.Format(check = false), debug))
      case "fmt" :: "--check" :: Nil => Right(CliInvocation(CliCommand.Format(check = true), debug))
      case "fmt" :: _                => Left("Usage: sprout fmt [--check]")
      case "doctor" :: Nil           => Right(CliInvocation(CliCommand.Doctor, debug))
      case "doctor" :: _             => Left("Usage: sprout doctor")
      case "setup-ide" :: Nil        => Right(CliInvocation(CliCommand.SetupIde, debug))
      case "bsp" :: Nil              => Right(CliInvocation(CliCommand.Bsp, debug))
      case command :: _              => Left(s"Unknown command '$command'. Run sprout --help.")

  private def scopedArgument(
      command: String,
      arguments: List[String],
      create: (String, DependencyScope) => CliCommand,
      debug: Boolean
  ): Either[String, CliInvocation] =
    val test = arguments.contains("--test")
    val values = arguments.filterNot(_ == "--test")
    values match
      case value :: Nil if !value.startsWith("--") =>
        Right(
          CliInvocation(
            create(value, if test then DependencyScope.Test else DependencyScope.Main),
            debug
          )
        )
      case Nil =>
        Left(s"Command '$command' requires ${
            if command == "add" then "a coordinate" else "a dependency name"
          }")
      case _ =>
        Left(
          s"Usage: sprout $command [--test] ${if command == "add" then "COORDINATE" else "NAME"}"
        )

  private def testCommand(
      arguments: List[String],
      debug: Boolean
  ): Either[String, CliInvocation] =
    val quiet = arguments.contains("--quiet")
    val values = arguments.filterNot(_ == "--quiet")
    values match
      case Nil => Right(CliInvocation(CliCommand.Test(None, verbose = !quiet), debug))
      case selector :: Nil if !selector.startsWith("--") =>
        Right(CliInvocation(CliCommand.Test(Some(selector), verbose = !quiet), debug))
      case _ => Left("Usage: sprout test [--quiet] [SUITE_OR_FILE]")
