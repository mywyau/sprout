package sprout.core

import cats.effect.IO
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

object Hashing:
  def compilationKey(request: CompilationRequest): IO[CacheKey] = IO.blocking {
    val digest = MessageDigest.getInstance("SHA-256")
    def add(value: String): Unit = digest.update(value.getBytes(StandardCharsets.UTF_8))
    request.sources.sortBy(_.toString).foreach { path =>
      add(path.toString); digest.update(Files.readAllBytes(path))
    }
    add(request.scalaVersion.value)
    request.compilerOptions.foreach(add)
    request.classpath.sortBy(_.toString).foreach { path =>
      add(path.toString); add(Files.size(path).toString)
    }
    CacheKey(digest.digest().map("%02x".format(_)).mkString)
  }
