package sprout.runner

import cats.effect.unsafe.implicits.global
import sprout.core.{TestOutput, TestSelection}
import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.Path

class MUnitTestRunnerSuite extends munit.FunSuite:
  test("keeps successful MUnit output compact by default") {
    val (result, output) = run(TestOutput.Compact)

    assertEquals(result.failed, 0)
    assertEquals(result.total, 1)
    assert(!output.contains("OutputFixtureSuite"))
  }

  test("prints successful MUnit output when verbose") {
    val (result, output) = run(TestOutput.Verbose)

    assertEquals(result.failed, 0)
    assertEquals(result.total, 1)
    assert(output.contains("OutputFixtureSuite"))
  }

  private def run(output: TestOutput) = this.synchronized {
    val bytes = ByteArrayOutputStream()
    val classes = Path.of(getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
    val original = System.out
    val captured = PrintStream(bytes)
    try
      System.setOut(captured)
      val result = MUnitTestRunner()
        .run(
          List(classes),
          List(classes),
          TestSelection.Suite("sprout.runner.OutputFixtureSuite"),
          output
        )
        .unsafeRunSync()
      captured.flush()
      result -> bytes.toString()
    finally
      System.setOut(original)
      captured.close()
  }

class OutputFixtureSuite extends munit.FunSuite:
  test("reports a passing fixture") {
    assertEquals(1 + 1, 2)
  }
