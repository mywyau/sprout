package sprout.core

import cats.effect.unsafe.implicits.global
import cats.effect.IO
import java.nio.file.Files

class ProjectLockSuite extends munit.FunSuite:
  test("rejects a second command while a project lock is held") {
    val root = Files.createTempDirectory("sprout-project-lock")

    ProjectLock(root) {
      intercept[SproutError.User](ProjectLock(root)(IO.unit).unsafeRunSync())
      IO.unit
    }.unsafeRunSync()
  }
