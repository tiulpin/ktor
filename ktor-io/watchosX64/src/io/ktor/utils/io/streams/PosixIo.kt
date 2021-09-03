/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.utils.io.streams

import kotlinx.cinterop.*
import platform.posix.*

public actual fun fwrite(
    __ptr: CValuesRef<*>?,
    __size: _size_t,
    __nitems: _size_t,
    __stream: CValuesRef<FILE>?
): _size_t = platform.posix.fwrite(__ptr, __size.convert(), __nitems.convert(), __stream).convert()

public actual fun fread(
    __ptr: CValuesRef<*>?,
    __size: _size_t,
    __nitems: _size_t,
    __stream: CValuesRef<FILE>?
): _size_t = platform.posix.fread(__ptr, __size.convert(), __nitems.convert(), __stream).convert()

public actual val SSIZE_MAX: _ssize_t = platform.posix.SSIZE_MAX.convert()
