package com.neoremind.kraps.rpc

import com.neoremind.kraps.RpcException

private[rpc] class RpcEndpointNotFoundException(uri: String)
  extends RpcException(s"Cannot find endpoint: $uri")
