// ktlint-disable filename
package io.ktor.utils.io

import io.ktor.utils.io.bits.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.core.internal.*
import kotlinx.cinterop.*


/**
 * Writes as much as possible and only suspends if buffer is full
 */
public suspend fun ByteWriteChannel.writeAvailable(
    src: CPointer<ByteVar>,
    offset: Int,
    length: Int
): Int = when (this) {
    is ByteChannelNative -> writeAvailable(src, offset, length)
    else -> error("Unsupported operation for $this")
}

/**
 * Writes as much as possible and only suspends if buffer is full
 */
public suspend fun ByteWriteChannel.writeAvailable(
    src: CPointer<ByteVar>,
    offset: Long,
    length: Long
): Int = when (this) {
    is ByteChannelNative -> writeAvailable(src, offset, length)
    else -> error("Unsupported operation for $this")
}

public suspend fun ByteWriteChannel.writeFully(src: CPointer<ByteVar>, offset: Int, length: Int): Unit {
    when (this) {
        is ByteChannelNative -> writeFully(src, offset, length)
        else -> error("Unsupported operation for $this")
    }
}
public suspend fun ByteWriteChannel.writeFully(src: CPointer<ByteVar>, offset: Long, length: Long) {
    when (this) {
        is ByteChannelNative -> writeFully(src, offset, length)
        else -> error("Unsupported operation for $this")
    }
}
