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

package net.neoremind.kraps

import java.util.concurrent.ConcurrentHashMap

import net.neoremind.kraps.util.Utils

import scala.collection.JavaConverters._

case class RpcConf(loadDefaults: Boolean = true) extends Cloneable with Serializable {

  /** Create a RpcConf that loads defaults from system properties and the classpath */
  def this() = this(true)

  private val settings = new ConcurrentHashMap[String, String]()

  loadFromSystemProperties(false)

  def loadFromSystemProperties(silent: Boolean): RpcConf = {
    // Load any spark.* system properties
    for ((key, value) <- Utils.getSystemProperties if key.startsWith("spark.")) {
      set(key, value)
    }
    this
  }

  /** Set a configuration variable. */
  def set(key: String, value: String): RpcConf = {
    settings.put(key, value)
    this
  }

  /** Remove a parameter from the configuration */
  def remove(key: String): RpcConf = {
    settings.remove(key)
    this
  }

  /** Get a parameter; throws a NoSuchElementException if it's not set */
  def get(key: String): String = {
    getOption(key).getOrElse(throw new NoSuchElementException(key))
  }

  /** Get a parameter, falling back to a default if not set */
  def get(key: String, defaultValue: String): String = {
    getOption(key).getOrElse(defaultValue)
  }

  /** Get a parameter as an Option */
  def getOption(key: String): Option[String] = {
    Option(settings.get(key))
  }

  /** Get all parameters as a list of pairs */
  def getAll: Array[(String, String)] = {
    settings.entrySet().asScala.map(x => (x.getKey, x.getValue)).toArray
  }

  /**
    * Get all parameters that start with `prefix`
    */
  def getAllWithPrefix(prefix: String): Array[(String, String)] = {
    getAll.filter { case (k, v) => k.startsWith(prefix) }
      .map { case (k, v) => (k.substring(prefix.length), v) }
  }

  /**
    * Get a time parameter as seconds; throws a NoSuchElementException if it's not set. If no
    * suffix is provided then seconds are assumed.
    *
    * @throws java.util.NoSuchElementException If the time parameter is not set
    */
  def getTimeAsSeconds(key: String): Long = {
    Utils.timeStringAsSeconds(get(key))
  }

  /**
    * Get a time parameter as seconds, falling back to a default if not set. If no
    * suffix is provided then seconds are assumed.
    */
  def getTimeAsSeconds(key: String, defaultValue: String): Long = {
    Utils.timeStringAsSeconds(get(key, defaultValue))
  }

  /**
    * Get a time parameter as milliseconds; throws a NoSuchElementException if it's not set. If no
    * suffix is provided then milliseconds are assumed.
    *
    * @throws java.util.NoSuchElementException If the time parameter is not set
    */
  def getTimeAsMs(key: String): Long = {
    Utils.timeStringAsMs(get(key))
  }

  /**
    * Get a time parameter as milliseconds, falling back to a default if not set. If no
    * suffix is provided then milliseconds are assumed.
    */
  def getTimeAsMs(key: String, defaultValue: String): Long = {
    Utils.timeStringAsMs(get(key, defaultValue))
  }

  /**
    * Get a size parameter as bytes; throws a NoSuchElementException if it's not set. If no
    * suffix is provided then bytes are assumed.
    *
    * @throws java.util.NoSuchElementException If the size parameter is not set
    */
  def getSizeAsBytes(key: String): Long = {
    Utils.byteStringAsBytes(get(key))
  }

  /**
    * Get a size parameter as bytes, falling back to a default if not set. If no
    * suffix is provided then bytes are assumed.
    */
  def getSizeAsBytes(key: String, defaultValue: String): Long = {
    Utils.byteStringAsBytes(get(key, defaultValue))
  }

  /**
    * Get a size parameter as bytes, falling back to a default if not set.
    */
  def getSizeAsBytes(key: String, defaultValue: Long): Long = {
    Utils.byteStringAsBytes(get(key, defaultValue + "B"))
  }

  /**
    * Get a size parameter as Kibibytes; throws a NoSuchElementException if it's not set. If no
    * suffix is provided then Kibibytes are assumed.
    *
    * @throws java.util.NoSuchElementException If the size parameter is not set
    */
  def getSizeAsKb(key: String): Long = {
    Utils.byteStringAsKb(get(key))
  }

  /**
    * Get a size parameter as Kibibytes, falling back to a default if not set. If no
    * suffix is provided then Kibibytes are assumed.
    */
  def getSizeAsKb(key: String, defaultValue: String): Long = {
    Utils.byteStringAsKb(get(key, defaultValue))
  }

  /**
    * Get a size parameter as Mebibytes; throws a NoSuchElementException if it's not set. If no
    * suffix is provided then Mebibytes are assumed.
    *
    * @throws java.util.NoSuchElementException If the size parameter is not set
    */
  def getSizeAsMb(key: String): Long = {
    Utils.byteStringAsMb(get(key))
  }

  /**
    * Get a size parameter as Mebibytes, falling back to a default if not set. If no
    * suffix is provided then Mebibytes are assumed.
    */
  def getSizeAsMb(key: String, defaultValue: String): Long = {
    Utils.byteStringAsMb(get(key, defaultValue))
  }

  /**
    * Get a size parameter as Gibibytes; throws a NoSuchElementException if it's not set. If no
    * suffix is provided then Gibibytes are assumed.
    *
    * @throws java.util.NoSuchElementException If the size parameter is not set
    */
  def getSizeAsGb(key: String): Long = {
    Utils.byteStringAsGb(get(key))
  }

  /**
    * Get a size parameter as Gibibytes, falling back to a default if not set. If no
    * suffix is provided then Gibibytes are assumed.
    */
  def getSizeAsGb(key: String, defaultValue: String): Long = {
    Utils.byteStringAsGb(get(key, defaultValue))
  }

  /** Get a parameter as an integer, falling back to a default if not set */
  def getInt(key: String, defaultValue: Int): Int = {
    getOption(key).map(_.toInt).getOrElse(defaultValue)
  }

  /** Get a parameter as a long, falling back to a default if not set */
  def getLong(key: String, defaultValue: Long): Long = {
    getOption(key).map(_.toLong).getOrElse(defaultValue)
  }

  /** Get a parameter as a double, falling back to a default if not set */
  def getDouble(key: String, defaultValue: Double): Double = {
    getOption(key).map(_.toDouble).getOrElse(defaultValue)
  }

  /** Get a parameter as a boolean, falling back to a default if not set */
  def getBoolean(key: String, defaultValue: Boolean): Boolean = {
    getOption(key).map(_.toBoolean).getOrElse(defaultValue)
  }

  /** Does the configuration contain a given parameter? */
  def contains(key: String): Boolean = {
    settings.containsKey(key)
  }

  /**
    * By using this instead of System.getenv(), environment variables can be mocked
    * in unit tests.
    */
  def getenv(name: String): String = System.getenv(name)

  /**
    * Return a string listing all keys and values, one per line. This is useful to print the
    * configuration out for debugging.
    */
  def toDebugString: String = {
    getAll.sorted.map { case (k, v) => k + "=" + v }.mkString("\n")
  }

  /** Set a parameter if it isn't already configured */
  def setIfMissing(key: String, value: String): RpcConf = {
    settings.putIfAbsent(key, value)
    this
  }

}
