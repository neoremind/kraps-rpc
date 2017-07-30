package com.neoremind.kraps.rpc

import com.neoremind.kraps.util.Utils


/**
  * Address for an RPC environment, with hostname and port.
  */
case class RpcAddress(host: String, port: Int) {

  def hostPort: String = host + ":" + port

  /** Returns a string in the form of "spark://host:port". */
  def toSparkURL: String = "spark://" + hostPort

  override def toString: String = hostPort
}


object RpcAddress {

  /** Return the [[RpcAddress]] represented by `uri`. */
  def fromURIString(uri: String): RpcAddress = {
    val uriObj = new java.net.URI(uri)
    RpcAddress(uriObj.getHost, uriObj.getPort)
  }

  /** Returns the [[RpcAddress]] encoded in the form of "spark://host:port" */
  def fromSparkURL(sparkUrl: String): RpcAddress = {
    val (host, port) = Utils.extractHostPortFromKrapsUrl(sparkUrl)
    RpcAddress(host, port)
  }
}
