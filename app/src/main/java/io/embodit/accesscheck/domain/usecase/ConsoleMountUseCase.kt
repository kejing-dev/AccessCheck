package io.embodit.accesscheck.domain.usecase

import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.ChairPose
import io.embodit.accesscheck.domain.model.V2
import io.embodit.accesscheck.domain.model.Vec3
import io.embodit.accesscheck.domain.model.segmentEntersObb
import kotlin.math.atan2

/**
 * Where the console goes around the wheelchair (design v2 `panels.console.placement` ② and
 * `occlusionRule`, QA q1-1: pushing, the console at the head's lower left fell behind the chair's back
 * and every tap landed on the chair).
 *
 * - [mount]: beside the push handle, outside the chair's footprint (inner edge 8 cm clear of it), level
 *   with the handle along the chair, top edge at handle height + 0.40 m held 0.20 - 0.55 m under the eye,
 *   turned towards the head about the vertical only.
 * - [liftedY]: the head-following console must not sit in the chair's box (footprint + 5 cm, up to the
 *   handle + 0.10 m) nor behind it as seen from the eye; then it rises to 0.15 m above the box.
 *
 * Pure math, JVM tested.
 */
class ConsoleMountUseCase {

    /** Centre and yaw (+Z of the panel towards the head) of the console beside the handle; [right] picks the side. */
    fun mount(pose: ChairPose, model: ChairModel, floorY: Float, head: Vec3, halfW: Float, halfH: Float, right: Boolean): Pair<Vec3, Float> {
        val out = model.widthM / 2f + SIDE_GAP_M + halfW
        val handle = pose.handle(model)
        val spot = handle + pose.side * (if (right) out else -out)
        val top = (floorY + model.handleY + TOP_ABOVE_HANDLE_M).coerceIn(head.y - TOP_BELOW_EYE_MAX_M, head.y - TOP_BELOW_EYE_MIN_M)
        val center = Vec3(spot.x, top - halfH, spot.z)
        val to = head - center
        return center to atan2(to.x, to.z)
    }

    /** The floor point beside the handle where [mount] puts the console (for the wall check). */
    fun mountSpot(pose: ChairPose, model: ChairModel, halfW: Float, right: Boolean): V2 {
        val out = model.widthM / 2f + SIDE_GAP_M + halfW
        return pose.handle(model) + pose.side * (if (right) out else -out)
    }

    /**
     * The height the console's centre needs so that the chair neither holds nor hides it: [panel]'s own
     * height when clear, else 0.15 m above the chair's box (plus the half height [halfH]).
     */
    fun liftedY(panel: Vec3, halfH: Float, pose: ChairPose, model: ChairModel, floorY: Float, head: Vec3): Float {
        val box = pose.footprint(model).grown(BOX_GROW_M)
        val top = floorY + model.handleY + BOX_ABOVE_HANDLE_M
        val inside = box.contains(V2.of(panel)) && panel.y - halfH < top
        // the sight line is lowest inside the box where it enters or leaves it
        val tIn = segmentEntersObb(V2.of(head), V2.of(panel), box)
        val tOut = segmentEntersObb(V2.of(panel), V2.of(head), box)?.let { 1f - it }
        val hidden = tIn != null && tOut != null && minOf(head.y + (panel.y - head.y) * tIn, head.y + (panel.y - head.y) * tOut) < top
        if (!inside && !hidden) return panel.y
        return maxOf(panel.y, top + LIFT_CLEAR_M + halfH)
    }

    companion object {
        const val SIDE_GAP_M = 0.08f
        const val TOP_ABOVE_HANDLE_M = 0.40f
        const val TOP_BELOW_EYE_MIN_M = 0.20f
        const val TOP_BELOW_EYE_MAX_M = 0.55f
        const val BOX_GROW_M = 0.05f
        const val BOX_ABOVE_HANDLE_M = 0.10f
        const val LIFT_CLEAR_M = 0.15f
    }
}
