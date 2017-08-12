package net.neoremind.kraps.rpc.netty

import java.io._
import java.net.{InetSocketAddress, URI}
import java.nio.ByteBuffer
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicBoolean
import javax.annotation.Nullable

import net.neoremind.kraps.RpcConf
import net.neoremind.kraps.rpc._
import net.neoremind.kraps.serializer.{JavaSerializer, JavaSerializerInstance}
import net.neoremind.kraps.util.{ThreadUtils, Utils}
import org.apache.spark.network.TransportContext
import org.apache.spark.network.client._
import org.apache.spark.network.server._
import org.slf4j.LoggerFactory

import scala.concurrent.{Future, Promise}
import scala.reflect.ClassTag
import scala.util.control.NonFatal
import scala.util.{DynamicVariable, Failure, Success}

class NettyRpcEnv(
                   val conf: RpcConf,
                   javaSerializerInstance: JavaSerializerInstance,
                   host: String) extends RpcEnv(conf) {

  private val log = LoggerFactory.getLogger(classOf[NettyRpcEnv])

  private[netty] val transportConf = KrapsTransportConf.fromSparkConf(
    conf.set("spark.rpc.io.numConnectionsPerPeer", "1"),
    "rpc",
    conf.getInt("spark.rpc.io.threads", 0))

  private val dispatcher: Dispatcher = new Dispatcher(this)

  // omit for signature
  private val streamManager = new StreamManager {
    override def getChunk(streamId: Long, chunkIndex: Int) = null
  }

  private val transportContext = new TransportContext(transportConf,
    new NettyRpcHandler(dispatcher, this, streamManager))

  private def createClientBootstraps(): java.util.List[TransportClientBootstrap] =
    java.util.Collections.emptyList[TransportClientBootstrap]

  private val clientFactory = transportContext.createClientFactory(createClientBootstraps())

  val timeoutScheduler = ThreadUtils.newDaemonSingleThreadScheduledExecutor("netty-rpc-env-timeout")

  // Because TransportClientFactory.createClient is blocking, we need to run it in this thread pool
  // to implement non-blocking send/ask.
  // TODO: a non-blocking TransportClientFactory.createClient in future
  private[netty] val clientConnectionExecutor = ThreadUtils.newDaemonCachedThreadPool(
    "netty-rpc-connection",
    conf.getInt("spark.rpc.connect.threads", 64))

  @volatile private var server: TransportServer = _

  private val stopped = new AtomicBoolean(false)

  /**
    * A map for [[RpcAddress]] and [[Outbox]]. When we are connecting to a remote [[RpcAddress]],
    * we just put messages to its [[Outbox]] to implement a non-blocking `send` method.
    */
  private val outboxes = new ConcurrentHashMap[RpcAddress, Outbox]()

  /**
    * Remove the address's Outbox and stop it.
    */
  private[netty] def removeOutbox(address: RpcAddress): Unit = {
    val outbox = outboxes.remove(address)
    if (outbox != null) {
      outbox.stop()
    }
  }

  def startServer(bindAddress: String, port: Int): Unit = {
    // here disable security
    val bootstraps: java.util.List[TransportServerBootstrap] = java.util.Collections.emptyList()
    server = transportContext.createServer(bindAddress, port, bootstraps)
    dispatcher.registerRpcEndpoint(
      RpcEndpointVerifier.NAME, new RpcEndpointVerifier(this, dispatcher))
  }

  @Nullable
  override lazy val address: RpcAddress = {
    if (server != null) RpcAddress(host, server.getPort()) else null
  }

  override def setupEndpoint(name: String, endpoint: RpcEndpoint): RpcEndpointRef = {
    dispatcher.registerRpcEndpoint(name, endpoint)
  }

  def asyncSetupEndpointRefByURI(uri: String): Future[RpcEndpointRef] = {
    val addr = RpcEndpointAddress(uri)
    val endpointRef = new NettyRpcEndpointRef(conf, addr, this)
    val verifier = new NettyRpcEndpointRef(
      conf, RpcEndpointAddress(addr.rpcAddress, RpcEndpointVerifier.NAME), this)
    verifier.ask[Boolean](RpcEndpointVerifier.createCheckExistence(endpointRef.name)).flatMap { find =>
      if (find) {
        Future.successful(endpointRef)
      } else {
        Future.failed(new RpcEndpointNotFoundException(uri))
      }
    }(ThreadUtils.sameThread)
  }

  override def stop(endpointRef: RpcEndpointRef): Unit = {
    require(endpointRef.isInstanceOf[NettyRpcEndpointRef])
    dispatcher.stop(endpointRef)
  }

  private def postToOutbox(receiver: NettyRpcEndpointRef, message: OutboxMessage): Unit = {
    if (receiver.client != null) {
      message.sendWith(receiver.client)
    } else {
      require(receiver.address != null,
        "Cannot send message to client endpoint with no listen address.")
      val targetOutbox = {
        val outbox = outboxes.get(receiver.address)
        if (outbox == null) {
          val newOutbox = new Outbox(this, receiver.address)
          val oldOutbox = outboxes.putIfAbsent(receiver.address, newOutbox)
          if (oldOutbox == null) {
            newOutbox
          } else {
            oldOutbox
          }
        } else {
          outbox
        }
      }
      if (stopped.get) {
        // It's possible that we put `targetOutbox` after stopping. So we need to clean it.
        outboxes.remove(receiver.address)
        targetOutbox.stop()
      } else {
        targetOutbox.send(message)
      }
    }
  }

  private[netty] def send(message: RequestMessage): Unit = {
    val remoteAddr = message.receiver.address
    if (remoteAddr == address) {
      // Message to a local RPC endpoint.
      try {
        dispatcher.postOneWayMessage(message)
      } catch {
        case e: RpcEnvStoppedException => log.warn(e.getMessage)
      }
    } else {
      // Message to a remote RPC endpoint.
      postToOutbox(message.receiver, OneWayOutboxMessage(serialize(message)))
    }
  }

  private[netty] def createClient(address: RpcAddress): TransportClient = {
    clientFactory.createClient(address.host, address.port)
  }

  private[netty] def ask[T: ClassTag](message: RequestMessage, timeout: RpcTimeout): Future[T] = {
    val promise = Promise[Any]()
    val remoteAddr = message.receiver.address

    def onFailure(e: Throwable): Unit = {
      if (!promise.tryFailure(e)) {
        log.warn(s"Ignored failure: $e")
      }
    }

    def onSuccess(reply: Any): Unit = reply match {
      case RpcFailure(e) => onFailure(e)
      case rpcReply =>
        if (!promise.trySuccess(rpcReply)) {
          log.warn(s"Ignored message: $reply")
        }
    }

    try {
      if (remoteAddr == address) {
        val p = Promise[Any]()
        p.future.onComplete {
          case Success(response) => onSuccess(response)
          case Failure(e) => onFailure(e)
        }(ThreadUtils.sameThread)
        dispatcher.postLocalMessage(message, p)
      } else {
        val rpcMessage = RpcOutboxMessage(serialize(message),
          onFailure,
          (client, response) => onSuccess(deserialize[Any](client, response)))
        postToOutbox(message.receiver, rpcMessage)
        promise.future.onFailure {
          case _: TimeoutException => rpcMessage.onTimeout()
          case _ =>
        }(ThreadUtils.sameThread)
      }

      val timeoutCancelable = timeoutScheduler.schedule(new Runnable {
        override def run(): Unit = {
          onFailure(new TimeoutException(s"Cannot receive any reply in ${timeout.duration}"))
        }
      }, timeout.duration.toNanos, TimeUnit.NANOSECONDS)
      promise.future.onComplete { v =>
        timeoutCancelable.cancel(true)
      }(ThreadUtils.sameThread)
    } catch {
      case NonFatal(e) =>
        onFailure(e)
    }
    promise.future.mapTo[T].recover(timeout.addMessageIfTimeout)(ThreadUtils.sameThread)
  }

  private[netty] def serialize(content: Any): ByteBuffer = {
    javaSerializerInstance.serialize(content)
  }

  private[netty] def deserialize[T: ClassTag](client: TransportClient, bytes: ByteBuffer): T = {
    NettyRpcEnv.currentClient.withValue(client) {
      deserialize { () =>
        javaSerializerInstance.deserialize[T](bytes)
      }
    }
  }

  override def endpointRef(endpoint: RpcEndpoint): RpcEndpointRef = {
    dispatcher.getRpcEndpointRef(endpoint)
  }

  override def shutdown(): Unit = {
    cleanup()
  }

  override def awaitTermination(): Unit = {
    dispatcher.awaitTermination()
  }

  private def cleanup(): Unit = {
    if (!stopped.compareAndSet(false, true)) {
      return
    }

    val iter = outboxes.values().iterator()
    while (iter.hasNext()) {
      val outbox = iter.next()
      outboxes.remove(outbox.address)
      outbox.stop()
    }
    if (timeoutScheduler != null) {
      timeoutScheduler.shutdownNow()
    }
    if (dispatcher != null) {
      dispatcher.stop()
    }
    if (server != null) {
      server.close()
    }
    if (clientFactory != null) {
      clientFactory.close()
    }
    if (clientConnectionExecutor != null) {
      clientConnectionExecutor.shutdownNow()
    }
  }

  override def deserialize[T](deserializationAction: () => T): T = {
    NettyRpcEnv.currentEnv.withValue(this) {
      deserializationAction()
    }
  }

}

private[netty] object NettyRpcEnv {
  /**
    * When deserializing the [[NettyRpcEndpointRef]], it needs a reference to [[NettyRpcEnv]].
    * Use `currentEnv` to wrap the deserialization codes. E.g.,
    *
    * {{{
    *   NettyRpcEnv.currentEnv.withValue(this) {
    *     your deserialization codes
    *   }
    * }}}
    */
  private[netty] val currentEnv = new DynamicVariable[NettyRpcEnv](null)

  /**
    * Similar to `currentEnv`, this variable references the client instance associated with an
    * RPC, in case it's needed to find out the remote address during deserialization.
    */
  private[netty] val currentClient = new DynamicVariable[TransportClient](null)

}

object NettyRpcEnvFactory extends RpcEnvFactory {

  def create(config: RpcEnvConfig): RpcEnv = {
    val conf = config.conf

    // Use JavaSerializerInstance in multiple threads is safe. However, if we plan to support
    // KryoSerializer in future, we have to use ThreadLocal to store SerializerInstance
    val javaSerializerInstance =
    new JavaSerializer(conf).newInstance().asInstanceOf[JavaSerializerInstance]
    val nettyEnv =
      new NettyRpcEnv(conf, javaSerializerInstance, config.bindAddress)
    if (!config.clientMode) {
      val startNettyRpcEnv: Int => (NettyRpcEnv, Int) = { actualPort =>
        nettyEnv.startServer(config.bindAddress, actualPort)
        (nettyEnv, nettyEnv.address.port)
      }
      try {
        Utils.startServiceOnPort(config.port, startNettyRpcEnv, conf, config.name)._1
      } catch {
        case NonFatal(e) =>
          nettyEnv.shutdown()
          throw e
      }
    }
    nettyEnv
  }
}

/**
  * The NettyRpcEnv version of RpcEndpointRef.
  *
  * This class behaves differently depending on where it's created. On the node that "owns" the
  * RpcEndpoint, it's a simple wrapper around the RpcEndpointAddress instance.
  *
  * On other machines that receive a serialized version of the reference, the behavior changes. The
  * instance will keep track of the TransportClient that sent the reference, so that messages
  * to the endpoint are sent over the client connection, instead of needing a new connection to
  * be opened.
  *
  * The RpcAddress of this ref can be null; what that means is that the ref can only be used through
  * a client connection, since the process hosting the endpoint is not listening for incoming
  * connections. These refs should not be shared with 3rd parties, since they will not be able to
  * send messages to the endpoint.
  *
  * @param conf            configuration.
  * @param endpointAddress The address where the endpoint is listening.
  * @param nettyEnv        The RpcEnv associated with this ref.
  */
private[netty] class NettyRpcEndpointRef(
                                          @transient private val conf: RpcConf,
                                          endpointAddress: RpcEndpointAddress,
                                          @transient @volatile private var nettyEnv: NettyRpcEnv)
  extends RpcEndpointRef(conf) with Serializable {

  @transient
  @volatile var client: TransportClient = _

  private val _address = if (endpointAddress.rpcAddress != null) endpointAddress else null
  private val _name = endpointAddress.name

  override def address: RpcAddress = if (_address != null) _address.rpcAddress else null

  private def readObject(in: ObjectInputStream): Unit = {
    in.defaultReadObject()
    nettyEnv = NettyRpcEnv.currentEnv.value
    client = NettyRpcEnv.currentClient.value
  }

  private def writeObject(out: ObjectOutputStream): Unit = {
    out.defaultWriteObject()
  }

  override def name: String = _name

  override def ask[T: ClassTag](message: Any, timeout: RpcTimeout): Future[T] = {
    nettyEnv.ask(RequestMessage(nettyEnv.address, this, message), timeout)
  }

  override def send(message: Any): Unit = {
    require(message != null, "Message is null")
    nettyEnv.send(RequestMessage(nettyEnv.address, this, message))
  }

  override def toString: String = s"NettyRpcEndpointRef(${_address})"

  def toURI: URI = new URI(_address.toString)

  final override def equals(that: Any): Boolean = that match {
    case other: NettyRpcEndpointRef => _address == other._address
    case _ => false
  }

  final override def hashCode(): Int = if (_address == null) 0 else _address.hashCode()
}

/**
  * The message that is sent from the sender to the receiver.
  */
private[netty] case class RequestMessage(
                                          senderAddress: RpcAddress, receiver: NettyRpcEndpointRef, content: Any)

/**
  * A response that indicates some failure happens in the receiver side.
  */
private[netty] case class RpcFailure(e: Throwable)

/**
  * Dispatches incoming RPCs to registered endpoints.
  *
  * The handler keeps track of all client instances that communicate with it, so that the RpcEnv
  * knows which `TransportClient` instance to use when sending RPCs to a client endpoint (i.e.,
  * one that is not listening for incoming connections, but rather needs to be contacted via the
  * client socket).
  *
  * Events are sent on a per-connection basis, so if a client opens multiple connections to the
  * RpcEnv, multiple connection / disconnection events will be created for that client (albeit
  * with different `RpcAddress` information).
  */
private[netty] class NettyRpcHandler(
                                      dispatcher: Dispatcher,
                                      nettyEnv: NettyRpcEnv,
                                      streamManager: StreamManager) extends RpcHandler {

  private val log = LoggerFactory.getLogger(classOf[NettyRpcHandler])

  // A variable to track the remote RpcEnv addresses of all clients
  private val remoteAddresses = new ConcurrentHashMap[RpcAddress, RpcAddress]()

  override def receive(
                        client: TransportClient,
                        message: ByteBuffer,
                        callback: RpcResponseCallback): Unit = {
    val messageToDispatch = internalReceive(client, message)
    dispatcher.postRemoteMessage(messageToDispatch, callback)
  }

  override def receive(
                        client: TransportClient,
                        message: ByteBuffer): Unit = {
    val messageToDispatch = internalReceive(client, message)
    dispatcher.postOneWayMessage(messageToDispatch)
  }

  private def internalReceive(client: TransportClient, message: ByteBuffer): RequestMessage = {
    val addr = client.getChannel().remoteAddress().asInstanceOf[InetSocketAddress]
    assert(addr != null)
    val clientAddr = RpcAddress(addr.getHostString, addr.getPort)
    val requestMessage = nettyEnv.deserialize[RequestMessage](client, message)
    if (requestMessage.senderAddress == null) {
      // Create a new message with the socket address of the client as the sender.
      RequestMessage(clientAddr, requestMessage.receiver, requestMessage.content)
    } else {
      // The remote RpcEnv listens to some port, we should also fire a RemoteProcessConnected for
      // the listening address
      val remoteEnvAddress = requestMessage.senderAddress
      if (remoteAddresses.putIfAbsent(clientAddr, remoteEnvAddress) == null) {
        dispatcher.postToAll(RemoteProcessConnected(remoteEnvAddress))
      }
      requestMessage
    }
  }

  override def getStreamManager: StreamManager = streamManager

  override def exceptionCaught(cause: Throwable, client: TransportClient): Unit = {
    val addr = client.getChannel.remoteAddress().asInstanceOf[InetSocketAddress]
    if (addr != null) {
      val clientAddr = RpcAddress(addr.getHostString, addr.getPort)
      dispatcher.postToAll(RemoteProcessConnectionError(cause, clientAddr))
      // If the remove RpcEnv listens to some address, we should also fire a
      // RemoteProcessConnectionError for the remote RpcEnv listening address
      val remoteEnvAddress = remoteAddresses.get(clientAddr)
      if (remoteEnvAddress != null) {
        dispatcher.postToAll(RemoteProcessConnectionError(cause, remoteEnvAddress))
      }
    } else {
      // If the channel is closed before connecting, its remoteAddress will be null.
      // See java.net.Socket.getRemoteSocketAddress
      // Because we cannot get a RpcAddress, just log it
      log.error("Exception before connecting to the client", cause)
    }
  }

  override def channelActive(client: TransportClient): Unit = {
    val addr = client.getChannel().remoteAddress().asInstanceOf[InetSocketAddress]
    assert(addr != null)
    val clientAddr = RpcAddress(addr.getHostString, addr.getPort)
    dispatcher.postToAll(RemoteProcessConnected(clientAddr))
  }

  override def channelInactive(client: TransportClient): Unit = {
    val addr = client.getChannel.remoteAddress().asInstanceOf[InetSocketAddress]
    if (addr != null) {
      val clientAddr = RpcAddress(addr.getHostString, addr.getPort)
      nettyEnv.removeOutbox(clientAddr)
      dispatcher.postToAll(RemoteProcessDisconnected(clientAddr))
      val remoteEnvAddress = remoteAddresses.remove(clientAddr)
      // If the remove RpcEnv listens to some address, we should also  fire a
      // RemoteProcessDisconnected for the remote RpcEnv listening address
      if (remoteEnvAddress != null) {
        dispatcher.postToAll(RemoteProcessDisconnected(remoteEnvAddress))
      }
    } else {
      // If the channel is closed before connecting, its remoteAddress will be null. In this case,
      // we can ignore it since we don't fire "Associated".
      // See java.net.Socket.getRemoteSocketAddress
    }
  }
}
