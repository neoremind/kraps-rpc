package com.neoremind.kraps.rpc

import org.apache.spark.SparkException

private[rpc] class RpcEndpointNotFoundException(uri: String)
  extends SparkException(s"Cannot find endpoint: $uri")
