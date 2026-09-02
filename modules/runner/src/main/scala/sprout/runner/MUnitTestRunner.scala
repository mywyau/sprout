package sprout.runner

import cats.effect.IO
import sprout.core.*
import sbt.testing.*
import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

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
        val frameworks = FrameworkDefinition.available(loader, classpath)
        val suites = classDirectories
          .flatMap(classNames)
          .groupBy(_.name)
          .values
          .map(_.maxBy(_.isModule))
          .toList
          .sortBy(_.name)
        val discovered = frameworks.flatMap { definition =>
          suites.flatMap { candidate =>
            val suite = loader.loadClass(candidate.className)
            definition.framework.fingerprints.toList.collect {
              case fingerprint if matches(fingerprint, suite, candidate.isModule) =>
                RunnableSuite(definition, fingerprint, candidate.name, candidate.isModule)
            }
          }
        }
        val candidates = discovered
          // A framework can expose multiple compatible fingerprints, and different discovery
          // mechanisms can report equivalent framework implementations. A suite must run once.
          .groupBy(_.name)
          .values
          .map(_.minBy(_.definition.factoryClass))
          .toList
        val selected = selectSuites(candidates.map(_.name), selection).toSet
        val runnable = candidates.filter(candidate => selected.contains(candidate.name))
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
        runnable
          .groupMap(candidate => (candidate.definition, candidate.fingerprint, candidate.isModule))(
            _.name
          )
          .foreach { case ((definition, fingerprint, isModule), names) =>
            val runner = definition.framework.runner(Array.empty, Array.empty, loader)
            var tasks = runner
              .tasks(
                names
                  .map(name => new TaskDef(name, fingerprint, isModule, Array(new SuiteSelector)))
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

  private def classNames(root: Path): List[SuiteClass] =
    if !Files.isDirectory(root) then Nil
    else
      val stream = Files.walk(root)
      try
        stream.iterator.asScala
          .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".class"))
          .flatMap { path =>
            val name = root.relativize(path).toString.replace(java.io.File.separatorChar, '.')
            if name.endsWith("$.class") then
              Some(SuiteClass(name.stripSuffix("$.class"), isModule = true))
            else if !name.contains("$") then
              Some(SuiteClass(name.stripSuffix(".class"), isModule = false))
            else None
          }
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
            throw SproutError.User(s"No test suite matches '$name'")
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

  private def matches(
      fingerprint: Fingerprint,
      suite: Class[?],
      isModule: Boolean
  ): Boolean = fingerprint match
    case value: SubclassFingerprint =>
      if value.isModule() != isModule || value.superclassName() == null then false
      else
        try
          val parent = suite.getClassLoader.loadClass(value.superclassName())
          parent.isAssignableFrom(suite) &&
          (!value.requireNoArgConstructor() || suite.getDeclaredConstructors.exists(
            _.getParameterCount == 0
          ))
        catch case _: ClassNotFoundException => false
    case value: AnnotatedFingerprint =>
      suite.getAnnotations.exists(_.annotationType.getName == value.annotationName())
    case _ => false

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

private final case class FrameworkDefinition(factoryClass: String, framework: Framework)
private final case class RunnableSuite(
    definition: FrameworkDefinition,
    fingerprint: Fingerprint,
    name: String,
    isModule: Boolean
)
private final case class SuiteClass(name: String, isModule: Boolean):
  def className: String = if isModule then name + "$" else name

private object FrameworkDefinition:
  def available(loader: ClassLoader, classpath: List[Path]): List[FrameworkDefinition] =
    val services = java.util.ServiceLoader.load(classOf[Framework], loader).iterator.asScala.toList
    val scanned = classpath.flatMap(frameworkClasses).flatMap { name =>
      try
        val candidate = loader.loadClass(name)
        Option.when(
          classOf[Framework].isAssignableFrom(candidate) &&
            !java.lang.reflect.Modifier.isAbstract(candidate.getModifiers)
        ) {
          candidate.getDeclaredConstructor().newInstance().asInstanceOf[Framework]
        }
      catch case NonFatal(_) => None
    }
    (services ++ scanned ++ Known.flatMap(instantiate(loader, _)))
      .groupBy(_.getClass.getName)
      .values
      .map(_.head)
      .toList
      .sortBy(_.getClass.getName)
      .map(framework => FrameworkDefinition(framework.getClass.getName, framework))

  private val Known = List(
    "munit.Framework",
    "org.scalatest.tools.Framework",
    "utest.runner.Framework"
  )

  private def instantiate(loader: ClassLoader, name: String): Option[Framework] =
    try Some(loader.loadClass(name).getDeclaredConstructor().newInstance().asInstanceOf[Framework])
    catch case NonFatal(_) => None

  private def frameworkClasses(path: Path): List[String] =
    if Files.isDirectory(path) then
      val stream = Files.walk(path)
      try
        stream.iterator.asScala
          .filter(value =>
            Files.isRegularFile(value) && value.getFileName.toString.endsWith("Framework.class")
          )
          .map(value =>
            path
              .relativize(value)
              .toString
              .stripSuffix(".class")
              .replace(java.io.File.separatorChar, '.')
          )
          .toList
      finally stream.close()
    else
      val jar = new JarFile(path.toFile)
      try
        jar.entries.asScala
          .filter(entry => !entry.isDirectory && entry.getName.endsWith("Framework.class"))
          .map(_.getName.stripSuffix(".class").replace('/', '.'))
          .toList
      catch case NonFatal(_) => Nil
      finally jar.close()
