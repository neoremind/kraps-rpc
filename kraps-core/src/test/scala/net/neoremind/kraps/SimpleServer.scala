/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.neoremind.kraps


import net.neoremind.kraps.rpc.netty.NettyRpcEnvFactory
import net.neoremind.kraps.rpc._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

/**
  * Created by xu.zhang on 7/23/17.
  */
object RpcServerTest {

  def main(args: Array[String]): Unit = {
    val config = RpcEnvServerConfig(new RpcConf(), "hello-server", "localhost", 52345)
    val rpcEnv: RpcEnv = NettyRpcEnvFactory.create(config)
    val helloEndpoint: RpcEndpoint = new HelloEndpoint(rpcEnv)
    val helloEndpointRef = rpcEnv.setupEndpoint(HelloEndpoint.ENDPOINT_NAME, helloEndpoint)
    val f = Future {
      val future: Future[String] = helloEndpointRef.ask[String](SayHello("abc"))
      future.onComplete {
        case scala.util.Success(value) => println(s"client got result => $value")
        case scala.util.Failure(e) => e.printStackTrace
      }
    }
    Await.result(f, Duration.apply("240s"))
    println("waiting to be called...")
    rpcEnv.awaitTermination()
  }

}

class HelloEndpoint(realRpcEnv: RpcEnv) extends ThreadSafeRpcEndpoint {

  override def onStart(): Unit = {
    println("start hello endpoint")
  }

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    // Messages sent and received locally
    case SayHello(msg) => {
      println(s"receive $msg")
      context.reply(msg.toUpperCase)
    }
  }

  override def onStop(): Unit = {
    println("stop hello...")
  }

  /**
    * The [[RpcEnv]] that this [[RpcEndpoint]] is registered to.
    */
  override val rpcEnv: RpcEnv = realRpcEnv
}

object HelloEndpoint {
  val ENDPOINT_NAME = "my-hello"
}

case class SayHello(msg: String)
