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

  test("parses an optional MUnit suite or source file") {
    assertEquals(
      Cli.parse(List("test")),
      Right(CliInvocation(CliCommand.Test(None, verbose = false), false))
    )
    assertEquals(
      Cli.parse(List("test", "CalculatorSuite")),
      Right(CliInvocation(CliCommand.Test(Some("CalculatorSuite"), verbose = false), false))
    )
    assertEquals(
      Cli.parse(List("test", "src/test/scala/CalculatorSuite.scala")),
      Right(
        CliInvocation(
          CliCommand.Test(Some("src/test/scala/CalculatorSuite.scala"), verbose = false),
          false
        )
      )
    )
    assertEquals(
      Cli.parse(List("test", "--verbose", "CalculatorSuite")),
      Right(CliInvocation(CliCommand.Test(Some("CalculatorSuite"), verbose = true), false))
    )
    assertEquals(
      Cli.parse(List("test", "CalculatorSuite", "--verbose")),
      Right(CliInvocation(CliCommand.Test(Some("CalculatorSuite"), verbose = true), false))
    )
    assertEquals(
      Cli.parse(List("test", "one", "two")),
      Left("Usage: sprout test [--verbose] [SUITE_OR_FILE]")
    )
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

  test("parses graph and why commands") {
    assertEquals(Cli.parse(List("graph")), Right(CliInvocation(CliCommand.Graph, false)))
    assertEquals(
      Cli.parse(List("why", "cats-core")),
      Right(CliInvocation(CliCommand.Why("cats-core"), false))
    )
    assertEquals(Cli.parse(List("why")), Left("Command 'why' requires a dependency name"))
  }

  test("parses package without accepting build-script arguments") {
    assertEquals(Cli.parse(List("package")), Right(CliInvocation(CliCommand.Package, false)))
    assertEquals(Cli.parse(List("package", "anything")), Left("Usage: sprout package"))
  }
