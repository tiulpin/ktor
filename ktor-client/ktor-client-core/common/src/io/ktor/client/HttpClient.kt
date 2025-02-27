/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.client

import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.utils.*
import io.ktor.events.*
import io.ktor.util.*
import io.ktor.utils.io.core.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

/**
 * Constructs an asynchronous [HttpClient] using optional [block] for configuring this client.
 *
 * The [HttpClientEngine] is selected from the dependencies.
 * https://ktor.io/docs/http-client-engines.html
 */
@KtorDsl
public expect fun HttpClient(
    block: HttpClientConfig<*>.() -> Unit = {}
): HttpClient

/**
 * Constructs an asynchronous [HttpClient] using the specified [engineFactory]
 * and an optional [block] for configuring this client.
 */
@KtorDsl
public fun <T : HttpClientEngineConfig> HttpClient(
    engineFactory: HttpClientEngineFactory<T>,
    block: HttpClientConfig<T>.() -> Unit = {}
): HttpClient {
    val config: HttpClientConfig<T> = HttpClientConfig<T>().apply(block)
    val engine = engineFactory.create(config.engineConfig)
    val client = HttpClient(engine, config, manageEngine = true)

    // If the engine was created using factory Ktor is responsible for its lifecycle management. Otherwise user has to
    // close engine by themself.
    client.coroutineContext[Job]!!.invokeOnCompletion {
        engine.close()
    }

    return client
}

/**
 * Constructs an asynchronous [HttpClient] using the specified [engine]
 * and a [block] for configuring this client.
 */
@KtorDsl
public fun HttpClient(
    engine: HttpClientEngine,
    block: HttpClientConfig<*>.() -> Unit
): HttpClient = HttpClient(engine, HttpClientConfig<HttpClientEngineConfig>().apply(block), manageEngine = false)

/**
 * Asynchronous client to perform HTTP requests.
 *
 * This is a generic implementation that uses a specific engine [HttpClientEngine].
 * @property engine: [HttpClientEngine] for executing requests.
 */
@OptIn(InternalAPI::class)
public class HttpClient(
    public val engine: HttpClientEngine,
    private val userConfig: HttpClientConfig<out HttpClientEngineConfig> = HttpClientConfig()
) : CoroutineScope, Closeable {
    private var manageEngine: Boolean = false

    internal constructor(
        engine: HttpClientEngine,
        userConfig: HttpClientConfig<out HttpClientEngineConfig>,
        manageEngine: Boolean
    ) : this(engine, userConfig) {
        this.manageEngine = manageEngine
    }

    private val closed = atomic(false)

    private val clientJob: CompletableJob = Job(engine.coroutineContext[Job])

    public override val coroutineContext: CoroutineContext = engine.coroutineContext + clientJob

    /**
     * Pipeline used for processing all the requests sent by this client.
     */
    public val requestPipeline: HttpRequestPipeline = HttpRequestPipeline(userConfig.developmentMode)

    /**
     * Pipeline used for processing all the responses sent by the server.
     */
    public val responsePipeline: HttpResponsePipeline = HttpResponsePipeline(userConfig.developmentMode)

    /**
     * Pipeline used for sending the request.
     */
    public val sendPipeline: HttpSendPipeline = HttpSendPipeline(userConfig.developmentMode)

    /**
     * Pipeline used for receiving request.
     */
    public val receivePipeline: HttpReceivePipeline = HttpReceivePipeline(userConfig.developmentMode)

    /**
     * Typed attributes used as a lightweight container for this client.
     */
    public val attributes: Attributes = Attributes(concurrent = true)

    /**
     * Client engine config.
     */
    public val engineConfig: HttpClientEngineConfig = engine.config

    /**
     * Provides events on client lifecycle
     */
    public val monitor: Events = Events()

    internal val config = HttpClientConfig<HttpClientEngineConfig>()

    init {
        if (manageEngine) {
            clientJob.invokeOnCompletion {
                if (it != null) {
                    engine.cancel()
                }
            }
        }

        engine.install(this)

        sendPipeline.intercept(HttpSendPipeline.Receive) { call ->
            check(call is HttpClientCall) { "Error: HttpClientCall expected, but found $call(${call::class})." }
            val response = receivePipeline.execute(Unit, call.response)
            call.setResponse(response)
            proceedWith(call)
        }

        with(userConfig) {
            config.install(HttpRequestLifecycle)
            config.install(BodyProgress)

            if (useDefaultTransformers) {
                config.install("DefaultTransformers") { defaultTransformers() }
            }

            config.install(HttpSend)
            config.install(HttpCallValidator)

            if (followRedirects) {
                config.install(HttpRedirect)
            }

            config += this

            if (useDefaultTransformers) {
                config.install(HttpPlainText)
            }

            config.addDefaultResponseValidation()

            config.install(this@HttpClient)
        }

        responsePipeline.intercept(HttpResponsePipeline.Receive) {
            try {
                proceed()
            } catch (cause: Throwable) {
                monitor.raise(HttpResponseReceiveFailed, HttpResponseReceiveFail(context.response, cause))
                throw cause
            }
        }
    }

    /**
     * Creates a new [HttpClientCall] from a request [builder].
     */
    internal suspend fun execute(builder: HttpRequestBuilder): HttpClientCall {
        monitor.raise(HttpRequestCreated, builder)

        return requestPipeline.execute(builder, builder.body) as HttpClientCall
    }

    /**
     * Check if the specified [capability] is supported by this client.
     */
    public fun isSupported(capability: HttpClientEngineCapability<*>): Boolean {
        return engine.supportedCapabilities.contains(capability)
    }

    /**
     * Returns a new [HttpClient] copying this client configuration,
     * and additionally configured by the [block] parameter.
     */
    public fun config(block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(
        engine,
        HttpClientConfig<HttpClientEngineConfig>().apply {
            plusAssign(userConfig)
            block()
        },
        manageEngine
    )

    /**
     * Closes the underlying [engine].
     */
    override fun close() {
        val success = closed.compareAndSet(false, true)
        if (!success) return

        val installedFeatures = attributes[PLUGIN_INSTALLED_LIST]
        installedFeatures.allKeys.forEach { key ->
            @Suppress("UNCHECKED_CAST")
            val plugin = installedFeatures[key as AttributeKey<Any>]

            if (plugin is Closeable) {
                plugin.close()
            }
        }

        clientJob.complete()
        if (manageEngine) {
            engine.close()
        }
    }

    override fun toString(): String = "HttpClient[$engine]"
}
