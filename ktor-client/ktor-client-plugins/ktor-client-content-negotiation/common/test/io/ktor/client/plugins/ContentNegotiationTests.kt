/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.plugins

import io.ktor.client.call.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.tests.utils.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import kotlin.test.*

class ContentNegotiationTests {
    @Suppress("PrivatePropertyName")
    private val XReturnAs = "X-Return-As"

    private fun TestClientBuilder<MockEngineConfig>.setupWithContentNegotiation(
        block: ContentNegotiation.Config.() -> Unit
    ) {
        config {
            engine {
                addHandler { request ->
                    respond(
                        content = "Generic Server Response",
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            request.headers[XReturnAs] ?: ContentType.Text.Plain.toString()
                        )
                    )
                }
            }
            install(ContentNegotiation) {
                block()
            }
        }
    }

    @Test
    fun addAcceptHeaders(): Unit = testWithEngine(MockEngine) {
        val registeredTypesToSend = listOf(
            ContentType("testing", "a"),
            ContentType("testing", "b"),
            ContentType("testing", "c"),
        )

        setupWithContentNegotiation {
            for (typeToSend in registeredTypesToSend) {
                register(typeToSend, TestContentConverter())
            }
        }

        test { client ->
            client.get("https://test.com/").apply {
                val sentTypes = assertNotNull(call.request.headers.getAll(HttpHeaders.Accept))
                    .map { ContentType.parse(it) }

                // Order NOT tested
                for (typeToSend in registeredTypesToSend) {
                    assertContains(sentTypes, typeToSend)
                }
            }
        }
    }

    @Test
    fun testKeepsContentType(): Unit = testWithEngine(MockEngine) {
        setupWithContentNegotiation {
            register(ContentType("testing", "a"), TestContentConverter())
        }

        test { client ->
            val response = client.get("https://test.com/") {
                contentType(ContentType("testing", "b"))
                setBody(ByteArray(100))
            }
            assertEquals(ContentType("testing", "b"), response.call.request.content.contentType)
        }
    }

    @Test
    fun testIgnoresByteReadChannel() {
        val contentType = ContentType("testing", "a")
        testWithEngine(MockEngine) {
            setupWithContentNegotiation {
                register(
                    contentType,
                    TestContentConverter(deserializeFn = { _, _, _ -> error("error") })
                )
            }

            test { client ->
                val response = client.get("https://test.com/") {
                    header(XReturnAs, contentType)
                }.bodyAsChannel()
                response.discard()
            }
        }
    }

    @Test
    fun replaceContentTypeInRequestPipeline(): Unit = testWithEngine(MockEngine) {
        val bodyContentType = ContentType("testing", "a")
        val sentContentType = ContentType("testing", "b")
        val serializedOutgoingContent = TextContent("CONTENT", sentContentType)

        setupWithContentNegotiation {
            register(bodyContentType, TestContentConverter()) {
                serializeFn = { _, _, _, _ -> serializedOutgoingContent }
            }
        }

        test { client ->
            client.post("https://test.com") {
                setBody(Thing)
                contentType(bodyContentType)
            }.apply {
                // Should be removed when proceeding with serialization
                assertNull(call.request.contentType())

                // The serialized OutgoingContent should contain the new type
                assertEquals(sentContentType, call.request.content.contentType)
            }
        }
    }

    @Test
    fun selectMatchingConverterInRequestPipeline(): Unit = testWithEngine(MockEngine) {
        val types = listOf(
            ContentType("testing", "a"),
            ContentType("testing", "b"),
        )

        val outgoingContentPerType = types.map { contentType ->
            contentType to TextContent("Serialise for: $contentType", contentType)
        }

        setupWithContentNegotiation {
            outgoingContentPerType.forEach { (contentType, outgoingContent) ->
                register(contentType, TestContentConverter()) {
                    serializeFn = { _, _, _, _ -> outgoingContent }
                }
            }
        }

        test { client ->
            outgoingContentPerType.forEach { (contentType, expectedOutgoingContent) ->
                client.post("https://test.com/") {
                    setBody(Thing)
                    contentType(contentType)
                }.apply {
                    assertEquals(expectedOutgoingContent, call.request.content)
                    assertEquals(contentType, call.request.content.contentType)
                }
            }
        }
    }

    @Test
    fun selectMatchingConverterInResponsePipeline(): Unit = testWithEngine(MockEngine) {
        val types = listOf(
            ContentType("testing", "a"),
            ContentType("testing", "b")
        )

        val responsesPerType = types.map { contentType ->
            // StringWrapper to avoid string body decoder
            contentType to StringWrapper("Deserialized matching: $contentType")
        }

        setupWithContentNegotiation {
            responsesPerType.forEach { (contentType, response) ->
                register(contentType, TestContentConverter()) {
                    deserializeFn = { _, _, _ -> response }
                }
            }
        }

        test { client ->
            responsesPerType.forEach { (contentType, expectedResponse) ->
                client.get("https://test.com") {
                    header(XReturnAs, contentType) // Ask server to send back the correct type
                }.apply {
                    assertEquals(contentType(), contentType)
                    assertEquals(expectedResponse, body())
                }
            }
        }
    }

    @Test
    fun selectConvertersInOrderInRequestPipeline() = testWithEngine(MockEngine) {
        val contentTypeToSend = ContentType("testing", "client-to-send")
        val outgoingContents = listOf(
            TextContent("FIRST CONVERTER", contentTypeToSend),
            TextContent("SECOND CONVERTER", contentTypeToSend),
        )

        assertTrue(outgoingContents.size > 1, "Must have more than 1 content converter registered for this test")

        setupWithContentNegotiation {
            // All converters match the same content type
            // outgoingContents[0] should be sent based on registration order
            outgoingContents.forEach { outgoingContent ->
                register(contentTypeToSend, TestContentConverter()) {
                    serializeFn = { _, _, _, _ -> outgoingContent }
                }
            }
        }

        test { client ->
            client.post("https://test.com/") {
                setBody(Thing)
                contentType(contentTypeToSend)
            }.apply {
                assertEquals(contentTypeToSend, call.request.content.contentType)
                assertEquals(outgoingContents.first(), call.request.content)
            }
        }
    }

    @Test
    fun selectConvertersInOrderInResponsePipeline() = testWithEngine(MockEngine) {
        val contentTypeToReceive = ContentType("testing", "client-to-receive")
        val deserializedValues = listOf(
            StringWrapper("FIRST CONVERTER"),
            StringWrapper("SECOND CONVERTER"),
        )

        assertTrue(deserializedValues.size > 1, "Must have more than 1 converter registered for this test")

        setupWithContentNegotiation {
            // All converters match the same content type
            // deserializedValues[0] should be returned based on registration order
            deserializedValues.forEach { value ->
                register(contentTypeToReceive, TestContentConverter()) {
                    deserializeFn = { _, _, _ -> value }
                }
            }
        }

        test { client ->
            client.get("https://test.com/") {
                header(XReturnAs, contentTypeToReceive)
            }.apply {
                assertEquals(contentTypeToReceive, contentType())
                assertEquals(deserializedValues.first(), body())
            }
        }
    }

    @Test
    fun selectFirstNonNullConverterOutputInRequestPipeline(): Unit = testWithEngine(MockEngine) {
        val contentTypeToSend = ContentType("testing", "client-send")
        val sentOutgoingContent = TextContent("NON-NULL-RESULT", contentTypeToSend)

        setupWithContentNegotiation {
            // All converters match the same content type

            register(contentTypeToSend, TestContentConverter())
            register(contentTypeToSend, TestContentConverter())

            // This one should yield the final result as it doesn't return null
            register(contentTypeToSend, TestContentConverter()) {
                serializeFn = { _, _, _, _ -> sentOutgoingContent }
            }

            register(contentTypeToSend, TestContentConverter())
        }

        test { client ->
            client.get("https://test.com/") {
                setBody(Thing)
                contentType(contentTypeToSend)
            }.apply {
                assertEquals(contentTypeToSend, call.request.content.contentType)
                assertEquals(sentOutgoingContent, call.request.content)
            }
        }
    }

    @Test
    fun selectFirstNonNullConverterOutputInResponsePipeline(): Unit = testWithEngine(MockEngine) {
        val contentTypeToReceive = ContentType("testing", "client-send")
        val receivedValue = StringWrapper("NON-NULL-DESERIALIZATION")

        setupWithContentNegotiation {
            // All converters match the same content type

            register(contentTypeToReceive, TestContentConverter())
            register(contentTypeToReceive, TestContentConverter())

            // This one should yield the final result as it doesn't return null
            register(contentTypeToReceive, TestContentConverter()) {
                deserializeFn = { _, _, _ -> receivedValue }
            }

            register(contentTypeToReceive, TestContentConverter())
        }

        test { client ->
            client.get("https://test.com/") {
                header(XReturnAs, contentTypeToReceive)
            }.apply {
                assertEquals(contentTypeToReceive, contentType())
                assertEquals(receivedValue, body())
            }
        }
    }

    object Thing

    data class StringWrapper(val value: String)
}
