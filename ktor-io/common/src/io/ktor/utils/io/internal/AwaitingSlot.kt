/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.utils.io.internal

import io.ktor.utils.io.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

/**
 * Exclusive slot for waiting.
 * Only one waiter allowed.
 */
internal class AwaitingSlot {
    private val suspension: AtomicRef<Continuation<Unit>?> = atomic(null)

    init {
        makeShared()
    }

    /**
     * Wait for other [sleep] or resume.
     */
    suspend fun sleep(condition: () -> Boolean) {
        if (trySuspend(condition)) {
            return
        }

        resume()
    }

    /**
     * Resume waiter.
     */
    fun resume() {
        val value = suspension.value
        if (value is CancelledSlot) {
            value.resume(Unit)
            return
        }

        suspension.getAndSet(null)?.resume(Unit)
    }

    /**
     * Cancel waiter.
     */
    fun cancel(cause: Throwable?) {
        val slot = CancelledSlot(cause)
        while (true) {
            val current = suspension.value
            if (current is CancelledSlot) {
                current.resume(Unit)
                break
            }

            if (!suspension.compareAndSet(current, slot)) continue
            current ?: break

            if (cause != null) {
                current.resumeWithException(cause)
            } else {
                current.resume(Unit)
            }

            break
        }
    }

    private suspend fun trySuspend(condition: () -> Boolean): Boolean {
        var suspended = false

        val current = suspension.value
        if (current is CancelledSlot) {
            current.resume(Unit)
            return false
        }

        suspendCancellableCoroutine<Unit> {
            if (!suspension.compareAndSet(null, it)) {
                it.resume(Unit)
                return@suspendCancellableCoroutine
            }

            if (!condition() &&  suspension.compareAndSet(it, null)) {
                it.resume(Unit)
                return@suspendCancellableCoroutine
            }

            suspended = true
        }


        return suspended
    }
}
