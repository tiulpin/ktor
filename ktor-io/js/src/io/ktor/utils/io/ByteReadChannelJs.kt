// ktlint-disable filename
package io.ktor.utils.io

import org.khronos.webgl.*

public suspend fun ByteReadChannel.readAvailable(dst: ArrayBuffer, offset: Int, length: Int): Int {
    check(this is ByteChannelJS)
    return readAvailable(dst, offset, length)
}

public suspend fun ByteReadChannel.readFully(dst: ArrayBuffer, offset: Int, length: Int) {
    check(this is ByteChannelJS)
    return readFully(dst, offset, length)
}
