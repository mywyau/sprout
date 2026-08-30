package sprout.compiler

import cats.effect.IO
import sprout.core.*

import java.net.{URL, URLClassLoader}
import java.nio.file.{Files, Path}
import java.util.Optional
import java.util.function.{Function as JavaFunction, Supplier}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.control.NonFatal

import sbt.internal.inc.{
  FileAnalysisStore,
  FreshCompilerCache,
  IncrementalCompilerImpl,
  Locate,
  PlainVirtualFile,
  PlainVirtualFileConverter,
  ScalaInstance,
  Stamps,
  ZincUtil
}
import sbt.internal.inc.classpath.ClasspathUtil
import xsbti.{Logger, Position, Problem, Reporter, Severity, VirtualFile}
import xsbti.compile.{
  AnalysisContents,
  AnalysisStore,
  ClasspathOptionsUtil,
  CompileAnalysis,
  CompileOrder,
  CompileProgress,
  IncOptions,
  PerClasspathEntryLookup
}
import xsbti.compile.analysis.ReadWriteMappers

/** Zinc-backed Scala 3 compilation. The persisted analysis is deliberately an implementation detail
  * of the compiler boundary so a future backend can replace it without affecting builds.
  */
final class ZincScalaCompiler(captureOutput: Boolean = false) extends ScalaCompiler[IO]:
  def compile(request: CompilationRequest): IO[CompilationResult] =
    if request.sources.isEmpty then IO.pure(CompilationResult.UpToDate)
    else
      request.incremental match
        case None =>
          IO.raiseError(
            SproutError.User("Incremental compiler state was not configured for this compilation")
          )
        case Some(incremental) =>
          IO.interruptibleMany(compileBlocking(request, incremental)).handleErrorWith {
            case error: SproutError => IO.raiseError(error)
            case error              => IO.raiseError(error)
          }

  private def compileBlocking(
      request: CompilationRequest,
      incremental: IncrementalCompilation
  ): CompilationResult =
    Files.createDirectories(request.outputDirectory)
    Files.createDirectories(incremental.stateDirectory)

    val compilerFiles = request.compilerClasspath.map(_.toFile).toArray
    val libraryFiles = request.compilerClasspath
      .filter(_.getFileName.toString.startsWith("scala-library-"))
      .map(_.toFile)
      .toArray
    val loader = CompilerClassLoader(request.compilerClasspath)
    val libraryLoader = URLClassLoader(
      libraryFiles.map(_.toURI.toURL),
      ClasspathUtil.rootLoader
    )

    try
      val instance = new ScalaInstance(
        request.scalaVersion.value,
        loader,
        loader,
        libraryLoader,
        libraryFiles,
        compilerFiles,
        compilerFiles,
        None
      )
      val incrementalCompiler = IncrementalCompilerImpl()
      val classpathOptions = ClasspathOptionsUtil.noboot(request.scalaVersion.value)
      val scalac = ZincUtil.scalaCompiler(instance, incremental.compilerBridge, classpathOptions)
      val compilers = incrementalCompiler.compilers(
        instance,
        classpathOptions,
        Some(Path.of(System.getProperty("java.home"))),
        scalac
      )
      val converter = PlainVirtualFileConverter.converter
      val classpath = (request.classpath :+ request.outputDirectory).distinct
        .filter(Files.exists(_))
        .map(PlainVirtualFile(_): VirtualFile)
        .toArray
      val sources = request.sources.map(PlainVirtualFile(_): VirtualFile).toArray
      val reporter = CollectingReporter(captureOutput)
      val progress = SourceProgress()
      val lookup = new PerClasspathEntryLookup:
        def analysis(entry: VirtualFile): Optional[CompileAnalysis] = Optional.empty()
        def definesClass(entry: VirtualFile): xsbti.compile.DefinesClass =
          Locate.definesClass(entry)
      val analysisFile = incremental.stateDirectory.resolve("analysis.zip")
      val store = AtomicAnalysisStore(analysisFile)
      val previous = store.get().toScala match
        case Some(contents) => incrementalCompiler.previousResult(contents)
        case None           => incrementalCompiler.emptyPreviousResult
      val setup = incrementalCompiler.setup(
        lookup,
        false,
        analysisFile,
        FreshCompilerCache(),
        IncOptions.of(),
        reporter,
        Some(progress),
        None,
        Array.empty
      )
      val inputs = incrementalCompiler.inputs(
        classpath,
        sources,
        request.outputDirectory,
        None,
        request.compilerOptions.toArray,
        Array.empty,
        100,
        Array.empty[JavaFunction[Position, Optional[Position]]],
        CompileOrder.Mixed,
        compilers,
        setup,
        previous,
        Optional.empty(),
        converter,
        Stamps.timeWrapBinaryStamps(converter)
      )

      try
        val result = incrementalCompiler.compile(inputs, ZincLogger(captureOutput))
        store.set(AnalysisContents.create(result.getAnalysis, result.getMiniSetup))
        if result.hasModified() then
          val count = Option
            .when(progress.sourceCount > 0)(progress.sourceCount)
            .getOrElse(request.sources.size)
          CompilationResult.Compiled(count)
        else CompilationResult.UpToDate
      catch
        case _: xsbti.CompileFailed =>
          val details = reporter.renderedProblems
          throw SproutError.Compilation(1, Option.when(captureOutput)(details).filter(_.nonEmpty))
    finally
      loader.close()
      libraryLoader.close()

private final class CompilerClassLoader(urls: Array[URL], parent: ClassLoader)
    extends URLClassLoader(urls, parent):
  override protected def loadClass(name: String, resolve: Boolean): Class[?] = synchronized {
    if name.startsWith("xsbti.") || name.startsWith("java.") || name.startsWith("javax.") then
      super.loadClass(name, resolve)
    else
      val loaded = findLoadedClass(name)
      val result =
        if loaded != null then loaded
        else
          try findClass(name)
          catch case _: ClassNotFoundException => super.loadClass(name, false)
      if resolve then resolveClass(result)
      result
  }

private object CompilerClassLoader:
  def apply(classpath: List[Path]): CompilerClassLoader =
    val applicationLoader = classOf[ZincScalaCompiler].getClassLoader
    val sharedInterfaces = new ClassLoader(ClasspathUtil.rootLoader):
      override protected def loadClass(name: String, resolve: Boolean): Class[?] =
        if name.startsWith("xsbti.") then applicationLoader.loadClass(name)
        else super.loadClass(name, resolve)
    new CompilerClassLoader(
      classpath.map(_.toUri.toURL).toArray,
      sharedInterfaces
    )

private final class SourceProgress extends CompileProgress:
  private val compiledSources = mutable.LinkedHashSet.empty[String]

  override def startUnit(phase: String, unitPath: String): Unit =
    if unitPath.nonEmpty then compiledSources += unitPath

  override def advance(
      current: Int,
      total: Int,
      previousPhase: String,
      nextPhase: String
  ): Boolean = true

  def sourceCount: Int = compiledSources.size

private object SourceProgress:
  def apply(): SourceProgress = new SourceProgress

private final class CollectingReporter(captureOutput: Boolean) extends Reporter:
  private val reported = mutable.ArrayBuffer.empty[Problem]

  def reset(): Unit = reported.clear()
  def hasErrors(): Boolean = reported.exists(_.severity() == Severity.Error)
  def hasWarnings(): Boolean = reported.exists(_.severity() == Severity.Warn)
  def printSummary(): Unit = ()
  def problems(): Array[Problem] = reported.toArray
  def comment(position: Position, message: String): Unit = ()

  def log(problem: Problem): Unit =
    reported += problem
    if !captureOutput then System.err.println(render(problem))

  def renderedProblems: String = reported.map(render).mkString(System.lineSeparator())

  private def render(problem: Problem): String =
    problem.rendered().toScala.getOrElse {
      val position = problem.position()
      val location = position.sourcePath().toScala.map { path =>
        val line = position.line().toScala.map(_ + 1).getOrElse(1)
        val column = position.pointer().toScala.map(_ + 1).getOrElse(1)
        s"$path:$line:$column"
      }
      s"${location.fold("")(_ + ": ")}${problem.message()}"
    }

private object CollectingReporter:
  def apply(captureOutput: Boolean): CollectingReporter = new CollectingReporter(captureOutput)

private final class ZincLogger(captureOutput: Boolean) extends Logger:
  def error(message: Supplier[String]): Unit =
    if !captureOutput then System.err.println(message.get())
  def warn(message: Supplier[String]): Unit =
    if !captureOutput then System.err.println(message.get())
  def info(message: Supplier[String]): Unit = ()
  def debug(message: Supplier[String]): Unit = ()
  def trace(error: Supplier[Throwable]): Unit = ()

private object ZincLogger:
  def apply(captureOutput: Boolean): ZincLogger = new ZincLogger(captureOutput)

/** Zinc's binary format tolerates corrupt reads. Sprout stages each complete update beside the
  * destination before the final atomic replacement, ensuring the rename stays on one filesystem.
  */
private final class AtomicAnalysisStore(destination: Path) extends AnalysisStore:
  private val mappers = ReadWriteMappers.getEmptyMappers()

  def get(): Optional[AnalysisContents] =
    try delegate(destination).get()
    catch case NonFatal(_) => Optional.empty()

  def unsafeGet(): AnalysisContents = get().orElseThrow()

  def set(contents: AnalysisContents): Unit =
    Files.createDirectories(destination.getParent)
    val staging = Files.createTempFile(destination.getParent, ".analysis-", ".tmp")
    try
      delegate(staging).set(contents)
      AtomicFile.moveReplacing(staging, destination)
    finally Files.deleteIfExists(staging)

  private def delegate(file: Path): AnalysisStore =
    FileAnalysisStore.binary(file.toFile, mappers, destination.getParent.toFile)

private object AtomicAnalysisStore:
  def apply(destination: Path): AtomicAnalysisStore = new AtomicAnalysisStore(destination)
