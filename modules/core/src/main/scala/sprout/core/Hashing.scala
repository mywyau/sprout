package sprout.core

import cats.effect.IO
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

object Hashing:
  private val CompilationFingerprintVersion = "sprout-compilation-fingerprint-v2"
  private val OutputFingerprintVersion = "sprout-output-fingerprint-v1"

  def compilationKey(request: CompilationRequest): IO[CacheKey] = IO.blocking {
    val fingerprint = Fingerprint(CompilationFingerprintVersion)
    fingerprint.add("scala-version", request.scalaVersion.value)
    fingerprint.add("jvm-target", request.jvmTarget.value)
    request.compilerOptions.zipWithIndex.foreach { case (option, index) =>
      fingerprint.add(s"compiler-option-$index", option)
    }
    request.sources.zipWithIndex.foreach { case (source, index) =>
      fingerprint.addPath(s"source-$index", source)
    }
    request.classpath.zipWithIndex.foreach { case (entry, index) =>
      fingerprint.addPath(s"classpath-$index", entry)
    }
    request.compilerClasspath.zipWithIndex.foreach { case (entry, index) =>
      fingerprint.addPath(s"compiler-classpath-$index", entry)
    }
    CacheKey(fingerprint.result())
  }

  def outputFingerprint(directory: Path): IO[Option[String]] = IO.blocking {
    val regularFiles = files(directory)
    Option.when(regularFiles.nonEmpty) {
      val fingerprint = Fingerprint(OutputFingerprintVersion)
      regularFiles.foreach(path => fingerprint.addFile(directory.relativize(path).toString, path))
      fingerprint.result()
    }
  }

  private final class Fingerprint private (digest: MessageDigest):
    def add(label: String, value: String): Unit =
      addBytes(label, value.getBytes(StandardCharsets.UTF_8))

    def addPath(label: String, path: Path): Unit =
      val normalized = path.toAbsolutePath.normalize
      add(s"$label-path", normalized.toString)
      if Files.isRegularFile(normalized) then addFile(s"$label-file", normalized)
      else if Files.isDirectory(normalized) then
        add(s"$label-kind", "directory")
        files(normalized).foreach(file => addFile(normalized.relativize(file).toString, file))
      else add(s"$label-kind", "missing")

    def addFile(label: String, path: Path): Unit =
      add(s"$label-kind", "file")
      add(s"$label-name", label)
      add(s"$label-size", Files.size(path).toString)
      val input = Files.newInputStream(path)
      try
        val buffer = Array.ofDim[Byte](64 * 1024)
        Iterator
          .continually(input.read(buffer))
          .takeWhile(_ != -1)
          .foreach(count => digest.update(buffer, 0, count))
      finally input.close()

    def result(): String = digest.digest().map(byte => f"${byte & 0xff}%02x").mkString

    private def addBytes(label: String, bytes: Array[Byte]): Unit =
      val labelBytes = label.getBytes(StandardCharsets.UTF_8)
      digest.update(ByteBuffer.allocate(4).putInt(labelBytes.length).array())
      digest.update(labelBytes)
      digest.update(ByteBuffer.allocate(8).putLong(bytes.length.toLong).array())
      digest.update(bytes)

  private object Fingerprint:
    def apply(version: String): Fingerprint =
      val fingerprint = new Fingerprint(MessageDigest.getInstance("SHA-256"))
      fingerprint.add("format", version)
      fingerprint

  private def files(directory: Path): List[Path] =
    if !Files.isDirectory(directory) then Nil
    else
      val stream = Files.walk(directory)
      try
        stream.iterator.asScala
          .filter(Files.isRegularFile(_))
          .toList
          .sortBy(path => directory.relativize(path).toString)
      finally stream.close()
