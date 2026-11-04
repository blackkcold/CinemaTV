package dev.cinematv

import android.os.Looper
import androidx.activity.ComponentActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Font-only test host. WindowManager readiness is separate from Compose draw readiness. */
class NotoTestActivity : ComponentActivity() {
    private val enterAnimationFinished = CountDownLatch(1)
    @Volatile var hasStopped = false
        private set

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        // This also arrives for non-animated launches. Keep an early callback for this instance.
        enterAnimationFinished.countDown()
    }

    override fun onStop() {
        hasStopped = true
        super.onStop()
    }

    fun awaitEnterAnimationComplete(timeoutMillis: Long): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Never wait for the enter callback on main" }
        require(timeoutMillis > 0)
        return enterAnimationFinished.await(timeoutMillis, TimeUnit.MILLISECONDS) && !hasStopped
    }
}
