/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.utils.io

import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import org.junit.*

class BlockingAdapterTest {

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun testBlockingAdapter() = repeat(1000) {
        val content = GlobalScope.writer {
            val text = "OK:42\n"
            val data = text.encodeToByteArray()
            for (byte in data) {
                channel.writeByte(byte)
                channel.flush()
            }
        }.channel

        content.toInputStream().reader().use { reader ->
            val firstByte = reader.read()
            if (firstByte == -1) {
                Assert.fail("Premature end of response stream at iteration $42")
            } else {
                Assert.assertEquals('O', firstByte.toChar())
                Assert.assertEquals("K:42\n", reader.readText())
            }
        }
    }
}
