// ktlint-disable filename
/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.utils.io.core

import io.ktor.utils.io.core.internal.*
import kotlinx.atomicfu.*

internal actual class InputSharedState actual constructor(
    head: ChunkBuffer,
    remaining: Long
) {
    private val _head: AtomicRef<ChunkBuffer> = atomic(head)
    private val _tailRemaining: AtomicLong = atomic(head.next?.remainingAll() ?: 0L)

    actual var head: ChunkBuffer
        get() = _head.value
        set(value) {
            _head.value = value
        }

    actual var tailRemaining: Long
        get() = _tailRemaining.value
        set(value) {
            _tailRemaining.value = value
        }
}
