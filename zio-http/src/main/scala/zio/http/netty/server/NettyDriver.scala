package zio.http.netty.server

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel._
import io.netty.util.ResourceLeakDetector
import zio._
import zio.http.netty._
import zio.http.service.ServerTime
import zio.http.{Driver, Http, HttpApp, Server, ServerConfig}

import java.net.InetSocketAddress

private[zio] final case class NettyDriver(
  appRef: AppRef,
  channelFactory: ChannelFactory[ServerChannel],
  channelInitializer: ChannelInitializer[Channel],
  eventLoopGroup: EventLoopGroup,
  errorCallbackRef: ErrorCallbackRef,
  serverConfig: ServerConfig,
) extends Driver { self =>

  def start: RIO[Scope, Int] =
    for {
      serverBootstrap <- ZIO.attempt(new ServerBootstrap().channelFactory(channelFactory).group(eventLoopGroup))
      chf             <- ZIO.attempt(serverBootstrap.childHandler(channelInitializer).bind(serverConfig.address))
      _               <- NettyFutureExecutor.scoped(chf)
      _    <- ZIO.succeed(ResourceLeakDetector.setLevel(serverConfig.leakDetectionLevel.jResourceLeakDetectionLevel))
      port <- ZIO.attempt(chf.channel().localAddress().asInstanceOf[InetSocketAddress].getPort)
    } yield port

  def setErrorCallback(newCallback: Option[Server.ErrorCallback]): UIO[Unit] = ZIO.succeed {
    var loop = true
    while (loop) {
      val oldCallback = errorCallbackRef.get()
      if (errorCallbackRef.compareAndSet(oldCallback, newCallback)) loop = false
    }
  }

  def addApp(newApp: HttpApp[Any, Throwable]): UIO[Unit] = ZIO.succeed {
    var loop = true
    while (loop) {
      val oldApp = appRef.get()
      if (appRef.compareAndSet(oldApp, newApp ++ oldApp)) loop = false
    }
  }

}

object NettyDriver {

  private type Env = AppRef
    with ChannelFactory[ServerChannel]
    with ChannelInitializer[Channel]
    with EventLoopGroup
    with ErrorCallbackRef
    with ServerConfig

  val make: ZIO[Env, Nothing, Driver] =
    for {
      app   <- ZIO.service[AppRef]
      cf    <- ZIO.service[ChannelFactory[ServerChannel]]
      cInit <- ZIO.service[ChannelInitializer[Channel]]
      elg   <- ZIO.service[EventLoopGroup]
      ecb   <- ZIO.service[ErrorCallbackRef]
      sc    <- ZIO.service[ServerConfig]
    } yield new NettyDriver(
      appRef = app,
      channelFactory = cf,
      channelInitializer = cInit,
      eventLoopGroup = elg,
      errorCallbackRef = ecb,
      serverConfig = sc,
    )

  val default: ZLayer[ServerConfig, Throwable, Driver] = ZLayer.scoped {
    val app  = ZLayer.succeed(new AtomicReference[HttpApp[Any, Throwable]](Http.empty))
    val ecb  = ZLayer.succeed(new AtomicReference[Option[Server.ErrorCallback]](Option.empty))
    val time = ZLayer.succeed(ServerTime.make(1000 millis))

    make
      .provideSome[ServerConfig & Scope](
        app,
        ecb,
        ChannelFactories.Server.fromConfig,
        EventLoopGroups.fromConfig,
        NettyRuntime.usingSharedThreadPool,
        ServerChannelInitializer.layer,
        ServerInboundHandler.layer,
        time,
      )

  }

}
