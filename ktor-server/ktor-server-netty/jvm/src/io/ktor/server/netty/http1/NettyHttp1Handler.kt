/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.netty.http1

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.netty.cio.*
import io.ktor.util.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import io.netty.buffer.*
import io.netty.channel.*
import io.netty.handler.codec.http.*
import io.netty.util.concurrent.*
import kotlinx.coroutines.*
import java.io.*
import kotlin.coroutines.*

internal class NettyHttp1Handler(
    private val enginePipeline: EnginePipeline,
    private val environment: ApplicationEngineEnvironment,
    private val callEventGroup: EventExecutorGroup,
    private val engineContext: CoroutineContext,
    private val userContext: CoroutineContext
) : ChannelInboundHandlerAdapter(), CoroutineScope {
    private val handlerJob = CompletableDeferred<Nothing>()
    override val coroutineContext: CoroutineContext get() = handlerJob

    lateinit var responseWriter: NettyResponsePipeline
    private var currentRequest: ByteWriteChannel? = null

    @OptIn(InternalAPI::class)
    override fun channelActive(ctx: ChannelHandlerContext) {
        responseWriter = NettyResponsePipeline(ctx, coroutineContext)

        ctx.pipeline().apply {
            addLast(callEventGroup, NettyApplicationCallHandler(userContext, enginePipeline, environment.log))
        }

        ctx.fireChannelActive()
    }

    override fun channelRead(context: ChannelHandlerContext, message: Any) {
        responseWriter.startReading()
        when (message) {
            is HttpRequest -> handleRequest(context, message)
            is HttpContent -> handleContent(context, message)
            is ByteBuf -> pipeBuffer(context, message)
            else -> {
                context.fireChannelRead(message)
            }
        }
    }

    override fun channelReadComplete(context: ChannelHandlerContext?) {
        responseWriter.stopReading()
        super.channelReadComplete(context)
    }

    @OptIn(EngineAPI::class)
    private fun handleRequest(context: ChannelHandlerContext, message: HttpRequest) {
        val call = handleIncomingCall(context, message)

        if (message is HttpContent) {
            handleContent(context, message)
        }

        context.fireChannelRead(call)

        responseWriter.processResponse(call)
    }

    private fun handleIncomingCall(context: ChannelHandlerContext, message: HttpRequest): NettyHttp1ApplicationCall {
        val requestBodyChannel: ByteChannel? = when {
            message is LastHttpContent && !message.content().isReadable -> null
            message.method() === HttpMethod.GET &&
                !HttpUtil.isContentLengthSet(message) && !HttpUtil.isTransferEncodingChunked(message) -> {
                null
            }
            else -> ByteChannel()
        }

        if (requestBodyChannel != null) {
            currentRequest = requestBodyChannel
        }

        return NettyHttp1ApplicationCall(
            environment.application,
            context,
            message,
            requestBodyChannel,
            engineContext,
            userContext
        )
    }

    private fun handleContent(context: ChannelHandlerContext, message: HttpContent) {
        try {
            val buffer = message.content()
            pipeBuffer(context, buffer)

            if (message is LastHttpContent) {
                currentRequest?.close()
            }
        } finally {
            message.release()
        }

    }

    private fun pipeBuffer(context: ChannelHandlerContext, message: ByteBuf) {
        if (message.readableBytes() == 0) return

        currentRequest!!.writeByteBuf(message)
        if (currentRequest!!.availableForWrite == 0) {
            context.pause()
        } else {
            context.resume()
        }
    }

    override fun channelInactive(context: ChannelHandlerContext) {
        context.pipeline().remove(NettyApplicationCallHandler::class.java)
        context.fireChannelInactive()
    }

    override fun exceptionCaught(context: ChannelHandlerContext, cause: Throwable) {
        if (cause is IOException || cause is ChannelIOException) {
            environment.application.log.debug("I/O operation failed", cause)
            handlerJob.cancel()
        } else {
            handlerJob.completeExceptionally(cause)
        }

        context.close()
    }
}

internal fun ChannelHandlerContext.pause() {
    channel().config().isAutoRead = false
}

internal fun ChannelHandlerContext.resume() {
    channel().config().isAutoRead = true
}
