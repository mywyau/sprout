import utest.*

object CalculatorTests extends TestSuite:
  val tests = Tests:
    test("adds two integers"):
      assert(1 + 1 == 2)
