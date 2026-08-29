package sprout.bsp

import cats.effect.IO
import ch.epfl.scala.bsp4j.BuildClient
import java.nio.file.Path
import org.eclipse.lsp4j.jsonrpc.Launcher

object BspServer:
  def run(root: Path, version: String): IO[Unit] = IO.blocking {
    val server = SproutBuildServer(root, version)
    val launcher = Launcher
      .Builder[BuildClient]()
      .setLocalService(server)
      .setRemoteInterface(classOf[BuildClient])
      .setInput(System.in)
      .setOutput(System.out)
      .create()
    server.connect(launcher.getRemoteProxy)
    launcher.startListening().get()
  }
