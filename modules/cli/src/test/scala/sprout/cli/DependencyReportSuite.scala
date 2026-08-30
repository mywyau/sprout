package sprout.cli

import sprout.core.*

class DependencyReportSuite extends munit.FunSuite:
  private val application = ResolvedModule("example", "application_3")
  private val left = ResolvedModule("example", "left_3")
  private val right = ResolvedModule("example", "right_3")
  private val common = ResolvedModule("example", "common_3")

  test("renders a deterministic diamond and marks its repeated dependency") {
    assertEquals(
      DependencyReport.graph("shop", diamond),
      """shop
        |└── application 1.0.0
        |    ├── left 1.0.0
        |    │   └── common 2.0.0
        |    └── right 1.0.0
        |        └── common 2.0.0 (repeated)""".stripMargin
    )
  }

  test("shows requested and selected versions for an eviction") {
    val library = ResolvedModule("example", "library_3")
    val graph = ResolvedDependencyGraph(
      List(ResolvedDependency(library, "2.0.0", direct = true, Nil)),
      List(DependencyRelation(None, library, "1.0.0", "2.0.0"))
    )

    assertEquals(
      DependencyReport.graph("shop", graph),
      "shop\n└── library 1.0.0 → 2.0.0 (selected)"
    )
  }

  test("renders every path introducing a dependency") {
    assertEquals(
      DependencyReport.why("shop", diamond, "common"),
      Right(
        """Path 1:
          |common 2.0.0
          |└── left 1.0.0
          |    └── application 1.0.0
          |        └── shop
          |
          |Path 2:
          |common 2.0.0
          |└── right 1.0.0
          |    └── application 1.0.0
          |        └── shop""".stripMargin
      )
    )
  }

  test("reports unknown and ambiguous dependency names") {
    assert(DependencyReport.why("shop", diamond, "missing").left.exists(_.contains("not found")))
    val duplicate = ResolvedModule("another", "common_3")
    val ambiguous = diamond.copy(
      modules = diamond.modules :+ ResolvedDependency(duplicate, "3.0.0", direct = true, Nil),
      relations = diamond.relations :+ DependencyRelation(None, duplicate, "3.0.0", "3.0.0")
    )

    val error = DependencyReport.why("shop", ambiguous, "common").left.toOption.get
    assert(error.contains("is ambiguous"))
    assert(error.contains("another:common_3"))
    assert(error.contains("example:common_3"))
  }

  private val diamond = ResolvedDependencyGraph(
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
