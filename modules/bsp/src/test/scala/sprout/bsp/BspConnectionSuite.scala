package sprout.bsp

import cats.effect.unsafe.implicits.global
import com.google.gson.JsonParser
import java.nio.file.Files

class BspConnectionSuite extends munit.FunSuite:
  test("writes a valid BSP connection file") {
    val root = Files.createTempDirectory("sprout-bsp-connection")
    val result = BspConnection.install(root, "0.2.1", "/opt/sprout/bin/sprout").unsafeRunSync()
    val json = JsonParser.parseString(Files.readString(result.path)).getAsJsonObject

    assertEquals(result.change, BspConnectionChange.Created)
    assertEquals(json.get("name").getAsString, "Sprout")
    assertEquals(json.get("version").getAsString, "0.2.1")
    assertEquals(json.get("bspVersion").getAsString, BspConnection.BspVersion)
    assertEquals(
      json.getAsJsonArray("argv").get(0).getAsString,
      "/opt/sprout/bin/sprout"
    )
    assertEquals(json.getAsJsonArray("argv").get(1).getAsString, "bsp")
    assertEquals(json.getAsJsonArray("languages").get(0).getAsString, "scala")
  }

  test("leaves a current connection unchanged") {
    val root = Files.createTempDirectory("sprout-bsp-current")
    BspConnection.install(root, "0.2.1", "/opt/sprout/bin/sprout").unsafeRunSync()
    val result = BspConnection.install(root, "0.2.1", "/opt/sprout/bin/sprout").unsafeRunSync()

    assertEquals(result.change, BspConnectionChange.Unchanged)
  }

  test("atomically replaces stale and malformed connections") {
    val root = Files.createTempDirectory("sprout-bsp-stale")
    val directory = Files.createDirectories(root.resolve(".bsp"))
    val path = directory.resolve("sprout.json")
    Files.writeString(path, "not json")

    val repaired = BspConnection
      .install(root, "0.2.1", "/new/location/sprout")
      .unsafeRunSync()
    val json = JsonParser.parseString(Files.readString(path)).getAsJsonObject

    assertEquals(repaired.change, BspConnectionChange.Updated)
    assertEquals(json.getAsJsonArray("argv").get(0).getAsString, "/new/location/sprout")
    val temporaryFiles = Files.list(directory)
    try assertEquals(temporaryFiles.count(), 1L)
    finally temporaryFiles.close()
  }
