// ktlint-disable filename
package io.ktor.utils.io

import kotlinx.cinterop.*

/**
 * Reads all available bytes to [dst] buffer and returns immediately or suspends if no bytes available
 * @return number of bytes were read or `-1` if the channel has been closed
 */
public suspend fun ByteReadChannel.readAvailable(dst: CPointer<ByteVar>, offset: Int, length: Int): Int {
    check(this is ByteChannelNative)
    return readAvailable(dst, offset, length)
}

/**
 * Reads all available bytes to [dst] buffer and returns immediately or suspends if no bytes available
 * @return number of bytes were read or `-1` if the channel has been closed
 */
public suspend fun ByteReadChannel.readAvailable(dst: CPointer<ByteVar>, offset: Long, length: Long): Int {
    check(this is ByteChannelNative)
    return readAvailable(dst, offset, length)
}


/**
 * Reads all [length] bytes to [dst] buffer or fails if channel has been closed.
 * Suspends if not enough bytes available.
 */
public suspend fun ByteReadChannel.readFully(dst: CPointer<ByteVar>, offset: Int, length: Int) {
    check(this is ByteChannelNative)
    readFully(dst, offset, length)
}


/**
 * Reads all [length] bytes to [dst] buffer or fails if channel has been closed.
 * Suspends if not enough bytes available.
 */
public suspend fun ByteReadChannel.readFully(dst: CPointer<ByteVar>, offset: Long, length: Long) {
    check(this is ByteChannelNative)
    readFully(dst, offset, length)
}
