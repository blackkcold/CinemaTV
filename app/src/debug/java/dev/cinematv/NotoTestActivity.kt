package dev.cinematv

import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Debug font/protocol test host. WindowManager readiness is separate from Compose draw readiness. */
class NotoTestActivity : ComponentActivity() {
    private val enterAnimationFinished = CountDownLatch(1)
    @Volatile var hasStopped = false
        private set

    @Volatile var splashExitRemovals = 0
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // windowDisablePreview prevents a starting window for this instrumented
        // app-origin launch. Handle any transferred/API31 splash without its
        // separate system exit animation; no product Activity uses this host.
        if (Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener { view ->
                view.remove()
                splashExitRemovals += 1
            }
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 31) splashScreen.clearOnExitAnimationListener()
        super.onDestroy()
    }

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
