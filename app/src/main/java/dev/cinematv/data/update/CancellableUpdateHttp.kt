package dev.cinematv.data.update

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response
import java.io.IOException

/** Caller owns URL validation and IO dispatch. Cancellation covers headers AND body consumption. */
internal suspend fun <T> consumeUpdateResponse(call: Call, consume: suspend (Response) -> T): T = coroutineScope {
    // Unconfined cancellation runs immediately even while this IO worker is blocked in a socket read.
    val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try {
        call.execute().use { consume(it) }
    } catch (error: IOException) {
        coroutineContext.ensureActive()
        throw error
    } finally { cancellation.cancel() }
}
