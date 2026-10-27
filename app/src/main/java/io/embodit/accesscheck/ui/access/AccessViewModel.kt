package io.embodit.accesscheck.ui.access

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.accesscheck.domain.model.AccessRules
import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.ChairPose
import io.embodit.accesscheck.domain.model.ClearFloorRecord
import io.embodit.accesscheck.domain.model.DoorEntry
import io.embodit.accesscheck.domain.model.DoorInfo
import io.embodit.accesscheck.domain.model.LanguagePref
import io.embodit.accesscheck.domain.model.LocationName
import io.embodit.accesscheck.domain.model.Obb2
import io.embodit.accesscheck.domain.model.Obstacle
import io.embodit.accesscheck.domain.model.ObstacleKind
import io.embodit.accesscheck.domain.model.ObstacleName
import io.embodit.accesscheck.domain.model.Pin
import io.embodit.accesscheck.domain.model.PinTag
import io.embodit.accesscheck.domain.model.Scuff
import io.embodit.accesscheck.domain.model.Settings
import io.embodit.accesscheck.domain.model.SurfaceKind
import io.embodit.accesscheck.domain.model.ThresholdPick
import io.embodit.accesscheck.domain.model.TurnRecord
import io.embodit.accesscheck.domain.model.UnitPref
import io.embodit.accesscheck.domain.model.V2
import io.embodit.accesscheck.domain.model.Vec3
import io.embodit.accesscheck.domain.model.Verdict
import io.embodit.accesscheck.domain.model.ViewHeight
import io.embodit.accesscheck.domain.usecase.DoorUseCase
import io.embodit.accesscheck.domain.usecase.FloorAimUseCase
import io.embodit.accesscheck.domain.usecase.LocateUseCase
import io.embodit.accesscheck.domain.usecase.PushFollowUseCase
import io.embodit.accesscheck.platform.AcLog
import io.embodit.accesscheck.platform.AccessGraph
import io.embodit.accesscheck.platform.report.ReportWriter
import io.embodit.accesscheck.ui.access.components.ReportText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The whole state machine of the app (design `states` S1 - S30, `rules`, `informationArchitecture.layers`).
 * Events come from the panels, the scene (taps, bumps, head samples ten times a second, planes,
 * tracking) and the debug hooks; the state goes out as [AccessUiState]. Judgements that need the floor
 * plan (turning disc, clear floor, doors) read the shared `ObstacleField`. Main thread only.
 */
class AccessViewModel(private val appContext: Context) : ViewModel() {

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AccessViewModel(context.applicationContext) as T
    }

    private val store = AccessGraph.settings(appContext)
    private val field = AccessGraph.field
    private val planeTracker = AccessGraph.planes
    private val doorRule = DoorUseCase()
    private val locate = LocateUseCase()
    private val pushRule = PushFollowUseCase()
    private val aim = FloorAimUseCase()

    private val _state = MutableStateFlow(AccessUiState())
    val state: StateFlow<AccessUiState> = _state.asStateFlow()
    private val _effects = MutableSharedFlow<AccessUiEffect>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val effects = _effects.asSharedFlow()

    // ---- the model
    private var settings: Settings = store.load()
    private var phase = Phase.STARTING
    private var blockReason: BlockReason? = null
    private var sceneReady = false
    private var hasFloor = false
    private var floorY = 0f
    private var planeCount = 0
    private var chairMode = ChairMode.NONE
    private var pose: ChairPose? = null
    private var poseSerial = 0
    private var placedAt: V2? = null
    private var coachStart: V2? = null
    private var pinnedDoorId: String? = null
    private var hintHoldMs: Long? = null
    private var placeAheadM = AccessRules.PLACE_AHEAD_M
    /** Placing again from a parked chair: where it stood (cancel goes back there); null on the first placing. */
    private var placingFrom: ChairPose? = null
    private var aimLost = false
    private var virtualFloor = false
    private var scanJob: Job? = null
    private var recallSerial = 0
    private var placeSideM = 0f
    private var hit = false
    private var hitName: ObstacleName? = null
    private var stuck = false
    private var tool = Tool.NONE
    private var card = Card.NONE
    private var dialog: DialogKind? = null
    private var cardDoorId: String? = null
    private var cardPinId: Int? = null
    private var view = ViewHeight.STAND
    private var height: HeightUi? = null
    private var hint: HintUi? = null
    private var hintSerial = 0
    private var hintJob: Job? = null
    private var turnJob: Job? = null
    private var coach = false
    private var trackingLost = false
    private var savedPath: String? = null
    private var turn: TurnUi? = null
    private var clearFloor: ClearFloorUi? = null
    private var clearSinceMs = NEVER
    private var clearRecorded = false
    private val doorIndex = LinkedHashMap<String, Int>()
    private val doorEntries = LinkedHashMap<String, DoorEntry>()
    private val virtualDoors = LinkedHashMap<String, DoorInfo>()
    private val doorDrafts = HashMap<String, Int>()
    private var expandedDoorId: String? = null
    private val doorFlash = HashMap<String, Long>()
    private val scuffs = ArrayList<Scuff>()
    private val openScuffs = HashMap<String, Int>()
    private val pins = ArrayList<Pin>()
    private val turnRecords = ArrayList<TurnRecord>()
    private val clearRecords = ArrayList<ClearFloorRecord>()
    private var uid = 1
    private var lastHitHintMs = HashMap<ObstacleName, Long>()
    private var lastStateName = ""
    private var forcedState: String? = null

    // ---- head
    private var head: Vec3? = null
    private var headForward = Vec3.AHEAD
    private var foot: V2? = null

    init {
        publish()
    }

    // =========================================================================================
    // events
    // =========================================================================================

    fun onEvent(event: AccessEvent) {
        when (event) {
            // scene
            AccessEvent.SceneReady -> {
                sceneReady = true
                refreshPhase()
            }
            is AccessEvent.PlanesChanged -> {
                planeCount = event.count
                hasFloor = event.hasFloor
                if (hasFloor) {
                    if (virtualFloor) AcLog.i("FLOOR real y=${fmt(event.floorY)} replaces the virtual one")
                    virtualFloor = false
                    floorY = event.floorY
                }
                syncDoors()
                refreshPhase()
                if (pose != null) reevaluateParked()
            }
            is AccessEvent.TrackingChanged -> setTrackingLost(event.lost)
            is AccessEvent.Blocked -> {
                blockReason = event.reason
                refreshPhase()
            }
            is AccessEvent.HeadSample -> onHead(event)
            is AccessEvent.ChairMoved -> onChairMoved(event.pose, event.settled)
            AccessEvent.DragStarted -> {
                if (chairMode == ChairMode.PARKED) {
                    setChairMode(ChairMode.DRAGGING)
                    turn = null
                }
            }
            is AccessEvent.DragEnded -> {
                if (chairMode == ChairMode.DRAGGING) {
                    pose = event.pose
                    setChairMode(ChairMode.PARKED)
                    onChairMoved(event.pose, settled = true)
                }
            }
            is AccessEvent.PlaceConfirmed -> if (chairMode == ChairMode.PLACING) confirmPlace(event.pose, "trigger")
            is AccessEvent.AimChanged -> onAim(event.onFloor)
            AccessEvent.ChairTapped -> if (chairMode == ChairMode.PLACING) confirmPlace(null, "pinch")
            is AccessEvent.Bumped -> onBumped(event)
            is AccessEvent.BumpEnded -> onBumpEnded(event)
            is AccessEvent.StuckChanged -> {
                stuck = event.stuck && chairMode == ChairMode.PUSHING
                if (stuck) showHint(HintKind.STUCK) else if (hint?.kind == HintKind.STUCK) clearHint()
                publish()
            }
            is AccessEvent.DoorCrossed -> onDoorCrossed(event)
            is AccessEvent.SurfaceTapped -> onSurfaceTapped(event)
            AccessEvent.NothingTapped -> onNothingTapped()
            // console
            AccessEvent.PlaceClicked -> placeChair()
            AccessEvent.PlaceHereClicked -> if (chairMode == ChairMode.PLACING) confirmPlace(null, "button")
            AccessEvent.PlaceCancelClicked -> cancelPlace()
            AccessEvent.ReplaceClicked -> if (chairMode == ChairMode.PARKED) startPlacing(pose)
            AccessEvent.BringChairClicked -> bringChair()
            AccessEvent.MuteClicked -> {
                settings = settings.copy(muted = !settings.muted)
                store.save(settings)
                AcLog.i("SOUND muted=${settings.muted}")
                publish()
            }
            AccessEvent.RecallClicked -> {
                recallSerial++
                AcLog.i("RECALL console")
                publish()
            }
            AccessEvent.PushClicked -> startPushing()
            AccessEvent.ParkClicked -> park()
            AccessEvent.TurnClicked -> startTurn()
            is AccessEvent.RotateClicked -> rotateBy(event.sign * AccessRules.ROTATE_STEP_DEG)
            is AccessEvent.HeightSelected -> selectHeight(event.view)
            AccessEvent.PinClicked -> togglePinTool()
            AccessEvent.ReportClicked -> toggleCard(Card.REPORT)
            AccessEvent.MoreClicked -> toggleCard(Card.MENU)
            // menu / dialog
            is AccessEvent.ChairSelected -> changeChair(event.model)
            is AccessEvent.LanguageSelected -> {
                settings = settings.copy(language = event.language)
                store.save(settings)
                publish()
            }
            is AccessEvent.UnitsSelected -> {
                settings = settings.copy(units = event.units)
                store.save(settings)
                publish()
            }
            AccessEvent.ClearClicked -> openDialog(DialogKind.CLEAR)
            AccessEvent.ClearConfirmed -> {
                clearMarks()
                closeCard()
            }
            AccessEvent.DialogDismissed -> closeCard()
            AccessEvent.ExitClicked -> emit(AccessUiEffect.ExitApp)
            // doors
            is AccessEvent.DoorPlateClicked -> openDoorCard(event.doorId)
            is AccessEvent.ThresholdSelected -> {
                val e = doorEntries[event.doorId] ?: return
                doorEntries[event.doorId] = e.copy(threshold = event.pick)
                AcLog.i("DOOR id=${event.doorId.take(8)} threshold=${event.pick.tag}")
                publish()
            }
            is AccessEvent.MeasureAdjusted -> {
                val e = doorEntries[event.doorId] ?: return
                val current = doorDrafts[event.doorId] ?: (e.measuredCm ?: doorRule.verdict(e, settings.chair).widthCm)
                doorDrafts[event.doorId] = (current + event.deltaCm).coerceIn(30, 200)
                publish()
            }
            is AccessEvent.MeasureConfirmed -> {
                val e = doorEntries[event.doorId] ?: return
                val cm = doorDrafts[event.doorId] ?: return
                doorEntries[event.doorId] = e.copy(measuredCm = cm)
                AcLog.i("DOOR id=${event.doorId.take(8)} measured=$cm")
                sound(SoundKind.CLICK)
                publish()
            }
            // pins / report
            is AccessEvent.PinTagSelected -> {
                val i = pins.indexOfFirst { it.id == event.pinId }
                if (i >= 0) pins[i] = pins[i].copy(tag = event.tag)
                AcLog.i("PIN id=${event.pinId} tag=${event.tag.tag}")
                if (card == Card.PIN_TAG && cardPinId == event.pinId) closeCard() else publish()
            }
            is AccessEvent.PinDeleted -> {
                pins.removeAll { it.id == event.pinId }
                if (cardPinId == event.pinId && card == Card.PIN_TAG) closeCard() else publish()
            }
            is AccessEvent.ScuffDeleted -> {
                scuffs.removeAll { it.id == event.id }
                publish()
            }
            is AccessEvent.TurnRecordDeleted -> {
                turnRecords.removeAll { it.id == event.id }
                publish()
            }
            is AccessEvent.ClearRecordDeleted -> {
                clearRecords.removeAll { it.id == event.id }
                publish()
            }
            AccessEvent.SaveClicked -> saveReport()
            AccessEvent.CardDismissed -> onNothingTapped()
            AccessEvent.CoachDismissed -> dismissCoach()
            AccessEvent.HintActionClicked -> {
                if (hint?.kind == HintKind.STUCK) park()
            }
            AccessEvent.WentBackground -> {
                if (chairMode == ChairMode.PUSHING) park()
                if (chairMode == ChairMode.PLACING) cancelPlace()
            }
            AccessEvent.CameForeground -> Unit
            // debug
            is AccessEvent.DebugState -> debugState(event.name)
            is AccessEvent.DebugDoor -> debugDoor(event)
            is AccessEvent.DebugBlock -> {
                blockReason = event.reason
                refreshPhase()
            }
            is AccessEvent.DebugTracking -> setTrackingLost(event.lost ?: !trackingLost)
            AccessEvent.DebugCoachReset -> {
                settings = settings.copy(coachSeen = false)
                store.save(settings)
                if (chairPlaced()) {
                    coach = true
                    publish()
                }
            }
            is AccessEvent.DebugObstacleAdd -> {
                val id = "virtual:${uid++}"
                field.addExtra(Obstacle(id, ObstacleKind.VIRTUAL, ObstacleName.LOW_OBSTACLE, Obb2(V2(event.x, event.z), V2(1f, 0f), event.w / 2f, event.d / 2f), 0f, event.h))
                AcLog.i("OBSTACLE add id=$id at=(${event.x}, ${event.z}) size=${event.w}x${event.d}x${event.h}")
                reevaluateParked()
                publish()
            }
            AccessEvent.DebugObstaclesClear -> {
                field.clearExtras()
                reevaluateParked()
                publish()
            }
            is AccessEvent.DebugPinFill -> debugPinFill(event.n)
            is AccessEvent.DebugScuffFill -> debugScuffFill(event.n)
            is AccessEvent.DebugPlaceAt -> {
                val p = ChairPose(V2(event.x, event.z), Math.toRadians(event.yawDeg.toDouble()).toFloat())
                placeChairAt(p)
            }
            is AccessEvent.DebugPin -> debugPin(event.tag)
            is AccessEvent.DebugPlaceAhead -> {
                placeAheadM = event.aheadM
                placeSideM = event.sideM
                AcLog.i("CMD place_ahead ${event.aheadM} side=${event.sideM}")
            }
            AccessEvent.DebugClear -> clearMarks()
        }
    }

    // =========================================================================================
    // phase
    // =========================================================================================

    private fun refreshPhase() {
        val next =
            when {
                blockReason != null -> Phase.BLOCKED
                !sceneReady -> Phase.STARTING
                !hasFloor && !virtualFloor -> Phase.SCANNING
                else -> Phase.READY
            }
        if (next == phase) {
            publish()
            return
        }
        phase = next
        when (next) {
            Phase.BLOCKED -> {
                dialog = DialogKind.UNAVAILABLE
                card = Card.DIALOG
                clearHint()
            }
            Phase.SCANNING -> {
                showHint(HintKind.SCANNING)
                scheduleVirtualFloor()
            }
            Phase.READY -> if (hint?.kind == HintKind.SCANNING) clearHint()
            Phase.STARTING -> Unit
        }
        if (next != Phase.BLOCKED && dialog == DialogKind.UNAVAILABLE) {
            dialog = null
            card = Card.NONE
        }
        publish()
    }

    /** Taste profile D7: never stuck in "looking for the floor"; after 5 s a virtual floor under the head will do. */
    private fun scheduleVirtualFloor() {
        scanJob?.cancel()
        scanJob =
            viewModelScope.launch {
                delay(AccessRules.SCAN_TIMEOUT_MS)
                if (phase != Phase.SCANNING || hasFloor) return@launch
                val eye = head?.y
                // the Stage origin sits on the floor when the head reads a standing / seated height; else 1.6 m under the eye
                floorY = if (eye != null && (eye < 0.9f || eye > 2.2f)) eye - 1.6f else 0f
                field.useVirtualFloor(floorY)
                virtualFloor = true
                AcLog.i("FLOOR virtual y=${fmt(floorY)} after ${AccessRules.SCAN_TIMEOUT_MS} ms without a floor plane")
                refreshPhase()
                showHint(HintKind.VIRTUAL_FLOOR)
                publish()
            }
    }

    private fun setTrackingLost(lost: Boolean) {
        if (lost == trackingLost) return
        trackingLost = lost
        if (lost) {
            showHint(HintKind.LOST)
            if (chairMode == ChairMode.PUSHING) setChairMode(ChairMode.PARKED, quiet = true)
            if (chairMode == ChairMode.PLACING) cancelPlace()
        } else {
            if (hint?.kind == HintKind.LOST) clearHint()
        }
        publish()
    }

    // =========================================================================================
    // head
    // =========================================================================================

    private fun onHead(e: AccessEvent.HeadSample) {
        head = e.position
        headForward = e.forward
        foot = e.foot
        var changed = false
        if (view != ViewHeight.STAND) {
            val eye = e.position.y - floorY
            val diff = Math.round((eye - view.targetM) * 100f)
            val next = HeightUi(view, eye, diff)
            if (height?.diffCm != diff) {
                height = next
                changed = true
            }
        }
        if (chairMode == ChairMode.WAITING_BEHIND) {
            val p = pose
            if (p != null && pushRule.isBehind(e.foot, p, settings.chair)) {
                AcLog.i("PUSH start reason=behind")
                beginPush()
                changed = true
            }
        }
        if (coach && chairMode == ChairMode.PUSHING) {
            val start = coachStart
            if (start != null && e.foot.distanceTo(start) > 1.0f) {
                dismissCoach()
                changed = true
            }
        }
        // door plates follow the chair, or the person when there is no chair
        if (syncDoorSlots()) changed = true
        if (changed) publish()
    }

    // =========================================================================================
    // chair layer
    // =========================================================================================

    private fun chairPlaced(): Boolean = pose != null && chairMode != ChairMode.NONE

    /** "Place wheelchair": the ghost appears ahead and follows the pointer's floor point (taste profile D1). */
    private fun placeChair() {
        if (phase != Phase.READY || chairMode != ChairMode.NONE) return
        startPlacing(null)
    }

    /** The pose ahead of the head, facing away from the person (the debug states and "bring it here"). */
    private fun aheadPose(aheadM: Float = placeAheadM, sideM: Float = placeSideM): ChairPose {
        val h = head ?: Vec3(0f, 1.6f, 0f)
        val flat = V2(headForward.x, headForward.z).let { if (it.length < 0.1f) V2(0f, -1f) else it.normalized() }
        val center = V2.of(h) + flat * aheadM + flat.leftPerp() * (-sideM)
        return ChairPose(center, V2.yawOf(flat))
    }

    /** The v0.1.0 placing (ahead of the head, then pushing): the debug states build on it. */
    private fun placeAheadNow() {
        if (phase != Phase.READY || chairMode != ChairMode.NONE) return
        placeChairAt(aheadPose())
    }

    private fun startPlacing(from: ChairPose?) {
        if (phase != Phase.READY || trackingLost) return
        placingFrom = from
        tool = Tool.NONE
        closeCardQuiet()
        turn = null
        clearFloor = null
        aimLost = false
        if (hint?.kind == HintKind.PIN_MODE || hint?.kind == HintKind.WAIT_BEHIND) clearHint()
        // the ghost starts where the chair stood, or ahead; the scene moves it to the pointer from the next frame
        pose = from ?: aheadPose(AccessRules.PLACE_AHEAD_M, 0f)
        poseSerial++
        AcLog.i("PLACE start from=${if (from != null) "parked" else "none"}")
        setChairMode(ChairMode.PLACING)
    }

    /** Puts the ghost down at [at] (the scene's pose at the trigger) or where it was last reported. */
    private fun confirmPlace(at: ChairPose?, how: String) {
        val p = at ?: pose ?: return
        val first = placingFrom == null
        placingFrom = null
        pose = p
        poseSerial++
        if (first) {
            placedAt = p.center
            coachStart = foot
            if (!settings.coachSeen) coach = true
        }
        turn = null
        if (hint?.kind == HintKind.AIM_FLOOR) clearHint()
        aimLost = false
        sound(SoundKind.PLACE)
        AcLog.i("PLACE chair=${settings.chair.tag} by=$how at=(${fmt(p.center.x)}, ${fmt(p.center.z)}) yaw=${fmt(Math.toDegrees(p.theta.toDouble()).toFloat(), 1)}")
        setChairMode(ChairMode.PARKED, quiet = true)
        onChairMoved(p, settled = true)
    }

    private fun cancelPlace() {
        if (chairMode != ChairMode.PLACING) return
        val from = placingFrom
        placingFrom = null
        if (hint?.kind == HintKind.AIM_FLOOR) clearHint()
        aimLost = false
        AcLog.i("PLACE cancel back=${if (from != null) "parked" else "none"}")
        if (from != null) {
            pose = from
            poseSerial++
            setChairMode(ChairMode.PARKED, quiet = true)
            reevaluateParked()
        } else {
            pose = null
            setChairMode(ChairMode.NONE, quiet = true)
        }
        publish()
    }

    private fun onAim(onFloor: Boolean) {
        if (chairMode != ChairMode.PLACING || aimLost == !onFloor) return
        aimLost = !onFloor
        if (aimLost) showHint(HintKind.AIM_FLOOR) else if (hint?.kind == HintKind.AIM_FLOOR) clearHint()
        publish()
    }

    /** "Bring it here": 1.2 m ahead of the head, facing away (taste profile D9 / D14; no walking needed, B17). */
    private fun bringChair() {
        if (chairMode != ChairMode.PARKED || trackingLost) return
        val p = aheadPose(AccessRules.PLACE_AHEAD_M, 0f)
        pose = p
        poseSerial++
        turn = null
        sound(SoundKind.CLICK)
        AcLog.i("BRING chair to=(${fmt(p.center.x)}, ${fmt(p.center.z)})")
        onChairMoved(p, settled = true)
    }

    private fun placeChairAt(p: ChairPose) {
        pose = p
        poseSerial++
        placedAt = p.center
        coachStart = foot
        turn = null
        sound(SoundKind.PLACE)
        AcLog.i("PLACE chair=${settings.chair.tag} at=(${fmt(p.center.x)}, ${fmt(p.center.z)}) yaw=${fmt(Math.toDegrees(p.theta.toDouble()).toFloat(), 1)}")
        if (!settings.coachSeen) coach = true
        beginPush()
    }

    /** Enters pushing (the scene runs the follow from the current head and pose). */
    private fun beginPush() {
        tool = Tool.NONE
        if (card != Card.DIALOG) closeCardQuiet()
        turn = null
        clearFloor = null
        clearSinceMs = NEVER
        if (hint?.kind == HintKind.WAIT_BEHIND || hint?.kind == HintKind.PIN_MODE) clearHint()
        setChairMode(ChairMode.PUSHING)
    }

    private fun startPushing() {
        val p = pose ?: return
        if (chairMode != ChairMode.PARKED || trackingLost) return
        val f = foot
        if (f != null && pushRule.isBehind(f, p, settings.chair)) {
            beginPush()
        } else {
            tool = Tool.NONE
            closeCardQuiet()
            setChairMode(ChairMode.WAITING_BEHIND)
            showHint(HintKind.WAIT_BEHIND)
            publish()
        }
    }

    private fun park() {
        if (pose == null) return
        if (chairMode == ChairMode.PUSHING || chairMode == ChairMode.WAITING_BEHIND || chairMode == ChairMode.DRAGGING) {
            stuck = false
            if (hint?.kind == HintKind.STUCK || hint?.kind == HintKind.WAIT_BEHIND) clearHint()
            setChairMode(ChairMode.PARKED)
            reevaluateParked()
            publish()
        }
    }

    private fun setChairMode(mode: ChairMode, quiet: Boolean = false) {
        if (chairMode == mode) return
        chairMode = mode
        if (mode != ChairMode.PUSHING) {
            stuck = false
        }
        if (mode == ChairMode.PUSHING || mode == ChairMode.DRAGGING) {
            turn = null
            clearFloor = null
            clearSinceMs = NEVER
        }
        syncDoorSlots()
        if (!quiet) publish()
    }

    private fun onChairMoved(p: ChairPose, settled: Boolean) {
        pose = p
        if (settled) {
            val target = snapTarget()
            if (target != null && snaps(p, target)) {
                pose = target
                poseSerial++
                sound(SoundKind.SNAP)
                AcLog.i("SNAP door approach at=(${fmt(target.center.x)}, ${fmt(target.center.z)})")
            }
            turn = null
        }
        if (chairMode == ChairMode.PARKED || chairMode == ChairMode.DRAGGING) reevaluateParked()
        syncDoorSlots()
        publish()
    }

    private fun rotateBy(deg: Float) {
        val p = pose ?: return
        if (chairMode != ChairMode.PARKED) return
        // sign +1 = right: a positive yaw turns left, so right subtracts
        pose = ChairPose(p.center, p.theta - Math.toRadians(deg.toDouble()).toFloat())
        poseSerial++
        turn = null
        sound(SoundKind.CLICK)
        reevaluateParked()
        publish()
    }

    private fun changeChair(model: ChairModel) {
        if (model == settings.chair) return
        settings = settings.copy(chair = model)
        store.save(settings)
        turn = null
        poseSerial++
        AcLog.i("CHAIR model=${model.tag}")
        reevaluateParked()
        publish()
    }

    /** Clear floor and snap target of a standing chair (design `rules.clearFloor`). */
    private fun reevaluateParked() {
        val p = pose ?: return
        if (chairMode != ChairMode.PARKED && chairMode != ChairMode.DRAGGING) {
            clearFloor = null
            return
        }
        val r = field.clearFloorCheck(p.clearFloor())
        val footHit = field.hits(p.footprint(settings.chair)).isNotEmpty()
        val next = if (r != null && !footHit) ClearFloorUi(r.shortCm, r.blocker) else null
        if (next != clearFloor) {
            clearFloor = next
            clearSinceMs = if (next != null) SystemClock.elapsedRealtime() else NEVER
            clearRecorded = false
            if (next != null) scheduleClearRecord()
        }
    }

    private fun scheduleClearRecord() {
        viewModelScope.launch {
            delay(AccessRules.CLEAR_FLOOR_RECORD_MS)
            val c = clearFloor ?: return@launch
            val p = pose ?: return@launch
            if (clearRecorded || chairMode != ChairMode.PARKED) return@launch
            clearRecorded = true
            val rec = ClearFloorRecord(uid++, locate.name(p.center, c.blocker, allDoors(), placedAt), c.shortCm, c.blocker, SystemClock.elapsedRealtime())
            clearRecords.add(0, rec)
            while (clearRecords.size > 3) clearRecords.removeAt(clearRecords.size - 1)
            AcLog.i("CLEARFLOOR record short=${c.shortCm} by=${c.blocker}")
            publish()
        }
    }

    // ---- turning (S8 / S9 / S26)
    private fun startTurn() {
        val p = pose ?: return
        if (chairMode != ChairMode.PARKED) return
        tool = Tool.NONE
        closeCardQuiet()
        val result = field.turnCheck(p.center)
        val now = SystemClock.elapsedRealtime()
        turn = TurnUi(p.center, result.red.toList(), result.pass, result.shortCm, result.blocker, result.blockerDir, now)
        setChairMode(ChairMode.TURNING, quiet = true)
        AcLog.i("TURN pass=${result.pass} short=${result.shortCm} by=${result.blocker}")
        publish()
        turnJob?.cancel()
        turnJob =
            viewModelScope.launch {
                delay(AccessRules.TURN_ANIM_MS)
                if (chairMode == ChairMode.TURNING) {
                    chairMode = ChairMode.PARKED
                    val rec = TurnRecord(uid++, locate.name(p.center, result.blocker ?: ObstacleName.WALL, allDoors(), placedAt), result.pass, result.shortCm, result.blocker, SystemClock.elapsedRealtime())
                    turnRecords.add(0, rec)
                    while (turnRecords.size > 3) turnRecords.removeAt(turnRecords.size - 1)
                    sound(if (result.pass) SoundKind.PASS else SoundKind.FAIL)
                    publish()
                }
            }
    }

    // ---- bumps and scuffs (S5, design `rules.scuff`)
    private fun onBumped(e: AccessEvent.Bumped) {
        hit = true
        hitName = e.name
        val now = SystemClock.elapsedRealtime()
        // (v0.1.0 compared against Long.MIN_VALUE: the subtraction overflowed and the hint never showed)
        val last = lastHitHintMs[e.name]
        if (last == null || now - last >= AccessRules.HIT_HINT_REPEAT_MS) {
            lastHitHintMs[e.name] = now
            showHint(HintKind.HIT, e.name)
            sound(SoundKind.BUMP)
        }
        if (openScuffs.containsKey(e.obstacleId)) {
            publish()
            return
        }
        val heightM = (e.contact.y - floorY).coerceIn(0.05f, AccessRules.COLLIDE_TOP_M)
        val near = scuffs.firstOrNull { it.obstacleId == e.obstacleId && it.enter.distanceTo(e.contact) < 0.10f }
        if (near != null) {
            val i = scuffs.indexOf(near)
            scuffs[i] = near.copy(level = minOf(3, near.level + 1), atMs = now)
            openScuffs[e.obstacleId] = near.id
        } else if (scuffs.size < AccessRules.SCUFF_MAX) {
            val id = uid++
            scuffs += Scuff(id, e.obstacleId, e.name, e.contact, e.normal, null, 1, heightM, locate.name(V2.of(e.contact), e.name, allDoors(), placedAt), now)
            openScuffs[e.obstacleId] = id
            AcLog.i("SCUFF add id=$id on=${e.name} at=${AcLog.pos(e.contact)} h=${fmt(heightM)}")
            if (scuffs.size >= AccessRules.SCUFF_MAX) showHint(HintKind.SCUFFS_FULL)
        }
        publish()
    }

    private fun onBumpEnded(e: AccessEvent.BumpEnded) {
        val id = openScuffs.remove(e.obstacleId)
        if (id != null) {
            val i = scuffs.indexOfFirst { it.id == id }
            if (i >= 0 && scuffs[i].exit == null && scuffs[i].enter.distanceTo(e.contact) > 0.06f) scuffs[i] = scuffs[i].copy(exit = e.contact)
        }
        if (openScuffs.isEmpty()) {
            hit = false
            hitName = null
            if (stuck) {
                stuck = false
                if (hint?.kind == HintKind.STUCK) clearHint()
            }
        }
        publish()
    }

    // ---- doors (S10 - S14)
    private fun syncDoors() {
        val planes = planeTracker.all
        for (plane in planes) {
            if (!plane.kind.isDoorway) continue
            val index = doorIndex.getOrPut(plane.id) { doorIndex.size + 1 }
            val info = doorRule.doorOf(plane, floorY, index) ?: continue
            val existing = doorEntries[plane.id]
            doorEntries[plane.id] = existing?.copy(info = info) ?: DoorEntry(info)
        }
        val gone = doorEntries.keys.filter { id -> id !in virtualDoors && planes.none { it.id == id } }
        for (id in gone) doorEntries.remove(id)
        syncDoorSlots()
    }

    private fun allDoors(): Collection<DoorInfo> = doorEntries.values.map { it.info }

    private var doorSlots: Map<String, Int> = emptyMap()

    /** Which doors show plates: the nearest two within 1.5 m of the chair (or the person), hidden past 2.5 m. */
    private fun syncDoorSlots(): Boolean {
        val ref = pose?.center ?: foot ?: return false
        val byDist = doorEntries.values.map { it to doorRule.distance(it.info, ref) }.sortedBy { it.second }
        val next = LinkedHashMap<String, Int>()
        for ((entry, d) in byDist) {
            val had = doorSlots[entry.info.id]
            val show = entry.info.id == pinnedDoorId || (if (had != null) d <= AccessRules.DOOR_HIDE_M else d <= AccessRules.DOOR_SHOW_M)
            if (!show) continue
            if (next.size >= 2) break
            next[entry.info.id] = -1
        }
        // keep slot letters stable
        val used = HashSet<Int>()
        for (id in next.keys) doorSlots[id]?.let { if (it >= 0 && used.add(it)) next[id] = it }
        for (id in next.keys) if (next[id] == -1) next[id] = (0..1).first { it !in used }.also { used.add(it) }
        if (next == doorSlots) return false
        doorSlots = next
        if (expandedDoorId != null && expandedDoorId !in next) {
            if (card == Card.DOOR) closeCardQuiet()
        }
        return true
    }

    private fun openDoorCard(doorId: String) {
        val e = doorEntries[doorId] ?: return
        if (card == Card.DOOR && cardDoorId == doorId) {
            closeCard()
            return
        }
        if (chairMode == ChairMode.PUSHING || chairMode == ChairMode.WAITING_BEHIND) park()
        doorDrafts[doorId] = e.measuredCm ?: doorRule.verdict(e, settings.chair).widthCm
        card = Card.DOOR
        cardDoorId = doorId
        expandedDoorId = doorId
        tool = Tool.NONE
        publish()
    }

    private fun onDoorCrossed(e: AccessEvent.DoorCrossed) {
        val entry = doorEntries[e.doorId] ?: return
        val v = doorRule.verdict(entry, settings.chair)
        val now = SystemClock.elapsedRealtime()
        if (v.width == Verdict.FAIL) {
            doorFlash[e.doorId] = now
            showHint(HintKind.DOOR_BLOCKED)
            sound(SoundKind.BUMP)
        } else if (e.frontWheels && v.threshold == Verdict.FAIL) {
            doorFlash[e.doorId] = now
            showHint(HintKind.THRESHOLD_CATCH, text = fmtCm(entry.threshold.cm.coerceAtLeast(2)))
        }
        publish()
    }

    /** The door approach frame the parked chair snaps to (taste profile D1): 0.3 m before the nearest shown door, facing it. */
    private fun snapTarget(): ChairPose? {
        val p = pose ?: return null
        val id = doorSlots.keys.firstOrNull() ?: return null
        val door = doorEntries[id]?.info ?: return null
        val n = doorRule.normalTowards(door, p.center)
        val center = door.center + n * (AccessRules.SNAP_FRONT_GAP_M + settings.chair.lengthM / 2f)
        return ChairPose(center, V2.yawOf(-n))
    }

    private fun snaps(p: ChairPose, target: ChairPose): Boolean {
        if (p.center.distanceTo(target.center) > AccessRules.SNAP_DIST_M) return false
        var d = abs(p.theta - target.theta) % (2f * PI.toFloat())
        if (d > PI.toFloat()) d = 2f * PI.toFloat() - d
        return Math.toDegrees(d.toDouble()) <= AccessRules.SNAP_ANGLE_DEG
    }

    // =========================================================================================
    // view, tools, cards
    // =========================================================================================

    private fun selectHeight(v: ViewHeight) {
        if (v == view) return
        view = v
        if (v == ViewHeight.STAND) {
            height = null
        } else {
            if (chairMode == ChairMode.PUSHING || chairMode == ChairMode.WAITING_BEHIND) park()
            val h = head
            val eye = (h?.y ?: (floorY + 1.6f)) - floorY
            height = HeightUi(v, eye, Math.round((eye - v.targetM) * 100f))
            showHint(if (v == ViewHeight.SEATED) HintKind.SIT_DOWN else HintKind.CROUCH)
        }
        AcLog.i("VIEW height=${v.tag}")
        publish()
    }

    private fun togglePinTool() {
        if (tool == Tool.PIN) {
            tool = Tool.NONE
            if (hint?.kind == HintKind.PIN_MODE) clearHint()
            publish()
            return
        }
        if (pins.size >= AccessRules.PIN_MAX) {
            showHint(HintKind.PINS_FULL)
            publish()
            return
        }
        if (chairMode == ChairMode.PUSHING || chairMode == ChairMode.WAITING_BEHIND) park()
        closeCardQuiet()
        tool = Tool.PIN
        showHint(HintKind.PIN_MODE)
        publish()
    }

    private fun onSurfaceTapped(e: AccessEvent.SurfaceTapped) {
        if (chairMode == ChairMode.PLACING) {
            // a pinch on the floor puts the chair where the hand ray met it (clear of the person, facing away,
            // keeping the thumbstick turn); a pinch anywhere else puts it where the ghost stands
            val plane = planeTracker.get(e.planeId)
            val f = foot
            val p = pose
            if (plane?.kind == SurfaceKind.FLOOR && f != null && p != null) {
                val turn = p.theta - aim.awayYaw(p.center, f, p.theta)
                confirmPlace(aim.ghost(V2.of(e.point), f, V2(headForward.x, headForward.z), turn), "pinch_floor")
            } else {
                confirmPlace(null, "pinch")
            }
            return
        }
        if (card == Card.MENU || card == Card.DIALOG || card == Card.DOOR || card == Card.PIN_TAG) {
            closeCard()
            return
        }
        if (tool != Tool.PIN) return
        if (pins.size >= AccessRules.PIN_MAX) {
            showHint(HintKind.PINS_FULL)
            publish()
            return
        }
        val plane = planeTracker.get(e.planeId)
        val name = plane?.let { ObstacleName.of(it.kind) } ?: ObstacleName.OBSTACLE
        addPin(e.point, e.normal, name)
    }

    private fun addPin(point: Vec3, normal: Vec3, name: ObstacleName, tag: PinTag? = null) {
        val id = uid++
        val heightM = (point.y - floorY).coerceAtLeast(0f)
        pins += Pin(id, point, normal, tag, heightM, locate.name(V2.of(point), name, allDoors(), placedAt), view == ViewHeight.CHILD)
        AcLog.i("PIN add id=$id at=${AcLog.pos(point)} h=${fmt(heightM)}")
        sound(SoundKind.PIN)
        tool = Tool.NONE
        if (hint?.kind == HintKind.PIN_MODE) clearHint()
        if (tag == null) {
            card = Card.PIN_TAG
            cardPinId = id
        }
        if (pins.size >= AccessRules.PIN_MAX) showHint(HintKind.PINS_FULL)
        publish()
    }

    private fun toggleCard(kind: Card) {
        if (card == kind) {
            closeCard()
            return
        }
        card = kind
        cardDoorId = null
        cardPinId = null
        if (kind == Card.MENU || kind == Card.REPORT) expandedDoorId = null
        publish()
    }

    private fun openDialog(kind: DialogKind) {
        dialog = kind
        card = Card.DIALOG
        publish()
    }

    private fun closeCardQuiet() {
        if (card == Card.PIN_TAG) {
            // a pin left without a tag gets the default one for its height mode
            val id = cardPinId
            val i = pins.indexOfFirst { it.id == id }
            if (i >= 0 && pins[i].tag == null) pins[i] = pins[i].copy(tag = if (pins[i].childMode) PinTag.HAZARD else PinTag.OUT_OF_REACH)
        }
        if (dialog == DialogKind.UNAVAILABLE) return
        card = Card.NONE
        dialog = null
        cardDoorId = null
        cardPinId = null
        expandedDoorId = null
        doorDrafts.clear()
    }

    private fun closeCard() {
        closeCardQuiet()
        publish()
    }

    /** Tap on empty space: closes the card, leaves the pin tool (design `informationArchitecture.deselect`). */
    private fun onNothingTapped() {
        if (chairMode == ChairMode.PLACING && card == Card.NONE) {
            confirmPlace(null, "pinch")
            return
        }
        if (card != Card.NONE && dialog != DialogKind.UNAVAILABLE) {
            closeCard()
            return
        }
        if (tool == Tool.PIN) {
            tool = Tool.NONE
            if (hint?.kind == HintKind.PIN_MODE) clearHint()
            publish()
        }
    }

    private fun dismissCoach() {
        if (!coach) return
        coach = false
        settings = settings.copy(coachSeen = true)
        store.save(settings)
        AcLog.i("COACH dismissed")
        publish()
    }

    private fun clearMarks() {
        scuffs.clear()
        openScuffs.clear()
        pins.clear()
        turnRecords.clear()
        clearRecords.clear()
        turn = null
        hit = false
        hitName = null
        AcLog.i("CLEAR marks")
        publish()
    }

    // =========================================================================================
    // hints
    // =========================================================================================

    private fun showHint(kind: HintKind, obstacle: ObstacleName? = null, text: String = "") {
        hintJob?.cancel()
        hint = HintUi(++hintSerial, kind, obstacle, text)
        AcLog.i("HINT ${kind.name.lowercase(Locale.US)}${if (obstacle != null) " $obstacle" else ""}")
        if (kind.durationMs != HINT_STICKY_MS) {
            val hold = hintHoldMs ?: kind.durationMs
            hintHoldMs = null
            hintJob =
                viewModelScope.launch {
                    delay(hold)
                    if (hint?.kind == kind) {
                        hint = null
                        publish()
                    }
                }
        }
    }

    private fun clearHint() {
        hintJob?.cancel()
        hint = null
    }

    // =========================================================================================
    // report (S18 / S19)
    // =========================================================================================

    private fun saveReport() {
        val s = _state.value
        if (s.reportEmpty) return
        val resources = io.embodit.accesscheck.ui.access.components.localizedResources(appContext, settings.language)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val fileName = "access-check-$stamp.txt"
        val text = ReportText.build(resources, s, Date())
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { ReportWriter.write(appContext, fileName, text) }
            result.onSuccess { path ->
                savedPath = path
                showHint(HintKind.SAVED, text = path)
                sound(SoundKind.SAVE)
                publish()
            }.onFailure { AcLog.e("REPORT save failed: $it", it) }
        }
    }

    // =========================================================================================
    // publishing
    // =========================================================================================

    private fun doorsUi(): List<DoorUi> =
        doorEntries.values.map { e ->
            DoorUi(
                entry = e,
                verdict = doorRule.verdict(e, settings.chair),
                slot = doorSlots[e.info.id] ?: -1,
                expanded = card == Card.DOOR && cardDoorId == e.info.id,
                draftCm = doorDrafts[e.info.id] ?: (e.measuredCm ?: doorRule.verdict(e, settings.chair).widthCm),
                flashAtMs = doorFlash[e.info.id] ?: 0L,
            )
        }

    private fun units(): UnitPref = settings.units ?: if (Locale.getDefault().country == "US") UnitPref.IN else UnitPref.CM

    private fun publish() {
        val name = forcedState ?: stateName()
        val next =
            AccessUiState(
                phase = phase,
                blockReason = blockReason,
                hasFloor = hasFloor,
                virtualFloor = virtualFloor,
                floorY = floorY,
                planeCount = planeCount,
                chair = settings.chair,
                chairMode = chairMode,
                pose = pose,
                poseSerial = poseSerial,
                hit = hit,
                hitName = hitName,
                stuck = stuck,
                placedAt = placedAt,
                tool = tool,
                card = card,
                dialog = dialog,
                cardDoorId = cardDoorId,
                cardPinId = cardPinId,
                doors = doorsUi(),
                turn = turn,
                clearFloor = clearFloor,
                scuffs = scuffs.toList(),
                scuffsFull = scuffs.size >= AccessRules.SCUFF_MAX,
                pins = pins.toList(),
                turnRecords = turnRecords.toList(),
                clearRecords = clearRecords.toList(),
                snapTarget = if (chairMode == ChairMode.PARKED || chairMode == ChairMode.DRAGGING || chairMode == ChairMode.PLACING) snapTarget() else null,
                view = view,
                height = height,
                hint = hint,
                coach = coach,
                trackingLost = trackingLost,
                language = settings.language,
                units = units(),
                savedPath = savedPath,
                muted = settings.muted,
                recallSerial = recallSerial,
                stateName = name,
            )
        _state.value = next
        if (name != lastStateName) {
            lastStateName = name
            AcLog.i("STATE $name chair=${chairMode.name.lowercase(Locale.US)} tool=${tool.name.lowercase(Locale.US)} card=${card.name.lowercase(Locale.US)} view=${view.tag}")
        }
    }

    /** The design's state id for the current combination (the first match wins; QA reads it in the log). */
    private fun stateName(): String {
        if (phase == Phase.BLOCKED) return "S20"
        if (phase == Phase.STARTING) return "S1"
        if (phase == Phase.SCANNING) return "S2"
        if (trackingLost) return "S24"
        if (card == Card.DIALOG && dialog == DialogKind.CLEAR) return "S22"
        if (card == Card.MENU) return "S23"
        if (card == Card.REPORT) return if (hint?.kind == HintKind.SAVED) "S19" else "S18"
        if (hint?.kind == HintKind.SAVED) return "S19"
        if (card == Card.DOOR) return "S13"
        if (card == Card.PIN_TAG || tool == Tool.PIN) return "S17"
        if (hint?.kind == HintKind.PINS_FULL) return "S29"
        if (hint?.kind == HintKind.SCUFFS_FULL) return "S30"
        if (coach && chairMode == ChairMode.PUSHING) return "S21"
        if (chairMode == ChairMode.PLACING) return "S31"
        if (height != null) return if (height!!.ok) "S16" else "S15"
        val doorShown = doorsUi().filter { it.slot >= 0 }.minByOrNull { it.slot }
        if (doorShown != null && chairMode != ChairMode.TURNING) {
            return when (doorShown.verdict.overall) {
                Verdict.PASS -> "S10"
                Verdict.TIGHT -> "S11"
                Verdict.FAIL -> "S12"
                Verdict.TO_MEASURE -> "S14"
            }
        }
        return when (chairMode) {
            ChairMode.NONE -> "S3"
            ChairMode.PLACING -> "S31"
            ChairMode.WAITING_BEHIND -> "S25"
            ChairMode.PUSHING -> if (stuck) "S28" else if (hit) "S5" else "S4"
            ChairMode.DRAGGING -> "S7"
            ChairMode.TURNING -> "S26"
            ChairMode.PARKED ->
                when {
                    turn != null -> if (turn!!.pass) "S8" else "S9"
                    clearFloor != null -> "S27"
                    else -> "S6"
                }
        }
    }

    private fun emit(e: AccessUiEffect) {
        _effects.tryEmit(e)
    }

    private fun sound(kind: SoundKind) {
        if (!settings.muted) emit(AccessUiEffect.Sound(kind))
    }

    private fun fmtCm(cm: Int): String = "$cm cm"

    // =========================================================================================
    // debug hooks (debug builds; the receiver calls through the probe host)
    // =========================================================================================

    private fun debugDoor(e: AccessEvent.DebugDoor) {
        val ref = pose?.center ?: foot ?: V2.ZERO
        // the nearest real door, else a virtual one 1.2 m ahead of the chair (or the person)
        // the nearest real door within reach, else a virtual one 1.2 m ahead of the chair (or the person)
        var id = doorEntries.values.minByOrNull { doorRule.distance(it.info, ref) }?.takeIf { doorRule.distance(it.info, ref) <= AccessRules.DOOR_HIDE_M }?.info?.id
        if (id == null) {
            id = "virtual-door"
            val heading = pose?.heading ?: V2(headForward.x, headForward.z).let { if (it.length < 0.1f) V2(0f, -1f) else it.normalized() }
            val center = ref + heading * 1.2f
            val index = doorIndex.getOrPut(id) { doorIndex.size + 1 }
            val info = DoorInfo(id, index, center, heading.leftPerp(), heading, e.widthM, floorY + 2.0f, floorY)
            virtualDoors[id] = info
            doorEntries[id] = DoorEntry(info)
        }
        val entry = doorEntries[id] ?: return
        val info = entry.info.copy(rawWidthM = if (e.measured) entry.info.rawWidthM else e.widthM)
        doorEntries[id] = entry.copy(info = info, measuredCm = if (e.measured) Math.round(e.widthM * 100f) else null, threshold = ThresholdPick.fromCm(e.thresholdCm))
        virtualDoors[id]?.let { virtualDoors[id] = info }
        pinnedDoorId = id
        syncDoorSlots()
        publish()
    }

    private fun debugPinFill(n: Int) {
        val h = head ?: Vec3(0f, 1.5f, 0f)
        val base = Vec3(h.x, floorY + 1.2f, h.z - 1.5f)
        while (pins.size < n.coerceAtMost(AccessRules.PIN_MAX)) {
            val i = pins.size
            pins += Pin(uid++, base + Vec3((i % 5) * 0.15f - 0.3f, (i / 5) * 0.12f, 0f), Vec3(0f, 0f, 1f), PinTag.entries[i % 3], 1.2f + (i / 5) * 0.12f, LocationName(null, 0, 1.5f, ObstacleName.WALL), false)
        }
        if (pins.size >= AccessRules.PIN_MAX) showHint(HintKind.PINS_FULL)
        publish()
    }

    private fun debugScuffFill(n: Int) {
        val h = head ?: Vec3(0f, 1.5f, 0f)
        val now = SystemClock.elapsedRealtime()
        while (scuffs.size < n.coerceAtMost(AccessRules.SCUFF_MAX)) {
            val i = scuffs.size
            val p = Vec3(h.x + (i % 8) * 0.18f - 0.7f, floorY + 0.15f + (i / 8) * 0.08f, h.z - 1.5f)
            scuffs += Scuff(uid++, "fill:$i", ObstacleName.WALL, p, Vec3(0f, 0f, 1f), p + Vec3(0.08f, 0f, 0f), 1 + i % 3, 0.15f, LocationName(null, 0, 1.5f, ObstacleName.WALL), now)
        }
        if (scuffs.size >= AccessRules.SCUFF_MAX) showHint(HintKind.SCUFFS_FULL)
        publish()
    }

    private fun debugPin(tag: PinTag?) {
        val p = pose
        val h = head ?: Vec3(0f, 1.5f, 0f)
        val at = if (p != null) (p.center + p.heading * (settings.chair.lengthM / 2f + 0.6f)).toVec3(floorY + 1.2f) else Vec3(h.x, floorY + 1.2f, h.z - 1.5f)
        if (chairMode == ChairMode.PUSHING || chairMode == ChairMode.WAITING_BEHIND) park()
        addPin(at, Vec3(0f, 0f, 1f), ObstacleName.WALL, tag)
    }

    /** `state Sxx`: sets the scene up so that the design's state shows (the other hooks do the rest). */
    private fun debugState(raw: String) {
        val name = raw.trim().uppercase(Locale.US).substringBefore(' ')
        forcedState = null
        AcLog.i("CMD state $name")
        fun ensureChair() {
            // a fresh chair at the standard spot (a chair pushed about by an earlier state would frame badly)
            chairMode = ChairMode.NONE
            pose = null
            placingFrom = null
            placeAheadNow()
        }
        fun ensureParked() {
            ensureChair()
            chairMode = ChairMode.PARKED
            coach = false
            reevaluateParked()
        }
        fun clearOverlays() {
            closeCardQuiet()
            tool = Tool.NONE
            clearHint()
            view = ViewHeight.STAND
            height = null
            turn = null
            if (name !in setOf("S10", "S11", "S12", "S13", "S14")) {
                pinnedDoorId = null
                for (id in virtualDoors.keys) doorEntries.remove(id)
                virtualDoors.clear()
                doorSlots = emptyMap()
            }
        }
        when (name) {
            "S1" -> {
                sceneReady = false
                phase = Phase.STARTING
            }
            "S2" -> {
                hasFloor = false
                phase = Phase.SCANNING
                showHint(HintKind.SCANNING)
            }
            "S3" -> {
                clearOverlays()
                chairMode = ChairMode.NONE
                pose = null
                turn = null
                clearFloor = null
                doorSlots = emptyMap()
                phase = if (blockReason != null) Phase.BLOCKED else Phase.READY
                hasFloor = true
                sceneReady = true
            }
            "S4" -> {
                clearOverlays()
                ensureChair()
                coach = false
                beginPush()
            }
            "S5" -> {
                clearOverlays()
                ensureChair()
                coach = false
                beginPush()
                val p = pose!!
                val c = p.center + p.heading * (settings.chair.lengthM / 2f)
                field.addExtra(Obstacle("virtual:${uid++}", ObstacleKind.VIRTUAL, ObstacleName.WALL, Obb2(c + p.heading * 0.02f, p.side, 1.0f, 0.03f), 0f, 2.4f))
            }
            "S6" -> {
                clearOverlays()
                ensureParked()
                turn = null
            }
            "S7" -> {
                clearOverlays()
                ensureParked()
                chairMode = ChairMode.DRAGGING
            }
            "S8" -> {
                clearOverlays()
                ensureParked()
                field.clearExtras()
                startTurn()
            }
            "S9" -> {
                clearOverlays()
                ensureParked()
                val p = pose!!
                field.addExtra(Obstacle("virtual:${uid++}", ObstacleKind.VIRTUAL, ObstacleName.CABINET, Obb2(p.center + p.side * 0.95f, p.heading, 0.45f, 0.25f), 0f, 0.85f))
                startTurn()
            }
            "S10" -> {
                clearOverlays()
                ensureParked()
                debugDoor(AccessEvent.DebugDoor(0.90f, true, 0))
            }
            "S11" -> {
                clearOverlays()
                ensureParked()
                debugDoor(AccessEvent.DebugDoor(0.78f, true, 0))
            }
            "S12" -> {
                clearOverlays()
                ensureParked()
                debugDoor(AccessEvent.DebugDoor(0.70f, false, 2))
            }
            "S13" -> {
                clearOverlays()
                ensureParked()
                debugDoor(AccessEvent.DebugDoor(0.86f, false, -1))
                doorSlots.keys.firstOrNull()?.let { openDoorCard(it) }
            }
            "S14" -> {
                clearOverlays()
                ensureParked()
                debugDoor(AccessEvent.DebugDoor(0.86f, false, -1))
            }
            "S15" -> {
                clearOverlays()
                ensureParked()
                view = ViewHeight.SEATED
                height = HeightUi(view, 1.60f, 40)
            }
            "S16" -> {
                clearOverlays()
                ensureParked()
                view = ViewHeight.CHILD
                height = HeightUi(view, 1.01f, 1)
            }
            "S17" -> {
                clearOverlays()
                ensureParked()
                tool = Tool.PIN
                showHint(HintKind.PIN_MODE)
            }
            "S18" -> {
                clearOverlays()
                ensureParked()
                if (scuffs.isEmpty() && pins.isEmpty() && turnRecords.isEmpty()) {
                    debugScuffFill(2)
                    debugPin(PinTag.OUT_OF_REACH)
                    closeCardQuiet()
                    val p = pose!!
                    turnRecords.add(0, TurnRecord(uid++, locate.name(p.center, ObstacleName.CABINET, allDoors(), placedAt), false, 18, ObstacleName.CABINET, SystemClock.elapsedRealtime()))
                    clearRecords.add(0, ClearFloorRecord(uid++, locate.name(p.center, ObstacleName.LOW_OBSTACLE, allDoors(), placedAt), 6, ObstacleName.LOW_OBSTACLE, SystemClock.elapsedRealtime()))
                    debugDoor(AccessEvent.DebugDoor(0.78f, true, 2))
                    clearHint()
                }
                card = Card.REPORT
            }
            "S19" -> {
                debugState("S18")
                hintHoldMs = 20000L
                saveReport()
                return
            }
            "S20" -> {
                blockReason = BlockReason.WITHOUT_PERMISSION
                refreshPhase()
                return
            }
            "S21" -> {
                clearOverlays()
                ensureChair()
                coach = true
                beginPush()
            }
            "S22" -> {
                clearOverlays()
                ensureParked()
                openDialog(DialogKind.CLEAR)
                return
            }
            "S23" -> {
                clearOverlays()
                ensureParked()
                card = Card.MENU
            }
            "S24" -> {
                clearOverlays()
                ensureParked()
                setTrackingLost(true)
                return
            }
            "S25" -> {
                clearOverlays()
                ensureParked()
                // the chair turned sideways: the person is beside it, not behind
                pose = pose?.let { ChairPose(it.center, it.theta + (PI / 2).toFloat()) }
                poseSerial++
                chairMode = ChairMode.WAITING_BEHIND
                showHint(HintKind.WAIT_BEHIND)
            }
            "S26" -> {
                clearOverlays()
                ensureParked()
                startTurn()
                turnJob?.cancel()
                return
            }
            "S27" -> {
                clearOverlays()
                ensureParked()
                val p = pose!!
                field.addExtra(Obstacle("virtual:${uid++}", ObstacleKind.VIRTUAL, ObstacleName.LOW_OBSTACLE, Obb2(p.center + p.side * 0.44f + p.heading * 0.3f, p.heading, 0.2f, 0.08f), 0f, 0.4f))
                reevaluateParked()
            }
            "S28" -> {
                debugState("S5")
                stuck = true
                showHint(HintKind.STUCK)
            }
            "S29" -> {
                clearOverlays()
                ensureParked()
                debugPinFill(AccessRules.PIN_MAX)
                return
            }
            "S31" -> {
                // placing: the ghost, 1.2 m ahead until the pointer moves it (`ray` / `trigger` debug hooks)
                clearOverlays()
                chairMode = ChairMode.NONE
                pose = null
                coach = false
                startPlacing(null)
                return
            }
            "S30" -> {
                clearOverlays()
                ensureParked()
                debugScuffFill(AccessRules.SCUFF_MAX)
                return
            }
            else -> AcLog.i("CMD state unknown $name")
        }
        syncDoorSlots()
        publish()
    }

    /** The whole model as one JSON object (debug `dump`). */
    fun dumpJson(): String {
        val s = _state.value
        val o = JSONObject()
        o.put("state", s.stateName)
        o.put("phase", s.phase.name)
        o.put("chairMode", s.chairMode.name)
        o.put("chair", s.chair.tag)
        s.pose?.let { p -> o.put("pose", JSONObject().put("x", p.center.x.toDouble()).put("z", p.center.z.toDouble()).put("yawDeg", Math.toDegrees(p.theta.toDouble()))) }
        o.put("hit", s.hit)
        o.put("tool", s.tool.name)
        o.put("card", s.card.name)
        o.put("view", s.view.tag)
        s.height?.let { o.put("height", JSONObject().put("eyeM", it.eyeM.toDouble()).put("diffCm", it.diffCm).put("ok", it.ok)) }
        s.turn?.let { o.put("turn", JSONObject().put("pass", it.pass).put("shortCm", it.shortCm).put("blocker", it.blocker?.name)) }
        s.clearFloor?.let { o.put("clearFloor", JSONObject().put("shortCm", it.shortCm).put("blocker", it.blocker.name)) }
        o.put("doors", JSONArray().apply { s.doors.forEach { d -> put(JSONObject().put("id", d.entry.info.id.take(8)).put("index", d.entry.info.index).put("slot", d.slot).put("widthCm", d.verdict.widthCm).put("approx", d.verdict.approx).put("measured", d.entry.measuredCm).put("threshold", d.entry.threshold.tag).put("verdict", d.verdict.overall.name)) } })
        o.put("scuffs", s.scuffs.size)
        o.put("pins", JSONArray().apply { s.pins.forEach { p -> put(JSONObject().put("id", p.id).put("tag", p.tag?.tag).put("h", p.heightM.toDouble())) } })
        o.put("turnRecords", s.turnRecords.size)
        o.put("clearRecords", s.clearRecords.size)
        o.put("hint", s.hint?.kind?.name)
        o.put("coach", s.coach)
        o.put("lost", s.trackingLost)
        o.put("planes", s.planeCount)
        o.put("obstacles", field.all().size)
        o.put("meshCells", field.meshCellCount)
        head?.let { o.put("head", JSONObject().put("x", it.x.toDouble()).put("y", it.y.toDouble()).put("z", it.z.toDouble())) }
        o.put("savedPath", s.savedPath)
        return o.toString()
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE

        private fun fmt(v: Float, decimals: Int = 2): String = String.format(Locale.US, "%.${decimals}f", v)

        @Suppress("unused") private fun cmUp(m: Float): Int = ceil(m * 100f).toInt()
    }
}
