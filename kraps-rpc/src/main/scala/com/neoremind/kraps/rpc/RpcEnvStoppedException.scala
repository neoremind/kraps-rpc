package net.neoremind.kraps.rpc

private[rpc] class RpcEnvStoppedException()
  extends IllegalStateException("RpcEnv already stopped.")
