package com.neoremind.kraps.rpc

import com.neoremind.kraps.RpcException


/**
  * An address identifier for an RPC endpoint.
  *
  * The `rpcAddress` may be null, in which case the endpoint is registered via a client-only
  * connection and can only be reached via the client that sent the endpoint reference.
  *
  * @param rpcAddress The socket address of the endpoint.
  * @param name Name of the endpoint.
  */
case class RpcEndpointAddress(val rpcAddress: RpcAddress, val name: String) {

  require(name != null, "RpcEndpoint name must be provided.")

  def this(host: String, port: Int, name: String) = {
    this(RpcAddress(host, port), name)
  }

  override val toString = if (rpcAddress != null) {
    s"spark://$name@${rpcAddress.host}:${rpcAddress.port}"
  } else {
    s"spark-client://$name"
  }
}

object RpcEndpointAddress {

  def apply(host: String, port: Int, name: String): RpcEndpointAddress = {
    new RpcEndpointAddress(host, port, name)
  }

  def apply(sparkUrl: String): RpcEndpointAddress = {
    try {
      val uri = new java.net.URI(sparkUrl)
      val host = uri.getHost
      val port = uri.getPort
      val name = uri.getUserInfo
      if (uri.getScheme != "spark" ||
        host == null ||
        port < 0 ||
        name == null ||
        (uri.getPath != null && !uri.getPath.isEmpty) || // uri.getPath returns "" instead of null
        uri.getFragment != null ||
        uri.getQuery != null) {
        throw new RpcException("Invalid URL: " + sparkUrl)
      }
      new RpcEndpointAddress(host, port, name)
    } catch {
      case e: java.net.URISyntaxException =>
        throw new RpcException("Invalid URL: " + sparkUrl, e)
    }
  }
}
