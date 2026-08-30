package sprout.bsp

import cats.effect.{IO, Resource}
import ch.epfl.scala.bsp4j.BuildClient
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.logging.{Level, Logger}
import org.eclipse.lsp4j.jsonrpc.Launcher

object BspServer:
  def run(root: Path, version: String): IO[Unit] =
    Resource
      .make(IO(Executors.newCachedThreadPool()))(executor =>
        IO(executor.shutdownNow()).map(_ => ())
      )
      .use { executor =>
        IO.blocking {
          Logger
            .getLogger("org.eclipse.lsp4j.jsonrpc.json.StreamMessageProducer")
            .setLevel(Level.OFF)
          val server = SproutBuildServer(root, version, closeInput)
          val launcher = Launcher
            .Builder[BuildClient]()
            .setLocalService(server)
            .setRemoteInterface(classOf[BuildClient])
            .setInput(System.in)
            .setOutput(System.out)
            .setExecutorService(executor)
            .create()
          server.connect(launcher.getRemoteProxy)
          launcher.startListening().get()
        }
      }

  private def closeInput(): Unit =
    try System.in.close()
    catch case _: IOException => ()
