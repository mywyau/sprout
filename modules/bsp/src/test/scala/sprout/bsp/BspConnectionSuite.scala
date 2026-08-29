package sprout.bsp

import cats.effect.unsafe.implicits.global
import com.google.gson.JsonParser
import java.nio.file.Files

class BspConnectionSuite extends munit.FunSuite:
  test("writes a valid BSP connection file") {
    val root = Files.createTempDirectory("sprout-bsp-connection")
    val path = BspConnection.install(root, "0.1.2", "/opt/sprout/bin/sprout").unsafeRunSync()
    val json = JsonParser.parseString(Files.readString(path)).getAsJsonObject

    assertEquals(json.get("name").getAsString, "Sprout")
    assertEquals(json.get("version").getAsString, "0.1.2")
    assertEquals(json.get("bspVersion").getAsString, BspConnection.BspVersion)
    assertEquals(
      json.getAsJsonArray("argv").get(0).getAsString,
      "/opt/sprout/bin/sprout"
    )
    assertEquals(json.getAsJsonArray("argv").get(1).getAsString, "bsp")
    assertEquals(json.getAsJsonArray("languages").get(0).getAsString, "scala")
  }
