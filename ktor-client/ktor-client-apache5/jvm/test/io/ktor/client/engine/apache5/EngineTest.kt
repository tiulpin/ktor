/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.apache5

import io.ktor.application.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.client.tests.utils.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.coroutines.*
import kotlin.test.*

class EngineTest: TestWithKtor() {
    override val server = embeddedServer(CIO, serverPort) {
        routing {
            get("/get") {
                call.respondText { "GET" }
            }
            get("/post") {
                call.respondText { "POST" }
            }
            get("/put") {
                call.respondText { "PUT" }
            }
            get("/delete") {
                call.respondText { "DELETE" }
            }
        }
    }

    @Test
    fun simpleGETRequest(): Unit = runBlocking {
        val response = makeRequest {
            method = HttpMethod.Get
            url {
                path("/get")
            }
        }

        assertEquals(HttpStatusCode.OK, response.statusCode)
        assertEquals("GET", response.body)
    }

    @Test
    fun simplePOSTRequest(): Unit = runBlocking {
        val response = makeRequest {
            method = HttpMethod.Get
            url {
                path("/post")
            }
        }

        assertEquals(HttpStatusCode.OK, response.statusCode)
        assertEquals("POST", response.body)
    }

    private suspend fun makeRequest(requestBuilder: HttpRequestBuilder.() -> Unit): HttpResponseData {
        val engine = ApacheEngine(Dispatchers.Default, HttpClientEngineConfig())
        val request = HttpRequestBuilder().apply {
            url {
                port = serverPort
            }
        }.apply(requestBuilder).build()
        return engine.execute(request)
    }
}
