package io.customer.sdk.insights

import android.content.Context
import io.customer.sdk.core.util.DispatchersProvider
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface DiagnosticsStore {
    suspend fun save(event: DiagnosticEvent)
    suspend fun getAll(): List<DiagnosticEvent>
    suspend fun clear()
}

class FileDiagnosticsStore(
    private val context: Context,
    private val dispatchers: DispatchersProvider,
    private val fileName: String = "diagnostics_logs.json",
    private val maxEvents: Int = 100,
    private val maxFileSizeBytes: Long = 100 * 1024, // 100KB
    private val eventTtlMillis: Long = 24 * 60 * 60 * 1000 // 24 hours
) : DiagnosticsStore {

    private val file = File(context.filesDir, fileName)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    private val mutex = Mutex()

    override suspend fun save(event: DiagnosticEvent) = mutex.withLock {
        withContext(dispatchers.background) {
            var events = getAll().toMutableList()

            // Remove expired events based on TTL
            val now = System.currentTimeMillis()
            events = events.filter { now - it.timestamp < eventTtlMillis }.toMutableList()

            // Add new event
            events.add(event)

            // Enforce max event count (keep most recent)
            if (events.size > maxEvents) {
                events = events.sortedByDescending { it.timestamp }
                    .take(maxEvents)
                    .toMutableList()
            }

            // Write to file
            val encodedData = json.encodeToString(events)

            // Check file size limit
            if (encodedData.length > maxFileSizeBytes) {
                // If exceeds size, keep only the most recent half
                val reducedEvents = events.sortedByDescending { it.timestamp }
                    .take(maxEvents / 2)
                file.writeText(json.encodeToString(reducedEvents))
            } else {
                file.writeText(encodedData)
            }
        }
    }

    override suspend fun getAll(): List<DiagnosticEvent> = mutex.withLock {
        withContext(dispatchers.background) {
            if (!file.exists()) return@withContext emptyList()
            val jsonText = file.readText()
            if (jsonText.isBlank()) return@withContext emptyList()
            try {
                json.decodeFromString<List<DiagnosticEvent>>(jsonText)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    override suspend fun clear(): Unit = mutex.withLock {
        withContext(dispatchers.background) {
            file.delete()
        }
    }
}
