package sprout.bsp

import cats.effect.{IO, Resource}
import ch.epfl.scala.bsp4j.BuildClient
import java.nio.file.Path
import java.util.concurrent.{CompletableFuture, Executors}
import java.util.logging.{Level, Logger}
import org.eclipse.lsp4j.jsonrpc.Launcher

object BspServer:
  def run(root: Path, version: String): IO[Unit] =
    Resource
      .make(IO(Executors.newCachedThreadPool()))(executor =>
        IO(executor.shutdownNow()).map(_ => ())
      )
      .use { executor =>
        for
          exitSignal <- IO(CompletableFuture[Void]())
          server = SproutBuildServer(
            root,
            version,
            () => {
              exitSignal.complete(null)
              ()
            }
          )
          launcher <- IO {
            Logger
              .getLogger("org.eclipse.lsp4j.jsonrpc.json.StreamMessageProducer")
              .setLevel(Level.OFF)
            val value = Launcher
              .Builder[BuildClient]()
              .setLocalService(server)
              .setRemoteInterface(classOf[BuildClient])
              .setInput(System.in)
              .setOutput(System.out)
              .setExecutorService(executor)
              .create()
            server.connect(value.getRemoteProxy)
            value
          }
          listening <- IO(launcher.startListening())
          outcome <- IO
            .interruptibleMany(listening.get())
            .race(IO.fromCompletableFuture(IO(exitSignal)))
          _ <- outcome match
            case Right(_) => IO.blocking(System.in.close())
            case Left(_)  => IO.unit
        yield ()
      }
