package sprout.runner

import cats.effect.unsafe.implicits.global
import sprout.core.{TestOutput, TestSelection}
import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.Path

class MUnitTestRunnerSuite extends munit.FunSuite:
  test("prints successful MUnit output by default") {
    val (result, output) = run()

    assertEquals(result.failed, 0)
    assertEquals(result.total, 1)
    assert(output.contains("OutputFixtureSuite"))
  }

  test("keeps successful MUnit output compact when quiet") {
    val (result, output) = run(Some(TestOutput.Compact))

    assertEquals(result.failed, 0)
    assertEquals(result.total, 1)
    assert(!output.contains("OutputFixtureSuite"))
  }

  private def run(output: Option[TestOutput] = None) = this.synchronized {
    val bytes = ByteArrayOutputStream()
    val classes = Path.of(getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
    val original = System.out
    val captured = PrintStream(bytes)
    try
      System.setOut(captured)
      val runner = MUnitTestRunner()
      val result = output
        .fold(
          runner.run(
            List(classes),
            List(classes),
            TestSelection.Suite("sprout.runner.OutputFixtureSuite")
          )
        )(value =>
          runner.run(
            List(classes),
            List(classes),
            TestSelection.Suite("sprout.runner.OutputFixtureSuite"),
            value
          )
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
