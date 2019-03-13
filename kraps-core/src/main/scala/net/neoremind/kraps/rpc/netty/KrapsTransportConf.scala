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

import java.lang
import java.util.Map

import net.neoremind.kraps.RpcConf
import org.apache.spark.network.util.{ConfigProvider, TransportConf}

/**
  * Created by xu.zhang on 7/30/17.
  */
object KrapsTransportConf {
  /**
    * Specifies an upper bound on the number of Netty threads that Kraps requires by default.
    * In practice, only 2-4 cores should be required to transfer roughly 10 Gb/s, and each core
    * that we use will have an initial overhead of roughly 32 MB of off-heap memory, which comes
    * at a premium.
    */
  private val MAX_DEFAULT_NETTY_THREADS = 8

  /**
    * Utility for creating a [[KrapsTransportConf]] from a [[net.neoremind.kraps.RpcConf]].
    *
    * @param conf           the [[net.neoremind.kraps.RpcConf]]
    * @param module         the module name
    * @param numUsableCores if nonzero, this will restrict the server and client threads to only
    *                       use the given number of cores, rather than all of the machine's cores.
    *                       This restriction will only occur if these properties are not already set.
    */
  def fromSparkConf(conf: RpcConf, module: String, numUsableCores: Int = 0): TransportConf = {
    // Specify thread configuration based on our JVM's allocation of cores (rather than necessarily
    // assuming we have all the machine's cores).
    // NB: Only set if serverThreads/clientThreads not already set.
    val numThreads = defaultNumThreads(numUsableCores)
    conf.setIfMissing(s"spark.$module.io.serverThreads", numThreads.toString)
    conf.setIfMissing(s"spark.$module.io.clientThreads", numThreads.toString)

    new TransportConf(module, new ConfigProvider {
      override def get(name: String): String = conf.get(name)

      override def getAll: lang.Iterable[Map.Entry[String, String]] = ??? //conf.getAll.toMap
    })
  }

  /**
    * Returns the default number of threads for both the Netty client and server thread pools.
    * If numUsableCores is 0, we will use Runtime get an approximate number of available cores.
    */
  private def defaultNumThreads(numUsableCores: Int): Int = {
    val availableCores =
      if (numUsableCores > 0) numUsableCores else Runtime.getRuntime.availableProcessors()
    math.min(availableCores, MAX_DEFAULT_NETTY_THREADS)
  }
}
