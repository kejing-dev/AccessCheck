package io.embodit.accesscheck.domain.usecase

import io.embodit.accesscheck.domain.model.AccessRules
import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.ChairPose
import io.embodit.accesscheck.domain.model.PointerRay
import io.embodit.accesscheck.domain.model.Quat4
import io.embodit.accesscheck.domain.model.V2
import io.embodit.accesscheck.domain.model.Vec3
import kotlin.math.abs

/**
 * Pointing the chair onto the floor (taste profile D1 "the landing point follows the controller ray",
 * D14 "keep clear of the person"): the ray's floor point, the last good one while the ray misses, the
 * push out of the clearance around the head, the facing "away from the person", the grab offset of a
 * drag, and the ray's hits on the chair and on panels (a ray resting on a panel moves nothing).
 * Pure math, JVM tested.
 */
class FloorAimUseCase(
    private val maxM: Float = AccessRules.AIM_MAX_M,
    private val clearM: Float = AccessRules.CLEARANCE_M,
) {
    /** What the ghost follows: a fresh floor point ([onFloor]) or the last good one. */
    data class Aim(val point: V2?, val onFloor: Boolean)

    /** Follows one ray over frames and keeps its last floor point while it misses. */
    class Tracker(private val rule: FloorAimUseCase) {
        var last: V2? = null
            private set

        fun update(ray: PointerRay, floorY: Float): Aim {
            val hit = rule.floorPoint(ray, floorY)
            if (hit != null) last = hit
            return Aim(last, hit != null)
        }

        fun reset(at: V2? = null) {
            last = at
        }
    }

    /** Where [ray] meets the floor plane, or null: aimed level or up, or farther than [maxM] (horizontally). */
    fun floorPoint(ray: PointerRay, floorY: Float): V2? {
        val d = ray.direction
        if (!d.isFinite() || d.y > -MIN_DOWN) return null
        val t = (floorY - ray.origin.y) / d.y
        if (t <= 0f) return null
        val p = ray.origin + d * t
        val reach = V2.of(p - ray.origin).length
        if (reach > maxM) return null
        return V2.of(p)
    }

    /**
     * Pushes [center] out of the clearance circle around the head ([clearM], horizontal): along the line
     * from the head, or straight ahead of the head ([forward]) when the point is right underneath.
     */
    fun clear(center: V2, head: V2, forward: V2): V2 {
        val away = center - head
        val d = away.length
        if (d >= clearM) return center
        val dir = if (d > 1e-3f) away * (1f / d) else flat(forward)
        return head + dir * clearM
    }

    /** The chair faces away from the person (their pushing direction): its heading points from the head to [center]. */
    fun awayYaw(center: V2, head: V2, fallback: Float): Float {
        val away = center - head
        return if (away.length < 1e-3f) fallback else V2.yawOf(away.normalized())
    }

    /** The ghost of placing: the aim point pushed clear, facing away, plus the thumbstick's turn [extraYaw]. */
    fun ghost(aim: V2, head: V2, forward: V2, extraYaw: Float): ChairPose {
        val c = clear(aim, head, forward)
        return ChairPose(c, awayYaw(c, head, V2.yawOf(flat(forward))) + extraYaw)
    }

    /** A drag keeps the grab point under the ray: centre = floor point + the offset taken at the grab. */
    fun dragged(aim: V2, grabOffset: V2, head: V2, forward: V2): V2 = clear(aim + grabOffset, head, forward)

    /**
     * Distance along [ray] to the chair's box (footprint x height, on the floor), or null. Slab test in
     * the chair's own frame.
     */
    fun hitsChair(ray: PointerRay, pose: ChairPose, model: ChairModel, floorY: Float): Float? {
        val turn = Quat4.axisAngle(Vec3.UP, pose.theta)
        val back = turn.conjugate()
        val center = Vec3(pose.center.x, floorY + model.heightM / 2f, pose.center.z)
        val o = back.rotate(ray.origin - center)
        val d = back.rotate(ray.direction)
        return slab(o, d, Vec3(model.widthM / 2f, model.heightM / 2f, model.lengthM / 2f))
    }

    /**
     * Distance along [ray] to a flat panel ([center], [rotation], half sizes [hw] x [hh] in its own x / y),
     * or null. Either face counts.
     */
    fun hitsPanel(ray: PointerRay, center: Vec3, rotation: Quat4, hw: Float, hh: Float): Float? {
        val n = rotation.rotate(Vec3(0f, 0f, 1f))
        val denom = n.dot(ray.direction)
        if (abs(denom) < 1e-5f) return null
        val t = n.dot(center - ray.origin) / denom
        if (t <= 0f) return null
        val local = rotation.conjugate().rotate(ray.origin + ray.direction * t - center)
        return if (abs(local.x) <= hw && abs(local.y) <= hh) t else null
    }

    /** Distance along [ray] to the floor plane (no reach limit), or null when it does not go down. */
    fun floorDistance(ray: PointerRay, floorY: Float): Float? {
        if (ray.direction.y > -MIN_DOWN) return null
        val t = (floorY - ray.origin.y) / ray.direction.y
        return if (t > 0f) t else null
    }

    private fun slab(o: Vec3, d: Vec3, h: Vec3): Float? {
        var tMin = 0f
        var tMax = Float.MAX_VALUE
        val os = floatArrayOf(o.x, o.y, o.z)
        val ds = floatArrayOf(d.x, d.y, d.z)
        val hs = floatArrayOf(h.x, h.y, h.z)
        for (i in 0..2) {
            if (abs(ds[i]) < 1e-6f) {
                if (abs(os[i]) > hs[i]) return null
                continue
            }
            var t1 = (-hs[i] - os[i]) / ds[i]
            var t2 = (hs[i] - os[i]) / ds[i]
            if (t1 > t2) t1 = t2.also { t2 = t1 }
            if (t1 > tMin) tMin = t1
            if (t2 < tMax) tMax = t2
            if (tMin > tMax) return null
        }
        return tMin
    }

    private fun flat(forward: V2): V2 = if (forward.length < 0.1f) V2(0f, -1f) else forward.normalized()

    companion object {
        /** A ray has to go down at least this much (about 1 degree) to count as aimed at the floor. */
        private const val MIN_DOWN = 0.02f
    }
}
