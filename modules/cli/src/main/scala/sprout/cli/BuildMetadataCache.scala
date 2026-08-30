package sprout.cli

import cats.effect.IO
import sprout.compiler.FileCache
import sprout.core.*
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.Base64
import scala.util.control.NonFatal

final case class CachedMetadata[+A](value: A, cached: Boolean)

final class BuildMetadataCache private (cache: Cache[IO]):
  def dependencies(
      scalaVersion: ScalaVersion,
      dependencies: List[Dependency]
  )(resolve: IO[ResolvedDependencies]): IO[CachedMetadata[ResolvedDependencies]] =
    cached(
      CacheKey(s"dependencies-${fingerprint(scalaVersion, dependencies)}"),
      BuildMetadataCache.decodeDependencies,
      BuildMetadataCache.validDependencies
    )(resolve.map(BuildMetadataCache.encodeDependencies))

  def compilerClasspath(
      scalaVersion: ScalaVersion
  )(resolve: IO[ResolvedClasspath]): IO[CachedMetadata[ResolvedClasspath]] =
    cached(
      CacheKey(s"compiler-classpath-${fingerprint(scalaVersion, Nil)}"),
      BuildMetadataCache.decodeClasspath,
      BuildMetadataCache.validClasspath
    )(resolve.map(BuildMetadataCache.encodeClasspath))

  def compilerBridge(scalaVersion: ScalaVersion)(resolve: IO[Path]): IO[CachedMetadata[Path]] =
    cached(
      CacheKey(s"compiler-bridge-${fingerprint(scalaVersion, Nil)}"),
      BuildMetadataCache.decodePath,
      BuildMetadataCache.validPath
    )(resolve.map(BuildMetadataCache.encodePath))

  def mainClass(
      compilationKey: CacheKey,
      classes: Path
  )(discover: IO[String]): IO[CachedMetadata[String]] =
    cached(
      CacheKey(s"main-class-${compilationKey.value}"),
      BuildMetadataCache.decodeMainClass,
      name => BuildMetadataCache.validMainClass(classes, name)
    )(discover.map(BuildMetadataCache.encodeMainClass))

  private def cached[A](
      key: CacheKey,
      decode: CachedValue => Option[A],
      valid: A => Boolean
  )(load: IO[CachedValue]): IO[CachedMetadata[A]] =
    cache.get(key).flatMap {
      case Some(value) =>
        decode(value).filter(valid) match
          case Some(result) => IO.pure(CachedMetadata(result, cached = true))
          case None         => loadAndStore(key, decode, load)
      case None => loadAndStore(key, decode, load)
    }

  private def loadAndStore[A](
      key: CacheKey,
      decode: CachedValue => Option[A],
      load: IO[CachedValue]
  ): IO[CachedMetadata[A]] =
    load.flatMap { value =>
      IO.fromOption(decode(value))(
        IllegalStateException("could not decode Sprout metadata just written")
      ).flatTap(_ => cache.put(key, value))
        .map(CachedMetadata(_, cached = false))
    }

  private def fingerprint(scalaVersion: ScalaVersion, dependencies: List[Dependency]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    def add(value: String): Unit =
      digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      digest.update(0.toByte)

    add("sprout-build-metadata-input-v1")
    add(scalaVersion.value)
    dependencies.foreach { dependency =>
      add(dependency.organisation.value)
      add(dependency.artifact.value)
      add(dependency.version.value)
      add(dependency.scope.toString)
      add(dependency.crossVersion.toString)
    }
    digest.digest().map(byte => f"${byte & 0xff}%02x").mkString

object BuildMetadataCache:
  def apply(metadataDirectory: Path): BuildMetadataCache =
    new BuildMetadataCache(
      FileCache(metadataDirectory.resolve("build-session"), metadataVersion = 2)
    )

  private val DependenciesFormat = "sprout-resolved-dependencies-v1:"
  private val ClasspathFormat = "sprout-resolved-classpath-v1:"
  private val BridgeFormat = "sprout-compiler-bridge-v1:"
  private val MainClassFormat = "sprout-main-class-v1:"

  private def encodeDependencies(value: ResolvedDependencies): CachedValue =
    encode(DependenciesFormat) { output =>
      writeClasspath(output, value.classpath)
      output.writeInt(value.graph.modules.size)
      value.graph.modules.foreach { dependency =>
        writeModule(output, dependency.module)
        output.writeUTF(dependency.version)
        output.writeBoolean(dependency.direct)
        writePaths(output, dependency.artifacts)
      }
      output.writeInt(value.graph.relations.size)
      value.graph.relations.foreach { relation =>
        output.writeBoolean(relation.parent.nonEmpty)
        relation.parent.foreach(writeModule(output, _))
        writeModule(output, relation.child)
        output.writeUTF(relation.requestedVersion)
        output.writeUTF(relation.selectedVersion)
      }
    }

  private def decodeDependencies(value: CachedValue): Option[ResolvedDependencies] =
    decode(value, DependenciesFormat) { input =>
      val classpath = readClasspath(input)
      val modules = List.fill(readCount(input)) {
        ResolvedDependency(
          readModule(input),
          input.readUTF(),
          input.readBoolean(),
          readPaths(input)
        )
      }
      val relations = List.fill(readCount(input)) {
        val parent = Option.when(input.readBoolean())(readModule(input))
        DependencyRelation(parent, readModule(input), input.readUTF(), input.readUTF())
      }
      ResolvedDependencies(classpath, ResolvedDependencyGraph(modules, relations))
    }

  private def encodeClasspath(value: ResolvedClasspath): CachedValue =
    encode(ClasspathFormat)(writeClasspath(_, value))

  private def decodeClasspath(value: CachedValue): Option[ResolvedClasspath] =
    decode(value, ClasspathFormat)(readClasspath)

  private def encodePath(value: Path): CachedValue = CachedValue(BridgeFormat + value.toString)

  private def decodePath(value: CachedValue): Option[Path] =
    Option.when(value.value.startsWith(BridgeFormat))(
      Path.of(value.value.drop(BridgeFormat.length))
    )

  private def encodeMainClass(value: String): CachedValue = CachedValue(MainClassFormat + value)

  private def decodeMainClass(value: CachedValue): Option[String] =
    Option.when(
      value.value.startsWith(MainClassFormat) && value.value.drop(MainClassFormat.length).nonEmpty
    )(
      value.value.drop(MainClassFormat.length)
    )

  private def validDependencies(value: ResolvedDependencies): Boolean =
    validClasspath(value.classpath) && value.graph.modules.forall(_.artifacts.forall(validPath))

  private def validClasspath(value: ResolvedClasspath): Boolean =
    value.artifacts.forall(artifact => validPath(artifact.file))

  private def validPath(path: Path): Boolean = Files.isRegularFile(path)

  private def validMainClass(classes: Path, name: String): Boolean =
    Files.isRegularFile(classes.resolve(name.replace('.', java.io.File.separatorChar) + ".class"))

  private def encode(prefix: String)(write: DataOutputStream => Unit): CachedValue =
    val bytes = ByteArrayOutputStream()
    val output = DataOutputStream(bytes)
    try write(output)
    finally output.close()
    CachedValue(prefix + Base64.getEncoder.encodeToString(bytes.toByteArray))

  private def decode[A](value: CachedValue, prefix: String)(read: DataInputStream => A): Option[A] =
    if !value.value.startsWith(prefix) then None
    else
      try
        val input = DataInputStream(
          ByteArrayInputStream(Base64.getDecoder.decode(value.value.drop(prefix.length)))
        )
        try Some(read(input))
        finally input.close()
      catch case NonFatal(_) => None

  private def writeClasspath(output: DataOutputStream, classpath: ResolvedClasspath): Unit =
    output.writeInt(classpath.artifacts.size)
    classpath.artifacts.foreach { artifact =>
      output.writeUTF(artifact.module)
      output.writeUTF(artifact.version)
      output.writeUTF(artifact.file.toString)
    }

  private def readClasspath(input: DataInputStream): ResolvedClasspath =
    ResolvedClasspath(List.fill(readCount(input)) {
      ResolvedArtifact(input.readUTF(), input.readUTF(), Path.of(input.readUTF()))
    })

  private def writeModule(output: DataOutputStream, module: ResolvedModule): Unit =
    output.writeUTF(module.organisation)
    output.writeUTF(module.name)

  private def readModule(input: DataInputStream): ResolvedModule =
    ResolvedModule(input.readUTF(), input.readUTF())

  private def writePaths(output: DataOutputStream, paths: List[Path]): Unit =
    output.writeInt(paths.size)
    paths.foreach(path => output.writeUTF(path.toString))

  private def readPaths(input: DataInputStream): List[Path] =
    List.fill(readCount(input))(Path.of(input.readUTF()))

  private def readCount(input: DataInputStream): Int =
    val count = input.readInt()
    if count < 0 || count > 100000 then
      throw IllegalArgumentException(s"invalid Sprout metadata entry count: $count")
    count
