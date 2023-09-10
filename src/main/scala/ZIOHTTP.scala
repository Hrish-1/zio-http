import zio._
import zio.http._
import zio.http.Header
import zio.http.Header.Custom
import zio.http.ChannelEvent._

case class WSChannel(clientId: String, channel: WebSocketChannel)

object ZIOHTTP extends ZIOAppDefault {

  val port = 8080

  val app: Http[Any, Nothing, Request, Response] = Http.collect[Request] {
    case Method.GET -> Root / "owls" => Response.text("Hello owls")
  }

  val zApp: Http[Any, Throwable, Request, Response] = Http.collectZIO[Request] {
    case Method.POST -> Root / "owls" =>
      for {
        _ <- Console.printLine("POST /owls")
        r <- Random
          .nextIntBetween(2, 5)
          .map(n => Response.text("Hello " * n + ", owls!"))
      } yield r
  }

  val socket = (id: String, roomName: String) =>
    Handler.webSocket { channel =>
      channel.receiveAll {
        case Read(WebSocketFrame.Text(text)) =>
          for {
            _ <- Console.printLine(s"text received $text")
            channels <- ZIO
              .serviceWithZIO[Ref[Map[String, Set[WSChannel]]]] { ref =>
                ref.get.map(m => m.getOrElse(roomName, Set()))
              }
            _ <- Console.printLine(s"channels $channels, ${channels.size}")
            _ <- ZIO.foreach(channels)(c =>
              c.channel.send(Read(WebSocketFrame.text(text)))
            )
            _ <- Console.printLine("end")
          } yield ()

        // Send a "greeting" message to the server once the connection is established
        case UserEventTriggered(UserEvent.HandshakeComplete) =>
          for {
            _ <- ZIO
              .serviceWithZIO[Ref[Map[String, Set[WSChannel]]]] { ref =>
                for {
                  _ <-
                    ref.modify(map => {
                      val ch = WSChannel(id, channel)
                      map.get(roomName) match {
                        case Some(value) =>
                          ((), map.updated(roomName, Set(ch) ++ value))
                        case None =>
                          ((), map.updated(roomName, Set(ch)))
                      }
                    })
                  m <- ref.get
                  _ <- Console.printLine(s"map contains $m")
                } yield ()
              }
            _ <- channel.send(Read(WebSocketFrame.text("Greetings!")))
          } yield ()

        // Log when the channel is getting closed
        case Read(WebSocketFrame.Close(status, reason)) =>
          for {
            _ <- Console.printLine(
              "Closing channel with status: " + status + " and reason: " + reason
            )
            // _ <- ZIO.serviceWithZIO[Ref[Map[String, Set[WSChannel]]]] {
            //   ref =>
            //     ref.modify(map => {
            //       map.get(id) match {
            //         case Some(value) =>
            //           ((), map.updated(id, value.filterNot(_.equals(channel))))
            //         case _ => ((), map)
            //       }
            //     })
            //
            // }
          } yield ()

        // Print the exception if it's not a normal close
        case ExceptionCaught(cause) =>
          Console.printLine(s"Channel error!: ${cause.getMessage}")

        case _ =>
          ZIO.unit
      }
    }

  val wsApp: Http[Ref[
    Map[String, Set[WSChannel]]
  ], Throwable, Request, Response] =
    Http.collectZIO[Request] {
      case Method.GET -> Root / "subscriptions" / id / roomName =>
        socket(id, roomName).toResponse
      case Method.GET -> Root / "ws" / id / roomName / "close" => 
        ZIO.serviceWithZIO[Ref[Map[String, Set[WSChannel]]]] { ref =>
          ref.modify(map => {
            map.get(roomName) match {
              case Some(value) =>
                val newChannels = value.filterNot(_.clientId.equals(id))
                (Response.text(newChannels.map(_.clientId).toString()), map.updated(roomName, newChannels))
              case None => (Response.text(""), map)
            }
          })
        }
    }

  val combinedApp = app ++ zApp ++ wsApp

  val httpLogging = combinedApp @@ HttpAppMiddleware.debug

  val httpProgram = for {
    _ <- Console.printLine(s"Starting server at http://localhost:$port")
    _ <- Server
      .serve(httpLogging.withDefaultErrorResponse)
      .provide(
        Server.defaultWithPort(port),
        ZLayer.fromZIO(Ref.make(Map.empty[String, Set[WSChannel]]))
      )
  } yield ()

  override def run = httpProgram

}

object Verbose {
  def log[R, E >: Throwable](
      http: Http[R, E, Request, Response]
  ): Http[R, E, Request, Response] =
    http
      .contramapZIO[Request](r => {
        for {
          _ <- Console.printLine(s"> ${r.method} ${r.path} ${r.version}").orDie
          _ <- ZIO.foreach(r.headers.toList) { h =>
            Console.printLine(s"> ${h.headerName}: ${h.renderedValue}").orDie
          }
        } yield r
      })
      .mapZIO[R, E, Response](res => {
        for {
          _ <- Console.printLine(s"< ${res.status}")
          _ <- ZIO.foreach(res.headers.toList) { h =>
            Console.printLine(s"< ${h.headerName}: ${h.renderedValue}")
          }
        } yield res
      })
  def customResponseHeader[R, E](
      http: Http[R, E, Request, Response]
  ): Http[R, E, Request, Response] = {
    http.mapZIO[R, E, Response] { res =>
      ZIO.succeed(res.addHeader(Custom("X-Custom-header", "my-value")))
    }
  }
}
