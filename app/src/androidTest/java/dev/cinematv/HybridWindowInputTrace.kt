package dev.cinematv

import android.app.Instrumentation
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import org.json.JSONArray
import org.json.JSONObject

/** Bounded metadata only; never changes key dispatch, focus, input mode or the IME. */
internal class HybridWindowInputTrace(
    private val instrumentation: Instrumentation,
    private val activity: MainActivity,
) : AutoCloseable {
    private val events = ArrayDeque<JSONObject>()
    private var dropped = 0
    private var closed = false
    private lateinit var original: Window.Callback
    private lateinit var observer: Window.Callback

    init {
        onMain {
            original = requireNotNull(activity.window.callback)
            observer = object : Window.Callback by original {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode !in REMOTE_KEYS) return original.dispatchKeyEvent(event)
                    note("before-dispatch", event)
                    return try {
                        original.dispatchKeyEvent(event).also { note("after-dispatch", event, handled = it) }
                    } catch (failure: Throwable) {
                        note("dispatch-threw", event, failure = failure.javaClass.name)
                        throw failure
                    }
                }
            }
            activity.window.callback = observer
        }
    }

    private fun note(stage: String, event: KeyEvent, handled: Boolean? = null, failure: String? = null) {
        if (closed) return
        // Diagnostic failures must not change the delegate's result or exception.
        runCatching {
            val decor = activity.window.decorView
            val focused = decor.findFocus()
            val entry = JSONObject().put("stage", stage).put("uptimeMs", SystemClock.uptimeMillis())
                .put("action", event.action).put("keyCode", event.keyCode).put("downTime", event.downTime)
                .put("eventTime", event.eventTime).put("repeat", event.repeatCount).put("flags", event.flags)
                .put("source", event.source).put("deviceId", event.deviceId)
                .put("handled", handled ?: JSONObject.NULL).put("failure", failure ?: JSONObject.NULL)
                .put("windowFocus", decor.hasWindowFocus()).put("touchMode", decor.isInTouchMode)
                .put("focusedView", focused?.javaClass?.name ?: JSONObject.NULL)
                .put("focusedViewDescendantFocusability", (focused as? ViewGroup)?.descendantFocusability ?: JSONObject.NULL)
            // IMM.isActive/isAcceptingText are deliberately absent: both call
            // checkFocus() in Android and can reconcile the connection we observe.
            if (events.size == EVENT_LIMIT) { events.removeFirst(); dropped++ }
            events.addLast(entry)
        }
    }

    fun drain(): JSONArray {
        val result = JSONArray()
        onMain {
            if (dropped > 0) result.put(JSONObject().put("stage", "bounded-buffer").put("dropped", dropped))
            events.forEach { result.put(it) }
            events.clear()
            dropped = 0
        }
        return result
    }

    override fun close() {
        onMain {
            closed = true
            if (activity.window.callback === observer) activity.window.callback = original
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else instrumentation.runOnMainSync(action)
    }

    private companion object {
        const val EVENT_LIMIT = 192
        val REMOTE_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
    }
}
