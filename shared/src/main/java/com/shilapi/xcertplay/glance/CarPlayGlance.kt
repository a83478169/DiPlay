package com.shilapi.xcertplay.glance

import com.shilapi.xcertplay.hud.BydHudRouteState
import com.shilapi.xcertplay.hud.ClusterSongState
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/**
 * What CarPlay is doing, at a glance, for widgets and other screens outside CarPlay: the next
 * maneuver from iAP2 route guidance (0x5201/0x5202) and the song from NowPlayingUpdate (0x5001).
 * Fed by every CarPlay session, whatever the BYD output settings.
 */
object CarPlayGlance {
    /** One state of the glance; [maneuverType] is Apple's RouteGuidanceManeuverType, null without a route. */
    data class Snapshot(
        val connected: Boolean = false,
        val maneuverType: Int? = null,
        val drivingSide: Int = 0,
        val distanceMeters: Int = 0,
        val road: String = "",
        val arrivalEpochSeconds: Long? = null,
        val remainingSeconds: Long? = null,
        val remainingMeters: Long? = null,
        val song: String? = null,
        val playing: Boolean = false,
    )

    private val route = BydHudRouteState()
    private val song = ClusterSongState()
    private var connected = false
    private var last = Snapshot()

    /**
     * Snapshot subscribers. Embedded hosts (e.g. NaviTool's tab) must coexist with the
     * built-in widget, so the single legacy [listener] slot is now backed by a list:
     * setting it replaces all subscribers (legacy exclusive semantics), while
     * [addListener]/[removeListener] support multiple concurrent subscribers.
     */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Snapshot) -> Unit>()
    /** Host diagnostics: route frames and parse outcomes, without exposing the raw payload. */
    private val diagnosticListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    fun addDiagnosticListener(subscriber: (String) -> Unit) { diagnosticListeners.add(subscriber) }

    fun removeDiagnosticListener(subscriber: (String) -> Unit) { diagnosticListeners.remove(subscriber) }

    private fun diagnostic(line: String) {
        diagnosticListeners.forEach { listener -> runCatching { listener(line) } }
    }

    var listener: ((Snapshot) -> Unit)?
        get() = listeners.lastOrNull()
        set(value) {
            synchronized(listeners) { listeners.clear() }
            value?.let { listeners.add(it) }
        }

    fun addListener(subscriber: (Snapshot) -> Unit) { listeners.add(subscriber) }

    fun removeListener(subscriber: (Snapshot) -> Unit) { listeners.remove(subscriber) }

    /** Refresh time-dependent guidance even when no new metadata frame has arrived. */
    fun snapshot(): Snapshot {
        val (changed, current) = synchronized(this) { publishLocked() to last }
        changed?.let { snap -> listeners.forEach { sub -> sub(snap) } }
        return current
    }

    fun onFrame(frame: Iap2Frame) {
        val routeFrame = frame.messageId == BydHudRouteState.ROUTE_GUIDANCE_UPDATE ||
            frame.messageId == BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE
        val changed = synchronized(this) {
            when (frame.messageId) {
                BydHudRouteState.ROUTE_GUIDANCE_UPDATE, BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE -> {
                    try {
                        val change = route.accept(frame.messageId, frame.payload)
                        diagnostic("CPNAV_TRACE frame id=0x" +
                            frame.messageId.toString(16) + " len=${frame.payload.size} change=$change")
                        change
                    } catch (error: Throwable) {
                        diagnostic("CPNAV_TRACE parse failed id=0x" +
                            frame.messageId.toString(16) + " len=${frame.payload.size} " +
                            "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                        null
                    }
                }
                ClusterSongState.NOW_PLAYING_UPDATE -> runCatching { song.accept(frame) }
                else -> return
            }
            publishLocked()
        }
        if (routeFrame && changed == null) {
            diagnostic("CPNAV_TRACE route frame produced no snapshot" +
                " current=${last.maneuverType}")
        }
        changed?.let { snap ->
            if (routeFrame) {
                diagnostic("CPNAV_TRACE snapshot apple=${snap.maneuverType} side=${snap.drivingSide}" +
                    " toNext=${snap.distanceMeters} road='${snap.road}'" +
                    " remain=${snap.remainingMeters} eta=${snap.remainingSeconds}")
            }
            listeners.forEach { sub -> sub(snap) }
        }
    }

    fun setConnected(next: Boolean) {
        val changed = synchronized(this) {
            if (connected == next) return
            connected = next
            if (!next) {
                route.clear()
                song.clear()
            }
            diagnostic("CPNAV_TRACE connected=$next")
            publishLocked()
        }
        changed?.let { snap -> listeners.forEach { sub -> sub(snap) } }
    }

    private fun publishLocked(): Snapshot? {
        val maneuver = route.currentApple()
        val current = song.current()
        val next = Snapshot(
            connected = connected,
            maneuverType = maneuver?.type,
            drivingSide = maneuver?.drivingSide ?: 0,
            distanceMeters = maneuver?.distanceMeters ?: 0,
            road = maneuver?.road.orEmpty(),
            arrivalEpochSeconds = maneuver?.arrivalEpochSeconds,
            remainingSeconds = maneuver?.remainingSeconds,
            remainingMeters = maneuver?.remainingMeters,
            song = current?.text,
            playing = current?.playing ?: false,
        )
        if (next == last) return null
        last = next
        return next
    }
}
