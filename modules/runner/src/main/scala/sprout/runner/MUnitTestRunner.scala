package sprout.runner

import cats.effect.IO
import sprout.core.*
import sbt.testing.*
import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

final class MUnitTestRunner extends TestRunner[IO]:
  def run(
      classDirectories: List[Path],
      classpath: List[Path],
      selection: TestSelection = TestSelection.All
  ): IO[TestResult] = IO.blocking {
    // Share sbt.testing types with Sprout while loading the project's framework and suites in isolation.
    val loader = new URLClassLoader(classpath.map(_.toUri.toURL).toArray, getClass.getClassLoader)
    try
      val suiteClass = loader.loadClass("munit.Suite")
      val allCandidates = classDirectories.flatMap(classNames).sorted.filter { name =>
        try suiteClass.isAssignableFrom(loader.loadClass(name))
        catch case _: Throwable => false
      }
      val candidates = selectSuites(allCandidates, selection)
      if candidates.isEmpty then throw SproutError.User("No MUnit test suites found")
      val framework = loader
        .loadClass("munit.Framework")
        .getDeclaredConstructor()
        .newInstance()
        .asInstanceOf[Framework]
      val runner = framework.runner(Array.empty, Array.empty, loader)
      var total = 0
      var failed = 0
      val handler = new EventHandler:
        def handle(event: Event): Unit =
          total += 1
          if event.status == Status.Failure || event.status == Status.Error then failed += 1
      val logger = new Logger:
        def ansiCodesSupported(): Boolean = false
        def error(message: String): Unit = System.err.println(message)
        def warn(message: String): Unit = System.err.println(message)
        def info(message: String): Unit = println(message)
        def debug(message: String): Unit = ()
        def trace(throwable: Throwable): Unit = throwable.printStackTrace()
      val fingerprint = new SubclassFingerprint:
        def isModule(): Boolean = false
        def requireNoArgConstructor(): Boolean = true
        def superclassName(): String = "munit.Suite"
      var tasks = runner
        .tasks(
          candidates
            .map(name => new TaskDef(name, fingerprint, false, Array(new SuiteSelector)))
            .toArray
        )
        .toList
      while tasks.nonEmpty do tasks = tasks.flatMap(_.execute(handler, Array(logger)))
      runner.done()
      TestResult(total, failed)
    finally loader.close()
  }

  private def classNames(root: Path): List[String] =
    if !Files.isDirectory(root) then Nil
    else
      val stream = Files.walk(root)
      try
        stream.iterator.asScala
          .filter(path =>
            Files.isRegularFile(path) && path.toString
              .endsWith(".class") && !path.getFileName.toString.contains("$")
          )
          .map(path =>
            root
              .relativize(path)
              .toString
              .stripSuffix(".class")
              .replace(java.io.File.separatorChar, '.')
          )
          .toList
      finally stream.close()

  private def selectSuites(candidates: List[String], selection: TestSelection): List[String] =
    selection match
      case TestSelection.All         => candidates
      case TestSelection.Suite(name) =>
        val selected =
          if name.contains('.') then candidates.filter(_ == name)
          else candidates.filter(_.split('.').lastOption.contains(name))
        selected match
          case Nil =>
            throw SproutError.User(s"No MUnit suite matches '$name'")
          case suite :: Nil => List(suite)
          case many         =>
            throw SproutError.User(
              s"MUnit suite name '$name' is ambiguous:\n\n${many.sorted.mkString("\n")}\n\n" +
                "Use a fully qualified suite name or a source file path."
            )
