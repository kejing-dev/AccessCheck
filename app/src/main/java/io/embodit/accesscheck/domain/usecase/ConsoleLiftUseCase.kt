package io.embodit.accesscheck.domain.usecase

import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.ChairPose
import io.embodit.accesscheck.domain.model.V2
import io.embodit.accesscheck.domain.model.Vec3
import io.embodit.accesscheck.domain.model.segmentEntersObb

/**
 * Keeps the head-following console clear of the wheelchair (design v2 `occlusionRule`, QA q1-1: pushing,
 * the console fell behind the chair's back). [liftedY]: the console must not sit in the chair's box
 * (footprint + 5 cm, up to the handle + 0.10 m) nor behind it as seen from the eye; then it rises to 0.15 m
 * above the box. (v0.1.1 also hung it beside the push handle; v0.1.2 dropped that: the user wants the panels
 * to follow the head and face it, and the pushed chair takes no taps, so the console stays reachable.)
 *
 * Pure math, JVM tested.
 */
class ConsoleLiftUseCase {

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
        const val BOX_GROW_M = 0.05f
        const val BOX_ABOVE_HANDLE_M = 0.10f
        const val LIFT_CLEAR_M = 0.15f
    }
}
