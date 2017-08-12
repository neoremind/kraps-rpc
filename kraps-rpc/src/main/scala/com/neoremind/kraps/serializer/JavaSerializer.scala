package net.neoremind.kraps.serializer

import java.io._
import java.nio.ByteBuffer

import net.neoremind.kraps.RpcConf
import net.neoremind.kraps.util.{ByteBufferInputStream, ByteBufferOutputStream, Utils}
import org.apache.spark.annotation.DeveloperApi
import org.nustaq.serialization.FSTConfiguration

import scala.reflect.ClassTag

class JavaSerializationStream(out: OutputStream, counterReset: Int, extraDebugInfo: Boolean)
  extends SerializationStream {
  private val objOut = new ObjectOutputStream(out)
  private var counter = 0

  /**
    * Calling reset to avoid memory leak:
    * http://stackoverflow.com/questions/1281549/memory-leak-traps-in-the-java-standard-api
    * But only call it every 100th time to avoid bloated serialization streams (when
    * the stream 'resets' object class descriptions have to be re-written)
    */
  def writeObject[T: ClassTag](t: T): SerializationStream = {
    try {
      objOut.writeObject(t)
    } catch {
      case e: NotSerializableException => throw e
    }
    counter += 1
    if (counterReset > 0 && counter >= counterReset) {
      objOut.reset()
      counter = 0
    }
    this
  }

  def flush() {
    objOut.flush()
  }

  def close() {
    objOut.close()
  }
}

class JavaDeserializationStream(in: InputStream, loader: ClassLoader)
  extends DeserializationStream {

  private val objIn = new ObjectInputStream(in) {
    override def resolveClass(desc: ObjectStreamClass): Class[_] =
      try {
        // scalastyle:off classforname
        Class.forName(desc.getName, false, loader)
        // scalastyle:on classforname
      } catch {
        case e: ClassNotFoundException =>
          JavaDeserializationStream.primitiveMappings.getOrElse(desc.getName, throw e)
      }
  }

  def readObject[T: ClassTag](): T = objIn.readObject().asInstanceOf[T]

  def close() {
    objIn.close()
  }
}

class FstSerializationStream(out: OutputStream, counterReset: Int, extraDebugInfo: Boolean)
  extends SerializationStream {
  val conf = new ThreadLocal[FSTConfiguration] {
    override def initialValue(): FSTConfiguration = {
      FSTConfiguration.createDefaultConfiguration()
    }
  }

  //private val objOut = new ObjectOutputStream(out)
  private var counter = 0

  /**
    * Calling reset to avoid memory leak:
    * http://stackoverflow.com/questions/1281549/memory-leak-traps-in-the-java-standard-api
    * But only call it every 100th time to avoid bloated serialization streams (when
    * the stream 'resets' object class descriptions have to be re-written)
    */
  def writeObject[T: ClassTag](t: T): SerializationStream = {
    try {
      conf.get().encodeToStream(out, t)
    } catch {
      case e: IOException => throw e
    }
    counter += 1
    if (counterReset > 0 && counter >= counterReset) {
      conf.get().getObjectOutput.resetForReUse()
      counter = 0
    }
    this
  }

  def flush() {
    Utils.tryOrIOException(conf.get().getObjectOutput.flush())
    Utils.tryOrIOException(out.flush())
  }

  def close() {
    Utils.tryOrIOException(conf.get().getObjectOutput.close())
    Utils.tryOrIOException(out.close())
  }
}

class FstDeserializationStream(in: InputStream, loader: ClassLoader)
  extends DeserializationStream {

  val conf = new ThreadLocal[FSTConfiguration] {
    override def initialValue(): FSTConfiguration = {
      FSTConfiguration.createDefaultConfiguration()
    }
  }

  //  def readObject[T: ClassTag](): T = objIn.readObject().asInstanceOf[T]
  def readObject[T: ClassTag](): T = conf.get().decodeFromStream(in).asInstanceOf[T]

  def close() {
    Utils.tryOrIOException(conf.get().getObjectInput.close())
    Utils.tryOrIOException(in)
  }
}

trait SerializationStreamFactory {
  def buildSerializationStream(s: OutputStream, counterReset: Int, extraDebugInfo: Boolean): SerializationStream

  def buildDeserializationStream(s: InputStream, classLoader: ClassLoader): DeserializationStream
}

class JavaSerializationStreamFactory extends SerializationStreamFactory {
  override def buildSerializationStream(s: OutputStream, counterReset: Int, extraDebugInfo: Boolean): SerializationStream =
    new JavaSerializationStream(s, counterReset, extraDebugInfo)

  override def buildDeserializationStream(s: InputStream, classLoader: ClassLoader): DeserializationStream =
    new JavaDeserializationStream(s, classLoader)
}

class FstSerializationStreamFactory extends SerializationStreamFactory {
  override def buildSerializationStream(s: OutputStream, counterReset: Int, extraDebugInfo: Boolean): SerializationStream =
    new FstSerializationStream(s, counterReset, extraDebugInfo)

  override def buildDeserializationStream(s: InputStream, classLoader: ClassLoader): DeserializationStream =
    new FstDeserializationStream(s, classLoader)
}

private object JavaDeserializationStream {
  val primitiveMappings = Map[String, Class[_]](
    "boolean" -> classOf[Boolean],
    "byte" -> classOf[Byte],
    "char" -> classOf[Char],
    "short" -> classOf[Short],
    "int" -> classOf[Int],
    "long" -> classOf[Long],
    "float" -> classOf[Float],
    "double" -> classOf[Double],
    "void" -> classOf[Void]
  )
}

class JavaSerializerInstance(counterReset: Int, extraDebugInfo: Boolean, defaultClassLoader: ClassLoader, streamFactory: SerializationStreamFactory)
  extends SerializerInstance {

  override def serialize[T: ClassTag](t: T): ByteBuffer = {
    val bos = new ByteBufferOutputStream()
    val out = serializeStream(bos)
    out.writeObject(t)
    out.close()
    bos.toByteBuffer
  }

  override def deserialize[T: ClassTag](bytes: ByteBuffer): T = {
    val bis = new ByteBufferInputStream(bytes)
    val in = deserializeStream(bis)
    in.readObject()
  }

  override def deserialize[T: ClassTag](bytes: ByteBuffer, loader: ClassLoader): T = {
    val bis = new ByteBufferInputStream(bytes)
    val in = deserializeStream(bis, loader)
    in.readObject()
  }

  override def serializeStream(s: OutputStream): SerializationStream = {
    //new JavaSerializationStream(s, counterReset, extraDebugInfo)
    streamFactory.buildSerializationStream(s, counterReset, extraDebugInfo)
  }

  override def deserializeStream(s: InputStream): DeserializationStream = {
    //new JavaDeserializationStream(s, defaultClassLoader)
    streamFactory.buildDeserializationStream(s, defaultClassLoader)
  }

  def deserializeStream(s: InputStream, loader: ClassLoader): DeserializationStream = {
    //new JavaDeserializationStream(s, loader)
    streamFactory.buildDeserializationStream(s, loader)
  }
}

/**
  * :: DeveloperApi ::
  * A Spark serializer that uses Java's built-in serialization.
  *
  * @note This serializer is not guaranteed to be wire-compatible across different versions of
  * Spark. It is intended to be used to serialize/de-serialize data within a single
  *       Spark application.
  */
@DeveloperApi
class JavaSerializer(conf: RpcConf) extends Serializer with Externalizable {
  private var counterReset = conf.getInt("spark.serializer.objectStreamReset", 100)
  private var extraDebugInfo = conf.getBoolean("spark.serializer.extraDebugInfo", true)

  protected def this() = this(new RpcConf()) // For deserialization only

  override def newInstance(): SerializerInstance = {
    val classLoader = defaultClassLoader.getOrElse(Thread.currentThread.getContextClassLoader)
    val streamFactory: SerializationStreamFactory = classLoader
      .loadClass(conf.get("spark.rpc.serialization.stream.factory", "net.neoremind.kraps.serializer.JavaSerializationStreamFactory"))
      .newInstance().asInstanceOf[SerializationStreamFactory]
    new JavaSerializerInstance(counterReset, extraDebugInfo, classLoader, streamFactory)
  }

  override def writeExternal(out: ObjectOutput): Unit = Utils.tryOrIOException {
    out.writeInt(counterReset)
    out.writeBoolean(extraDebugInfo)
  }

  override def readExternal(in: ObjectInput): Unit = Utils.tryOrIOException {
    counterReset = in.readInt()
    extraDebugInfo = in.readBoolean()
  }
}
