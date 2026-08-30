package sprout.core

class ResolvedDependencyGraphSuite extends munit.FunSuite:
  test("finds every deterministic path through a diamond graph") {
    val application = ResolvedModule("example", "application_3")
    val left = ResolvedModule("example", "left_3")
    val right = ResolvedModule("example", "right_3")
    val common = ResolvedModule("example", "common_3")
    val graph = ResolvedDependencyGraph(
      List(
        ResolvedDependency(application, "1.0.0", direct = true, Nil),
        ResolvedDependency(left, "1.0.0", direct = false, Nil),
        ResolvedDependency(right, "1.0.0", direct = false, Nil),
        ResolvedDependency(common, "2.0.0", direct = false, Nil)
      ),
      List(
        DependencyRelation(None, application, "1.0.0", "1.0.0"),
        DependencyRelation(Some(application), right, "1.0.0", "1.0.0"),
        DependencyRelation(Some(left), common, "2.0.0", "2.0.0"),
        DependencyRelation(Some(application), left, "1.0.0", "1.0.0"),
        DependencyRelation(Some(right), common, "2.0.0", "2.0.0")
      )
    )

    assertEquals(graph.parents(common), List(left, right))
    assertEquals(
      graph.pathsTo(common),
      List(
        List(application, left, common),
        List(application, right, common)
      )
    )
  }

  test("matches friendly Scala artifact names and detects ambiguous names") {
    val typelevel = ResolvedModule("org.typelevel", "core_3")
    val example = ResolvedModule("com.example", "core_3")
    val graph = ResolvedDependencyGraph(
      List(
        ResolvedDependency(typelevel, "1.0.0", direct = true, Nil),
        ResolvedDependency(example, "2.0.0", direct = true, Nil)
      ),
      List(
        DependencyRelation(None, typelevel, "1.0.0", "1.0.0"),
        DependencyRelation(None, example, "2.0.0", "2.0.0")
      )
    )

    assertEquals(graph.matching("core").map(_.module), List(example, typelevel))
    assertEquals(graph.matching("org.typelevel:core_3").map(_.module), List(typelevel))
  }
