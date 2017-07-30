package com.neoremind.kraps.rpc

/**
  * A callback that [[RpcEndpoint]] can use to send back a message or failure. It's thread-safe
  * and can be called in any thread.
  */
private[spark] trait RpcCallContext {

  /**
    * Reply a message to the sender. If the sender is [[RpcEndpoint]], its [[RpcEndpoint.receive]]
    * will be called.
    */
  def reply(response: Any): Unit

  /**
    * Report a failure to the sender.
    */
  def sendFailure(e: Throwable): Unit

  /**
    * The sender of this message.
    */
  def senderAddress: RpcAddress
}
