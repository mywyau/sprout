package sprout.compiler

import cats.effect.IO
import sprout.core.*
import java.nio.file.{Files, Path}

final class ProcessScalaCompiler extends ScalaCompiler[IO]:
  def compile(request: CompilationRequest): IO[CompilationResult] =
    if request.sources.isEmpty then IO.pure(CompilationResult.UpToDate)
    else
      IO.blocking {
        deleteExistingOutput(request.outputDirectory)
        Files.createDirectories(request.outputDirectory)
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString
        val command = List(
          java,
          "-cp",
          render(request.compilerClasspath),
          "dotty.tools.dotc.Main",
          "-classpath",
          render(request.classpath),
          "-d",
          request.outputDirectory.toString
        ) ++ request.compilerOptions ++ request.sources.map(_.toString)
        val exit = new ProcessBuilder(command*).inheritIO().start().waitFor()
        if exit != 0 then throw SproutError.Compilation(exit)
        CompilationResult.Compiled(request.sources.size)
      }

  private def deleteExistingOutput(directory: Path): Unit =
    if Files.isDirectory(directory) then
      val stream = Files.walk(directory)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      finally stream.close()

  private def render(paths: List[Path]): String = paths.mkString(java.io.File.pathSeparator)

final class CachingScalaCompiler(delegate: ScalaCompiler[IO], cache: Cache[IO])
    extends ScalaCompiler[IO]:
  def compile(request: CompilationRequest): IO[CompilationResult] =
    for
      key <- Hashing.compilationKey(request)
      cached <- cache.get(key)
      reusable <- outputExists(request.outputDirectory)
      result <-
        if cached.nonEmpty && reusable then IO.pure(CompilationResult.UpToDate)
        else
          delegate.compile(request).flatTap {
            case CompilationResult.Compiled(_) => cache.put(key, CachedValue("compiled"))
            case CompilationResult.UpToDate    => IO.unit
          }
    yield result

  private def outputExists(directory: Path): IO[Boolean] = IO.blocking {
    if !Files.isDirectory(directory) then false
    else
      val stream = Files.walk(directory)
      try stream.anyMatch(path => Files.isRegularFile(path) && path.toString.endsWith(".class"))
      finally stream.close()
  }
