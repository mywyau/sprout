package sprout.cli

import scala.collection.mutable
import sprout.core.*

private[cli] object DependencyReport:
  def graph(projectName: String, graph: ResolvedDependencyGraph): String =
    if graph.roots.isEmpty then s"$projectName\n└── (no dependencies)"
    else
      val seen = mutable.Set.empty[ResolvedModule]
      val ambiguousNames = graph.modules
        .groupBy(_.module.displayName)
        .collect { case (name, dependencies) if dependencies.size > 1 => name }
        .toSet
      val lines = mutable.ListBuffer(projectName)

      def render(relations: List[DependencyRelation], prefix: String): Unit =
        relations.zipWithIndex.foreach { case (relation, index) =>
          val last = index == relations.size - 1
          val connector = if last then "└── " else "├── "
          val repeated = !seen.add(relation.child)
          lines += s"$prefix$connector${label(relation, ambiguousNames)}${
              if repeated then " (repeated)" else ""
            }"
          if !repeated then
            val childPrefix = prefix + (if last then "    " else "│   ")
            render(graph.children(relation.child), childPrefix)
        }

      render(graph.roots, "")
      lines.mkString("\n")

  def why(
      projectName: String,
      graph: ResolvedDependencyGraph,
      query: String
  ): Either[String, String] =
    graph.matching(query) match
      case Nil =>
        Left(
          s"Dependency '$query' was not found in the resolved main dependency graph"
        )
      case dependency :: Nil =>
        val paths = graph.pathsTo(dependency.module)
        val rendered = paths.map(path => renderPath(projectName, graph, path))
        val output =
          if rendered.size == 1 then rendered.head
          else
            rendered.zipWithIndex
              .map { case (path, index) => s"Path ${index + 1}:\n$path" }
              .mkString("\n\n")
        Right(output)
      case matches =>
        val candidates = matches.map(_.module.id).mkString("\n")
        Left(
          s"Dependency name '$query' is ambiguous\n\n$candidates\n\nUse: sprout why ORGANISATION:ARTIFACT"
        )

  private def renderPath(
      projectName: String,
      graph: ResolvedDependencyGraph,
      path: List[ResolvedModule]
  ): String =
    val reversed = path.reverse
    val dependencyLines = reversed.zipWithIndex.map { case (module, depth) =>
      val dependency = graph.dependency(module).get
      val prefix = if depth == 0 then "" else "    " * (depth - 1) + "└── "
      s"$prefix${module.displayName} ${dependency.version}"
    }
    val projectPrefix = "    " * (reversed.size - 1) + "└── "
    (dependencyLines :+ s"$projectPrefix$projectName").mkString("\n")

  private def label(relation: DependencyRelation, ambiguousNames: Set[String]): String =
    val name =
      if ambiguousNames.contains(relation.child.displayName) then relation.child.id
      else relation.child.displayName
    if relation.evicted then
      s"$name ${relation.requestedVersion} → ${relation.selectedVersion} (selected)"
    else s"$name ${relation.selectedVersion}"
