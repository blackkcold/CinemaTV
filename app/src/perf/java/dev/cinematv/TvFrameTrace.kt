package dev.cinematv

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Input/response identity for the native TV performance protocol. A response is the
 * first draw attributable to a specific input receipt, not "any following frame".
 */
internal class TvFrameTrace {
    data class Input(val id: Long, val keyCode: Int, val route: String, val receivedNs: Long)

    private val nextId = AtomicLong(1)
    private val inputs = mutableListOf<JSONObject>()
    private val responses = mutableMapOf<Long, Long>()

    fun onInput(event: KeyEvent, route: String, receivedNs: Long = System.nanoTime()): Long? {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return null
        val id = nextId.getAndIncrement()
        synchronized(inputs) {
            inputs += JSONObject().put("id", id).put("key", event.keyCode)
                .put("route", route).put("receivedNs", receivedNs)
        }
        return id
    }

    fun onResponse(inputId: Long, firstDrawNs: Long) {
        synchronized(responses) {
            if (inputId in responses) return
            responses[inputId] = firstDrawNs
        }
    }

    fun snapshot(): JSONObject = synchronized(inputs) {
        val array = JSONArray()
        inputs.forEach { input ->
            val copy = JSONObject(input.toString())
            responses[input.getLong("id")]?.let { copy.put("responseDrawNs", it) }
            array.put(copy)
        }
        JSONObject().put("inputs", array)
    }
}
