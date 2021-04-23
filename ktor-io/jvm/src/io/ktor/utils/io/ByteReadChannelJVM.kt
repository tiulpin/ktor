package io.ktor.utils.io

import io.ktor.utils.io.bits.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.core.internal.*
import io.ktor.utils.io.internal.*
import java.nio.*

public fun <R> ByteReadChannel.lookAhead(visitor: LookAheadSession.() -> R): R = when (this) {
    is ByteBufferChannel -> lookAhead(visitor)
    is ByteChannelSequentialJVM -> lookAhead(visitor)
    else -> error("Unsupported operation on $this")
}

public suspend fun <R> ByteReadChannel.lookAheadSuspend(
    visitor: suspend LookAheadSuspendSession.() -> R
): R = when (this) {
    is ByteBufferChannel -> lookAheadSuspend(visitor)
    is ByteChannelSequentialJVM -> lookAheadSuspend(visitor)
    else -> error("Unsupported operation on $this")
}

/**
 * reads all available bytes to [dst] buffer and returns immediately or suspends if no bytes available
 * @return number of bytes were read or `-1` if the channel has been closed
 */
public suspend fun ByteReadChannel.readAvailable(dst: ByteBuffer): Int = when (this) {
    is ByteBufferChannel -> readAvailable(dst)
    is ByteChannelSequentialJVM -> readAvailable(dst)
    else -> error("Unsupported operation on $this")
}

/**
 * Invokes [block] if it is possible to read at least [min] byte
 * providing byte buffer to it so lambda can read from the buffer
 * up to [ByteBuffer.available] bytes. If there are no [min] bytes available then the invocation returns 0.
 *
 * Warning: it is not guaranteed that all of available bytes will be represented as a single byte buffer
 * eg: it could be 4 bytes available for read but the provided byte buffer could have only 2 available bytes:
 * in this case you have to invoke read again (with decreased [min] accordingly).
 *
 * @param min amount of bytes available for read, should be positive
 * @param block to be invoked when at least [min] bytes available
 *
 * @return number of consumed bytes or -1 if the block wasn't executed.
 */
public fun ByteReadChannel.readAvailable(min: Int = 1, block: (ByteBuffer) -> Unit): Int = when (this) {
    is ByteBufferChannel -> readAvailable(min, block)
    is ByteChannelSequentialJVM -> readAvailable(min, block)
    else -> error("Unsupported operation on $this")
}

/**
 * Reads all [length] bytes to [dst] buffer or fails if channel has been closed.
 * Suspends if not enough bytes available.
 */
public suspend fun ByteReadChannel.readFully(dst: ByteBuffer): Int = when (this) {
    is ByteBufferChannel -> readFully(dst)
    is ByteChannelSequentialJVM -> readFully(dst)
    else -> error("Unsupported operation on $this")
}

/**
 * Invokes [consumer] when it will be possible to read at least [min] bytes
 * providing byte buffer to it so lambda can read from the buffer
 * up to [ByteBuffer.remaining] bytes. If there are no [min] bytes available then the invocation could
 * suspend until the requirement will be met.
 *
 * If [min] is zero then the invocation will suspend until at least one byte available.
 *
 * Warning: it is not guaranteed that all of remaining bytes will be represented as a single byte buffer
 * eg: it could be 4 bytes available for read but the provided byte buffer could have only 2 remaining bytes:
 * in this case you have to invoke read again (with decreased [min] accordingly).
 *
 * It will fail with [EOFException] if not enough bytes ([availableForRead] < [min]) available
 * in the channel after it is closed.
 *
 * [consumer] lambda should modify buffer's position accordingly. It also could temporarily modify limit however
 * it should restore it before return. It is not recommended to access any bytes of the buffer outside of the
 * provided byte range [position(); limit()) as there could be any garbage or incomplete data.
 *
 * @param min amount of bytes available for read, should be positive or zero
 * @param consumer to be invoked when at least [min] bytes available for read
 */
public suspend fun ByteReadChannel.read(min: Int = 1, consumer: (ByteBuffer) -> Unit) {
    when (this) {
        is ByteBufferChannel -> read(min, consumer)
        is ByteChannelSequentialJVM -> read(min, consumer)
        else -> error("Unsupported operation on $this")
    }
}

public actual suspend fun ByteReadChannel.joinTo(dst: ByteWriteChannel, closeOnEnd: Boolean) {
    require(dst !== this)

    if (this is ByteBufferChannel && dst is ByteBufferChannel) {
        return dst.joinFrom(this, closeOnEnd)
    }

    return joinToImplSuspend(dst, closeOnEnd)
}

private suspend fun ByteReadChannel.joinToImplSuspend(dst: ByteWriteChannel, close: Boolean) {
    copyTo(dst, Long.MAX_VALUE)
    if (close) {
        dst.close()
    } else {
        dst.flush()
    }
}

/**
 * Reads up to [limit] bytes from receiver channel and writes them to [dst] channel.
 * Closes [dst] channel if fails to read or write with cause exception.
 * @return a number of copied bytes
 */
public actual suspend fun ByteReadChannel.copyTo(dst: ByteWriteChannel, limit: Long): Long {
    require(this !== dst)

    if (limit == 0L) {
        return 0L
    }

    if (this is ByteBufferChannel && dst is ByteBufferChannel) {
        return dst.copyDirect(this, limit, null)
    } else if (this is ByteChannelSequentialBase && dst is ByteChannelSequentialBase) {
        return copyToSequentialImpl(dst, Long.MAX_VALUE) // more specialized extension function
    }

    return copyToImpl(dst, limit)
}

private suspend fun ByteReadChannel.copyToImpl(dst: ByteWriteChannel, limit: Long): Long {
    val buffer = ChunkBuffer.Pool.borrow()
    val dstNeedsFlush = !dst.autoFlush

    try {
        var copied = 0L

        while (true) {
            val remaining = limit - copied
            if (remaining == 0L) break
            buffer.resetForWrite(minOf(buffer.capacity.toLong(), remaining).toInt())

            val size = readAvailable(buffer)
            if (size == -1) break

            dst.writeFully(buffer)
            copied += size

            if (dstNeedsFlush && availableForRead == 0) {
                dst.flush()
            }
        }
        return copied
    } catch (t: Throwable) {
        dst.close(t)
        throw t
    } finally {
        buffer.release(ChunkBuffer.Pool)
    }
}
