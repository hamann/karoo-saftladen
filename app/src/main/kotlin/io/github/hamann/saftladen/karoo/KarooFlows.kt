package io.github.hamann.saftladen.karoo

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.OnHttpResponse
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Observe a [KarooEvent] as a flow, unregistering the consumer when collection stops.
 *
 * The Karoo System delivers the current value on registration for state-like events
 * (such as `RideState` and `SavedDevices`), so `first()` can be used to take a snapshot.
 */
inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> {
    return callbackFlow {
        val consumerId = addConsumer<T> { event: T -> trySend(event) }
        awaitClose { removeConsumer(consumerId) }
    }
}

/**
 * Observe a streaming data type, unregistering the consumer when collection stops.
 */
fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> {
    return callbackFlow {
        val consumerId = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
            trySend(event.state)
        }
        awaitClose { removeConsumer(consumerId) }
    }
}

/**
 * Bind to the Karoo System and suspend until it is ready, at most [timeoutMs].
 *
 * The caller owns the binding and must call [KarooSystemService.disconnect] when done.
 */
suspend fun KarooSystemService.connectAndAwait(timeoutMs: Long = 15_000): Boolean {
    if (connected) return true
    return withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val resumed = AtomicBoolean(false)
            connect { isConnected ->
                if (isConnected && resumed.compareAndSet(false, true)) {
                    continuation.resumeWith(Result.success(true))
                }
            }
        }
    } ?: connected
}

/**
 * Perform an HTTP request through the Karoo System's network connection.
 *
 * Returns null if no final response arrived within [timeoutMs].
 */
suspend fun KarooSystemService.makeHttpRequest(
    request: OnHttpResponse.MakeHttpRequest,
    timeoutMs: Long = 60_000,
): HttpResponseState.Complete? {
    return withTimeoutOrNull(timeoutMs) {
        callbackFlow {
            val consumerId = addConsumer<OnHttpResponse>(request) { event: OnHttpResponse ->
                trySend(event.state)
            }
            awaitClose { removeConsumer(consumerId) }
        }
            .filterIsInstance<HttpResponseState.Complete>()
            .first()
    }
}
