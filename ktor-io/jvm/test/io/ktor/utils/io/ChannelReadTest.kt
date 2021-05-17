/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.utils.io

import kotlinx.coroutines.*
import java.nio.*
import kotlin.test.*

class ChannelReadTest {

    @Test
    fun testDelimiterWithFlush(): Unit = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        val delimiter = ByteBuffer.wrap("A".repeat(1002).encodeToByteArray())
        val destination = ByteBuffer.allocate(501)

        val chunk = "A".repeat(501).encodeToByteArray()
        channel.writeByte('F'.code.toByte())
        channel.writeFully(chunk)

        repeat(501) {
            channel.writeByte('A'.code.toByte())
        }

        channel.close()

        launch {
            assertEquals(1, channel.readUntilDelimiter(delimiter, destination))
        }
    }
}
