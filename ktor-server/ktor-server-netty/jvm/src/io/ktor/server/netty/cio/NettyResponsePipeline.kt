/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.server.netty.cio

import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.netty.http2.NettyHttp2ApplicationResponse
import io.ktor.util.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.netty.channel.*
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http2.*
import kotlinx.coroutines.*
import java.io.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.*

private const val UNFLUSHED_LIMIT = 65536

@OptIn(InternalAPI::class, EngineAPI::class, DelicateCoroutinesApi::class)
internal class NettyResponsePipeline constructor(
    private val context: ChannelHandlerContext,
    override val coroutineContext: CoroutineContext
) : CoroutineScope {
    private var currentResponse: NettyApplicationCall? = null

    fun processResponse(call: NettyApplicationCall) {
        val current = currentResponse
        if (current == null) {
            currentResponse = call
        } else {
            current.scheduleNext(call)
            return
        }

        startResponseProcessing()
    }

    private fun startResponseProcessing() {
        launch(NettyDispatcher + NettyDispatcher.CurrentContext(context)) {
            while (true) {
                val call = currentResponse ?: break
                processElement(call)
                currentResponse = currentResponse?.next
            }
        }
    }

    private suspend fun processElement(call: NettyApplicationCall) {
        try {
            processCall(call)
        } catch (actualException: Throwable) {
            processCallFailed(call, actualException)
        } finally {
            call.responseWriteJob.cancel()
        }
    }

    private fun processCallFailed(call: NettyApplicationCall, actualException: Throwable) {
        val t = when {
            actualException is IOException && actualException !is ChannelIOException ->
                ChannelWriteException(exception = actualException)
            else -> actualException
        }

        call.response.responseChannel.cancel(t)
        call.responseWriteJob.cancel()
        call.response.cancel()
        call.dispose()
    }

    private fun processUpgrade(responseMessage: Any): ChannelFuture {
        val future = context.write(responseMessage)
        context.pipeline().replace(HttpServerCodec::class.java, "direct-encoder", NettyDirectEncoder())
        context.flush()
        return future
    }

    private fun hasNextResponseMessage(): Boolean = currentResponse?.next != null

    companion object {
        val responses = AtomicLong()
        val flushCount = AtomicLong()
        val flushAvoided = AtomicLong()
        val flushNotAvoided = AtomicLong()
        init {
            GlobalScope.launch {
                while (true) {
                    delay(3000)

                    val currentFlushCount = flushCount.getAndSet(0)
                    val currentResponses = responses.getAndSet(0).toDouble()
                    val currentFlushAvoided = flushAvoided.getAndSet(0)
                    val currentFlushNotAvoided = flushNotAvoided.getAndSet(0)
                    if (currentFlushCount == 0L) {
                        println("No flushes for $currentResponses count, avoided: $currentFlushAvoided, not $currentFlushNotAvoided")
                    } else {
                        val flushSize = currentResponses / currentFlushCount
                        println("Average flush size: $flushSize, avoided $currentFlushAvoided, not $currentFlushNotAvoided")
                    }
                }
            }
        }
    }

    private suspend fun finishCall(call: NettyApplicationCall, lastMessage: Any?, lastFuture: ChannelFuture) {
        val shouldClose = !call.request.keepAlive

        val future = if (lastMessage != null) {
            context.write(lastMessage)
        } else null

        future?.suspendWriteAwait()

        if (shouldClose) {
            flushCount.incrementAndGet()
            context.flush()
            lastFuture.suspendWriteAwait()
            context.close()
            return
        }

        if (hasNextResponseMessage()) return
        context.channel().eventLoop().execute {
            if (!hasNextResponseMessage()) {
                flushNotAvoided.incrementAndGet()
                flushCount.incrementAndGet()
                context.flush()
            } else {
                flushAvoided.incrementAndGet()
            }
        }
    }

    private suspend fun processCall(call: NettyApplicationCall) {
        val responseMessage = call.response.responseMessage.await()
        val response = call.response

        val requestMessageFuture = if (response.isUpgradeResponse()) {
            processUpgrade(responseMessage)
        } else {
            context.write(responseMessage)
        }

        if (responseMessage is FullHttpResponse) {
            return finishCall(call, null, requestMessageFuture)
        } else if (responseMessage is Http2HeadersFrame && responseMessage.isEndStream) {
            return finishCall(call, null, requestMessageFuture)
        }

        val responseChannel = response.responseChannel
        val knownSize = when {
            responseChannel === ByteReadChannel.Empty -> 0
            responseMessage is HttpResponse -> responseMessage.headers().getInt("Content-Length", -1)
            responseMessage is Http2HeadersFrame -> responseMessage.headers().getInt("content-length", -1)
            else -> -1
        }

        when (knownSize) {
            0 -> processEmpty(call, requestMessageFuture)
            in 1..65536 -> processSmallContent(call, response, knownSize)
            -1 -> processBodyFlusher(call, response, requestMessageFuture)
            else -> processBodyGeneral(call, response, requestMessageFuture)
        }
    }

    private fun trailerMessage(response: NettyApplicationResponse): Any? =
        if (response is NettyHttp2ApplicationResponse) {
            response.trailerMessage()
        } else {
            null
        }

    private suspend fun processEmpty(call: NettyApplicationCall, lastFuture: ChannelFuture) {
        return finishCall(call, LastHttpContent.EMPTY_LAST_CONTENT, lastFuture)
    }

    private suspend fun processSmallContent(call: NettyApplicationCall, response: NettyApplicationResponse, size: Int) {
        val buffer = context.alloc().buffer(size)
        val channel = response.responseChannel

        val start = buffer.writerIndex()
        channel.readFully(buffer.nioBuffer(start, buffer.writableBytes()))
        buffer.writerIndex(start + size)

        val future = context.write(DefaultHttpContent(buffer))

        val lastMessage = trailerMessage(response) ?: LastHttpContent.EMPTY_LAST_CONTENT
        finishCall(call, lastMessage, future)
    }

    @OptIn(ExperimentalIoApi::class)
    private suspend fun processBodyGeneral(
        call: NettyApplicationCall,
        response: NettyApplicationResponse,
        requestMessageFuture: ChannelFuture
    ) {
        val channel = response.responseChannel

        var unflushedBytes = 0
        var lastFuture: ChannelFuture = requestMessageFuture

        @Suppress("DEPRECATION")
        channel.lookAheadSuspend {
            while (true) {
                val buffer = request(0, 1)
                if (buffer == null) {
                    if (!awaitAtLeast(1)) break
                    continue
                }

                val rc = buffer.remaining()
                val buf = context.alloc().buffer(rc)
                val idx = buf.writerIndex()
                buf.setBytes(idx, buffer)
                buf.writerIndex(idx + rc)

                consumed(rc)
                unflushedBytes += rc

                val message = DefaultHttpContent(buf)

                if (unflushedBytes >= UNFLUSHED_LIMIT) {
                    val future = context.writeAndFlush(message)
                    lastFuture = future
                    future.suspendAwait()
                    unflushedBytes = 0
                } else {
                    lastFuture = context.write(message)
                }
            }
        }

        val lastMessage = trailerMessage(response) ?: LastHttpContent.EMPTY_LAST_CONTENT
        finishCall(call, lastMessage, lastFuture)
    }

    @OptIn(ExperimentalIoApi::class)
    private suspend fun processBodyFlusher(
        call: NettyApplicationCall,
        response: NettyApplicationResponse,
        requestMessageFuture: ChannelFuture
    ) {
        val channel = response.responseChannel

        var unflushedBytes = 0
        var lastFuture: ChannelFuture = requestMessageFuture

        @Suppress("DEPRECATION")
        channel.lookAheadSuspend {
            while (true) {
                val buffer = request(0, 1)
                if (buffer == null) {
                    if (!awaitAtLeast(1)) break
                    continue
                }

                val rc = buffer.remaining()
                val buf = context.alloc().buffer(rc)
                val idx = buf.writerIndex()
                buf.setBytes(idx, buffer)
                buf.writerIndex(idx + rc)

                consumed(rc)
                unflushedBytes += rc

                val message = DefaultHttpContent(buf)

                if (unflushedBytes >= UNFLUSHED_LIMIT || channel.availableForRead == 0) {
                    val future = context.writeAndFlush(message)
                    lastFuture = future
                    future.suspendAwait()
                    unflushedBytes = 0
                } else {
                    lastFuture = context.write(message)
                }
            }
        }

        val lastMessage = trailerMessage(response) ?: LastHttpContent.EMPTY_LAST_CONTENT
        finishCall(call, lastMessage, lastFuture)
    }
}

@OptIn(InternalAPI::class)
private fun NettyApplicationResponse.isUpgradeResponse() =
    status()?.value == HttpStatusCode.SwitchingProtocols.value
