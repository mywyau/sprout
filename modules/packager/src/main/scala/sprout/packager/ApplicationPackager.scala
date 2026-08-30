package sprout.packager

import cats.effect.IO
import java.io.{BufferedOutputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{
  AtomicMoveNotSupportedException,
  Files,
  Path,
  StandardCopyOption,
  StandardOpenOption
}
import java.security.MessageDigest
import java.util.jar.{Attributes, JarEntry, JarOutputStream, Manifest}
import org.apache.commons.compress.archivers.tar.{TarArchiveEntry, TarArchiveOutputStream}
import org.apache.commons.compress.archivers.zip.{ZipArchiveEntry, ZipArchiveOutputStream}
import org.apache.commons.compress.compressors.gzip.{GzipCompressorOutputStream, GzipParameters}
import scala.jdk.CollectionConverters.*
import sprout.core.SproutError

final case class ApplicationPackageRequest(
    name: String,
    scalaVersion: String,
    mainClass: String,
    classesDirectory: Path,
    resourceDirectories: List[Path],
    runtimeDependencies: List[Path],
    outputDirectory: Path
)

final case class ApplicationPackageResult(
    applicationDirectory: Path,
    tarArchive: Path,
    zipArchive: Path,
    archiveChecksums: Path,
    mainClass: String,
    dependencyCount: Int
)

final class ApplicationPackager:
  private val executableMode = 493 // 0755
  private val regularFileMode = 420 // 0644

  def create(request: ApplicationPackageRequest): IO[ApplicationPackageResult] = IO.blocking {
    Files.createDirectories(request.outputDirectory)
    val staging = Files.createTempDirectory(request.outputDirectory, s".${request.name}-")
    try
      val bin = Files.createDirectories(staging.resolve("bin"))
      val lib = Files.createDirectories(staging.resolve("lib"))
      val metadata = Files.createDirectories(staging.resolve("metadata"))
      val dependencyFiles = copyDependencies(request.runtimeDependencies, lib)
      val applicationJar = lib.resolve(s"${request.name}.jar")
      writeApplicationJar(request, applicationJar, dependencyFiles.map(_.getFileName.toString))
      writeLaunchers(request, bin, applicationJar.getFileName.toString, dependencyFiles)
      writeString(
        metadata.resolve("application.properties"),
        s"name=${request.name}\nmain-class=${request.mainClass}\nscala-version=${request.scalaVersion}\n"
      )
      writeContentChecksums(staging, metadata.resolve("checksums.txt"))

      val applicationDirectory = request.outputDirectory.resolve(request.name)
      replaceDirectory(staging, applicationDirectory)
      val tarArchive = request.outputDirectory.resolve(s"${request.name}.tar.gz")
      val zipArchive = request.outputDirectory.resolve(s"${request.name}.zip")
      writeArchiveAtomically(tarArchive)(temporary =>
        writeTarGzip(applicationDirectory, temporary, request.name)
      )
      writeArchiveAtomically(zipArchive)(temporary =>
        writeZip(applicationDirectory, temporary, request.name)
      )
      val archiveChecksums = request.outputDirectory.resolve(s"${request.name}-checksums.txt")
      writeStringAtomically(
        archiveChecksums,
        checksumLines(List(tarArchive, zipArchive), request.outputDirectory)
      )
      ApplicationPackageResult(
        applicationDirectory,
        tarArchive,
        zipArchive,
        archiveChecksums,
        request.mainClass,
        dependencyFiles.size
      )
    finally deleteTree(staging)
  }

  private def copyDependencies(dependencies: List[Path], destination: Path): List[Path] =
    dependencies.distinct.zipWithIndex.map { case (source, index) =>
      if !Files.isRegularFile(source) then
        throw SproutError.User(s"Runtime dependency is not a file: $source")
      val safeName = source.getFileName.toString.replaceAll("[^A-Za-z0-9._-]", "_")
      val target = destination.resolve(f"${index + 1}%04d-$safeName")
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
      target
    }

  private def writeApplicationJar(
      request: ApplicationPackageRequest,
      destination: Path,
      dependencies: List[String]
  ): Unit =
    val inputs = collectJarInputs(request.classesDirectory, request.resourceDirectories)
    val output = new JarOutputStream(
      new BufferedOutputStream(
        Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
      )
    )
    try
      val manifest = Manifest()
      manifest.getMainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
      manifest.getMainAttributes.put(Attributes.Name.MAIN_CLASS, request.mainClass)
      if dependencies.nonEmpty then
        manifest.getMainAttributes.put(Attributes.Name.CLASS_PATH, dependencies.mkString(" "))
      val bytes = ByteArrayOutputStream()
      manifest.write(bytes)
      val manifestEntry = JarEntry("META-INF/MANIFEST.MF")
      manifestEntry.setTime(0L)
      output.putNextEntry(manifestEntry)
      output.write(bytes.toByteArray)
      output.closeEntry()

      inputs.foreach { case (name, source) =>
        val entry = JarEntry(name)
        entry.setTime(0L)
        output.putNextEntry(entry)
        Files.copy(source, output)
        output.closeEntry()
      }
    finally output.close()

  private def collectJarInputs(
      classesDirectory: Path,
      resourceDirectories: List[Path]
  ): List[(String, Path)] =
    val roots = classesDirectory :: resourceDirectories.filter(Files.isDirectory(_))
    val inputs = roots.flatMap { root =>
      files(root).map(path => archivePath(root.relativize(path)) -> path)
    }
    val duplicate =
      inputs.groupBy(_._1).collectFirst { case (name, values) if values.size > 1 => name }
    duplicate.foreach(name =>
      throw SproutError.User(s"Cannot package duplicate application entry: $name")
    )
    if inputs.exists(_._1.equalsIgnoreCase("META-INF/MANIFEST.MF")) then
      throw SproutError.User("src/main/resources must not define META-INF/MANIFEST.MF")
    inputs.sortBy(_._1)

  private def writeLaunchers(
      request: ApplicationPackageRequest,
      bin: Path,
      applicationJar: String,
      dependencies: List[Path]
  ): Unit =
    val jars = applicationJar :: dependencies.map(_.getFileName.toString)
    val unixClasspath = jars.map(name => s"$$APP_HOME/lib/$name").mkString(":")
    val windowsClasspath = jars.map(name => s"%APP_HOME%\\lib\\$name").mkString(";")
    val unix =
      s"""#!/bin/sh
         |set -eu
         |APP_HOME=$$(CDPATH= cd -- "$$(dirname -- "$$0")/.." && pwd)
         |if [ -n "$${JAVA_HOME:-}" ]; then
         |  JAVA_CMD="$$JAVA_HOME/bin/java"
         |else
         |  JAVA_CMD=java
         |fi
         |APP_CLASSPATH="$unixClasspath"
         |exec "$$JAVA_CMD" $${JAVA_OPTS:-} -cp "$$APP_CLASSPATH" ${shellQuote(
          request.mainClass
        )} "$$@"
         |""".stripMargin
    val windows =
      s"""@echo off
         |setlocal
         |set "APP_HOME=%~dp0.."
         |if defined JAVA_HOME (
         |  set "JAVA_CMD=%JAVA_HOME%\\bin\\java.exe"
         |) else (
         |  set "JAVA_CMD=java"
         |)
         |set "APP_CLASSPATH=$windowsClasspath"
         |"%JAVA_CMD%" %JAVA_OPTS% -cp "%APP_CLASSPATH%" ${request.mainClass} %*
         |exit /b %ERRORLEVEL%
         |""".stripMargin
    val unixPath = bin.resolve(request.name)
    writeString(unixPath, unix)
    makeExecutable(unixPath)
    writeString(bin.resolve(s"${request.name}.cmd"), windows)

  private def writeContentChecksums(root: Path, destination: Path): Unit =
    val content = files(root).filterNot(_ == destination)
    writeString(destination, checksumLines(content, root))

  private def checksumLines(paths: List[Path], root: Path): String =
    paths
      .sortBy(path => archivePath(root.relativize(path)))
      .map { path =>
        s"${sha256(path)}  ${archivePath(root.relativize(path))}"
      }
      .mkString("", "\n", "\n")

  private def sha256(path: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val input = Files.newInputStream(path)
    try
      val buffer = Array.ofDim[Byte](64 * 1024)
      Iterator
        .continually(input.read(buffer))
        .takeWhile(_ != -1)
        .foreach(count => digest.update(buffer, 0, count))
    finally input.close()
    digest.digest().map("%02x".format(_)).mkString

  private def writeTarGzip(source: Path, destination: Path, name: String): Unit =
    val parameters = GzipParameters()
    parameters.setModificationTime(0L)
    val gzip = new GzipCompressorOutputStream(
      new BufferedOutputStream(Files.newOutputStream(destination)),
      parameters
    )
    val output = new TarArchiveOutputStream(gzip)
    output.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
    try
      archiveEntries(source).foreach { case (path, relative, directory) =>
        val entryName =
          s"$name${if relative.isEmpty then "/" else s"/$relative${if directory then "/" else ""}"}"
        val entry = new TarArchiveEntry(entryName)
        entry.setModTime(0L)
        entry.setUserId(0)
        entry.setGroupId(0)
        entry.setUserName("")
        entry.setGroupName("")
        entry.setMode(
          if directory || isUnixLauncher(relative, name) then executableMode
          else regularFileMode
        )
        entry.setSize(if directory then 0L else Files.size(path))
        output.putArchiveEntry(entry)
        if !directory then Files.copy(path, output)
        output.closeArchiveEntry()
      }
      output.finish()
    finally output.close()

  private def writeZip(source: Path, destination: Path, name: String): Unit =
    val output = new ZipArchiveOutputStream(
      new BufferedOutputStream(Files.newOutputStream(destination))
    )
    try
      archiveEntries(source).foreach { case (path, relative, directory) =>
        val entryName =
          s"$name${if relative.isEmpty then "/" else s"/$relative${if directory then "/" else ""}"}"
        val entry = new ZipArchiveEntry(entryName)
        entry.setTime(0L)
        entry.setUnixMode(
          if directory || isUnixLauncher(relative, name) then executableMode
          else regularFileMode
        )
        output.putArchiveEntry(entry)
        if !directory then Files.copy(path, output)
        output.closeArchiveEntry()
      }
      output.finish()
    finally output.close()

  private def archiveEntries(root: Path): List[(Path, String, Boolean)] =
    val stream = Files.walk(root)
    try
      stream.iterator.asScala
        .map(path => (path, archivePath(root.relativize(path)), Files.isDirectory(path)))
        .toList
        .sortBy(_._2)
    finally stream.close()

  private def files(root: Path): List[Path] =
    if !Files.isDirectory(root) then Nil
    else
      val stream = Files.walk(root)
      try stream.iterator.asScala.filter(Files.isRegularFile(_)).toList
      finally stream.close()

  private def writeArchiveAtomically(destination: Path)(write: Path => Unit): Unit =
    val temporary =
      Files.createTempFile(destination.getParent, s".${destination.getFileName}.", ".tmp")
    try
      write(temporary)
      moveReplacing(temporary, destination)
    finally Files.deleteIfExists(temporary)

  private def writeStringAtomically(destination: Path, content: String): Unit =
    val temporary =
      Files.createTempFile(destination.getParent, s".${destination.getFileName}.", ".tmp")
    try
      writeString(temporary, content)
      moveReplacing(temporary, destination)
    finally Files.deleteIfExists(temporary)

  private def replaceDirectory(staging: Path, destination: Path): Unit =
    deleteTree(destination)
    try Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
    catch case _: AtomicMoveNotSupportedException => Files.move(staging, destination)

  private def moveReplacing(source: Path, destination: Path): Unit =
    try
      Files.move(
        source,
        destination,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
    catch
      case _: AtomicMoveNotSupportedException =>
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)

  private def deleteTree(root: Path): Unit =
    if Files.exists(root) then
      val stream = Files.walk(root)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      finally stream.close()

  private def writeString(path: Path, content: String): Unit =
    Files.writeString(
      path,
      content,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
      StandardOpenOption.TRUNCATE_EXISTING
    )
    ()

  private def makeExecutable(path: Path): Unit =
    try
      val permissions = Files.getPosixFilePermissions(path).asScala
      permissions ++= Set(
        PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_EXECUTE
      )
      Files.setPosixFilePermissions(path, permissions.asJava)
    catch case _: UnsupportedOperationException => ()

  private def archivePath(path: Path): String =
    path.iterator.asScala.map(_.toString).mkString("/")

  private def isUnixLauncher(relative: String, name: String): Boolean =
    relative == s"bin/$name"

  private def shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
