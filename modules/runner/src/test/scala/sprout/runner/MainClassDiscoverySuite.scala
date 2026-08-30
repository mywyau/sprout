package sprout.runner

import cats.effect.unsafe.implicits.global
import java.nio.file.Path

class MainClassDiscoverySuite extends munit.FunSuite:
  test("discovers a Scala main class without launching javap") {
    val classes = Path.of(getClass.getProtectionDomain.getCodeSource.getLocation.toURI)

    assertEquals(
      MainClassDiscovery.discover(classes, Nil).unsafeRunSync(),
      "sprout.runner.DiscoveryTestApplication"
    )
  }

object DiscoveryTestApplication:
  def main(args: Array[String]): Unit = ()
