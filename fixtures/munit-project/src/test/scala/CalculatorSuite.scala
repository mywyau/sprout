class CalculatorSuite extends munit.FunSuite:
  test("adds two integers"):
    assertEquals(Calculator.add(20, 22), 42)
