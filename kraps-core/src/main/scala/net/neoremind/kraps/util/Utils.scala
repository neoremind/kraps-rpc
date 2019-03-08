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

package net.neoremind.kraps.util

import java.io.IOException
import java.net.BindException

import io.netty.channel.unix.Errors.NativeIoException
import net.neoremind.kraps.{RpcConf, RpcException}
import org.apache.spark.network.util.JavaUtils
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

object Utils {

  private val log = LoggerFactory.getLogger(Utils.getClass)

  /**
    * Returns the system properties map that is thread-safe to iterator over. It gets the
    * properties which have been set explicitly, as well as those for which only a default value
    * has been defined.
    */
  def getSystemProperties: Map[String, String] = {
    System.getProperties.stringPropertyNames().asScala
      .map(key => (key, System.getProperty(key))).toMap
  }

  /**
    * Convert a time parameter such as (50s, 100ms, or 250us) to microseconds for internal use. If
    * no suffix is provided, the passed number is assumed to be in ms.
    */
  def timeStringAsMs(str: String): Long = {
    JavaUtils.timeStringAsMs(str)
  }

  /**
    * Convert a time parameter such as (50s, 100ms, or 250us) to seconds for internal use. If
    * no suffix is provided, the passed number is assumed to be in seconds.
    */
  def timeStringAsSeconds(str: String): Long = {
    JavaUtils.timeStringAsSec(str)
  }

  /**
    * Convert a passed byte string (e.g. 50b, 100k, or 250m) to bytes for internal use.
    *
    * If no suffix is provided, the passed number is assumed to be in bytes.
    */
  def byteStringAsBytes(str: String): Long = {
    JavaUtils.byteStringAsBytes(str)
  }

  /**
    * Convert a passed byte string (e.g. 50b, 100k, or 250m) to kibibytes for internal use.
    *
    * If no suffix is provided, the passed number is assumed to be in kibibytes.
    */
  def byteStringAsKb(str: String): Long = {
    JavaUtils.byteStringAsKb(str)
  }

  /**
    * Convert a passed byte string (e.g. 50b, 100k, or 250m) to mebibytes for internal use.
    *
    * If no suffix is provided, the passed number is assumed to be in mebibytes.
    */
  def byteStringAsMb(str: String): Long = {
    JavaUtils.byteStringAsMb(str)
  }

  /**
    * Convert a passed byte string (e.g. 50b, 100k, or 250m, 500g) to gibibytes for internal use.
    *
    * If no suffix is provided, the passed number is assumed to be in gibibytes.
    */
  def byteStringAsGb(str: String): Long = {
    JavaUtils.byteStringAsGb(str)
  }

  /**
    * Convert a Java memory parameter passed to -Xmx (such as 300m or 1g) to a number of mebibytes.
    */
  def memoryStringToMb(str: String): Int = {
    // Convert to bytes, rather than directly to MB, because when no units are specified the unit
    // is assumed to be bytes
    (JavaUtils.byteStringAsBytes(str) / 1024 / 1024).toInt
  }

  def checkHost(host: String, message: String = "") {
    assert(host.indexOf(':') == -1, message)
  }

  /**
    * Return a pair of host and port extracted from the `sparkUrl`.
    *
    * A spark url (`spark://host:port`) is a special URI that its scheme is `spark` and only contains
    * host and port.
    *
    * @throws RpcException if sparkUrl is invalid.
    */
  @throws(classOf[RpcException])
  def extractHostPortFromKrapsUrl(sparkUrl: String): (String, Int) = {
    try {
      val uri = new java.net.URI(sparkUrl)
      val host = uri.getHost
      val port = uri.getPort
      if (uri.getScheme != "spark" ||
        host == null ||
        port < 0 ||
        (uri.getPath != null && !uri.getPath.isEmpty) || // uri.getPath returns "" instead of null
        uri.getFragment != null ||
        uri.getQuery != null ||
        uri.getUserInfo != null) {
        throw new RpcException("Invalid URL: " + sparkUrl)
      }
      (host, port)
    } catch {
      case e: java.net.URISyntaxException =>
        throw new RpcException("Invalid URL: " + sparkUrl, e)
    }
  }

  /**
    * Maximum number of retries when binding to a port before giving up.
    */
  def portMaxRetries(conf: RpcConf): Int = {
    val maxRetries = conf.getOption("spark.port.maxRetries").map(_.toInt)
    if (conf.contains("spark.testing")) {
      // Set a higher number of retries for tests...
      maxRetries.getOrElse(100)
    } else {
      maxRetries.getOrElse(16)
    }
  }

  /**
    * Return whether the exception is caused by an address-port collision when binding.
    */
  def isBindCollision(exception: Throwable): Boolean = {
    exception match {
      case e: BindException =>
        if (e.getMessage != null) {
          return true
        }
        isBindCollision(e.getCause)
      //      case e: MultiException =>
      //        e.getThrowables.asScala.exists(isBindCollision)
      case e: NativeIoException =>
        (e.getMessage != null && e.getMessage.startsWith("bind() failed: ")) ||
          isBindCollision(e.getCause)
      case e: Exception => isBindCollision(e.getCause)
      case _ => false
    }
  }

  /**
    * Attempt to start a service on the given port, or fail after a number of attempts.
    * Each subsequent attempt uses 1 + the port used in the previous attempt (unless the port is 0).
    *
    * @param startPort    The initial port to start the service on.
    * @param startService Function to start service on a given port.
    *                     This is expected to throw java.net.BindException on port collision.
    * @param conf         A SparkConf used to get the maximum number of retries when binding to a port.
    * @param serviceName  Name of the service.
    * @return (service: T, port: Int)
    */
  def startServiceOnPort[T](
                             startPort: Int,
                             startService: Int => (T, Int),
                             conf: RpcConf,
                             serviceName: String = ""): (T, Int) = {

    require(startPort == 0 || (1024 <= startPort && startPort < 65536),
      "startPort should be between 1024 and 65535 (inclusive), or 0 for a random free port.")

    val serviceString = if (serviceName.isEmpty) "" else s" '$serviceName'"
    val maxRetries = portMaxRetries(conf)
    for (offset <- 0 to maxRetries) {
      // Do not increment port if startPort is 0, which is treated as a special port
      val tryPort = if (startPort == 0) {
        startPort
      } else {
        // If the new port wraps around, do not try a privilege port
        ((startPort + offset - 1024) % (65536 - 1024)) + 1024
      }
      try {
        val (service, port) = startService(tryPort)
        log.info(s"Successfully started service$serviceString on port $port.")
        return (service, port)
      } catch {
        case e: Exception if isBindCollision(e) =>
          if (offset >= maxRetries) {
            val exceptionMessage = s"${e.getMessage}: Service$serviceString failed after " +
              s"$maxRetries retries (starting from $startPort)! Consider explicitly setting " +
              s"the appropriate port for the service$serviceString (for example spark.ui.port " +
              s"for SparkUI) to an available port or increasing spark.port.maxRetries."
            val exception = new BindException(exceptionMessage)
            // restore original stack trace
            exception.setStackTrace(e.getStackTrace)
            throw exception
          }
          log.warn(s"Service$serviceString could not bind on port $tryPort. " +
            s"Attempting port ${tryPort + 1}.")
      }
    }
    // Should never happen
    throw new RpcException(s"Failed to start service$serviceString on port $startPort")
  }

  /**
    * Execute a block of code that returns a value, re-throwing any non-fatal uncaught
    * exceptions as IOException. This is used when implementing Externalizable and Serializable's
    * read and write methods, since Java's serializer will not report non-IOExceptions properly;
    * see SPARK-4080 for more context.
    */
  def tryOrIOException[T](block: => T): T = {
    try {
      block
    } catch {
      case e: IOException =>
        println("Exception encountered", e)
        throw e
      case NonFatal(e) =>
        println("Exception encountered", e)
        throw new IOException(e)
    }
  }

}
