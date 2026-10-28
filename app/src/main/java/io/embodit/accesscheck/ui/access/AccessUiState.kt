package io.embodit.accesscheck.ui.access

import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.ChairPose
import io.embodit.accesscheck.domain.model.ClearFloorRecord
import io.embodit.accesscheck.domain.model.DoorEntry
import io.embodit.accesscheck.domain.model.DoorVerdict
import io.embodit.accesscheck.domain.model.LanguagePref
import io.embodit.accesscheck.domain.model.ObstacleName
import io.embodit.accesscheck.domain.model.Pin
import io.embodit.accesscheck.domain.model.PinTag
import io.embodit.accesscheck.domain.model.Scuff
import io.embodit.accesscheck.domain.model.ThresholdPick
import io.embodit.accesscheck.domain.model.TurnRecord
import io.embodit.accesscheck.domain.model.UnitPref
import io.embodit.accesscheck.domain.model.V2
import io.embodit.accesscheck.domain.model.Vec3
import io.embodit.accesscheck.domain.model.ViewHeight

/** Session phase before the chair layer matters (design S1 / S2 / S20). */
enum class Phase { STARTING, SCANNING, READY, BLOCKED }

enum class BlockReason { NOT_IN_FULL_SPACE, WITHOUT_PERMISSION, DEVICE_NOT_SUPPORTED }

/**
 * The chair layer (design `informationArchitecture.layers.chair`). PLACING (v0.1.1, S31): a see-through
 * ghost follows the pointer's floor point until the trigger / a pinch / the console button puts it down.
 */
enum class ChairMode { NONE, PLACING, WAITING_BEHIND, PUSHING, PARKED, DRAGGING, TURNING }

/** The tool layer. */
enum class Tool { NONE, PIN }

/** The card layer: at most one at a time. */
enum class Card { NONE, DOOR, PIN_TAG, REPORT, MENU, DIALOG }

enum class DialogKind { CLEAR, UNAVAILABLE }

/** A hint with this duration stays until its state goes away. */
const val HINT_STICKY_MS = -1L

enum class HintKind(val durationMs: Long) {
    SCANNING(HINT_STICKY_MS),
    HIT(2000L),
    STUCK(HINT_STICKY_MS),
    WAIT_BEHIND(HINT_STICKY_MS),
    SAVED(3000L),
    PIN_MODE(HINT_STICKY_MS),
    LOST(HINT_STICKY_MS),
    PINS_FULL(12000L),
    SCUFFS_FULL(12000L),
    SIT_DOWN(4000L),
    CROUCH(4000L),
    THRESHOLD_CATCH(3000L),
    DOOR_BLOCKED(3000L),
    STICK_HINT(3000L),
    /** Placing while the pointer misses the floor (aimed up, or farther than 6 m). */
    AIM_FLOOR(HINT_STICKY_MS),
    /** No floor found in 5 s: going on with a virtual one (taste profile D7). */
    VIRTUAL_FLOOR(5000L),
}

data class HintUi(val id: Int, val kind: HintKind, val obstacle: ObstacleName? = null, val text: String = "")

data class DoorUi(
    val entry: DoorEntry,
    val verdict: DoorVerdict,
    /** Which of the two door-plate slots shows it, or -1 when out of range. */
    val slot: Int,
    val expanded: Boolean,
    /** The pending tape value in the expanded card. */
    val draftCm: Int,
    /** Red flash of the gate: the chair crossed a blocked door line (elapsedRealtime, 0 = none). */
    val flashAtMs: Long,
)

data class TurnUi(
    val center: V2,
    val red: List<Boolean>,
    val pass: Boolean,
    val shortCm: Int,
    val blocker: ObstacleName?,
    val blockerDir: V2?,
    val startedAtMs: Long,
)

data class ClearFloorUi(val shortCm: Int, val blocker: ObstacleName)

data class HeightUi(val view: ViewHeight, val eyeM: Float, val diffCm: Int) {
    val ok: Boolean
        get() = kotlin.math.abs(diffCm) <= 5
}

/** Everything the panels and the scene draw. */
data class AccessUiState(
    val phase: Phase = Phase.STARTING,
    val blockReason: BlockReason? = null,
    val hasFloor: Boolean = false,
    /** The floor is a stand-in at a standard height: none was found (taste profile D7). */
    val virtualFloor: Boolean = false,
    val floorY: Float = 0f,
    val planeCount: Int = 0,
    // chair layer
    val chair: ChairModel = ChairModel.MANUAL,
    val chairMode: ChairMode = ChairMode.NONE,
    val pose: ChairPose? = null,
    /** Bumped whenever the ViewModel sets the pose itself (place, rotate keys, snap, size change). */
    val poseSerial: Int = 0,
    val hit: Boolean = false,
    val hitName: ObstacleName? = null,
    val stuck: Boolean = false,
    /** Where the chair was placed (the "outside" of doors, the start point of names). */
    val placedAt: V2? = null,
    // tool / card layers
    val tool: Tool = Tool.NONE,
    val card: Card = Card.NONE,
    val dialog: DialogKind? = null,
    val cardDoorId: String? = null,
    val cardPinId: Int? = null,
    // world content
    val doors: List<DoorUi> = emptyList(),
    val turn: TurnUi? = null,
    val clearFloor: ClearFloorUi? = null,
    val scuffs: List<Scuff> = emptyList(),
    val scuffsFull: Boolean = false,
    val pins: List<Pin> = emptyList(),
    val turnRecords: List<TurnRecord> = emptyList(),
    val clearRecords: List<ClearFloorRecord> = emptyList(),
    /** The door approach pose the chair snaps to (taste profile D1), when a door is near and the chair is parked. */
    val snapTarget: ChairPose? = null,
    // view layer
    val view: ViewHeight = ViewHeight.STAND,
    val height: HeightUi? = null,
    // surfaces
    val hint: HintUi? = null,
    val coach: Boolean = false,
    val trackingLost: Boolean = false,
    val language: LanguagePref = LanguagePref.SYSTEM,
    val units: UnitPref = UnitPref.CM,
    val savedPath: String? = null,
    /** Feedback sounds off (taste profile D15). */
    val muted: Boolean = false,
    /** Bumped by "bring the panel back": the scene parks the console in front of the head again (D9). */
    val recallSerial: Int = 0,
    val stateName: String = "S1",
) {
    val menuOpen: Boolean
        get() = card == Card.MENU

    val reportOpen: Boolean
        get() = card == Card.REPORT

    val chairPlaced: Boolean
        get() = pose != null && chairMode != ChairMode.NONE

    val parked: Boolean
        get() = chairMode == ChairMode.PARKED

    val busy: Boolean
        get() = chairMode == ChairMode.TURNING

    val reportEmpty: Boolean
        get() = scuffs.isEmpty() && pins.isEmpty() && turnRecords.isEmpty() && clearRecords.isEmpty() && doors.none { it.entry.measuredCm != null || it.entry.threshold != ThresholdPick.UNSET }

    val pinsFull: Boolean
        get() = pins.size >= io.embodit.accesscheck.domain.model.AccessRules.PIN_MAX

    val cardDoor: DoorUi?
        get() = cardDoorId?.let { id -> doors.firstOrNull { it.entry.info.id == id } }
}

sealed interface AccessUiEffect {
    data object ExitApp : AccessUiEffect

    data class Sound(val kind: SoundKind) : AccessUiEffect
}

enum class SoundKind { PLACE, BUMP, PASS, FAIL, PIN, SNAP, SAVE, CLICK }

/** Everything that can happen: panel controls, scene callbacks and the debug hooks. */
sealed interface AccessEvent {
    // ---- scene
    data object SceneReady : AccessEvent

    data class PlanesChanged(val count: Int, val hasFloor: Boolean, val floorY: Float, val doorPlaneIds: List<String>) : AccessEvent

    data class TrackingChanged(val lost: Boolean) : AccessEvent

    data class Blocked(val reason: BlockReason?) : AccessEvent

    /** Ten times a second: head position, flat forward, and the floor point under the head. */
    data class HeadSample(val position: Vec3, val forward: Vec3, val foot: V2) : AccessEvent

    /** The scene moved the chair (pushing, dragging, thumbstick); [settled] on a drag end / stick release. */
    data class ChairMoved(val pose: ChairPose, val settled: Boolean) : AccessEvent

    data object DragStarted : AccessEvent

    data class DragEnded(val pose: ChairPose) : AccessEvent

    /** v0.1.2: a controller's trigger / grip or a closed hand took hold of the chair ([by] = the first grip): pushing starts. */
    data class HoldStarted(val by: String) : AccessEvent

    /** The last grip let go with the chair at [pose]: it is parked there. */
    data class HoldEnded(val pose: ChairPose) : AccessEvent

    /** Placing: the trigger put the ghost down at [pose]. */
    data class PlaceConfirmed(val pose: ChairPose) : AccessEvent

    /** Placing: the pointer reached the floor again ([onFloor]) or lost it. */
    data class AimChanged(val onFloor: Boolean) : AccessEvent

    /** A system tap (pinch, emulator click) landed on the chair or its ghost. */
    data object ChairTapped : AccessEvent

    data class Bumped(val obstacleId: String, val name: ObstacleName, val contact: Vec3, val normal: Vec3) : AccessEvent

    data class BumpEnded(val obstacleId: String, val contact: Vec3) : AccessEvent

    data class StuckChanged(val stuck: Boolean) : AccessEvent

    data class DoorCrossed(val doorId: String, val frontWheels: Boolean) : AccessEvent

    data class SurfaceTapped(val planeId: String, val point: Vec3, val normal: Vec3) : AccessEvent

    data object NothingTapped : AccessEvent

    // ---- console
    data object PlaceClicked : AccessEvent

    /** Placing: "put it here" on the console (no controller at hand). */
    data object PlaceHereClicked : AccessEvent

    data object PlaceCancelClicked : AccessEvent

    /** Parked: place the chair again with the pointer. */
    data object ReplaceClicked : AccessEvent

    /** Parked: the chair comes to 1.2 m in front of the person, facing away (D9 / D14). */
    data object BringChairClicked : AccessEvent

    data object MuteClicked : AccessEvent

    /** The console (and its hint) come back in front of the head (D9). */
    data object RecallClicked : AccessEvent

    data object PushClicked : AccessEvent

    data object ParkClicked : AccessEvent

    data object TurnClicked : AccessEvent

    data class RotateClicked(val sign: Int) : AccessEvent

    data class HeightSelected(val view: ViewHeight) : AccessEvent

    data object PinClicked : AccessEvent

    data object ReportClicked : AccessEvent

    data object MoreClicked : AccessEvent

    // ---- menu / dialog
    data class ChairSelected(val model: ChairModel) : AccessEvent

    data class LanguageSelected(val language: LanguagePref) : AccessEvent

    data class UnitsSelected(val units: UnitPref) : AccessEvent

    data object ClearClicked : AccessEvent

    data object ClearConfirmed : AccessEvent

    data object DialogDismissed : AccessEvent

    data object ExitClicked : AccessEvent

    // ---- doors
    data class DoorPlateClicked(val doorId: String) : AccessEvent

    data class ThresholdSelected(val doorId: String, val pick: ThresholdPick) : AccessEvent

    data class MeasureAdjusted(val doorId: String, val deltaCm: Int) : AccessEvent

    data class MeasureConfirmed(val doorId: String) : AccessEvent

    // ---- pins / report
    data class PinTagSelected(val pinId: Int, val tag: PinTag) : AccessEvent

    data class PinDeleted(val pinId: Int) : AccessEvent

    data class ScuffDeleted(val id: Int) : AccessEvent

    data class TurnRecordDeleted(val id: Int) : AccessEvent

    data class ClearRecordDeleted(val id: Int) : AccessEvent

    data object SaveClicked : AccessEvent

    data object CardDismissed : AccessEvent

    data object CoachDismissed : AccessEvent

    data object HintActionClicked : AccessEvent

    data object WentBackground : AccessEvent

    data object CameForeground : AccessEvent

    // ---- debug hooks (debug builds only)
    data class DebugState(val name: String) : AccessEvent

    data class DebugDoor(val widthM: Float, val measured: Boolean, val thresholdCm: Int) : AccessEvent

    data class DebugBlock(val reason: BlockReason?) : AccessEvent

    data class DebugTracking(val lost: Boolean?) : AccessEvent

    data object DebugCoachReset : AccessEvent

    data class DebugObstacleAdd(val x: Float, val z: Float, val w: Float, val d: Float, val h: Float) : AccessEvent

    data object DebugObstaclesClear : AccessEvent

    data class DebugPinFill(val n: Int) : AccessEvent

    data class DebugScuffFill(val n: Int) : AccessEvent

    data class DebugPlaceAt(val x: Float, val z: Float, val yawDeg: Float) : AccessEvent

    /** Where "place" lands the chair from now on: [aheadM] ahead of the head, [sideM] to the right. */
    data class DebugPlaceAhead(val aheadM: Float, val sideM: Float) : AccessEvent

    data class DebugPin(val tag: PinTag?) : AccessEvent

    data object DebugClear : AccessEvent
}
