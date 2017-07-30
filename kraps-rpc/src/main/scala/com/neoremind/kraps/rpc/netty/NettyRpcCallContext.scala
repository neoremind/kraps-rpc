package com.neoremind.kraps.rpc.netty

import scala.concurrent.Promise

import org.apache.spark.internal.Logging
import org.apache.spark.network.client.RpcResponseCallback
import org.apache.spark.rpc.{RpcAddress, RpcCallContext}

private[netty] abstract class NettyRpcCallContext(override val senderAddress: RpcAddress)
  extends RpcCallContext with Logging {

  protected def send(message: Any): Unit

  override def reply(response: Any): Unit = {
    send(response)
  }

  override def sendFailure(e: Throwable): Unit = {
    send(RpcFailure(e))
  }

}

/**
  * If the sender and the receiver are in the same process, the reply can be sent back via `Promise`.
  */
private[netty] class LocalNettyRpcCallContext(
                                               senderAddress: RpcAddress,
                                               p: Promise[Any])
  extends NettyRpcCallContext(senderAddress) {

  override protected def send(message: Any): Unit = {
    p.success(message)
  }
}

/**
  * A [[RpcCallContext]] that will call [[RpcResponseCallback]] to send the reply back.
  */
private[netty] class RemoteNettyRpcCallContext(
                                                nettyEnv: NettyRpcEnv,
                                                callback: RpcResponseCallback,
                                                senderAddress: RpcAddress)
  extends NettyRpcCallContext(senderAddress) {

  override protected def send(message: Any): Unit = {
    val reply = nettyEnv.serialize(message)
    callback.onSuccess(reply)
  }
}
