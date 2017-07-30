package com.neoremind.kraps.rpc

private[rpc] class RpcEnvStoppedException()
  extends IllegalStateException("RpcEnv already stopped.")
