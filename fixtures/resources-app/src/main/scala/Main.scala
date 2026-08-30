import java.nio.charset.StandardCharsets

object Main:
  def resource(name: String): String =
    val stream = Option(getClass.getResourceAsStream(s"/$name")).getOrElse(
      throw IllegalArgumentException(s"Missing resource: $name")
    )
    try String(stream.readAllBytes(), StandardCharsets.UTF_8).trim
    finally stream.close()

  def main(args: Array[String]): Unit =
    println(resource("application.conf"))
