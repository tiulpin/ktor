package io.ktor.utils.io.jvm.nio

import io.ktor.utils.io.*
import java.nio.*
import java.nio.channels.*

/**
 * Copy up to [limit] bytes to blocking NIO [channel]. Copying to non-blocking channel requires selection and
 * not supported. It does suspend if no data available in byte channel but may block if destination NIO channel blocks.
 *
 * @return number of bytes copied
 */
public suspend fun ByteReadChannel.copyTo(channel: WritableByteChannel, limit: Long = Long.MAX_VALUE): Long {
    require(limit >= 0L) { "Limit shouldn't be negative: $limit" }
    if (channel is SelectableChannel && !channel.isBlocking) {
        throw IllegalArgumentException("Non-blocking channels are not supported")
    }

    if (isClosedForRead) return 0

    var copied = 0L
    val copy: (ByteBuffer) -> Unit = copy@{ buffer: ByteBuffer ->
        val bytesToCopy = limit - copied

        if (bytesToCopy >= buffer.remaining()) {
            var written = 0L
            while (buffer.hasRemaining()) {
                written += channel.write(buffer)
            }

            copied += written
            return@copy
        }
        val currentLimit = buffer.limit()
        buffer.limit(buffer.position() + bytesToCopy.toInt())

        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }

        buffer.limit(currentLimit)
        copied += bytesToCopy
    }

    while (!isClosedForRead && copied < limit) {
        read(consumer = copy)
    }

    closedCause?.let { throw it }

    return copied
}

/**
 * Copy up to [limit] bytes to blocking [pipe]. A shortcut to copyTo function with NIO channel destination
 *
 * @return number of bytes were copied
 */
public suspend fun ByteReadChannel.copyTo(pipe: Pipe, limit: Long = Long.MAX_VALUE): Long = copyTo(pipe.sink(), limit)
