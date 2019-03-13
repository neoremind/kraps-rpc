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

package net.neoremind.kraps.rpc.netty

import java.io.{Externalizable, ObjectInput, ObjectOutput}

import net.neoremind.kraps.rpc.{RpcCallContext, RpcEndpoint, RpcEnv}
import net.neoremind.kraps.util.Utils

/**
  * An [[net.neoremind.kraps.rpc.RpcEndpoint]] for remote [[net.neoremind.kraps.rpc.RpcEnv]]s to query if an `RpcEndpoint` exists.
  *
  * This is used when setting up a remote endpoint reference.
  */
private[netty] class RpcEndpointVerifier(override val rpcEnv: RpcEnv, dispatcher: Dispatcher)
  extends RpcEndpoint {

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case c: RpcEndpointVerifier.CheckExistence => context.reply(dispatcher.verify(c.getName))
  }
}

private[netty] object RpcEndpointVerifier {
  val NAME = "endpoint-verifier"

  /** A message used to ask the remote [[RpcEndpointVerifier]] if an `RpcEndpoint` exists. */
  class CheckExistence(var name: String) extends Serializable with Externalizable {
    def getName: String = name

    def this() = this(null) // For deserialization only

    override def writeExternal(out: ObjectOutput): Unit = Utils.tryOrIOException {
      out.writeUTF(name)
    }

    override def readExternal(in: ObjectInput): Unit = Utils.tryOrIOException {
      name = in.readUTF()
    }
  }

  def createCheckExistence(name: String) = {
    new CheckExistence(name)
  }

}
