package net.neoremind.kraps

import java.io.{Externalizable, ObjectInput, ObjectOutput}

import net.neoremind.kraps.rpc._
import net.neoremind.kraps.rpc.netty.NettyRpcEnvFactory
import net.neoremind.kraps.util.Utils
import org.apache.commons.lang3.StringUtils
import org.slf4j.Logger

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.Try

/**
  * Created by xu.zhang on 7/30/17.
  */
class SimpleRpcTest extends BaseRpcTest {

  def assertBlock: PartialFunction[Try[String], Unit] = {
    case scala.util.Success(value) => value should be("ABC")
    case scala.util.Failure(e) => log.error("got failure: " + e.getMessage)
  }

  "EchoEndpoint" should "echo message in upper case" in {
    runServerAndAwaitTermination({
      val echoEndpoint: RpcEndpoint = new EchoEndpoint(serverRpcEnv)
      serverRpcEnv.setupEndpoint(EchoEndpoint.ENDPOINT_NAME, echoEndpoint)
    })

    def runBlock(endPointRef: RpcEndpointRef) = endPointRef.ask[String](Say("abc"))

    clientCall(EchoEndpoint.ENDPOINT_NAME)(runBlock, assertBlock)
  }

  "EchoEndpoint" should "say complex message" in {
    runServerAndAwaitTermination({
      val echoEndpoint: RpcEndpoint = new EchoEndpoint(serverRpcEnv)
      serverRpcEnv.setupEndpoint(EchoEndpoint.ENDPOINT_NAME, echoEndpoint)
    })

    def assertBlock: PartialFunction[Try[ComplexMessage], Unit] = {
      case scala.util.Success(value) => value should be(ComplexMessage("hello", Int.MaxValue, Long.MinValue, true, Say("yes")))
      case scala.util.Failure(e) => log.error("got failure: " + e.getMessage)
    }

    def runBlock(endPointRef: RpcEndpointRef) = endPointRef.ask[ComplexMessage](Say("complex"))

    clientCall(EchoEndpoint.ENDPOINT_NAME)(runBlock, assertBlock)
  }

  "EchoEndpoint" should "fail when not endpoint found" in {
    runServerAndAwaitTermination({
      // no endpoint
    })

    def runBlock(endPointRef: RpcEndpointRef) = endPointRef.ask[String](Say("abc"))

    val thrown = the[RpcException] thrownBy clientCall(EchoEndpoint.ENDPOINT_NAME)(runBlock, assertBlock)
    thrown.getMessage should equal("Exception thrown in awaitResult")
    thrown.getCause.getMessage should fullyMatch regex """Cannot find endpoint: spark://my-echo@.*(\d*)"""
  }

  "EchoEndpoint" should "got exception when remote endpoint return RpcFailure" in {
    runServerAndAwaitTermination({
      val echoEndpoint: RpcEndpoint = new EchoEndpoint(serverRpcEnv)
      serverRpcEnv.setupEndpoint(EchoEndpoint.ENDPOINT_NAME, echoEndpoint)
    })

    def runBlock(endPointRef: RpcEndpointRef) = endPointRef.ask[String](Say("bad"))

    val thrown = the[SayFailureException] thrownBy clientCall(EchoEndpoint.ENDPOINT_NAME)(runBlock, assertBlock)
    thrown.getMessage should equal("Sorry, say failed")
  }

  "EchoEndpoint" should "got exception when remote endpoint return SayUnexpectedFailureException" in {
    runServerAndAwaitTermination({
      val echoEndpoint: RpcEndpoint = new EchoEndpoint(serverRpcEnv)
      serverRpcEnv.setupEndpoint(EchoEndpoint.ENDPOINT_NAME, echoEndpoint)
    })

    def runBlock(endPointRef: RpcEndpointRef) = endPointRef.ask[String](SayUnexpectedFailure("fail"))

    val thrown = the[SayUnexpectedFailureException] thrownBy clientCall(EchoEndpoint.ENDPOINT_NAME)(runBlock, assertBlock)
    thrown.getMessage should equal("Sorry, say unexpected failed")
  }

  "EchoEndpoint" should "fail due to remote port not correct" in {
    runServerAndAwaitTermination({
      val echoEndpoint: RpcEndpoint = new EchoEndpoint(serverRpcEnv)
      serverRpcEnv.setupEndpoint(EchoEndpoint.ENDPOINT_NAME, echoEndpoint)
    })

    def runBlock(endPointRef: RpcEndpointRef) = endPointRef.ask[String](Say("abc"))

    val thrown = the[RpcException] thrownBy clientCall(EchoEndpoint.ENDPOINT_NAME)(runBlock, assertBlock, port = 9999)
    thrown.getMessage should equal("Exception thrown in awaitResult")
    thrown.getCause.getMessage should fullyMatch regex """Failed to connect to localhost.*(\d*)"""
  }

  "EchoEndpoint" should "fail due to remote address not correct" in {
    val rpcConf = new RpcConf()
    // or "spark.network.timeout"
    rpcConf.set("spark.rpc.lookupTimeout", "2s")

    runServerAndAwaitTermination({
      val echoEndpoint: RpcEndpoint = new EchoEndpoint(serverRpcEnv)
      serverRpcEnv.setupEndpoint(EchoEndpoint.ENDPOINT_NAME, echoEndpoint)
    })

    def runBlock(endPointRef: RpcEndpointRef) = endPointRef.ask[String](Say("abc"))

    val thrown = the[RpcException] thrownBy clientCall(EchoEndpoint.ENDPOINT_NAME)(runBlock, assertBlock, rpcConf = rpcConf, host = "xxxmm")
    thrown.getCause.getMessage should equal("Failed to connect to xxxmm:" + _port.get())
  }

  "EchoEndpoint" should "client call timeout due to slow response" in {
    val rpcConf = new RpcConf()
    rpcConf.set("spark.rpc.askTimeout", "3s")

    runServerAndAwaitTermination({
      val echoEndpoint: RpcEndpoint = new EchoEndpoint(serverRpcEnv)
      serverRpcEnv.setupEndpoint(EchoEndpoint.ENDPOINT_NAME, echoEndpoint)
    })

    def runBlock(endPointRef: RpcEndpointRef) = endPointRef.ask[String](SayTimeout(5000, "abc"))

    val thrown = the[RpcTimeoutException] thrownBy clientCall(EchoEndpoint.ENDPOINT_NAME)(runBlock, assertBlock, rpcConf)
    thrown.getMessage should equal("Cannot receive any reply in 3 seconds. This timeout is controlled by spark.rpc.askTimeout")
  }

  "EchoEndpoint" should "client call should retry" in {
    val rpcConf = new RpcConf()
    rpcConf.set("spark.rpc.numRetries", "2")
    rpcConf.set("spark.rpc.retry.wait", "2s")

    runServerAndAwaitTermination({
      val echoEndpoint: RpcEndpoint = new EchoEndpoint(serverRpcEnv)
      serverRpcEnv.setupEndpoint(EchoEndpoint.ENDPOINT_NAME, echoEndpoint)
    })

    def runBlock(endPointRef: RpcEndpointRef) = endPointRef.askWithRetry[String](Say("bad"))

    val thrown = the[RpcException] thrownBy clientCallNonFuture(EchoEndpoint.ENDPOINT_NAME)(runBlock, assertBlock)
    thrown.getMessage should equal("Error sending message [message = Say(bad)]")
  }

  "EchoEndpoint" should "ok on all kinds of parameters" in {
    val rpcConf = new RpcConf()
    // NIO or EPOLL for low-level IO. NIO is always available,
    // while EPOLL is only available on Linux. NIO uses io.netty.channel.nio.NioEventLoopGroup while EPOLL
    rpcConf.set("spark.rpc.io.mode", "NIO")

    // NIO event loop reactor thread size
    rpcConf.set("spark.rpc.io.serverThreads", "4")
    rpcConf.set("spark.rpc.io.clientThreads", "4")

    // Number of concurrent connections between two nodes for fetching data.
    // For reusing, used on client side to build client pool, please always set to 1
    rpcConf.set("spark.rpc.io.numConnectionsPerPeer", "1")

    rpcConf.set("spark.rpc.netty.dispatcher.numThreads", "8")

    // Because TransportClientFactory.createClient is blocking, we need to run it in this thread pool
    // to implement non-blocking send/ask. Every remote address will have one client to serve, this
    // is the pool used to create client.
    rpcConf.set("spark.rpc.connect.threads", "64")

    runServerAndAwaitTermination({
      val echoEndpoint: RpcEndpoint = new EchoEndpoint(serverRpcEnv)
      serverRpcEnv.setupEndpoint(EchoEndpoint.ENDPOINT_NAME, echoEndpoint)
    }, rpcConf = rpcConf)

    def runBlock(endPointRef: RpcEndpointRef) = endPointRef.ask[String](Say("abc"))

    clientCall(EchoEndpoint.ENDPOINT_NAME)(runBlock, assertBlock, rpcConf = rpcConf)
  }

  "EchoEndpoint" should "send one way should work" in {
    runServerAndAwaitTermination({
      val echoEndpoint: RpcEndpoint = new EchoEndpoint(serverRpcEnv)
      serverRpcEnv.setupEndpoint(EchoEndpoint.ENDPOINT_NAME, echoEndpoint)
    })

    def runBlock(endPointRef: RpcEndpointRef) = endPointRef.send(Say("abc"))

    def assertBlock: PartialFunction[Try[Unit], Unit] = {
      case scala.util.Success(value) => log.info(s"$value")
      case scala.util.Failure(e) => log.error("got failure: " + e.getMessage)
    }

    clientCallNonFuture(EchoEndpoint.ENDPOINT_NAME)(runBlock, assertBlock)
    Thread.sleep(5000)
  }

  /**
    * RpcEndpointRef is get by the return object of `setupEndpoint`
    */
  "EchoEndpoint" should "run locally successfully" in {
    import scala.concurrent.ExecutionContext.Implicits.global

    val config = RpcEnvServerConfig(new RpcConf(), "hello-server", _host, _port.get())
    serverRpcEnv = NettyRpcEnvFactory.create(config)
    val echoEndpoint: RpcEndpoint = new EchoEndpoint(serverRpcEnv)
    val _endpointRef = serverRpcEnv.setupEndpoint(EchoEndpoint.ENDPOINT_NAME, echoEndpoint)
    val future: Future[String] = _endpointRef.ask[String](Say("abc"))
    Await.result(future, Duration.apply(clientCallWaitTimeInSec))

    future onComplete {
      case scala.util.Success(value) => log.info(s"$value")
      case scala.util.Failure(e) => log.error("got failure: " + e.getMessage)
    }
  }

  "EchoEndpoint" should "work on FST serializer" in {
    val _rpcConf = new RpcConf()
    _rpcConf.set("spark.rpc.serialization.stream.factory", "net.neoremind.kraps.serializer.FstSerializationStreamFactory")

    runServerAndAwaitTermination({
      val echoEndpoint: RpcEndpoint = new EchoEndpoint(serverRpcEnv)
      serverRpcEnv.setupEndpoint(EchoEndpoint.ENDPOINT_NAME, echoEndpoint)
    }, rpcConf = _rpcConf)

    def runBlock(endPointRef: RpcEndpointRef) = endPointRef.ask[String](Say("abc"))

    clientCall(EchoEndpoint.ENDPOINT_NAME)(runBlock, assertBlock, rpcConf = _rpcConf)
  }
}


class EchoEndpoint(realRpcEnv: RpcEnv)(implicit log: Logger) extends RpcEndpoint {

  override def onStart(): Unit = {
    log.info("server start echo endpoint")
  }

  override def receive: PartialFunction[Any, Unit] = {
    case Say(msg) => {
      log.info(s"server received $msg")
    }
  }

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case Say(msg) => {
      log.info(s"server received $msg")
      msg match {
        case _ if StringUtils.equalsIgnoreCase(msg, "bad") => context.sendFailure(new SayFailureException)
        case _ if StringUtils.equalsIgnoreCase(msg, "complex") => context.reply(ComplexMessage("hello", Int.MaxValue, Long.MinValue, true, Say("yes")))
        case _ => context.reply(msg.toUpperCase)
      }
      if (StringUtils.equalsIgnoreCase(msg, "bad")) {
        context.sendFailure(new SayFailureException)
      } else {
        context.reply(msg.toUpperCase)
      }
    }
    case SayUnexpectedFailure(msg) => {
      context.sendFailure(new SayUnexpectedFailureException)
    }
    case SayTimeout(sleepTimeInMs, msg) => {
      Thread.sleep(sleepTimeInMs)
      context.reply(msg.toUpperCase)
    }
  }

  override def onStop(): Unit = {
    log.info("server stop echo endpoint")
  }

  /**
    * The [[RpcEnv]] that this [[RpcEndpoint]] is registered to.
    */
  override val rpcEnv: RpcEnv = realRpcEnv
}

object EchoEndpoint {
  val ENDPOINT_NAME = "my-echo"
}

case class ComplexMessage(var fieldStr: String,
                          var fieldInt: Int,
                          var fieldLong: Long,
                          var fieldBoolean: Boolean,
                          var fieldSay: Say) extends Externalizable {

  def this() = this(null, 0, 0L, false, null) // For deserialization only

  override def writeExternal(out: ObjectOutput): Unit = Utils.tryOrIOException {
    out.writeUTF(fieldStr)
    out.writeInt(fieldInt)
    out.writeLong(fieldLong)
    out.writeBoolean(fieldBoolean)
    out.writeObject(fieldSay)
  }

  override def readExternal(in: ObjectInput): Unit = Utils.tryOrIOException {
    fieldStr = in.readUTF()
    fieldInt = in.readInt()
    fieldLong = in.readLong()
    fieldBoolean = in.readBoolean()
    fieldSay = in.readObject().asInstanceOf[Say]
  }
}

case class Say(msg: String)

case class SayTimeout(sleepTimeInMs: Int, msg: String)

case class SayUnexpectedFailure(msg: String)

class SayFailureException() extends IllegalStateException("Sorry, say failed")

class SayUnexpectedFailureException() extends IllegalStateException("Sorry, say unexpected failed")

