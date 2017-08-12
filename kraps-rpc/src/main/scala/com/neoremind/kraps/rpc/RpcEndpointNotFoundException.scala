package net.neoremind.kraps.rpc

import net.neoremind.kraps.RpcException

private[rpc] class RpcEndpointNotFoundException(uri: String)
  extends RpcException(s"Cannot find endpoint: $uri")
