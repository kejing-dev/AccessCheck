package io.embodit.accesscheck.platform.sense

import android.os.Handler
import android.os.Looper
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.mesh.MeshAnchor
import com.pico.spatial.sense.mesh.MeshTrackingManager
import io.embodit.accesscheck.platform.AcLog

/**
 * Spatial mesh (`MeshTrackingManager`), kept on at its default detail for the whole session (design
 * `container.mrCapabilities`: no start / stop cycling, no clustering). The latest anchor of each UUID
 * is kept; [forEachVertex] walks all their vertices in Stage metres when the occupancy grid is rebuilt
 * (about once a second, see `AccessScene`). Main thread only.
 *
 * Whether the emulator reports any mesh at all is a `needsCheck` of the design; the toilet / basin
 * obstacles that depend on it are headset-only and the debug `obstacle_add` stands in for them.
 */
class MeshTracker {
    private val main = Handler(Looper.getMainLooper())
    private val anchors = LinkedHashMap<String, MeshAnchor>()
    private var subscription: Cancellable? = null
    private var started = false

    /** Bumped on every anchor change. */
    var version = 0
        private set

    val anchorCount: Int
        get() = anchors.size

    fun start() {
        if (started) return
        started = true
        subscription =
            runCatching {
                MeshTrackingManager.subscribeAnchorUpdate { update ->
                    if (Looper.myLooper() == Looper.getMainLooper()) handle(update) else main.post { handle(update) }
                }
            }.onFailure { AcLog.e("MESH subscribe failed: $it", it) }.getOrNull()
        runCatching { MeshTrackingManager.start() }
            .onFailure { AcLog.e("MESH start failed: $it", it) }
        AcLog.i("MESH tracking started state=${runCatching { MeshTrackingManager.state }.getOrNull()}")
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { subscription?.cancel() }
        subscription = null
        runCatching { MeshTrackingManager.stop() }
        anchors.clear()
        version++
    }

    private fun handle(update: AnchorUpdate<MeshAnchor>) {
        if (!started) return
        val anchor = update.anchor
        val id = anchor.anchorUUID.toString()
        when (update.event) {
            AnchorUpdate.Event.ADDED, AnchorUpdate.Event.LOADED, AnchorUpdate.Event.UPDATED -> {
                anchors[id] = anchor
                version++
            }
            AnchorUpdate.Event.REMOVED -> {
                if (anchors.remove(id) != null) version++
            }
            else -> Unit
        }
    }

    /** Every vertex of every mesh anchor, Stage metres. */
    fun forEachVertex(block: (Float, Float, Float) -> Unit) {
        for (anchor in anchors.values) {
            val vertices = runCatching { anchor.vertices }.getOrNull() ?: continue
            val transform = runCatching { anchor.transform }.getOrNull() ?: continue
            val q = transform.quaternion
            val p = transform.position
            for (v in vertices) {
                val w = q.rotateVector(v) + p
                block(w.x, w.y, w.z)
            }
        }
    }
}
