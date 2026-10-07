package io.github.hamann.saftladen.report

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import timber.log.Timber
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * A durable FIFO buffer of reports waiting to be uploaded.
 *
 * One file per report so that a crash can never corrupt more than the report being
 * written, and so that the buffer survives reboots. Files are named
 * `<zero-padded createdAt millis>-<reportId>.json`, which makes lexicographic order
 * the same as chronological order.
 */
class ReportStore(
    private val directory: File,
    private val maxReports: Int = DEFAULT_MAX_REPORTS,
    private val maxAge: Duration = DEFAULT_MAX_AGE,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** A buffered report, [body] being the exact bytes to upload. */
    data class Entry(
        val id: String,
        val reportId: String,
        val createdAt: Instant,
        val body: ByteArray,
    ) {
        // Generated equals/hashCode would compare `body` by identity.
        override fun equals(other: Any?) = this === other || (other is Entry && other.id == id)

        override fun hashCode() = id.hashCode()
    }

    private val mutex = Mutex()

    /**
     * Append [report] to the buffer, pruning entries that are too old or over the cap.
     *
     * @return the buffer entry id, or null if the report could not be written.
     */
    suspend fun enqueue(report: BatteryReport, createdAt: Instant = Instant.now()): String? =
        mutex.withLock {
            withContext(dispatcher) {
                if (!directory.exists() && !directory.mkdirs()) {
                    Timber.e("Could not create report directory %s", directory)
                    return@withContext null
                }
                val id = entryId(createdAt, report.reportId)
                val target = File(directory, "$id.json")
                val temp = File(directory, "$id.json.tmp")
                try {
                    temp.writeText(reportJson.encodeToString(report))
                    if (!temp.renameTo(target)) {
                        temp.delete()
                        Timber.e("Could not move %s into place", temp)
                        return@withContext null
                    }
                } catch (e: Exception) {
                    temp.delete()
                    Timber.e(e, "Could not buffer report %s", report.reportId)
                    return@withContext null
                }
                prune(keep = target.name)
                id
            }
        }

    /** All buffered reports, oldest first. Unreadable files are dropped. */
    suspend fun pending(): List<Entry> = mutex.withLock {
        withContext(dispatcher) {
            reportFiles().mapNotNull { file ->
                val id = file.nameWithoutExtension
                val createdAt = createdAtOf(id)
                if (createdAt == null) {
                    Timber.w("Dropping buffered report with unexpected name %s", file.name)
                    file.delete()
                    return@mapNotNull null
                }
                try {
                    Entry(
                        id = id,
                        reportId = id.substringAfter('-'),
                        createdAt = createdAt,
                        body = file.readBytes(),
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Could not read buffered report %s", file.name)
                    null
                }
            }
        }
    }

    suspend fun count(): Int = mutex.withLock {
        withContext(dispatcher) { reportFiles().size }
    }

    /** Remove a single entry, e.g. after it was delivered or permanently rejected. */
    suspend fun remove(id: String): Boolean = mutex.withLock {
        withContext(dispatcher) { File(directory, "$id.json").delete() }
    }

    suspend fun clear() = mutex.withLock {
        withContext(dispatcher) { reportFiles().forEach { it.delete() } }
    }

    private fun reportFiles(): List<File> =
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Drop expired and excess entries, never touching [keep] (the report just written). */
    private fun prune(keep: String) {
        val files = reportFiles()
        val expiredBefore = Instant.now().minus(maxAge)
        val expired = files.filter { file ->
            file.name != keep && createdAtOf(file.nameWithoutExtension)?.isBefore(expiredBefore) == true
        }
        val remaining = files - expired.toSet()
        // Oldest first, so dropping from the head keeps the newest reports.
        val excess = remaining.take((remaining.size - maxReports).coerceAtLeast(0))
            .filter { it.name != keep }

        (expired + excess).forEach {
            Timber.w("Pruning buffered report %s", it.name)
            it.delete()
        }
    }

    private fun entryId(createdAt: Instant, reportId: String) =
        "%013d-%s".format(createdAt.toEpochMilli(), reportId)

    private fun createdAtOf(entryId: String): Instant? =
        entryId.substringBefore('-').toLongOrNull()?.let(Instant::ofEpochMilli)

    companion object {
        const val DEFAULT_MAX_REPORTS = 200
        val DEFAULT_MAX_AGE: Duration = Duration.ofDays(30)
    }
}
