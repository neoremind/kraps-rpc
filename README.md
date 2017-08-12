# kraps-rpc
[![Build Status](https://travis-ci.org/neoremind/kraps-rpc.svg?branch=master)](https://travis-ci.org/neoremind/kraps-rpc)
[![Coverage Status](https://coveralls.io/repos/github/neoremind/kraps-rpc/badge.svg?branch=master)](https://coveralls.io/github/neoremind/kraps-rpc?branch=master)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.neoremind/kraps-rpc/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.neoremind/kraps-rpc)
[![Hex.pm](https://img.shields.io/hexpm/l/plug.svg)](http://www.apache.org/licenses/LICENSE-2.0)


Kraps-rpc is a RPC framework split from [Spark](https://github.com/apache/spark), you can regard it as `spark-rpc` with the word *spark* reversed. 

This module is mainly for study how RPC works in Spark, as people know that Spark consists many distributed components, such as driver, master, executor, block manager, etc, and they communicate with each other through RPC. In Spark project the functionality is sealed in `Spark-core` module. 

## 1 How to run  

The following examples can be found in [kraps-rpc-example]()

### 1.1 Create an endpoint

Creating an endpoint which contains the business logic you would like to provide as a RPC service. Below shows a simple example of a hello world echo service.

```
class HelloEndpoint(override val rpcEnv: RpcEnv) extends ThreadSafeRpcEndpoint {

  override def onStart(): Unit = {
    println("start hello endpoint")
  }

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case SayHi(msg) => {
      println(s"receive $msg")
      context.reply(s"hi, $msg")
    }
    case SayBye(msg) => {
      println(s"receive $msg")
      context.reply(s"bye, $msg")
    }
  }

  override def onStop(): Unit = {
    println("stop hello endpoint")
  }
}


case class SayHi(msg: String)

case class SayBye(msg: String)

```

### 1.2 Run server

There are a couple of steps to create a RPC server which provide `HelloEndpoint` service.

1. Create `RpcEnvServerConfig`, `RpcConf` is where you can specify some parameters for the server, will be discussed later, `hello-server` is just a simple name, no real use later. Host and port must be speicified.
2. Create `RpcEnv` which launches the server via TCP at localhost on port 52345.
3. Create `HelloEndpoint` and setup it with an identifier of `hello-service`, the name is for client to route into the correct service.
4. `awaitTermination` will block the thread and make server run with exit JVM.

```
object HelloworldServer {

  def main(args: Array[String]): Unit = {
    val config = RpcEnvServerConfig(new RpcConf(), "hello-server", "localhost", 52345)
    val rpcEnv: RpcEnv = NettyRpcEnvFactory.create(config)
    val helloEndpoint: RpcEndpoint = new HelloEndpoint(rpcEnv)
    rpcEnv.setupEndpoint("hello-service", helloEndpoint)
    rpcEnv.awaitTermination()
  }
}
```

### 1.3 Create client

#### 1.3.1 Async invocation

Creating `RpcEnv` is the same as above, and here use `setupEndpointRef` to create a stub to call remote server at localhost on port 52345 and route to `hello-service`.

`Future` is used here for async invocation.

```
object HelloworldClient {

  def main(args: Array[String]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val rpcConf = new RpcConf()
    val config = RpcEnvClientConfig(rpcConf, "hello-client")
    val rpcEnv: RpcEnv = NettyRpcEnvFactory.create(config)
    val endPointRef: RpcEndpointRef = rpcEnv.setupEndpointRef(RpcAddress("localhost", 52345), "hell-service")
    val future: Future[String] = endPointRef.ask[String](SayHi("neo"))
    future.onComplete {
      case scala.util.Success(value) => println(s"Got the result = $value")
      case scala.util.Failure(e) => println(s"Got error: $e")
    }
    Await.result(future, Duration.apply("30s"))
  }
}
```

#### 1.3.2 Sync invocation

Creating `RpcEnv` is the same as above, and here use `setupEndpointRef` to create a stub to call remote server at localhost on port 52345 and route to `hello-service`.

Use `askWithRetry` instead of `ask` to call in sync way. 

*Note that in lastest Spark version the method signature has changed to `askSync`.*

```
object HelloworldClient {

  def main(args: Array[String]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val rpcConf = new RpcConf()
    val rpcConf = new RpcConf()
    val config = RpcEnvClientConfig(rpcConf, "hello-client")
    val rpcEnv: RpcEnv = NettyRpcEnvFactory.create(config)
    val endPointRef: RpcEndpointRef = rpcEnv.setupEndpointRef(RpcAddress("localhost", 52345), "hello-service")
    val result = endPointRef.askWithRetry[String](SayBye("neo"))
    println(result)
  }
}
```

## 2. About RpcConf

`RpcConf` is simply `SparkConf` in Spark, there are a couple of parameters to specify. The are listed below, for most of them can reference to [Spark Configuration](http://spark.apache.org/docs/2.1.0/configuration.html).

```
val rpcConf = new RpcConf()

// Timeout to use for RPC remote endpoint lookup, whenever a call is made the client will always ask the server whether specific endpoint exists or not, this is for the asking timeout, default is 120s
rpcConf.set("spark.rpc.lookupTimeout", "2s") 

// Timeout to use for RPC ask operations, default is 120s
rpcConf.set("spark.rpc.askTimeout", "3s")

// Number of times to retry connecting, default is 3
rpcConf.set("spark.rpc.numRetries", "2")

// Number of milliseconds to wait on each retry, default is 3s
rpcConf.set("spark.rpc.retry.wait", "2s")

// Number of concurrent connections between two nodes for fetching data.
// For reusing, used on client side to build client pool, please always set to 1
rpcConf.set("spark.rpc.io.numConnectionsPerPeer", "1")

// Actor Inbox dispatcher thread pool size, default is 8
rpcConf.set("spark.rpc.netty.dispatcher.numThreads", "8")

// Because TransportClientFactory.createClient is blocking, we need to run it in this thread pool, to implement non-blocking send/ask. Every remote address will have one client to serve, this is the pool used to create client.
rpcConf.set("spark.rpc.connect.threads", "64")

// By default kraps-rpc and spark rpc use java native serialization, but its performance is not good, here kraps-rpc provide an alternative to use FST serialization which is 99% compatible with java but provide a better performance in both time consuming and byte size.
rpcConf.set("spark.rpc.serialization.stream.factory", "com.neoremind.kraps.serializer.FstSerializationStreamFactory")
```

### 3. More examples

Please visit [Test cases]() 

### 4. Ackownledgement

The development of Kraps-rpc is inspired by Spark. Kraps-rpc with Apache2.0 Open Source License retains all copyright, trademark, author’s information from Spark.



