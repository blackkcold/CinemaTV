package dev.cinematv.ui.shared

/** A view can finish only a physical Back press whose initial down it received. */
internal class RemoteBackPress {
    private var ownedDownTime: Long? = null

    fun down(downTime: Long, repeatCount: Int) {
        if (repeatCount == 0) ownedDownTime = downTime
    }

    fun up(downTime: Long, cancelled: Boolean): Boolean {
        val owned = ownedDownTime == downTime
        ownedDownTime = null
        return owned && !cancelled
    }

    fun reset() { ownedDownTime = null }
}
