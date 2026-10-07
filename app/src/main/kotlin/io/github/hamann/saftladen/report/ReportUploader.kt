package io.github.hamann.saftladen.report

import io.github.hamann.saftladen.karoo.connectAndAwait
import io.github.hamann.saftladen.karoo.makeHttpRequest
import io.github.hamann.saftladen.settings.SaftladenSettings
import io.github.hamann.saftladen.settings.SettingsRepository
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import timber.log.Timber

/**
 * Drains [ReportStore] to the configured endpoint over the Karoo System's connection.
 *
 * Requests are sent with `waitForConnection = false` so that a missing network fails fast
 * and the report stays in our own buffer, which — unlike Karoo's in-memory request queue —
 * survives a reboot.
 */
class ReportUploader(
    private val store: ReportStore,
    private val settingsRepository: SettingsRepository,
    private val karooSystemFactory: () -> KarooSystemService,
) {
    sealed interface Result {
        /** Buffer is empty, or there is nothing we can do until the rider acts. */
        data class Done(val message: String, val delivered: Int = 0) : Result

        /** Something transient went wrong; the caller should try again later. */
        data class Retry(val message: String, val delivered: Int = 0) : Result
    }

    suspend fun flush(): Result {
        val settings = settingsRepository.current()
        val pending = store.pending()

        if (pending.isEmpty()) {
            return Result.Done("Nothing to send")
        }
        if (!settings.enabled) {
            return Result.Done("${pending.size} report(s) buffered, reporting is switched off")
        }
        if (!settings.isConfigured) {
            return record(Result.Done("${pending.size} report(s) buffered, no endpoint configured"))
        }

        val karooSystem = karooSystemFactory()
        if (!karooSystem.connectAndAwait()) {
            return record(Result.Retry("Karoo System is not available"))
        }

        return try {
            upload(karooSystem, settings, pending)
        } finally {
            karooSystem.disconnect()
        }
    }

    private suspend fun upload(
        karooSystem: KarooSystemService,
        settings: SaftladenSettings,
        pending: List<ReportStore.Entry>,
    ): Result {
        var delivered = 0
        var rejection: String? = null
        for (entry in pending) {
            when (val outcome = send(karooSystem, settings, entry)) {
                is Outcome.Delivered -> {
                    store.remove(entry.id)
                    delivered++
                }

                is Outcome.Rejected -> {
                    // The endpoint will never accept this report, so keep the buffer moving.
                    Timber.w("Dropping report %s: %s", entry.reportId, outcome.message)
                    store.remove(entry.id)
                    rejection = outcome.message
                }

                is Outcome.Failed -> return record(Result.Retry(outcome.message, delivered))
            }
        }
        return record(Result.Done(rejection ?: "Sent $delivered report(s)", delivered))
    }

    private suspend fun send(
        karooSystem: KarooSystemService,
        settings: SaftladenSettings,
        entry: ReportStore.Entry,
    ): Outcome {
        if (entry.body.size > OnHttpResponse.MAX_REQUEST_SIZE) {
            return Outcome.Rejected("Report ${entry.reportId} is too large for Karoo's HTTP bridge")
        }

        val response = karooSystem.makeHttpRequest(
            OnHttpResponse.MakeHttpRequest(
                method = "POST",
                url = settings.endpointUrl,
                headers = settings.requestHeaders(),
                body = entry.body,
                waitForConnection = false,
            ),
            timeoutMs = REQUEST_TIMEOUT_MS,
        ) ?: return Outcome.Failed("Timed out sending report ${entry.reportId}")

        return response.toOutcome(entry)
    }

    private fun HttpResponseState.Complete.toOutcome(entry: ReportStore.Entry): Outcome = when {
        error != null -> Outcome.Failed("Upload failed: $error")
        statusCode in 200..299 -> Outcome.Delivered
        statusCode == 408 || statusCode == 429 -> Outcome.Failed("Endpoint busy (HTTP $statusCode)")
        statusCode in 400..499 -> Outcome.Rejected(
            "Endpoint rejected report ${entry.reportId} with HTTP $statusCode",
        )

        else -> Outcome.Failed("Endpoint returned HTTP $statusCode")
    }

    private suspend fun record(result: Result): Result {
        val (message, succeeded) = when (result) {
            is Result.Done -> result.message to (result.delivered > 0)
            is Result.Retry -> result.message to false
        }
        Timber.i("Flush: %s", message)
        settingsRepository.recordAttempt(message, succeeded)
        return result
    }

    private sealed interface Outcome {
        data object Delivered : Outcome

        /** Permanent: retrying the same body will not help. */
        data class Rejected(val message: String) : Outcome

        /** Transient: try again later. */
        data class Failed(val message: String) : Outcome
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 60_000L
    }
}
