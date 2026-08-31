package sprout.runner

import cats.effect.IO
import sprout.core.*
import sbt.testing.*
import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

final class MUnitTestRunner extends TestRunner[IO]:
  def run(
      classDirectories: List[Path],
      classpath: List[Path],
      selection: TestSelection = TestSelection.All,
      output: TestOutput = TestOutput.Verbose
  ): IO[TestResult] = IO.blocking {
    // Share sbt.testing types with Sprout while loading the project's framework and suites in isolation.
    val loader = new URLClassLoader(classpath.map(_.toUri.toURL).toArray, getClass.getClassLoader)
    try
      var total = 0
      var failed = 0
      val capturedOutput = captureSuccessfulOutput(output) {
        val frameworks = FrameworkDefinition.available(loader)
        val candidates = frameworks.flatMap { definition =>
          val suiteClass = loader.loadClass(definition.suiteClass)
          classDirectories.flatMap(classNames).distinct.sorted.collect {
            case name if suiteClass.isAssignableFrom(loader.loadClass(name)) => definition -> name
          }
        }
        val selected = selectSuites(candidates.map(_._2), selection).toSet
        val runnable = candidates.filter((_, name) => selected.contains(name))
        if runnable.isEmpty then throw SproutError.User("No supported test suites found")
        val handler = new EventHandler:
          def handle(event: Event): Unit =
            total += 1
            if event.status == Status.Failure || event.status == Status.Error then
              failed += 1
              renderFailure(event)
        val logger = new Logger:
          def ansiCodesSupported(): Boolean = false
          def error(message: String): Unit = System.err.println(message)
          def warn(message: String): Unit = System.err.println(message)
          def info(message: String): Unit = if output == TestOutput.Verbose then println(message)
          def debug(message: String): Unit = ()
          def trace(throwable: Throwable): Unit = throwable.printStackTrace()
        runnable.groupMap(_._1)(_._2).foreach { case (definition, names) =>
          val framework = definition.create(loader)
          val runner = framework.runner(Array.empty, Array.empty, loader)
          val fingerprint = new SubclassFingerprint:
            def isModule(): Boolean = false
            def requireNoArgConstructor(): Boolean = true
            def superclassName(): String = definition.suiteClass
          var tasks = runner
            .tasks(
              names
                .map(name => new TaskDef(name, fingerprint, false, Array(new SuiteSelector)))
                .toArray
            )
            .toList
          while tasks.nonEmpty do tasks = tasks.flatMap(_.execute(handler, Array(logger)))
          runner.done()
        }
      }
      if failed > 0 && capturedOutput.nonEmpty then System.err.print(capturedOutput)
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
              s"Test suite name '$name' is ambiguous:\n\n${many.sorted.mkString("\n")}\n\n" +
                "Use a fully qualified suite name or a source file path."
            )

  private def renderFailure(event: Event): Unit =
    val selector = event.selector match
      case test: TestSelector => s" (${test.testName})"
      case _                  => ""
    System.err.println(s"${event.fullyQualifiedName}$selector: ${event.status}")
    val throwable = event.throwable
    if throwable.isDefined then throwable.get.printStackTrace()

  private def captureSuccessfulOutput(output: TestOutput)(run: => Unit): String =
    if output == TestOutput.Verbose then
      run
      ""
    else
      MUnitTestRunner.outputLock.synchronized {
        val bytes = ByteArrayOutputStream()
        val original = System.out
        val captured = PrintStream(bytes)
        try
          System.setOut(captured)
          run
          captured.flush()
          bytes.toString(StandardCharsets.UTF_8)
        finally
          System.setOut(original)
          captured.close()
      }

object MUnitTestRunner:
  private val outputLock = new Object

private final case class FrameworkDefinition(factoryClass: String, suiteClass: String):
  def create(loader: ClassLoader): Framework =
    loader.loadClass(factoryClass).getDeclaredConstructor().newInstance().asInstanceOf[Framework]

private object FrameworkDefinition:
  private val Supported = List(
    FrameworkDefinition("munit.Framework", "munit.Suite"),
    FrameworkDefinition("org.scalatest.tools.Framework", "org.scalatest.Suite")
  )

  def available(loader: ClassLoader): List[FrameworkDefinition] =
    Supported.filter { definition =>
      try
        loader.loadClass(definition.factoryClass)
        loader.loadClass(definition.suiteClass)
        true
      catch case _: ClassNotFoundException => false
    }
