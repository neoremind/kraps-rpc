package net.neoremind.kraps.util


import net.neoremind.kraps.RpcConf
import net.neoremind.kraps.rpc.{RpcAddress, RpcEndpointRef, RpcEnv, RpcTimeout}

object RpcUtils {

  /**
    * Retrieve a `RpcEndpointRef` which is located in the driver via its name.
    */
  def makeDriverRef(name: String, conf: RpcConf, rpcEnv: RpcEnv): RpcEndpointRef = {
    val driverHost: String = conf.get("spark.driver.host", "localhost")
    val driverPort: Int = conf.getInt("spark.driver.port", 7077)
    Utils.checkHost(driverHost, "Expected hostname")
    rpcEnv.setupEndpointRef(RpcAddress(driverHost, driverPort), name)
  }

  /** Returns the configured number of times to retry connecting */
  def numRetries(conf: RpcConf): Int = {
    conf.getInt("spark.rpc.numRetries", 3)
  }

  /** Returns the configured number of milliseconds to wait on each retry */
  def retryWaitMs(conf: RpcConf): Long = {
    conf.getTimeAsMs("spark.rpc.retry.wait", "3s")
  }

  /** Returns the default Spark timeout to use for RPC ask operations. */
  def askRpcTimeout(conf: RpcConf): RpcTimeout = {
    RpcTimeout(conf, Seq("spark.rpc.askTimeout", "spark.network.timeout"), "120s")
  }

  /** Returns the default Spark timeout to use for RPC remote endpoint lookup. */
  def lookupRpcTimeout(conf: RpcConf): RpcTimeout = {
    RpcTimeout(conf, Seq("spark.rpc.lookupTimeout", "spark.network.timeout"), "120s")
  }

  private val MAX_MESSAGE_SIZE_IN_MB = Int.MaxValue / 1024 / 1024

  /** Returns the configured max message size for messages in bytes. */
  def maxMessageSizeBytes(conf: RpcConf): Int = {
    val maxSizeInMB = conf.getInt("spark.rpc.message.maxSize", 128)
    if (maxSizeInMB > MAX_MESSAGE_SIZE_IN_MB) {
      throw new IllegalArgumentException(
        s"spark.rpc.message.maxSize should not be greater than $MAX_MESSAGE_SIZE_IN_MB MB")
    }
    maxSizeInMB * 1024 * 1024
  }
}
