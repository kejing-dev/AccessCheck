package io.embodit.accesscheck.ui.access.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import io.embodit.accesscheck.ui.access.AccessEvent
import io.embodit.accesscheck.ui.access.AccessUiState
import java.util.concurrent.ConcurrentHashMap

/**
 * Seams for QA's adb harness. Nothing here is product UI and nothing here does anything by itself:
 * the hooks are only driven by the debug-build receiver (`src/debug`). In a release build no receiver
 * exists, [AcProbe.enabled] stays false and the bounds modifiers are no-ops.
 */
object AcProbe {
    @Volatile var enabled: Boolean = false

    data class ControlBounds(val cx: Float, val cy: Float, val w: Float, val h: Float)

    private val controls = ConcurrentHashMap<String, ConcurrentHashMap<String, ControlBounds>>()
    private val panelSizes = ConcurrentHashMap<String, Pair<Int, Int>>()

    fun report(panel: String, name: String, bounds: ControlBounds, panelWidth: Int, panelHeight: Int) {
        controls.getOrPut(panel) { ConcurrentHashMap() }[name] = bounds
        panelSizes[panel] = panelWidth to panelHeight
    }

    fun forget(panel: String, name: String) {
        controls[panel]?.remove(name)
    }

    fun controlsOf(panel: String): Map<String, ControlBounds> = controls[panel]?.toSortedMap() ?: emptyMap()

    fun sizeOf(panel: String): Pair<Int, Int>? = panelSizes[panel]

    /** What the screen exposes to the debug receiver while it is composed. */
    interface Host {
        fun dispatch(event: AccessEvent)

        fun uiState(): AccessUiState

        fun dumpJson(): String

        fun scene(): SceneProbe?

        /** Presses a panel control by its stable name; false when no such control is composed. */
        fun pressControl(name: String): Boolean
    }

    interface SceneProbe {
        fun poseLine(): String

        fun panelLines(): List<String>

        fun planesSummary(): List<String>

        fun parkPanel(): String

        fun setFpsLine(on: Boolean): String

        /** Overrides the head's floor point (and heading) for the push follow; null clears it. */
        fun setDebugHead(x: Float?, z: Float?, yawDeg: Float?): String

        /** Overrides the eye height above the floor; null clears it. */
        fun setDebugEye(heightM: Float?): String

        /** Draws (or hides) every obstacle of the field. */
        fun showObstacles(on: Boolean): String

        /** Moves the parked chair as a drag would (debug `drag`). */
        fun debugDrag(x: Float, z: Float): String

        /** Stands in for the controller: its ray, trigger (0..1) and thumbstick x; null leaves a value as it is. */
        fun setDebugPointer(ray: io.embodit.accesscheck.domain.model.PointerRay?, trigger: Float?, stickX: Float?): String

        /** Gives the controller back. */
        fun clearDebugPointer(): String

        /** Points the stand-in controller at the floor point (x, z) from beside the head. */
        fun aimAt(x: Float, z: Float): String

        /** Same, at the floor point [aheadM] in front of the head (level) and [sideM] to its right. */
        fun aimAhead(aheadM: Float, sideM: Float): String

        /** v0.1.2 stand-ins: the stand-in controller's grip (0..1) and moving it level by (dx, dz). */
        fun setDebugGrip(value: Float): String

        fun moveDebugPointer(dx: Float, dz: Float): String

        /** A stand-in tracked hand: palm in Stage metres, [closed] = open | fist | pinch; null palm = real hands. */
        fun setDebugHand(palm: io.embodit.accesscheck.domain.model.Vec3?, closed: String, right: Boolean): String

        /** The stand-in hand at the chair's left / right grip, 5 cm behind the handle bar. */
        fun handAtHandle(closed: String, right: Boolean): String

        fun moveDebugHand(dx: Float, dz: Float): String
    }

    @Volatile var host: Host? = null

    /** Panel controls by name (registered by `Modifier.probe`): the debug command `ui <name>` presses one. */
    private val clicks = ConcurrentHashMap<String, () -> Unit>()

    fun registerClick(name: String, onClick: () -> Unit) {
        clicks[name] = onClick
    }

    fun unregisterClick(name: String) {
        clicks.remove(name)
    }

    fun click(name: String): Boolean {
        val c = clicks[name] ?: return false
        c()
        return true
    }
}

/** Id of the attachment panel whose content is being composed. */
val LocalProbePanel = compositionLocalOf { "" }

/** Registers the on-panel bounds of a control under a stable [name]. A no-op unless the app is debuggable. */
@Composable
fun Modifier.probe(name: String, onClick: (() -> Unit)? = null): Modifier {
    if (!AcProbe.enabled) return this
    val panel = LocalProbePanel.current
    DisposableEffect(panel, name, onClick) {
        if (onClick != null) AcProbe.registerClick(name, onClick)
        onDispose {
            AcProbe.forget(panel, name)
            if (onClick != null) AcProbe.unregisterClick(name)
        }
    }
    return this.onGloballyPositioned { c ->
        if (c.isAttached) {
            val root = c.findRootCoordinates()
            val topLeft = root.localPositionOf(c, Offset.Zero)
            val bottomRight = root.localPositionOf(c, Offset(c.size.width.toFloat(), c.size.height.toFloat()))
            AcProbe.report(
                panel,
                name,
                AcProbe.ControlBounds((topLeft.x + bottomRight.x) / 2f, (topLeft.y + bottomRight.y) / 2f, bottomRight.x - topLeft.x, bottomRight.y - topLeft.y),
                root.size.width,
                root.size.height,
            )
        }
    }
}
