package io.ktor.utils.io

import io.ktor.utils.io.core.*
import io.ktor.utils.io.core.internal.*
import java.nio.*

/**
 * Creates channel for reading from the specified byte buffer.
 */
public fun ByteReadChannel(content: ByteBuffer): ByteReadChannel = ByteChannelSequentialJVM(
    ChunkBuffer(content.slice()).apply {
        commitWritten(content.remaining())
    }, autoFlush = true
).apply { close() }

/**
 * Creates buffered channel for asynchronous reading and writing of sequences of bytes.
 */
public actual fun ByteChannel(autoFlush: Boolean): ByteChannel = ByteChannelSequentialJVM(
    ChunkBuffer.Empty, autoFlush = autoFlush
)

/**
 * Creates channel for reading from the specified byte array.
 */
public actual fun ByteReadChannel(content: ByteArray, offset: Int, length: Int): ByteReadChannel {
    if (content.isEmpty() || offset >= length) return ByteReadChannel.Empty

    val buffer = ByteBuffer.wrap(content, offset, length)
    val head = ChunkBuffer(buffer).apply {
        commitWritten(length - offset)
    }
    return ByteChannelSequentialJVM(head, false).apply { close() }
}

/**
 * Creates buffered channel for asynchronous reading and writing of sequences of bytes using [close] function to close
 * channel.
 */
public fun ByteChannel(autoFlush: Boolean = false, exceptionMapper: (Throwable?) -> Throwable?): ByteChannel =
    object : ByteChannelSequentialJVM(ChunkBuffer.Empty, autoFlush = autoFlush) {
        override fun close(cause: Throwable?): Boolean {
            val mappedException = exceptionMapper(cause)
            return super.close(mappedException)
        }
    }
