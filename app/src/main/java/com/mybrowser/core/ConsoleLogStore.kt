package com.mybrowser.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

enum class ConsoleLogLevel { LOG, INFO, DEBUG, WARNING, ERROR, TIP }

data class ConsoleLogEntry(
    val id: Long,
    val level: ConsoleLogLevel,
    val message: String,
    val sourceId: String,
    val lineNumber: Int,
    val timestampMs: Long,
)

/** Keeps the browser's real WebChromeClient console stream bounded and observable. */
class ConsoleLogStore(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private val nextId = AtomicLong(1L)
    private val buffer = BufferedLog<ConsoleLogEntry>(maxEntries)
    val entries = buffer.entries
    fun setVisible(value: Boolean) = buffer.setVisible(value)
    fun snapshot(): List<ConsoleLogEntry> = buffer.snapshot()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    fun add(
        level: ConsoleLogLevel,
        message: String?,
        sourceId: String?,
        lineNumber: Int,
    ) {
        val text = message?.trim()?.take(MAX_MESSAGE_LENGTH)
            ?.takeIf { it.isNotBlank() } ?: ""
        buffer.edit { ring ->
            val entry = ConsoleLogEntry(
                id = nextId.getAndIncrement(),
                level = level,
                message = text,
                sourceId = sourceId.orEmpty().take(MAX_SOURCE_LENGTH),
                lineNumber = lineNumber.coerceAtLeast(0),
                timestampMs = System.currentTimeMillis(),
            )
            ring.add(entry)
        }
    }

    fun clear() {
        buffer.clear()
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 300
        const val MAX_MESSAGE_LENGTH = 4_096
        const val MAX_SOURCE_LENGTH = 1_024
    }
}
