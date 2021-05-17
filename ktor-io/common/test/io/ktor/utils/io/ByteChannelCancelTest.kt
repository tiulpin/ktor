package io.ktor.utils.io

import io.ktor.test.dispatcher.*
import io.ktor.utils.io.errors.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class ByteChannelCancelTest {

    @Test
    fun testCopyAndCloseSourceCancel() = testSuspend {
        val source = ByteChannel()
        val destination = ByteChannel()
        val origin = IOException("FOO")

        launch(Dispatchers.Unconfined) {
            try {
                source.copyAndClose(destination)
            } catch (cause: Throwable) {
                assertIs<IOException>(cause)
            }
        }

        source.close(origin)
        assertIs<IOException>(destination.closedCause)
    }

    @Test
    fun testCopyAndCloseDestinationCancel() = testSuspend {
        val source = ByteChannel()
        val destination = ByteChannel()
        val origin = IOException("FOO")

        launch(Dispatchers.Unconfined) {
            try {
                source.copyAndClose(destination)
            } catch (cause: Throwable) {
                assertIs<IOException>(cause)
            }
        }

        destination.cancel(origin)
        assertIs<IOException>(source.closedCause)
    }
}
