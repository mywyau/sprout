import java.nio.charset.StandardCharsets

class ResourceSuite extends munit.FunSuite:
  private def resource(name: String): String =
    val stream = Option(getClass.getResourceAsStream(s"/$name")).getOrElse(fail(s"Missing resource: $name"))
    try String(stream.readAllBytes(), StandardCharsets.UTF_8).trim
    finally stream.close()

  test("loads main and test resources from compiled output") {
    assertEquals(resource("application.conf"), "message = resource copied by Sprout")
    assertEquals(resource("test-fixture.txt"), "test fixture copied by Sprout")
  }
