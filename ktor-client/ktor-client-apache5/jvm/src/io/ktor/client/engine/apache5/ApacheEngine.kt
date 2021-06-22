/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.apache5

import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.client.utils.*
import io.ktor.http.*
import io.ktor.util.date.*
import kotlinx.coroutines.*
import org.apache.hc.client5.http.async.methods.*
import org.apache.hc.client5.http.impl.async.*
import org.apache.hc.core5.concurrent.*
import org.apache.hc.core5.http.*
import java.lang.Exception
import kotlin.coroutines.*


public class ApacheEngine(override val dispatcher: CoroutineDispatcher, override val config: HttpClientEngineConfig) :
    HttpClientEngineBase("ktor-apache5") {

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        HttpAsyncClients.createDefault().use { client ->
            client.start()

            val builder = SimpleRequestBuilder.create(data.method.value)
                .setHttpHost(HttpHost(data.url.host, data.url.port))
                .setPath(data.url.encodedPath)

            for ((name, values) in data.headers.entries()) {
                values.forEach { v ->
                    builder.addHeader(name, v)
                }
            }

            val responseDeferred = CompletableDeferred<SimpleHttpResponse>(coroutineContext[Job])

            client.execute(builder.build(), object : FutureCallback<SimpleHttpResponse> {
                override fun completed(result: SimpleHttpResponse?) {
                    if (result != null) responseDeferred.complete(result)
                }

                override fun failed(ex: Exception?) {
                    if (ex != null) responseDeferred.completeExceptionally(ex)
                }

                override fun cancelled() {
                    responseDeferred.cancel()
                }
            })

            val response = responseDeferred.await()

            val headersBuilder = HeadersBuilder()
            for (header in response.headers) {
                headersBuilder.append(header.name, header.value)
            }

            return HttpResponseData(
                statusCode = HttpStatusCode(response.code, response.reasonPhrase),
                requestTime = GMTDate.START,
                headers = headersBuilder.build(),
                version = HttpProtocolVersion(response.version.protocol, response.version.major, response.version.minor),
                body = if (response.body != null) response.body.bodyText else EmptyContent,
                callContext = EmptyCoroutineContext
            )
        }
    }
}
