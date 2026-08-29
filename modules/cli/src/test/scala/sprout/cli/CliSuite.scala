package sprout.cli

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
