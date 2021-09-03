/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.utils.io.streams

// Fixme KT-48574

import kotlinx.cinterop.*
import platform.posix.*

public typealias _size_t = size_t
public typealias _ssize_t = ssize_t

public expect fun fwrite(
    __ptr: CValuesRef<*>?,
    __size: _size_t,
    __nitems: _size_t,
    __stream: CValuesRef<FILE>?
): _size_t

public expect fun fread(
    __ptr: CValuesRef<*>?,
    __size: _size_t,
    __nitems: _size_t,
    __stream: CValuesRef<FILE>?
): _size_t

public expect val SSIZE_MAX: _ssize_t
