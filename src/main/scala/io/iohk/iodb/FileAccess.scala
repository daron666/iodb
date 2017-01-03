package io.iohk.iodb

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileChannel.MapMode
import java.nio.file.StandardOpenOption
import java.nio.{ByteBuffer, MappedByteBuffer}

import io.iohk.iodb.Store._
import io.iohk.iodb.Utils.keyCountOffset

/**
  * Different ways to access files (RandomAccessFile, memory-mapped, direct mmap with Unsafe)
  */
sealed abstract class FileAccess {


  def getValue(fileHandle: Any, key: K, keySize: Int, baseKeyOffset: Long): V


  /**
    * gets file size of given file
    *
    * @param fileHandle for opened file
    * @return file size
    */
  def fileSize(fileHandle: Any): Long


  /**
    * Reads data from given offset
    *
    * @param fileHandle
    * @param offset
    * @param data
    */

  def readData(fileHandle: Any, offset: Int, data: Array[Byte]): Unit

  /**
    * Read all key-value pairs from given log file
    *
    * @param fileHandle
    * @param baseKeyOffset offset where keys are starting
    * @param keySize       size of key
    * @return iterator over key-value pairs
    */
  def readKeyValues(fileHandle: Any, baseKeyOffset: Long, keySize: Int): Iterator[(K, V)]

  /**
    * Opens new file
    *
    * @param fileName name of file
    * @return handle for opened file in given file access method
    */
  def open(fileName: String): Any

  /**
    * Close file and release all resources associated  with file
    *
    * @param file handle for opened file
    */
  def close(file: Any)



}

object FileAccess {

  abstract class ByteBufferFileAccess extends FileAccess {

    /** casts parameter to MappedByteBuffer */
    protected[iodb] def cast(fileHandle: Any) = fileHandle.asInstanceOf[MappedByteBuffer]

    override def open(fileName: String): MappedByteBuffer = mmap(fileName)


    override def close(file: Any): Unit = {
      Utils.unmap(cast(file))
    }

    /**
      * gets file size of given file
      *
      * @param fileHandle for opened file
      * @return file size
      */
    override def fileSize(fileHandle: Any): Long = cast(fileHandle).limit()

    /**
      * Reads data from given offset
      *
      * @param fileHandle
      * @param offset
      * @param data
      */
    override def readData(fileHandle: Any, offset: Int, data: Array[Byte]): Unit = {
      val buf2 = cast(fileHandle).duplicate()
      buf2.position(LogStore.headerSizeWithoutVersionID)
      buf2.get(data)
    }

    /**
      * Read all key-value pairs from given log file
      *
      * @param fileHandle
      * @return iterator over key-value pairs
      */
    override def readKeyValues(fileHandle: Any, baseKeyOffset: Long, keySize: Int): Iterator[(K, V)] = {
      val buf = cast(fileHandle).duplicate()
      val valueBuf = buf.duplicate()
      buf.position(baseKeyOffset.toInt)

      val count = buf.getInt(keyCountOffset)
      (0 until count).map { i =>
        val key = new Array[Byte](keySize)
        buf.get(key)

        //load value
        val valueSize = buf.getInt()
        val valueOffset = buf.getLong
        val value =
          if (valueSize == -1) null
          else {
            //TODO seek should not be necessary here
            valueBuf.position(valueOffset.toInt)
            val value = new Array[Byte](valueSize)
            valueBuf.get(value)
            ByteArrayWrapper(value)
          }
        ByteArrayWrapper(key) -> value
      }.iterator
    }

  }

  /** use memory mapped files, fast but can cause problem on Windows.  */
  object MMAP extends ByteBufferFileAccess {


    override def getValue(fileHandle: Any, key: K, keySize: Int, baseKeyOffset: Long): V = {

      val buf = cast(fileHandle).duplicate()

      def loadValue(): V = {
        //key found, load value
        val valueSize = buf.getInt()
        if (valueSize == -1)
          return LogStore.tombstone //tombstone, return nothing

        //load value
        val valueOffset = buf.getLong()
        buf.position(valueOffset.toInt)
        val ret = new Array[Byte](valueSize)
        buf.get(ret)
        ByteArrayWrapper(ret)
      }

      val keySizeExtra = keySize + 4 + 8

      val key2 = new Array[Byte](keySize)
      val keyCount: Long = buf.getInt(keyCountOffset)
      var lo: Long = 0
      var hi: Long = keyCount - 1

      while (lo <= hi) {

        //split interval
        val mid = (lo + hi) / 2
        val keyOffset = baseKeyOffset + mid * keySizeExtra
        //load key

        buf.position(keyOffset.toInt)
        buf.get(key2)
        //compare keys and split intervals if not equal
        val comp = Utils.BYTE_ARRAY_COMPARATOR.compare(key2, key.data)
        if (comp < 0) lo = mid + 1
        else if (comp > 0) hi = mid - 1
        else {
          return loadValue()
        }
      }
      null
    }
  }


  /** Use `sun.misc.Unsafe` with direct memory access. Very fast, but can cause JVM  and has problems on 32bit systems and Windows. */
  object UNSAFE extends ByteBufferFileAccess {
    override def getValue(fileHandle: Any, key: K, keySize: Int, baseKeyOffset: Long): V = {

      val buf = cast(fileHandle).duplicate()

      def loadValue(): V = {
        //key found, load value
        val valueSize = buf.getInt()
        if (valueSize == -1)
          return LogStore.tombstone //tombstone, return nothing

        //load value
        val valueOffset = buf.getLong()
        buf.position(valueOffset.toInt)
        val ret = new Array[Byte](valueSize)
        buf.get(ret)
        ByteArrayWrapper(ret)
      }

      val keySizeExtra = keySize + 4 + 8
      val r = Utils.unsafeBinarySearch(buf, key.data, baseKeyOffset.toInt)
      if (r < 0)
        return null
      val keyOffset = baseKeyOffset + r * keySizeExtra
      //load key
      buf.position(keyOffset.toInt + keySize)
      return loadValue
    }

  }

  /**
    * Use `FileChannel` to access files. Slower, but safer. Keeps many file handles open,
    * and might cause crash if 'maximal number of open files per process' is exceed.
    */
  object FILE_CHANNEL extends FileAccess {

    protected def cast(fileHandle: Any) = fileHandle.asInstanceOf[FileChannel]

    override def getValue(fileHandle: Any, key: K, keySize: Int, baseKeyOffset: Long): V = {
      val c = cast(fileHandle)
      val tempBuf = ByteBuffer.allocate(8)

      val keySizeExtra = keySize + 4 + 8

      val key2 = new Array[Byte](keySize)
      val key2B = ByteBuffer.wrap(key2)
      val keyCount: Long = readInt(c, keyCountOffset, tempBuf)
      var lo: Long = 0
      var hi: Long = keyCount - 1

      while (lo <= hi) {

        //split interval
        val mid = (lo + hi) / 2
        val keyOffset = baseKeyOffset + mid * keySizeExtra
        //load key
        key2B.clear()
        Utils.readFully(c, keyOffset, key2B)
        //compare keys and split intervals if not equal
        val comp = Utils.BYTE_ARRAY_COMPARATOR.compare(key2, key.data)
        if (comp < 0) lo = mid + 1
        else if (comp > 0) hi = mid - 1
        else {
          //key found, load value
          val valueSize = readInt(c, keyOffset + keySize, tempBuf)
          if (valueSize == -1)
            return LogStore.tombstone //tombstone, return nothing

          //load value
          val valueOffset = readLong(c, keyOffset + keySize + 4, tempBuf)
          val ret = new Array[Byte](valueSize)
          val ret2 = ByteBuffer.wrap(ret)
          Utils.readFully(c, valueOffset, ret2)
          return ByteArrayWrapper(ret)
        }
      }
      null
    }

    protected def readLong(c: FileChannel, offset: Long, buf: ByteBuffer = ByteBuffer.allocate(8)): Long = {
      buf.clear();
      buf.limit(8)
      Utils.readFully(c, offset, buf)
      return buf.getLong(0)
    }


    protected def readInt(c: FileChannel, offset: Long, buf: ByteBuffer = ByteBuffer.allocate(4)): Int = {
      buf.position(0);
      buf.limit(4)
      Utils.readFully(c, offset, buf)
      return buf.getInt(0)
    }

    override def readData(fileHandle: Any, offset: Int, data: Array[Byte]): Unit = {
      val c = cast(fileHandle)
      val b = ByteBuffer.wrap(data)
      c.position(offset)
      Utils.readFully(c, offset, b)
    }

    override def readKeyValues(fileHandle: Any, baseKeyOffset: Long, keySize: Int): Iterator[(K, V)] = {
      val c = cast(fileHandle)
      var keyPos = baseKeyOffset
      val tempBuf = ByteBuffer.allocate(8)

      val count = readInt(c, keyCountOffset, tempBuf)
      (0 until count).map { i =>
        val key = new Array[Byte](keySize)
        val keyB = ByteBuffer.wrap(key)
        Utils.readFully(c, keyPos, keyB)
        keyPos += keySize

        //load value
        val valueSize = readInt(c, keyPos, tempBuf)
        keyPos += 4
        val valueOffset = readLong(c, keyPos, tempBuf)
        keyPos += 8
        val value =
          if (valueSize == -1) null
          else {
            val ret = new Array[Byte](valueSize)
            val ret2 = ByteBuffer.wrap(ret)
            Utils.readFully(c, valueOffset, ret2)
            ByteArrayWrapper(ret)
          }
        ByteArrayWrapper(key) -> value
      }.iterator
    }

    override def open(fileName: String): Any = {
      FileChannel.open(new File(fileName).toPath, StandardOpenOption.READ)
    }

    override def fileSize(fileHandle: Any): Long = cast(fileHandle).size()

    override def close(file: Any): Unit = cast(file).close()
  }

  /**
    * Use `FileChannel` to access files, no file handles are kept open.
    * Slower and safer.
    */

  object SAFE extends FileAccess {

    protected def cast(fileHandle: Any) = fileHandle.asInstanceOf[File]

    protected def open2(fileHandle: Any) = FileChannel.open(cast(fileHandle).toPath, StandardOpenOption.READ)

    override def getValue(fileHandle: Any, key: K, keySize: Int, baseKeyOffset: Long): V = {
      val c = open2(fileHandle)
      try {
        return FILE_CHANNEL.getValue(fileHandle = c, key = key, keySize = keySize, baseKeyOffset = baseKeyOffset)
      } finally {
        c.close()
      }
    }

    override def readData(fileHandle: Any, offset: Int, data: Array[Byte]): Unit = {
      val c = open2(fileHandle)
      try {
        FILE_CHANNEL.readData(fileHandle = c, offset = offset, data = data)
      } finally {
        c.close()
      }
    }

    override def readKeyValues(fileHandle: Any, baseKeyOffset: Long, keySize: Int): Iterator[(K, V)] = {
      val c = open2(fileHandle)
      try {
        return FILE_CHANNEL.readKeyValues(fileHandle = c, baseKeyOffset = baseKeyOffset, keySize = keySize).toBuffer.iterator
      } finally {
        c.close()
      }
    }

    override def fileSize(fileHandle: Any): Long = cast(fileHandle).length()

    override def open(fileName: String): Any = new File(fileName)

    override def close(file: Any): Unit = {
      //nothing to do, java.io.File consumes no system resources
    }
  }

  /**
    * Memory maps file into read-only ByteBuffer. File must be smaller than 2GB due to addressing limit.
    *
    * @param fileName to be mapped
    * @return ByteByffer of memory mapped file
    */
  protected def mmap(fileName: String): MappedByteBuffer = {
    val file = new File(fileName)
    val c = FileChannel.open(file.toPath, StandardOpenOption.READ)
    val ret = c.map(MapMode.READ_ONLY, 0, file.length())
    c.close()
    ret
  }


}