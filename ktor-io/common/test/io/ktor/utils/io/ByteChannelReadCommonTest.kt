/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.utils.io

import io.ktor.test.dispatcher.*
import kotlinx.coroutines.*
import kotlin.test.*

class ByteChannelReadCommonTest {

    @Test
    fun testReadUtf8LineTo(): Unit = testSuspend {
        val channel = ByteChannel()
        channel.writeStringUtf8("Line 1\r\n")
        channel.writeStringUtf8("Line 2\r\n")
        channel.writeStringUtf8("Line 3\r\n")
        channel.flush()

        val builder = StringBuilder()

        channel.readUTF8LineTo(builder)
        assertEquals("Line 1", builder.toString())
        assertEquals(channel.availableForRead, 16)
        assertEquals(channel.totalBytesRead, 8)

        channel.readUTF8LineTo(builder)
        assertEquals("Line 1Line 2", builder.toString())
        assertEquals(channel.availableForRead, 8)
        assertEquals(channel.totalBytesRead, 16)

        channel.readUTF8LineTo(builder)
        assertEquals("Line 1Line 2Line 3", builder.toString())
        assertEquals(channel.availableForRead, 0)
        assertEquals(channel.totalBytesRead, 24)
    }

    @Test
    fun testEmptyReadSuspends() = testSuspend {
        val channel = ByteChannel()

        val job = launch(Dispatchers.Unconfined) {
            val array = ByteArray(4096)
            channel.readAvailable(array)
        }

        job.cancel()
    }
}
