package ru.semetrics.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class NetworkTransportTest {

    private lateinit var server: MockWebServer
    private lateinit var transport: NetworkTransport

    private val event = EventPayload(
        eventName = "e", userId = null, anonymousId = null, sessionId = null,
        sourceId = null, sourceVersion = null, clientTs = "2026-01-01T00:00:00.000Z",
    )

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        transport = NetworkTransport(server.url("/events").toString(), "sm_test")
    }

    @After
    fun tearDown() = server.shutdown()

    private fun sendAndCatch(): Exception? = runBlocking {
        try {
            transport.sendBatch(listOf(event)); null
        } catch (e: Exception) {
            e
        }
    }

    @Test
    fun `success does not throw`() {
        server.enqueue(MockResponse().setBody("""{"status":{"code":""},"data":{"accepted":1}}"""))
        assertEquals(null, sendAndCatch())
        assertEquals("/events/ingest/batch", server.takeRequest().path)
    }

    @Test
    fun `5xx and 429 are retryable`() {
        // 408 OkHttp повторяет сам, поэтому проверяем его через isRetryableStatus
        assertTrue(isRetryableStatus(408))
        for (code in listOf(500, 502, 503, 429)) {
            server.enqueue(MockResponse().setResponseCode(code))
            val e = sendAndCatch() as? TransportException ?: fail("ожидали TransportException для $code")
            assertTrue("HTTP $code", (e as TransportException).retryable)
        }
    }

    @Test
    fun `4xx is not retryable`() {
        for (code in listOf(400, 401, 403, 422)) {
            server.enqueue(MockResponse().setResponseCode(code))
            val e = sendAndCatch() as? TransportException ?: fail("ожидали TransportException для $code")
            assertFalse("HTTP $code", (e as TransportException).retryable)
        }
    }

    @Test
    fun `server rejection in status code is not retryable`() {
        server.enqueue(MockResponse().setBody("""{"status":{"code":"validation_error","message":"bad"}}"""))
        val e = sendAndCatch() as TransportException
        assertFalse(e.retryable)
    }

    @Test
    fun `non json body is a generic error and therefore retryable`() {
        server.enqueue(MockResponse().setBody("<html>captive portal</html>"))
        val e = sendAndCatch()
        assertTrue(e != null && e !is TransportException)
    }
}
