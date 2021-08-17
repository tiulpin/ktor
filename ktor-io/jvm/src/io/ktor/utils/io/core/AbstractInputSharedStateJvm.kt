// ktlint-disable filename
/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.utils.io.core

import io.ktor.utils.io.core.internal.*

internal actual class InputSharedState actual constructor(
    actual var head: ChunkBuffer,
    remaining: Long
) {
    actual var tailRemaining: Long = head.next?.remainingAll() ?: 0L
}
