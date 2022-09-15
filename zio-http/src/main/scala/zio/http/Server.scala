package zio.http

import zio._
import zio.http.Server.ErrorCallback
import zio.http.netty.server._
import java.util.concurrent.atomic.AtomicReference

trait Server {
  def install[R](httpApp: HttpApp[R, Throwable], errorCallback: Option[ErrorCallback] = None): URIO[R, Unit]

  def port: Int

}

object Server {

  type ErrorCallback = Throwable => ZIO[Any, Nothing, Unit]
  def serve[R](
    httpApp: HttpApp[R, Throwable],
    errorCallback: Option[ErrorCallback] = None,
  ): URIO[R with Server, Nothing] =
    install(httpApp, errorCallback) *> ZIO.never

  def install[R](
    httpApp: HttpApp[R, Throwable],
    errorCallback: Option[ErrorCallback] = None,
  ): URIO[R with Server, Int] = {
    ZIO.serviceWithZIO[Server](_.install(httpApp, errorCallback)) *> ZIO.service[Server].map(_.port)
  }

  val default = ServerConfig.live >>> live

  val live = NettyDriver.default >>> base

  val base: ZLayer[
    Driver with AtomicReference[HttpApp[Any, Throwable]] with AtomicReference[Option[ErrorCallback]],
    Throwable,
    Server,
  ] = ZLayer.scoped {
    for {
      driver   <- ZIO.service[Driver]
      appRef   <- ZIO.service[AtomicReference[HttpApp[Any, Throwable]]]
      errorRef <- ZIO.service[AtomicReference[Option[ErrorCallback]]]
      port     <- driver.start()
    } yield ServerLive(appRef, errorRef, port)
  }

  private final case class ServerLive(
    appRef: java.util.concurrent.atomic.AtomicReference[HttpApp[Any, Throwable]],
    errorRef: java.util.concurrent.atomic.AtomicReference[Option[ErrorCallback]],
    bindPort: Int,
  ) extends Server {
    override def install[R](httpApp: HttpApp[R, Throwable], errorCallback: Option[ErrorCallback]): URIO[R, Unit] =
      ZIO.environment[R].map { env =>
        val newApp =
          if (env == ZEnvironment.empty) httpApp.asInstanceOf[HttpApp[Any, Throwable]]
          else httpApp.provideEnvironment(env)
        var loop   = true
        while (loop) {
          val oldApp = appRef.get()
          if (appRef.compareAndSet(oldApp, newApp ++ oldApp)) loop = false
        }
        ()
      } *> setErrorCallback(errorCallback)

    override def port: Int = bindPort

    private def setErrorCallback(errorCallback: Option[ErrorCallback]): UIO[Unit] = {
      ZIO
        .environment[Any]
        .as {
          var loop = true
          while (loop) {
            val oldErrorCallback = errorRef.get()
            if (errorRef.compareAndSet(oldErrorCallback, errorCallback)) loop = false
          }
          ()
        }
        .unless(errorCallback.isEmpty)
        .map(_.getOrElse(()))
    }
  }

}
