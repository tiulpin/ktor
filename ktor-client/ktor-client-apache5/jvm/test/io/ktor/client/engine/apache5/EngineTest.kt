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
            post("/post") {
                call.respondText { "POST" }
            }
            put("/put") {
                call.respondText { "PUT" }
            }
            delete("/delete") {
                call.respondText { "DELETE" }
            }
            head("/head") {
                call.respond(HttpStatusCode.OK)
            }
            get("/echo-headers") {
                call.request.headers.forEach { name, values ->
                    values.forEach { v -> call.response.header(name, v) }
                }
                call.respond(HttpStatusCode.OK)
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
            method = HttpMethod.Post
            url {
                path("/post")
            }
        }

        assertEquals(HttpStatusCode.OK, response.statusCode)
        assertEquals("POST", response.body)
    }

    @Test
    fun simplePUTRequest(): Unit = runBlocking {
        val response = makeRequest {
            method = HttpMethod.Put
            url {
                path("/put")
            }
        }

        assertEquals(HttpStatusCode.OK, response.statusCode)
        assertEquals("PUT", response.body)
    }

    @Test
    fun simpleDELETERequest(): Unit = runBlocking {
        val response = makeRequest {
            method = HttpMethod.Delete
            url {
                path("/delete")
            }
        }

        assertEquals(HttpStatusCode.OK, response.statusCode)
        assertEquals("DELETE", response.body)
    }

    @Test
    fun simpleHEADRequest(): Unit = runBlocking {
        val response = makeRequest {
            method = HttpMethod.Head
            url {
                path("/head")
            }
        }

        assertEquals(HttpStatusCode.OK, response.statusCode)
    }

    @Test
    fun sendSomeHeaders(): Unit = runBlocking {
        val response = makeRequest {
            method = HttpMethod.Get
            url {
                path("/echo-headers")
            }
            header("single", "test")
            header("multiple", "test1")
            header("multiple", "test2")
        }

        assertEquals(HttpStatusCode.OK, response.statusCode)
        assertEquals("test", response.headers["single"])
        assertEquals(listOf("test1", "test2"), response.headers.getAll("multiple"))
    }

    @Test
    fun contentLengthAndTypeHeaders(): Unit = runBlocking {
        val response = makeRequest {
            method = HttpMethod.Get
            url {
                path("/get")
            }
        }

        assertEquals("3", response.headers["Content-Length"])
        assertEquals("text/plain; charset=UTF-8", response.headers["Content-Type"])
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
