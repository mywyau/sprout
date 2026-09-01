package sprout.cli

import cats.effect.unsafe.implicits.global
import sprout.config.Lockfile
import sprout.core.*
import java.nio.file.Files

class DoctorSuite extends munit.FunSuite:
  test("reports a missing lockfile as a blocking check") {
    val root = Files.createTempDirectory("sprout-doctor-missing-lock")
    Files.createDirectories(root.resolve("src/main/scala"))
    Files.writeString(
      root.resolve("sprout.toml"),
      """[project]
        |name = "example"
        |scala = "3.3.6"
        |""".stripMargin
    )

    val report = Doctor.inspect(root).unsafeRunSync()
    val lockfile = report.checks.find(_.label == "Lockfile").get

    assertEquals(lockfile.status, DoctorStatus.Error)
    assertEquals(lockfile.detail, "missing sprout.lock; run sprout lock")
    assert(!report.healthy)
  }

  test("reports malformed BSP configuration without blocking builds") {
    val root = Files.createTempDirectory("sprout-doctor-bsp")
    Files.createDirectories(root.resolve("src/main/scala"))
    Files.createDirectories(root.resolve(".bsp"))
    Files.writeString(
      root.resolve("sprout.toml"),
      """[project]
        |name = "example"
        |scala = "3.3.6"
        |""".stripMargin
    )
    Files.writeString(root.resolve(".bsp/sprout.json"), "not json")

    val report = Doctor.inspect(root).unsafeRunSync()
    val bsp = report.checks.find(_.label == "BSP").get

    assertEquals(bsp.status, DoctorStatus.Warning)
    assertEquals(bsp.detail, "connection is invalid; run sprout setup-ide")
  }

  test("reports a lockfile that no longer matches the project configuration") {
    val root = Files.createTempDirectory("sprout-doctor-stale-lock")
    val project = Project(
      ProjectName.from("example").toOption.get,
      ScalaVersion.from("3.3.6").toOption.get,
      Nil,
      ProjectLayout.conventional(root)
    )
    Files.createDirectories(root.resolve("src/main/scala"))
    Files.writeString(
      root.resolve("sprout.toml"),
      """[project]
        |name = "example"
        |scala = "3.3.6"
        |""".stripMargin
    )
    val empty = ResolvedDependencies(
      ResolvedClasspath(Nil),
      ResolvedDependencyGraph(Nil, Nil)
    )
    Lockfile.write(project, empty, empty, empty).unsafeRunSync()
    Files.writeString(
      root.resolve("sprout.toml"),
      """[project]
        |name = "example"
        |scala = "3.3.7"
        |""".stripMargin
    )

    val report = Doctor.inspect(root).unsafeRunSync()
    val lockfile = report.checks.find(_.label == "Lockfile").get

    assertEquals(lockfile.status, DoctorStatus.Error)
    assertEquals(lockfile.detail, "does not match sprout.toml; run sprout lock")
  }
