package sprout.core

import java.nio.file.Path

trait DependencyResolver[F[_]]:
  def resolve(scalaVersion: ScalaVersion, dependencies: List[Dependency]): F[ResolvedDependencies]
  def compilerClasspath(scalaVersion: ScalaVersion): F[ResolvedClasspath]
  def compilerBridge(scalaVersion: ScalaVersion): F[Path]

trait ScalaCompiler[F[_]]:
  def compile(request: CompilationRequest): F[CompilationResult]

opaque type CacheKey = String
object CacheKey:
  def apply(value: String): CacheKey = value
  extension (key: CacheKey) def value: String = key

opaque type CachedValue = String
object CachedValue:
  def apply(value: String): CachedValue = value
  extension (value: CachedValue) def value: String = value

trait Cache[F[_]]:
  def get(key: CacheKey): F[Option[CachedValue]]
  def put(key: CacheKey, value: CachedValue): F[Unit]

trait ApplicationRunner[F[_]]:
  def run(mainClass: String, classpath: List[Path], arguments: List[String]): F[Int]

trait TestRunner[F[_]]:
  def run(
      classDirectories: List[Path],
      classpath: List[Path],
      selection: TestSelection = TestSelection.All,
      output: TestOutput = TestOutput.Compact
  ): F[TestResult]

final case class TestResult(total: Int, failed: Int)

enum TestSelection:
  case All
  case Suite(name: String)

enum TestOutput:
  case Compact, Verbose
