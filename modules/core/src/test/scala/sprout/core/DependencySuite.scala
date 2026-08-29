package sprout.core

class DependencySuite extends munit.FunSuite:
  test("parses Scala cross-version coordinates") {
    val dependency =
      Dependency.parse("org.typelevel::cats-effect:3.6.3", DependencyScope.Main).toOption.get
    val scalaVersion = ScalaVersion.from("3.3.6").toOption.get
    assertEquals(dependency.resolvedArtifact(scalaVersion), "cats-effect_3")
    assertEquals(dependency.display, "org.typelevel::cats-effect:3.6.3")
  }

  test("parses ordinary Maven coordinates without a Scala suffix") {
    val dependency =
      Dependency.parse("com.example:library:1.2.3", DependencyScope.Test).toOption.get
    val scalaVersion = ScalaVersion.from("3.3.6").toOption.get
    assertEquals(dependency.resolvedArtifact(scalaVersion), "library")
    assertEquals(dependency.scope, DependencyScope.Test)
  }

  test("rejects incomplete coordinates") {
    assert(Dependency.parse("org.example:library", DependencyScope.Main).isLeft)
  }
