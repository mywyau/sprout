package sprout.config

import cats.effect.unsafe.implicits.global
import sprout.core.*
import java.nio.file.Files

class LockfileSuite extends munit.FunSuite:
  test("writes a deterministic lockfile and rejects changed inputs or artifact bytes") {
    val root = Files.createTempDirectory("sprout-lock")
    val artifact = Files.writeString(root.resolve("library.jar"), "original")
    val project = Project(
      ProjectName.from("example").toOption.get,
      ScalaVersion.from("3.3.6").toOption.get,
      Nil,
      ProjectLayout.conventional(root)
    )
    val module = ResolvedModule("example", "library")
    val resolved = ResolvedDependencies(
      ResolvedClasspath(List(ResolvedArtifact(module.id, "1.0.0", artifact))),
      ResolvedDependencyGraph(
        List(ResolvedDependency(module, "1.0.0", direct = true, List(artifact))),
        List(DependencyRelation(None, module, "1.0.0", "1.0.0"))
      )
    )

    Lockfile.write(project, resolved, resolved, resolved).unsafeRunSync()
    val first = Files.readString(root.resolve("sprout.lock"))
    Lockfile.write(project, resolved, resolved, resolved).unsafeRunSync()
    assertEquals(Files.readString(root.resolve("sprout.lock")), first)
    Lockfile
      .verify(project, resolved, resolved, Lockfile.load(project).unsafeRunSync().get)
      .unsafeRunSync()
    assertEquals(
      Lockfile.mainModules(Lockfile.load(project).unsafeRunSync().get),
      List(LockedModule(module, "1.0.0"))
    )

    val changedScala = project.copy(scalaVersion = ScalaVersion.from("3.3.7").toOption.get)
    intercept[SproutError.User](
      Lockfile
        .verify(changedScala, resolved, resolved, Lockfile.load(project).unsafeRunSync().get)
        .unsafeRunSync()
    )

    Files.writeString(artifact, "changed!")
    intercept[SproutError.User](
      Lockfile
        .verify(project, resolved, resolved, Lockfile.load(project).unsafeRunSync().get)
        .unsafeRunSync()
    )
  }
