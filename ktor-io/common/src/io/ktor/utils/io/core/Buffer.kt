@file:Suppress("NOTHING_TO_INLINE")

package io.ktor.utils.io.core

import io.ktor.utils.io.bits.*
import io.ktor.utils.io.core.internal.*
import io.ktor.utils.io.errors.EOFException
import kotlin.contracts.*

/**
 * Represents a buffer with read and write positions.
 *
 * Concurrent unsafe: the same memory could be shared between different instances of [Buffer] however you can't
 * read/write using the same [Buffer] instance from different threads.
 */
public open class Buffer(public val memory: Memory) {
    private val bufferState: BufferSharedState = BufferSharedState(memory.size32)

    /**
     * Current read position. It is always non-negative and will never run ahead of the [writePosition].
     * It is usually greater or equal to [startGap] reservation.
     * This position is affected by [discard], [rewind], [resetForRead], [resetForWrite], [reserveStartGap]
     * and [reserveEndGap].
     */
    public var readPosition: Int
        get() = bufferState.readPosition
        private set(value) {
            bufferState.readPosition = value
        }

    /**
     * Current write position. It is always non-negative and will never run ahead of the [limit].
     * It is always greater or equal to the [readPosition].
     * * This position is affected by [resetForRead], [resetForWrite], [reserveStartGap]
     * and [reserveEndGap].
     */
    public var writePosition: Int
        get() = bufferState.writePosition
        private set(value) {
            bufferState.writePosition = value
        }

    /**
     * Write position limit. No bytes could be written ahead of this limit. When the limit is less than the [capacity]
     * then this means that there are reserved bytes in the end ([endGap]). Such a reserved space in the end could be used
     * to write size, hash and so on. Also it is useful when several buffers are connected into a chain and some
     * primitive value (e.g. `kotlin.Int`) is separated into two chunks so bytes from the second chain could be copied
     * to the reserved space of the first chunk and then the whole value could be read at once.
     */
    public var limit: Int
        get() = bufferState.limit
        private set(value) {
            bufferState.limit = value
        }

    /**
     * Buffer's capacity (including reserved [startGap] and [endGap]. Value for released buffer is unspecified.
     */
    public val capacity: Int = memory.size32

    /**
     * Number of bytes available for reading.
     */
    public inline val readRemaining: Int get() = writePosition - readPosition

    /**
     * Size of the free space available for writing in bytes.
     */
    public inline val writeRemaining: Int get() = limit - writePosition

    /**
     * User data: could be a session, connection or anything useful
     */
    @Deprecated(
        "Will be removed. Inherit Buffer and add required fields instead.",
        level = DeprecationLevel.ERROR
    )
    public var attachment: Any?
        get() = bufferState.attachment
        set(value) {
            bufferState.attachment = value
        }

    /**
     * Discard [count] readable bytes.
     *
     * @throws EOFException if [count] is bigger than available bytes.
     */
    public fun discardExact(count: Int = readRemaining) {
        if (count == 0) return

        val newReadPosition = readPosition + count
        if (count < 0 || newReadPosition > writePosition) {
            discardFailed(count, readRemaining)
        }
        readPosition = newReadPosition
    }

    public fun commitWritten(count: Int) {
        val newWritePosition = writePosition + count
        if (count < 0 || newWritePosition > limit) {
            commitWrittenFailed(count, writeRemaining)
        }
        writePosition = newWritePosition
    }

    /**
     * @return `true` if there is free space
     */
    @PublishedApi
    internal fun commitWrittenUntilIndex(position: Int): Boolean {
        val limit = limit
        if (position < writePosition) {
            commitWrittenFailed(position - writePosition, writeRemaining)
        }
        if (position >= limit) {
            if (position == limit) {
                writePosition = position
                return false
            }
            commitWrittenFailed(position - writePosition, writeRemaining)
        }

        writePosition = position
        return true
    }

    internal fun discardUntilIndex(position: Int) {
        if (position < 0 || position > writePosition) {
            discardFailed(position - readPosition, readRemaining)
        }

        if (readPosition != position) {
            readPosition = position
        }
    }

    /**
     * Rewind [readPosition] backward to make [count] bytes available for reading again.
     * @throws IllegalArgumentException when [count] is too big and not enough bytes available before the [readPosition]
     */
    public fun rewind(count: Int = readPosition) {
        val newReadPosition = readPosition - count
        readPosition = newReadPosition
    }

    /**
     * Marks the whole buffer available for read and no for write
     */
    public fun resetForRead() {
        readPosition = 0

        val capacity = capacity
        writePosition = capacity
    }

    /**
     * Marks all capacity writable except the start gap reserved before. The end gap reservation is discarded.
     */
    public fun resetForWrite() {
        resetForWrite(capacity)
    }

    /**
     * Marks up to [limit] bytes of the buffer available for write and no bytes for read.
     * It does respect [startGap] already reserved. All extra bytes after the specified [limit]
     * are considered as [endGap].
     */
    public fun resetForWrite(limit: Int) {
        readPosition = 0
        writePosition = 0
        this.limit = limit
    }

    protected open fun duplicateTo(copy: Buffer) {
        copy.limit = limit
        copy.readPosition = readPosition
        copy.writePosition = writePosition
    }

    /**
     * Create a new [Buffer] instance pointing to the same memory and having the same positions.
     */
    public open fun duplicate(): Buffer = Buffer(memory).apply {
        duplicateTo(this)
    }

    /**
     * Peek the next unsigned byte or return `-1` if no more bytes available for reading. No bytes will be marked
     * as consumed in any case.
     * @return an unsigned byte or `-1` if not even a byte is available for reading.
     * @see tryReadByte
     * @see readByte
     */
    public fun tryPeekByte(): Int {
        val readPosition = readPosition
        if (readPosition == writePosition) return -1
        return memory[readPosition].toInt() and 0xff
    }

    /**
     * Read the next unsigned byte or return `-1` if no more bytes available for reading. The returned byte is marked
     * as consumed.
     * @return an unsigned byte or `-1` if not even a byte is available for reading.
     * @see tryPeekByte
     * @see readByte
     */
    public fun tryReadByte(): Int {
        val readPosition = readPosition
        if (readPosition == writePosition) return -1
        this.readPosition = readPosition + 1
        return memory[readPosition].toInt() and 0xff
    }

    /**
     * Read the next byte or fail with [EOFException] if it's not available. The returned byte is marked
     * as consumed.
     * @throws EOFException when not even a byte is available for reading.
     * @see tryPeekByte
     * @see tryReadByte
     */
    public fun readByte(): Byte {
        val readPosition = readPosition
        if (readPosition == writePosition) {
            throw EOFException("No readable bytes available.")
        }
        this.readPosition = readPosition + 1
        return memory[readPosition]
    }

    /**
     * Write a byte [value] at [writePosition] (incremented when written successfully).
     * @throws InsufficientSpaceException when no free space in the buffer.
     */
    public fun writeByte(value: Byte) {
        val writePosition = writePosition
        if (writePosition == limit) {
            throw InsufficientSpaceException("No free space in the buffer to write a byte")
        }
        memory[writePosition] = value
        this.writePosition = writePosition + 1
    }

    /**
     * Clear buffer's state: read/write positions, gaps and so on. Byte content is not cleaned-up.
     */
    public open fun reset() {
        resetForWrite()
    }

    override fun toString(): String {
        return "Buffer[${hashCode()}]($readRemaining used, $writeRemaining free)"
    }

    public companion object {
        /**
         * The empty buffer singleton: it has zero capacity for read and write.
         */
        public val Empty: Buffer get() = ChunkBuffer.Empty
    }
}

/**
 * @return `true` if there are available bytes to be read
 */
public inline fun Buffer.canRead(): Boolean = writePosition > readPosition

/**
 * @return `true` if there is free room to for write
 */
public inline fun Buffer.canWrite(): Boolean = limit > writePosition

/**
 * Apply [block] of code with buffer's memory providing read range indices. The returned value of [block] lambda should
 * return number of bytes to be marked as consumed.
 * No read/write functions on this buffer should be called inside of [block] otherwise an undefined behaviour may occur
 * including data damage.
 */
@OptIn(ExperimentalContracts::class)
public inline fun Buffer.read(block: (memory: Memory, start: Int, endExclusive: Int) -> Int): Int {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    val rc = block(memory, readPosition, writePosition)
    discardExact(rc)
    return rc
}

/**
 * Apply [block] of code with buffer's memory providing write range indices. The returned value of [block] lambda should
 * return number of bytes were written.
 * o read/write functions on this buffer should be called inside of [block] otherwise an undefined behaviour may occur
 * including data damage.
 */
@OptIn(ExperimentalContracts::class)
public inline fun Buffer.write(block: (memory: Memory, start: Int, endExclusive: Int) -> Int): Int {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    val rc = block(memory, writePosition, limit)
    commitWritten(rc)
    return rc
}

internal fun discardFailed(count: Int, readRemaining: Int): Nothing {
    throw EOFException("Unable to discard $count bytes: only $readRemaining available for reading")
}

internal fun commitWrittenFailed(count: Int, writeRemaining: Int): Nothing {
    throw EOFException("Unable to discard $count bytes: only $writeRemaining available for writing")
}

public class InsufficientSpaceException(message: String = "Not enough free space") : Exception(message) {
    public constructor(
        size: Int,
        availableSpace: Int
    ) : this("Not enough free space to write $size bytes, available $availableSpace bytes.")

    public constructor(
        name: String,
        size: Int,
        availableSpace: Int
    ) : this("Not enough free space to write $name of $size bytes, available $availableSpace bytes.")

    public constructor(
        size: Long,
        availableSpace: Long
    ) : this("Not enough free space to write $size bytes, available $availableSpace bytes.")
}
