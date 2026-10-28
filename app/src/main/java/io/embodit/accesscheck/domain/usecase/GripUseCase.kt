package io.embodit.accesscheck.domain.usecase

import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.ChairPose
import io.embodit.accesscheck.domain.model.Vec3

/**
 * Taking hold of the parked chair (v0.1.2, the user on the headset: "after placing, pressing trigger or grip
 * starts pushing ... and hand gestures too, gripping the chair to push it"). Pure, with its own JVM tests.
 *
 * - The push handle is the bar between the two grips, at handle height, across the back of the chair.
 * - A controller holds while its trigger or grip is pressed (0.6 on, 0.4 off).
 * - A hand holds while it is closed: a fist (index, middle and ring tips within 6 cm of the palm on
 *   average, open again past 8 cm) or a pinch (0.8 on, 0.5 off). A hold starts only near the handle.
 */
class GripUseCase {

    /** The two grips of the push handle (left, right), Stage space. */
    fun grips(pose: ChairPose, model: ChairModel, floorY: Float): Pair<Vec3, Vec3> {
        val h = pose.handle(model)
        val half = pose.side * (model.widthM / 2f)
        val y = floorY + model.handleY
        return (h - half).toVec3(y) to (h + half).toVec3(y)
    }

    /** Distance from [p] to the push handle bar. */
    fun handleDistance(p: Vec3, pose: ChairPose, model: ChairModel, floorY: Float): Float {
        val (a, b) = grips(pose, model, floorY)
        val ab = b - a
        val t = ((p - a).dot(ab) / ab.dot(ab)).coerceIn(0f, 1f)
        return p.distanceTo(a + ab * t)
    }

    fun nearHandle(p: Vec3, pose: ChairPose, model: ChairModel, floorY: Float): Boolean = handleDistance(p, pose, model, floorY) <= NEAR_HANDLE_M

    /** Trigger or grip held, with hysteresis on the analog values; the device's pressed flags count as held. */
    fun controllerHeld(trigger: Float, triggerPressed: Boolean, grip: Float, gripPressed: Boolean, was: Boolean): Boolean {
        if (triggerPressed || gripPressed) return true
        val on = if (was) BUTTON_OFF else BUTTON_ON
        return trigger >= on || grip >= on
    }

    /** A closed fist: the mean of the finger tips' distances to the palm. */
    fun fist(palm: Vec3, tips: List<Vec3>, was: Boolean): Boolean {
        if (tips.isEmpty()) return false
        val mean = tips.sumOf { it.distanceTo(palm).toDouble() }.toFloat() / tips.size
        return mean < if (was) FIST_OPEN_M else FIST_CLOSE_M
    }

    fun pinch(value: Float, was: Boolean): Boolean = value >= if (was) PINCH_OFF else PINCH_ON

    companion object {
        /** A controller or palm this close to the handle bar takes hold of it. */
        const val NEAR_HANDLE_M = 0.25f
        const val BUTTON_ON = 0.6f
        const val BUTTON_OFF = 0.4f
        const val FIST_CLOSE_M = 0.06f
        const val FIST_OPEN_M = 0.08f
        const val PINCH_ON = 0.8f
        const val PINCH_OFF = 0.5f
    }
}
