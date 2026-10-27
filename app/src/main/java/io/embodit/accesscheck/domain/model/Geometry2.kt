package io.embodit.accesscheck.domain.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ground-plane geometry: every judgement of the app (bumps, the turning disc, the clear floor, doors)
 * is made on the floor plan, in Stage metres on the (x, z) plane. Plain JVM, no SDK types.
 */
data class V2(val x: Float, val z: Float) {
    operator fun plus(o: V2) = V2(x + o.x, z + o.z)

    operator fun minus(o: V2) = V2(x - o.x, z - o.z)

    operator fun times(s: Float) = V2(x * s, z * s)

    operator fun unaryMinus() = V2(-x, -z)

    fun dot(o: V2): Float = x * o.x + z * o.z

    val length: Float
        get() = sqrt(x * x + z * z)

    fun normalized(): V2 {
        val l = length
        return if (l < 1e-9f) this else V2(x / l, z / l)
    }

    fun distanceTo(o: V2): Float = (this - o).length

    fun lerp(o: V2, t: Float): V2 = this + (o - this) * t

    /** The vector turned a quarter to the left when seen from above (y up): `up x v`. */
    fun leftPerp(): V2 = V2(z, -x)

    fun isFinite(): Boolean = x.isFinite() && z.isFinite()

    fun toVec3(y: Float): Vec3 = Vec3(x, y, z)

    companion object {
        val ZERO = V2(0f, 0f)

        fun of(v: Vec3): V2 = V2(v.x, v.z)

        /** Unit heading of a yaw [theta] (rotation about +Y; 0 = Stage ahead, -Z). */
        fun heading(theta: Float): V2 = V2(-sin(theta), -cos(theta))

        /** Unit vector to the right of [heading]. */
        fun side(theta: Float): V2 = V2(cos(theta), -sin(theta))

        /** Yaw of a heading vector (inverse of [heading]). */
        fun yawOf(h: V2): Float = kotlin.math.atan2(-h.x, -h.z)
    }
}

/** Oriented rectangle on the floor plan: centre, unit axis [u] and half sizes along [u] and its left perpendicular. */
data class Obb2(val center: V2, val u: V2, val hu: Float, val hv: Float) {
    val v: V2
        get() = u.leftPerp()

    fun corners(): List<V2> {
        val a = u * hu
        val b = v * hv
        return listOf(center + a + b, center - a + b, center - a - b, center + a - b)
    }

    /** Coordinates of [p] in the rectangle's own frame. */
    fun local(p: V2): V2 {
        val d = p - center
        return V2(d.dot(u), d.dot(v))
    }

    fun contains(p: V2, margin: Float = 0f): Boolean {
        val l = local(p)
        return abs(l.x) <= hu + margin && abs(l.z) <= hv + margin
    }

    /** Nearest point of the rectangle (its area) to [p]. */
    fun closestPoint(p: V2): V2 {
        val l = local(p)
        val cx = l.x.coerceIn(-hu, hu)
        val cz = l.z.coerceIn(-hv, hv)
        return center + u * cx + v * cz
    }

    /** Distance of [p] from the rectangle, 0 inside. */
    fun distanceTo(p: V2): Float = closestPoint(p).distanceTo(p)

    /** Half extent of the rectangle when projected on unit [axis]. */
    fun extentOn(axis: V2): Float = abs(u.dot(axis)) * hu + abs(v.dot(axis)) * hv

    fun grown(by: Float): Obb2 = Obb2(center, u, hu + by, hv + by)

    companion object {
        /** Axis-aligned square of half size [h] at [c]. */
        fun square(c: V2, h: Float): Obb2 = Obb2(c, V2(1f, 0f), h, h)

        /** Rectangle of [length] along the heading of [theta] and [width] across, centred on [c]. */
        fun footprint(c: V2, theta: Float, width: Float, length: Float): Obb2 =
            Obb2(c, V2.heading(theta), length / 2f, width / 2f)
    }
}

/** Separating axis test of two rectangles. */
fun obbIntersects(a: Obb2, b: Obb2): Boolean = obbPenetration(a, b) != null

/**
 * Least overlap of two rectangles along one of their four axes, metres, and that axis pointing from
 * [b] to [a]; null when they do not overlap. The depth is how far [a] has to move to clear [b].
 */
fun obbPenetration(a: Obb2, b: Obb2): Pair<Float, V2>? {
    var best = Float.MAX_VALUE
    var bestAxis = V2(1f, 0f)
    val d = a.center - b.center
    for (axis in arrayOf(a.u, a.v, b.u, b.v)) {
        val overlap = a.extentOn(axis) + b.extentOn(axis) - abs(d.dot(axis))
        if (overlap <= 0f) return null
        if (overlap < best) {
            best = overlap
            bestAxis = if (d.dot(axis) >= 0f) axis else -axis
        }
    }
    return best to bestAxis
}

/** Share of the way from [p0] to [p1] where the segment first enters the rectangle, or null. Slab method in the box frame. */
fun segmentEntersObb(p0: V2, p1: V2, box: Obb2): Float? {
    val a = box.local(p0)
    val b = box.local(p1)
    val dx = b.x - a.x
    val dz = b.z - a.z
    var t0 = 0f
    var t1 = 1f
    if (abs(dx) < 1e-9f) {
        if (abs(a.x) > box.hu) return null
    } else {
        val ta = (-box.hu - a.x) / dx
        val tb = (box.hu - a.x) / dx
        t0 = max(t0, min(ta, tb))
        t1 = min(t1, max(ta, tb))
        if (t0 > t1) return null
    }
    if (abs(dz) < 1e-9f) {
        if (abs(a.z) > box.hv) return null
    } else {
        val ta = (-box.hv - a.z) / dz
        val tb = (box.hv - a.z) / dz
        t0 = max(t0, min(ta, tb))
        t1 = min(t1, max(ta, tb))
        if (t0 > t1) return null
    }
    return t0
}

/** Even-odd test of [p] against a polygon on the floor plan. */
fun pointInPolygon(p: V2, poly: List<V2>): Boolean {
    if (poly.size < 3) return true
    var inside = false
    var j = poly.size - 1
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[j]
        if ((a.z > p.z) != (b.z > p.z)) {
            val x = (b.x - a.x) * (p.z - a.z) / (b.z - a.z) + a.x
            if (p.x < x) inside = !inside
        }
        j = i
    }
    return inside
}

/** Distance from [c] along unit [dir] to the first polygon edge, or null when the ray meets none within [maxDist]. */
fun rayToPolygonEdge(c: V2, dir: V2, poly: List<V2>, maxDist: Float): Float? {
    var best: Float? = null
    var j = poly.size - 1
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[j]
        j = i
        val e = b - a
        val denom = dir.x * e.z - dir.z * e.x
        if (abs(denom) < 1e-9f) continue
        val ac = a - c
        val t = (ac.x * e.z - ac.z * e.x) / denom
        val s = (ac.x * dir.z - ac.z * dir.x) / denom
        if (t >= 0f && t <= maxDist && s >= 0f && s <= 1f) {
            if (best == null || t < best) best = t
        }
    }
    return best
}

/** Smallest rectangle around [points] that is aligned with [u] (unit). */
fun fitObb(points: List<V2>, u: V2): Obb2? {
    if (points.isEmpty()) return null
    val v = u.leftPerp()
    var minU = Float.MAX_VALUE
    var maxU = -Float.MAX_VALUE
    var minV = Float.MAX_VALUE
    var maxV = -Float.MAX_VALUE
    for (p in points) {
        val a = p.dot(u)
        val b = p.dot(v)
        if (a < minU) minU = a
        if (a > maxU) maxU = a
        if (b < minV) minV = b
        if (b > maxV) maxV = b
    }
    if (!minU.isFinite() || !maxU.isFinite() || !minV.isFinite() || !maxV.isFinite()) return null
    val cu = (minU + maxU) / 2f
    val cv = (minV + maxV) / 2f
    return Obb2(u * cu + v * cv, u, (maxU - minU) / 2f, (maxV - minV) / 2f)
}
