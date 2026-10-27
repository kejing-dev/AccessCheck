package io.embodit.accesscheck.domain.usecase

import io.embodit.accesscheck.domain.model.DoorInfo
import io.embodit.accesscheck.domain.model.LocationName
import io.embodit.accesscheck.domain.model.ObstacleName
import io.embodit.accesscheck.domain.model.V2

/**
 * Stable names for places in the report (design `rules.scuff`, review R1-9 / R2-9): "door 1, right-hand
 * wall going in, 0.8 m from the door, 15 cm up". "Outside" a door is the side where the chair was
 * placed; left and right are as seen from outside looking in. Without a door the start point (where
 * the chair was placed) is the reference.
 */
class LocateUseCase {

    fun name(point: V2, obstacle: ObstacleName, doors: Collection<DoorInfo>, placedAt: V2?): LocationName {
        val door = doors.minByOrNull { it.center.distanceTo(point) }
        if (door == null) {
            val origin = placedAt ?: V2.ZERO
            return LocationName(null, 0, origin.distanceTo(point), obstacle)
        }
        val outside = placedAt ?: point
        val outsideNormal = if ((outside - door.center).dot(door.normal) >= 0f) door.normal else -door.normal
        val inward = -outsideNormal
        val left = inward.leftPerp()
        val hand = if (obstacle == ObstacleName.WALL) (if ((point - door.center).dot(left) > 0f) -1 else 1) else 0
        return LocationName(door.index, hand, door.center.distanceTo(point), obstacle)
    }
}
