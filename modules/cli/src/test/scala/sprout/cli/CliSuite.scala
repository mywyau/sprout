package sprout.cli

import sprout.core.DependencyScope

class CliSuite extends munit.FunSuite:
  test("parses run arguments and debug independently") {
    assertEquals(
      Cli.parse(List("--debug", "run", "one", "two")),
      Right(CliInvocation(CliCommand.Run(List("one", "two")), debug = true))
    )
  }

  test("requires a name for new") {
    assertEquals(Cli.parse(List("new")), Left("Command 'new' requires a project name"))
  }

  test("rejects unknown commands") {
    assert(Cli.parse(List("publish")).isLeft)
  }

  test("parses IDE setup and the internal BSP command") {
    assertEquals(Cli.parse(List("setup-ide")), Right(CliInvocation(CliCommand.SetupIde, false)))
    assertEquals(Cli.parse(List("bsp")), Right(CliInvocation(CliCommand.Bsp, false)))
  }

  test("parses dependency commands and test scope") {
    assertEquals(
      Cli.parse(List("add", "org.typelevel::cats-effect:3.6.3")),
      Right(
        CliInvocation(
          CliCommand.Add("org.typelevel::cats-effect:3.6.3", DependencyScope.Main),
          false
        )
      )
    )
    assertEquals(
      Cli.parse(List("add", "--test", "org.scalameta::munit:1.1.1")),
      Right(
        CliInvocation(
          CliCommand.Add("org.scalameta::munit:1.1.1", DependencyScope.Test),
          false
        )
      )
    )
    assertEquals(
      Cli.parse(List("remove", "--test", "munit")),
      Right(CliInvocation(CliCommand.Remove("munit", DependencyScope.Test), false))
    )
  }

  test("requires exactly one dependency command argument") {
    assertEquals(Cli.parse(List("add")), Left("Command 'add' requires a coordinate"))
    assert(Cli.parse(List("remove", "one", "two")).isLeft)
  }
