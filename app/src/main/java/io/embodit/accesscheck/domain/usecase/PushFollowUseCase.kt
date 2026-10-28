package io.embodit.accesscheck.domain.usecase

import io.embodit.accesscheck.domain.model.AccessRules
import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.ChairPose
import io.embodit.accesscheck.domain.model.V2
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp

/**
 * "Handle follows the hand" (design `rules.push`, review R2-1), a pure function with its own JVM tests.
 *
 * Input is the floor projection of the head, filtered by a 3 cm dead zone and a 0.15 s low pass. When
 * pushing starts the handle distance `d` is locked to the person's distance from the handle (0.2 - 1.0 m)
 * and eased to 0.35 m within 0.5 s. Every frame: delta = h - h_prev, s = delta . heading, l = delta . side;
 * s > 0 -> theta += clamp(atan2(l, s), -s k, +s k) with k = 0.9 rad/m (turning radius about 1.1 m);
 * s <= 0 -> theta unchanged. Then handle = h + d heading(theta), centre = handle + L/2 heading(theta).
 * So: walking forward steers with the walk, backing up reverses straight, a sidestep is an arc, turning
 * the head alone (under 3 cm) moves nothing.
 *
 * v0.1.2, pushing with the hands ("trigger or grip on the chair, or a hand closed on the push handle"): the
 * input is the held grip point (controller or palm) instead of the head, and the handle's place relative to
 * that grip is locked as it was when the hold began ([startHeld]: along and across the chair, no ease). With
 * two grips held (both controllers or both hands) the input is their midpoint and the heading follows the line
 * between them, offset by the turn it had when the pair closed, so turning the hands turns the chair.
 */
class PushFollowUseCase {

    data class State(
        val theta: Float,
        /** Current handle distance, where the ease started and the time left of the ease to 0.35 m. */
        val d: Float,
        val dFrom: Float,
        val easeLeftS: Float,
        /** Filtered head point and the dead-zone anchor. */
        val h: V2,
        val anchor: V2,
        val pose: ChairPose,
        /** The handle's offset across the chair from the input (right positive) and the handle distance eased to. */
        val lat: Float = 0f,
        val dTo: Float = AccessRules.HANDLE_M,
        /** Two grips held: chair heading minus the grips' heading when the pair closed; null with one input. */
        val pairOffset: Float? = null,
    )

    /** Locks the handle distance; the chair does not move in this step. */
    fun start(person: V2, pose: ChairPose, model: ChairModel): State {
        val handle = pose.handle(model)
        val d = person.distanceTo(handle).coerceIn(AccessRules.HANDLE_MIN_M, AccessRules.HANDLE_MAX_M)
        return State(theta = pose.theta, d = d, dFrom = d, easeLeftS = AccessRules.HANDLE_EASE_S, h = person, anchor = person, pose = pose)
    }

    /** Locks the handle relative to [grip] (along and across the chair); [pairYaw] when two grips hold. */
    fun startHeld(grip: V2, pose: ChairPose, model: ChairModel, pairYaw: Float? = null): State {
        val to = pose.handle(model) - grip
        val d = to.dot(pose.heading)
        return State(
            theta = pose.theta, d = d, dFrom = d, easeLeftS = 0f, h = grip, anchor = grip, pose = pose,
            lat = to.dot(pose.side), dTo = d, pairOffset = pairYaw?.let { wrap(pose.theta - it) },
        )
    }

    fun step(s: State, personRaw: V2, dtS: Float, model: ChairModel, pairYaw: Float? = null): State {
        val dt = dtS.coerceIn(0f, 0.25f)
        // dead zone, then low pass
        val anchor = if (personRaw.distanceTo(s.anchor) >= AccessRules.DEAD_ZONE_M) personRaw else s.anchor
        val alpha = 1f - exp(-dt / AccessRules.LOW_PASS_TAU_S)
        val h = s.h.lerp(anchor, alpha)
        // the handle distance eases to the design value
        val easeLeft = (s.easeLeftS - dt).coerceAtLeast(0f)
        val q = 1f - easeLeft / AccessRules.HANDLE_EASE_S
        val d = s.dFrom + (s.dTo - s.dFrom) * q
        // steering
        val delta = h - s.h
        val heading = V2.heading(s.theta)
        val side = V2.side(s.theta)
        val forward = delta.dot(heading)
        val lateral = delta.dot(side)
        var theta = s.theta
        val pair = s.pairOffset
        if (pair != null && pairYaw != null) {
            // two grips: the heading follows the line between them (same low pass as the position)
            theta += wrap(pairYaw + pair - theta) * alpha
        } else if (forward > 1e-6f) {
            val wanted = atan2(lateral, forward)
            val cap = forward * AccessRules.TURN_RATE_RAD_PER_M
            // a positive yaw turns the heading to the left (rotation about +Y), so a rightward lateral share turns it down
            theta -= wanted.coerceIn(-cap, cap)
        }
        val newHeading = V2.heading(theta)
        val handle = h + newHeading * d + V2.side(theta) * s.lat
        val center = handle + newHeading * (model.lengthM / 2f)
        return State(theta, d, s.dFrom, easeLeft, h, anchor, ChairPose(center, theta), s.lat, s.dTo, s.pairOffset)
    }

    /**
     * True when the person stands behind the chair: within [AccessRules.BEHIND_HALF_ANGLE_DEG] of the
     * tail direction and 0.2 - 1.0 m from the handle (design `rules.park`).
     */
    fun isBehind(person: V2, pose: ChairPose, model: ChairModel): Boolean {
        val handle = pose.handle(model)
        val to = person - handle
        val dist = to.length
        if (dist < AccessRules.HANDLE_MIN_M || dist > AccessRules.HANDLE_MAX_M) return false
        val tail = -pose.heading
        val cos = to.dot(tail) / dist
        return cos >= COS_BEHIND
    }

    companion object {
        /** cos 60 degrees. */
        private const val COS_BEHIND = 0.5f

        /** The chair heading for two grips: the right one to the right of the heading. Null when closer than 10 cm. */
        fun pairYaw(left: V2, right: V2): Float? {
            val r = right - left
            if (r.length < 0.1f) return null
            // side(theta) = (cos, -sin) points right
            return atan2(-r.z, r.x)
        }

        fun wrap(a: Float): Float {
            var x = a
            while (x > PI.toFloat()) x -= 2f * PI.toFloat()
            while (x < -PI.toFloat()) x += 2f * PI.toFloat()
            return x
        }
    }
}
