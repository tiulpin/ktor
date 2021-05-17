package io.ktor.utils.io.internal

import kotlin.coroutines.*
import kotlin.coroutines.cancellation.*

internal class CancelledSlot(val cause: Throwable?) : Continuation<Unit> {
    override val context: CoroutineContext
        get() = EmptyCoroutineContext

    override fun resumeWith(result: Result<Unit>) {
        cause?.let { throw it }
    }
}
