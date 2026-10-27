package io.embodit.accesscheck.domain.model

/** Every number of the design package in one place (design `rules`, `entities3d`, ADA 2010 references). */
object AccessRules {
    /** Turning circle diameter, ADA 304.3.1. */
    const val TURN_DIAMETER_M = 1.525f
    const val TURN_RADIUS_M = TURN_DIAMETER_M / 2f
    const val TURN_SEGMENTS = 72

    /** Clear floor space, ADA 305.3. */
    const val CLEAR_FLOOR_W_M = 0.76f
    const val CLEAR_FLOOR_L_M = 1.22f

    /** Door clear width, ADA 404.2.3. */
    const val DOOR_PASS_CM = 81.5f

    /** The plane width of a doorway reads about this much wider than the ADA clear width. */
    const val DOOR_AUTO_MINUS_CM = 5f

    /** Chair width plus this is the least a doorway can have before it is "blocked". */
    const val DOOR_MARGIN_CM = 5f

    /** Side reach band, ADA 308.3 (unobstructed). */
    const val REACH_LOW_M = 0.38f
    const val REACH_HIGH_M = 1.22f
    const val REACH_WALL_WITHIN_M = 0.6f

    /** Eye heights of the two viewpoints and the tolerance of "that is the height". */
    const val EYE_SEATED_M = 1.20f
    const val EYE_CHILD_M = 1.00f
    const val EYE_TOLERANCE_M = 0.05f

    /** Collision height: obstacles whose lower edge is above this do not take part. */
    const val COLLIDE_TOP_M = 0.68f

    /** A table top at or above this leaves knee space and is no obstacle. */
    const val TABLE_KNEE_M = 0.71f

    /** Mesh vertices between these heights above the floor fill the occupancy grid. */
    const val MESH_LOW_M = 0.10f
    const val MESH_HIGH_M = 0.68f
    const val MESH_WALL_CLEAR_M = 0.05f
    const val GRID_CELL_M = 0.05f

    /** A doorway counts only when its bottom edge is this close to the floor. */
    const val DOOR_BOTTOM_MAX_M = 0.10f
    const val DOOR_SHOW_M = 1.5f
    const val DOOR_HIDE_M = 2.5f

    /** Push follow (design `rules.push`). */
    const val HANDLE_M = 0.35f
    const val HANDLE_MIN_M = 0.2f
    const val HANDLE_MAX_M = 1.0f
    const val HANDLE_EASE_S = 0.5f
    const val DEAD_ZONE_M = 0.03f
    const val LOW_PASS_TAU_S = 0.15f
    const val TURN_RATE_RAD_PER_M = 0.9f

    /** "Behind the chair": within this angle of the tail direction and this range of the handle. */
    const val BEHIND_HALF_ANGLE_DEG = 60f

    /** A person standing still while the chair presses a wall for this long gets the stuck hint. */
    const val STUCK_MS = 2000L
    const val STUCK_SPEED_M_S = 0.05f

    /** Pools. */
    const val SCUFF_MAX = 40
    const val SCRAPE_MAX = 20
    const val PIN_MAX = 20

    /** The chair lands this far ahead when placed. */
    const val PLACE_AHEAD_M = 1.2f

    /** Rotate keys turn the parked chair by this. */
    const val ROTATE_STEP_DEG = 15f

    /** Thumbstick rotation rate at full deflection and its dead zone (taste profile D1). */
    const val STICK_RATE_DEG_S = 90f
    const val STICK_DEAD = 0.15f

    /** Snap to the door approach frame within this distance and angle (taste profile D1). */
    const val SNAP_DIST_M = 0.25f
    const val SNAP_ANGLE_DEG = 15f
    const val SNAP_FRONT_GAP_M = 0.30f

    const val TURN_ANIM_MS = 1000L
    const val HIT_RELEASE_MS = 300L
    const val HIT_HINT_REPEAT_MS = 2000L
    const val CLEAR_FLOOR_RECORD_MS = 2000L
}

/** The two wheelchairs of the design (`assumptions`): the footprint sizes are the truth. */
enum class ChairModel(val tag: String, val widthM: Float, val lengthM: Float, val heightM: Float, val handleY: Float, val rearWheelR: Float) {
    MANUAL("manual", 0.68f, 1.10f, 0.92f, 0.92f, 0.30f),
    POWER("power", 0.70f, 1.20f, 1.00f, 1.00f, 0.22f);

    val widthCm: Int
        get() = Math.round(widthM * 100f)

    val lengthCm: Int
        get() = Math.round(lengthM * 100f)

    companion object {
        fun fromTag(tag: String?): ChairModel = entries.firstOrNull { it.tag == tag } ?: MANUAL
    }
}

/** Where the chair stands: footprint centre on the floor plan and heading yaw. */
data class ChairPose(val center: V2, val theta: Float) {
    val heading: V2
        get() = V2.heading(theta)

    val side: V2
        get() = V2.side(theta)

    fun footprint(model: ChairModel): Obb2 = Obb2.footprint(center, theta, model.widthM, model.lengthM)

    /** The push handle: half a length behind the centre. */
    fun handle(model: ChairModel): V2 = center - heading * (model.lengthM / 2f)

    /** The clear floor rectangle 0.76 x 1.22 around the chair, along its heading. */
    fun clearFloor(): Obb2 = Obb2.footprint(center, theta, AccessRules.CLEAR_FLOOR_W_M, AccessRules.CLEAR_FLOOR_L_M)
}

enum class LanguagePref(val tag: String) {
    SYSTEM("system"),
    ZH("zh"),
    EN("en");

    companion object {
        fun fromTag(tag: String?): LanguagePref = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

enum class UnitPref(val tag: String) {
    CM("cm"),
    IN("in");

    companion object {
        fun fromTag(tag: String?): UnitPref = entries.firstOrNull { it.tag == tag } ?: CM
    }
}

data class Settings(
    val chair: ChairModel = ChairModel.MANUAL,
    val language: LanguagePref = LanguagePref.SYSTEM,
    /** Null = not chosen yet: cm, or in when the system region is en-US. */
    val units: UnitPref? = null,
    val coachSeen: Boolean = false,
)

/** What kind of thing an obstacle is, for naming it ("by the cabinet"). */
enum class ObstacleName {
    WALL,
    TABLE,
    CABINET,
    WASHING_MACHINE,
    REFRIGERATOR,
    BED,
    SOFA,
    CHAIR,
    LOW_OBSTACLE,
    OBSTACLE;

    companion object {
        fun of(kind: SurfaceKind): ObstacleName =
            when (kind) {
                SurfaceKind.WALL -> WALL
                SurfaceKind.TABLE -> TABLE
                SurfaceKind.CABINET -> CABINET
                SurfaceKind.WASHING_MACHINE -> WASHING_MACHINE
                SurfaceKind.REFRIGERATOR -> REFRIGERATOR
                SurfaceKind.BED -> BED
                SurfaceKind.SOFA -> SOFA
                SurfaceKind.CHAIR -> CHAIR
                else -> OBSTACLE
            }
    }
}

enum class ObstacleKind { WALL, FURNITURE, MESH, VIRTUAL }

/**
 * One solid thing on the floor plan: a rectangle with the heights of its lower and upper edge above
 * the floor. Only obstacles whose lower edge is below [AccessRules.COLLIDE_TOP_M] take part.
 */
data class Obstacle(
    val id: String,
    val kind: ObstacleKind,
    val name: ObstacleName,
    val box: Obb2,
    val yLow: Float,
    val yHigh: Float,
    /** The plane this came from (walls and furniture), for the scuff's surface. */
    val planeId: String? = null,
) {
    val participates: Boolean
        get() = yLow < AccessRules.COLLIDE_TOP_M
}

/** A doorway seen in the room (a DOOR / OPENING plane whose bottom edge reaches the floor). */
data class DoorInfo(
    val id: String,
    /** 1-based order of first sighting ("Door 1"). */
    val index: Int,
    /** Floor point under the middle of the opening. */
    val center: V2,
    /** Unit vector along the opening (along the wall). */
    val along: V2,
    /** Unit horizontal normal of the opening (sign arbitrary). */
    val normal: V2,
    val rawWidthM: Float,
    /** Stage height of the opening's top edge and of the floor under it. */
    val topY: Float,
    val floorY: Float,
) {
    val rawWidthCm: Int
        get() = Math.round(rawWidthM * 100f)

    val heightM: Float
        get() = (topY - floorY).coerceIn(1.5f, 2.6f)
}

enum class ThresholdPick(val tag: String, val cm: Int) {
    UNSET("unset", -1),
    NONE("none", 0),
    CM1("1", 1),
    CM2("2", 2),
    CM3("3", 3);

    companion object {
        fun fromTag(tag: String?): ThresholdPick = entries.firstOrNull { it.tag == tag } ?: UNSET

        fun fromCm(cm: Int): ThresholdPick =
            when {
                cm < 0 -> UNSET
                cm == 0 -> NONE
                cm == 1 -> CM1
                cm == 2 -> CM2
                else -> CM3
            }
    }
}

/** Best first, so that `maxOf` gives the overall (worst) verdict. */
enum class Verdict { PASS, TO_MEASURE, TIGHT, FAIL }

/** The user's facts about one door: a tape measurement and the threshold pick. */
data class DoorEntry(val info: DoorInfo, val measuredCm: Int? = null, val threshold: ThresholdPick = ThresholdPick.UNSET)

data class DoorVerdict(
    val widthCm: Int,
    /** True when the width is the plane estimate ("about"). */
    val approx: Boolean,
    val width: Verdict,
    val threshold: Verdict,
    val overall: Verdict,
)

enum class PinTag(val tag: String) {
    OUT_OF_REACH("reach"),
    CANT_SEE("see"),
    HAZARD("hazard"),
}

enum class ViewHeight(val tag: String, val targetM: Float) {
    STAND("stand", 0f),
    SEATED("seat", AccessRules.EYE_SEATED_M),
    CHILD("child", AccessRules.EYE_CHILD_M),
}

/** Which door a spot is near, which side of it, and how far. */
data class LocationName(
    /** Door index, or null when the room has no door yet ("the start"). */
    val doorIndex: Int?,
    /** +1 right hand, -1 left hand (looking in from outside), 0 when the thing is not a wall. */
    val hand: Int,
    val distanceM: Float,
    val obstacle: ObstacleName,
)

/** A scuff left on a surface: one or two ellipses and the scrape between them. */
data class Scuff(
    val id: Int,
    val obstacleId: String,
    val name: ObstacleName,
    val enter: Vec3,
    /** Unit normal of the surface at the mark, towards the room. */
    val normal: Vec3,
    val exit: Vec3?,
    /** 1 - 3: repeated bumps at the same spot darken the mark. */
    val level: Int,
    val heightM: Float,
    val where: LocationName,
    val atMs: Long,
)

data class Pin(
    val id: Int,
    val position: Vec3,
    val normal: Vec3,
    val tag: PinTag?,
    val heightM: Float,
    val where: LocationName,
    val childMode: Boolean,
)

data class TurnRecord(val id: Int, val where: LocationName, val pass: Boolean, val shortCm: Int, val blocker: ObstacleName?, val atMs: Long)

data class ClearFloorRecord(val id: Int, val where: LocationName, val shortCm: Int, val blocker: ObstacleName, val atMs: Long)

/** Why room sensing cannot run (the tracking provider's support state). */
enum class BlockReason { NOT_IN_FULL_SPACE, WITHOUT_PERMISSION, DEVICE_NOT_SUPPORTED }
