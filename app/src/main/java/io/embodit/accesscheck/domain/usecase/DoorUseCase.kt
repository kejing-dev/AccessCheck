package io.embodit.accesscheck.domain.usecase

import io.embodit.accesscheck.domain.model.AccessRules
import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.DoorEntry
import io.embodit.accesscheck.domain.model.DoorInfo
import io.embodit.accesscheck.domain.model.DoorVerdict
import io.embodit.accesscheck.domain.model.PlaneSurface
import io.embodit.accesscheck.domain.model.ThresholdPick
import io.embodit.accesscheck.domain.model.V2
import io.embodit.accesscheck.domain.model.Verdict
import io.embodit.accesscheck.domain.model.fitObb
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Doors (design `rules.door`, `rules.threshold`, `panels.doorPlate.notes`, review R1-6 / R2-3).
 *
 * The plane width of a doorway reads about 5 cm wider than the ADA clear width, so the estimate is
 * always "about" and can only say "blocked" (raw width under chair width + 5 cm) or "measure to
 * confirm". Green needs a tape measurement of at least 81.5 cm (and of the chair width + 5 cm for a chair set
 * wider than 76.5 cm, v0.1.2) and a threshold pick of "none".
 */
class DoorUseCase {

    /** A doorway from a DOOR / OPENING plane whose bottom edge reaches the floor; null otherwise. */
    fun doorOf(plane: PlaneSurface, floorY: Float, index: Int): DoorInfo? {
        if (!plane.kind.isDoorway || !plane.vertical) return null
        if (plane.minY - floorY > AccessRules.DOOR_BOTTOM_MAX_M) return null
        val n = V2(plane.normal.x, plane.normal.z)
        if (n.length < 0.5f) return null
        val normal = n.normalized()
        val along = normal.leftPerp()
        val points = plane.vertices.map { V2.of(it) }
        val box = fitObb(points, along) ?: return null
        val width = box.hu * 2f
        if (width < 0.4f || width > 2.5f) return null
        return DoorInfo(plane.id, index, box.center, along, normal, width, plane.maxY, floorY)
    }

    fun verdict(entry: DoorEntry, chair: ChairModel): DoorVerdict {
        val minPass = chair.widthCm + AccessRules.DOOR_MARGIN_CM
        // a wide chair (v0.1.2: the size is adjustable) needs its own width + margin before a door counts as passing
        val passAt = maxOf(AccessRules.DOOR_PASS_CM, minPass)
        val measured = entry.measuredCm
        val width: Verdict
        val shown: Int
        if (measured != null) {
            shown = measured
            width =
                when {
                    measured >= passAt -> Verdict.PASS
                    measured >= minPass -> Verdict.TIGHT
                    else -> Verdict.FAIL
                }
        } else {
            shown = max(0, Math.round(entry.info.rawWidthM * 100f - AccessRules.DOOR_AUTO_MINUS_CM))
            width = if (entry.info.rawWidthM * 100f < minPass) Verdict.FAIL else Verdict.TO_MEASURE
        }
        val threshold =
            when (entry.threshold) {
                ThresholdPick.UNSET -> Verdict.TO_MEASURE
                ThresholdPick.NONE -> Verdict.PASS
                ThresholdPick.CM1 -> Verdict.TIGHT
                ThresholdPick.CM2, ThresholdPick.CM3 -> Verdict.FAIL
            }
        return DoorVerdict(shown, measured == null, width, threshold, maxOf(width, threshold))
    }

    /** Horizontal distance from [p] to the doorway's centre. */
    fun distance(door: DoorInfo, p: V2): Float = hypot(p.x - door.center.x, p.z - door.center.z)

    /** True when the segment [a] - [b] crosses the door line within the opening's own span. */
    fun crossesLine(door: DoorInfo, a: V2, b: V2): Boolean {
        val sa = (a - door.center).dot(door.normal)
        val sb = (b - door.center).dot(door.normal)
        if ((sa > 0f) == (sb > 0f)) return false
        val t = sa / (sa - sb)
        val p = a.lerp(b, t)
        return abs((p - door.center).dot(door.along)) <= door.rawWidthM / 2f + 0.2f
    }

    /** Unit normal of the door pointing towards [p] (the chair's side). */
    fun normalTowards(door: DoorInfo, p: V2): V2 = if ((p - door.center).dot(door.normal) >= 0f) door.normal else -door.normal
}
