import org.scalatest.funsuite.AnyFunSuite

class CalculatorSpec extends AnyFunSuite:
  test("adds two integers") {
    assert(Calculator.add(1, 2) == 3)
  }
