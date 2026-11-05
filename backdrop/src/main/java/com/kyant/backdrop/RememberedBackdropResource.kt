package com.kyant.backdrop

import androidx.compose.runtime.RememberObserver

/** Own an allocation even when the composition that created it is abandoned.
 * Mirrors rememberGraphicsLayer's RememberObserver lifecycle, with a shared failure
 * boundary and an idempotent release. Generic ownership keeps this contract JVM-testable.
 */
internal class RememberedBackdropResource<T : Any>(
    private val failureState: BackdropFailureState,
    create: () -> T,
    private val release: (T) -> Unit,
) : RememberObserver {
    var value: T? = failureState.attempt("source.create", create)
        private set

    override fun onRemembered() = Unit

    override fun onForgotten() = releaseOnce()

    override fun onAbandoned() = releaseOnce()

    private fun releaseOnce() {
        val owned = value ?: return
        // Clear before calling the platform, including the exceptional path. Do not
        // repeat an uncertain release or skip cleanup because the scene already failed.
        value = null
        try {
            release(owned)
        } catch (cause: RuntimeException) {
            failureState.fail("source.release", cause)
        }
    }
}
