/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.apache5

import io.ktor.client.engine.*
import io.ktor.client.request.*
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

            val request = SimpleRequestBuilder.get()
                .setHttpHost(HttpHost(data.url.host, data.url.port))
                .setPath(data.url.encodedPath)
                .build()

            val responseDeferred = CompletableDeferred<SimpleHttpResponse>(coroutineContext[Job])

            client.execute(request, object : FutureCallback<SimpleHttpResponse> {
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

            return HttpResponseData(
                statusCode = HttpStatusCode(response.code, response.reasonPhrase),
                requestTime = GMTDate.START,
                headers = Headers.Empty,
                version = HttpProtocolVersion(response.version.protocol, response.version.major, response.version.minor),
                body = response.body.bodyText,
                callContext = EmptyCoroutineContext
            )
        }
    }
}
