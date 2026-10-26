package ru.semetrics.sdk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** In-memory замена Room DAO. */
private class FakeDao : EventDao {
    val rows = mutableListOf<QueuedEvent>()
    private var nextId = 1L

    override suspend fun insert(event: QueuedEvent) {
        rows += event.copy(id = nextId++)
    }

    override suspend fun getBatch(limit: Int) = rows.sortedBy { it.id }.take(limit)

    override suspend fun deleteByIds(ids: List<Long>) {
        rows.removeAll { it.id in ids }
    }

    override suspend fun count() = rows.size
}

private fun FakeDao.fill(n: Int) = runBlocking {
    repeat(n) { insert(QueuedEvent(eventName = "e$it", userId = null, anonymousId = null, sessionId = null,
        sourceId = null, sourceVersion = null, propertiesJson = null, clientTs = "2026-01-01T00:00:00.000Z")) }
}

class DrainQueueTest {

    @Test
    fun `success sends all batches and empties queue`() = runBlocking {
        val dao = FakeDao().apply { fill(5) }
        val sent = mutableListOf<Int>()

        val result = drainQueue(dao, batchSize = 2) { sent += it.size }

        assertEquals(DrainResult.DONE, result)
        assertEquals(listOf(2, 2, 1), sent)
        assertEquals(0, dao.rows.size)
    }

    @Test
    fun `network error keeps batch in queue`() = runBlocking {
        val dao = FakeDao().apply { fill(3) }

        val result = drainQueue(dao, batchSize = 2) { throw IOException("Unable to resolve host") }

        assertEquals(DrainResult.RETRY_LATER, result)
        assertEquals(3, dao.rows.size)
    }

    @Test
    fun `retryable http error keeps batch in queue`() = runBlocking {
        val dao = FakeDao().apply { fill(3) }

        val result = drainQueue(dao, batchSize = 2) { throw TransportException("HTTP 503", retryable = true) }

        assertEquals(DrainResult.RETRY_LATER, result)
        assertEquals(3, dao.rows.size)
    }

    @Test
    fun `rejected batch is dropped and does not block the rest of the queue`() = runBlocking {
        val dao = FakeDao().apply { fill(5) }
        var calls = 0
        val sent = mutableListOf<String>()

        val result = drainQueue(dao, batchSize = 2) { batch ->
            if (calls++ == 0) throw TransportException("HTTP 422", retryable = false)
            sent += batch.map { it.eventName }
        }

        assertEquals(DrainResult.DONE, result)
        assertEquals(listOf("e2", "e3", "e4"), sent)
        assertEquals(0, dao.rows.size)
    }
}
