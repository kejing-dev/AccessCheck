package io.embodit.accesscheck.ui.access.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.accesscheck.domain.model.AccessRules
import io.embodit.accesscheck.domain.model.ChairPose
import io.embodit.accesscheck.domain.model.Obb2
import io.embodit.accesscheck.domain.model.Quat4
import io.embodit.accesscheck.domain.model.SurfaceKind
import io.embodit.accesscheck.domain.model.V2
import io.embodit.accesscheck.domain.model.Vec3
import io.embodit.accesscheck.domain.model.Verdict
import io.embodit.accesscheck.domain.model.ViewHeight
import io.embodit.accesscheck.domain.usecase.DoorUseCase
import io.embodit.accesscheck.domain.usecase.HitSurfaceUseCase
import io.embodit.accesscheck.domain.usecase.PushFollowUseCase
import io.embodit.accesscheck.platform.AcLog
import io.embodit.accesscheck.platform.AccessGraph
import io.embodit.accesscheck.platform.sense.PlaneTracker
import io.embodit.accesscheck.platform.tracking.HeadPose
import io.embodit.accesscheck.ui.access.AccessEvent
import io.embodit.accesscheck.ui.access.AccessUiState
import io.embodit.accesscheck.ui.access.BlockReason
import io.embodit.accesscheck.ui.access.Card
import io.embodit.accesscheck.ui.access.ChairMode
import io.embodit.accesscheck.ui.access.Phase
import io.embodit.accesscheck.ui.access.Tool
import io.embodit.accesscheck.ui.access.debug.AcProbe
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: AccessScene? = null
}

/** The one per-frame ECS system of the app. */
class AccessFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/**
 * The 3D side of the app: the chair, its following, bumping, scuffs, the turning ring, doors, reach band
 * and eye line, the tap targets, and the placement of the pre-built panels. Compose state is rendered
 * through [render] (idempotent diff); per-frame work happens in [onFrame] only and never goes through
 * Compose (the head is reported to the ViewModel ten times a second, the chair pose as well while the
 * scene drives it). Everything started in [attach] is stopped in [detach]. Main thread only.
 */
class AccessScene(private val sink: (AccessEvent) -> Unit) : AcProbe.SceneProbe {

    /** Panel id -> the panel is in view: a pooled panel composes its content only then. */
    val panelShown = mutableStateMapOf<String, Boolean>()

    /** Bumped to make `SpatialView.update` run again while panel entities are still missing. */
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val planeTracker = AccessGraph.planes
    private val meshTracker = AccessGraph.mesh
    private val headTracker = AccessGraph.head
    private val controller = AccessGraph.controller
    private val field = AccessGraph.field
    private val pushRule = PushFollowUseCase()
    private val doorRule = DoorUseCase()
    private val hits = HitSurfaceUseCase()

    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var contentProbe: Entity? = null
    private var materials: SceneMaterials? = null
    private var shared: SharedResources? = null
    private var colliders: PlaneColliders? = null
    private var rig: PanelRig? = null
    private var chair: ChairView? = null
    private var marks: MarksView? = null
    private var attached = false

    private val consoleFollower = Follower("console", CONSOLE_OFFSET, CONSOLE_HALF_EXTENTS, CONSOLE_LEAN)
    private val hintFollower = Follower("hint", HINT_OFFSET, HINT_HALF_EXTENTS, nearM = 0.5f, farM = 1.6f)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buildJob: Job? = null

    private var ui = AccessUiState()
    private var head = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var lastFrameMs = 0L
    private var lastHeadSentMs = 0L
    private var lastPoseSentMs = 0L
    private var lastPlaneCount = -1
    private var lastPlanesEmitMs = 0L
    private var lastFloor = false
    private var lastLost = false
    private var lastBlock: BlockReason? = null
    private var blockChecked = false
    private var fieldPlaneVersion = -1
    private var lastMeshVersion = -1
    private var lastMeshRebuildMs = 0L
    private var panelPressed = false
    private var lastPanelPressMs = NEVER
    private var dialogPlaced = false

    // chair driving
    private var livePose: ChairPose? = null
    private var shownPose: ChairPose? = null
    private var lastSerial = -1
    private var lastMode = ChairMode.NONE
    private var push: PushFollowUseCase.State? = null
    private var dragging = false
    private var dragPxToM = 0.001f
    private var stickActive = false
    private var activeHits = HashMap<String, V2>()
    private var prevCenter: V2? = null
    private var prevFront: V2? = null
    private var stillSinceMs = NEVER
    private var lastFoot: V2? = null
    private var stuckSent = false

    // debug overrides
    private var debugFoot: V2? = null
    private var debugYaw: Float? = null
    private var debugEye: Float? = null
    private var obstaclesShown = false
    private var fpsOn = false
    private var frames = 0
    private var fpsStartMs = 0L

    /** The console's pointer: a press holds the lazy follow still. */
    fun onPanelPointer(pressed: Boolean) {
        panelPressed = pressed
        lastPanelPressMs = SystemClock.elapsedRealtime()
    }

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        val probe = Entity().named("contentProbe")
        content.addEntity(probe)
        contentProbe = probe
        // An entity added to the SpatialView is not at the Stage origin. Move the root once so that
        // its local space equals Stage space (Spatial Ruler finding).
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        AcLog.i("STAGE root offset=${AcLog.pos(offset.domain())}")

        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        for (id in PanelSpec.pool) {
            val (w, h) = PanelSpec.size(id)
            rig.world(id, w, h)
        }
        headTracker.start()
        controller.start()
        planeTracker.start(
            PlaneTracker.Listener { id, surface, _ ->
                if (surface != null) colliders?.upsert(surface) else colliders?.remove(id)
                fieldPlaneVersion = -1
            }
        )
        meshTracker.start()
        ActiveScene.scene = this
        bindPanels(attachments)
        consoleFollower.reset()
        hintFollower.reset()
        buildJob =
            scope.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) AcLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
    }

    /** Materials and pools; the main thread is given back between steps (design: pre-build in "starting"). */
    private suspend fun build(root: Entity, t0: Long) {
        val sh = SharedResources()
        shared = sh
        val m = SceneMaterials(sh)
        materials = m
        yield()
        colliders =
            PlaneColliders(root).also {
                it.buildBackstop()
                planeTracker.all.forEach(it::upsert)
            }
        yield()
        chair = ChairView(root, m, sh).also { it.prepare() }
        yield()
        marks = MarksView(root, m, sh).also { it.prepare() }
        yield()
        attached = true
        AcLog.i("SCENE attached build_ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui, force = true)
        sink(AccessEvent.SceneReady)
    }

    /** Called from `SpatialView(update = ...)`: parents freshly composed panel entities under their holders. */
    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        val complete = rig.bind(attachments, PanelSpec.pool, SystemClock.elapsedRealtime())
        if (!complete) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, REBIND_DELAY_MS)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        buildJob?.cancel()
        buildJob = null
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        planeTracker.stop()
        meshTracker.stop()
        headTracker.stop()
        controller.stop()
        panelShown.clear()
        runCatching { rig?.release() }
        chair?.destroy()
        marks?.destroy()
        colliders?.clear()
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        stageRoot = null
        contentProbe = null
        content = null
        materials = null
        colliders = null
        rig = null
        chair = null
        marks = null
        hasHead = false
        lastPlaneCount = -1
        lastLost = false
        dialogPlaced = false
        push = null
        activeHits.clear()
        AcLog.i("SCENE detached")
    }

    // =========================================================================================
    // model -> entities (called when the UI state changes)
    // =========================================================================================

    fun render(state: AccessUiState, force: Boolean = false) {
        val previous = ui
        ui = state
        if (!attached) return
        val chair = chair ?: return
        val marks = marks ?: return
        chair.build(state.chair)
        // the chair pose: the ViewModel's unless the scene is driving it
        if (force || state.poseSerial != lastSerial || state.pose == null || !sceneDrives(state)) {
            lastSerial = state.poseSerial
            livePose = state.pose
            if (state.poseSerial != previous.poseSerial) shownPose = state.pose
        }
        if (state.chairMode != lastMode) {
            onModeChanged(previous.chairMode, state.chairMode, state)
            lastMode = state.chairMode
        }
        chair.setShown(state.pose != null && state.chairMode != ChairMode.NONE)
        chair.setStale(state.trackingLost)
        marks.syncScuffs(state.scuffs)
        marks.syncPins(state.pins)
        marks.syncTurn(state.turn, state.floorY)
        syncPanels(state)
    }

    private fun sceneDrives(state: AccessUiState): Boolean =
        state.chairMode == ChairMode.PUSHING || state.chairMode == ChairMode.DRAGGING || (state.chairMode == ChairMode.PARKED && stickActive)

    private fun onModeChanged(from: ChairMode, to: ChairMode, state: AccessUiState) {
        AcLog.i("CHAIR mode ${from.name.lowercase(Locale.US)} -> ${to.name.lowercase(Locale.US)}")
        if (to == ChairMode.PUSHING) {
            val p = livePose ?: state.pose
            val f = currentFoot()
            if (p != null && f != null) push = pushRule.start(f, p, state.chair) else push = null
            stillSinceMs = NEVER
            stuckSent = false
        } else {
            push = null
            if (stuckSent) {
                stuckSent = false
                sink(AccessEvent.StuckChanged(false))
            }
        }
        if (to != ChairMode.DRAGGING) dragging = false
        if (to == ChairMode.NONE) {
            activeHits.clear()
            prevCenter = null
            prevFront = null
        }
    }

    private fun syncPanels(state: AccessUiState) {
        val rig = rig ?: return
        if (!state.menuOpen) rig[PanelSpec.MENU]?.hide()
        if (!state.reportOpen) rig[PanelSpec.REPORT]?.hide()
        if (!(state.coach && state.chairMode == ChairMode.PUSHING)) rig[PanelSpec.COACH]?.hide()
        if (state.card != Card.PIN_TAG) rig[PanelSpec.PIN_TAG]?.hide()
        if (state.card != Card.DOOR) rig[PanelSpec.DOOR_CARD]?.hide()
        val plateShown = state.pose != null && (state.chairMode == ChairMode.PARKED || state.chairMode == ChairMode.TURNING || state.chairMode == ChairMode.DRAGGING)
        if (!plateShown) rig[PanelSpec.CHAIR_PLATE]?.hide()
        val dialog = rig[PanelSpec.DIALOG]
        if (state.dialog != null) {
            if (!dialogPlaced) {
                val ahead = head.forward.let { if (it.length < 0.1f) Vec3.AHEAD else it.normalized() }
                dialog?.show(head.position + ahead * PanelSpec.DIALOG_DISTANCE_M, PanelSpec.MAIN_SCALE * 1.5f)
                dialogPlaced = true
            }
        } else {
            dialog?.hide()
            dialogPlaced = false
        }
        if (state.hint == null) rig[PanelSpec.HINT]?.hide()
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0.016f else ((now - lastFrameMs) / 1000f).coerceIn(0.001f, 0.1f)
        lastFrameMs = now
        headTracker.pose()?.let {
            head = applyDebugHead(it)
            hasHead = true
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(head.view)
        val state = ui
        val hold = panelPressed || (lastPanelPressMs != NEVER && now - lastPanelPressMs < PANEL_HOLD_MS) || state.menuOpen || state.dialog != null || state.reportOpen
        if (hasHead && head.position.length > 1e-3f) {
            consoleFollower.headOffset = if (state.chairMode == ChairMode.PUSHING) CONSOLE_OFFSET_PUSHING else CONSOLE_OFFSET
            consoleFollower.follow(head, planeTracker.all, planeTracker.count, now, hold)
            hintFollower.follow(head, planeTracker.all, planeTracker.count, now, hold = false)
        }
        placeConsoleFamily(state, rig)
        countFrame(now)
        if (!attached) return

        pollBlock(now)
        pollTracking(now)
        pollPlanes(now)
        pollField(now)
        driveChair(state, now, dt)
        placeWorldPanels(state, rig)
        if (hasHead && now - lastHeadSentMs >= HEAD_SAMPLE_MS) {
            lastHeadSentMs = now
            val foot = currentFoot() ?: V2.of(head.position)
            sink(AccessEvent.HeadSample(head.position, head.forward, foot))
        }
        rig.faceViewer(head.position)
    }

    private fun applyDebugHead(real: HeadPose): HeadPose {
        val f = debugFoot
        val yaw = debugYaw
        val eye = debugEye
        if (f == null && yaw == null && eye == null) return real
        val pos = Vec3(f?.x ?: real.position.x, if (eye != null) ui.floorY + eye else real.position.y, f?.z ?: real.position.z)
        val rot = if (yaw != null) Quat4.axisAngle(Vec3.UP, yaw) else real.rotation
        return HeadPose(pos, rot)
    }

    /** The floor point under the head (the push input), or null before the first head sample. */
    private fun currentFoot(): V2? = if (hasHead) V2.of(head.position) else null

    // ---- the chair: following, dragging, turning with the stick, bumping, doors
    private fun driveChair(state: AccessUiState, now: Long, dt: Float) {
        val chair = chair ?: return
        val marks = marks ?: return
        val pose = livePose
        if (pose == null || state.chairMode == ChairMode.NONE) {
            chair.setHit(false)
            marks.syncClearFloor(null, false, false, state.floorY)
            marks.syncSnap(null, null, state.floorY)
            marks.syncReach(null, null, state.floorY)
            syncEyeLine(state)
            syncGates(state, null)
            return
        }
        var next = pose
        var scenePose = false
        when (state.chairMode) {
            ChairMode.PUSHING -> {
                val foot = currentFoot()
                val p = push
                if (foot != null && !state.trackingLost) {
                    val s = if (p == null) pushRule.start(foot, pose, state.chair) else pushRule.step(p, foot, dt, state.chair)
                    push = s
                    next = s.pose
                    scenePose = true
                    trackStill(foot, now)
                }
            }
            ChairMode.PARKED -> {
                val sample = controller.sample()
                val x = sample?.stickX ?: 0f
                if (state.card == Card.NONE && state.tool == Tool.NONE && abs(x) > AccessRules.STICK_DEAD) {
                    val rate = Math.toRadians(AccessRules.STICK_RATE_DEG_S.toDouble()).toFloat()
                    next = ChairPose(pose.center, pose.theta - x * rate * dt)
                    stickActive = true
                    scenePose = true
                } else if (stickActive) {
                    stickActive = false
                    sink(AccessEvent.ChairMoved(pose, settled = true))
                }
            }
            ChairMode.DRAGGING -> scenePose = dragging
            else -> Unit
        }
        livePose = next
        // the drawn pose eases towards the live one (0.3 s transition of the design)
        val shown = shownPose
        val eased =
            if (shown == null || state.chairMode == ChairMode.TURNING) next
            else {
                val k = (dt / 0.08f).coerceIn(0f, 1f)
                var dTheta = next.theta - shown.theta
                while (dTheta > PI.toFloat()) dTheta -= 2f * PI.toFloat()
                while (dTheta < -PI.toFloat()) dTheta += 2f * PI.toFloat()
                ChairPose(shown.center.lerp(next.center, k), shown.theta + dTheta * k)
            }
        shownPose = eased
        val spin =
            if (state.chairMode == ChairMode.TURNING && state.turn != null) {
                val q = ((now - state.turn.startedAtMs) / AccessRules.TURN_ANIM_MS.toFloat()).coerceIn(0f, 1f)
                Ease.inOutCubic(q) * 2f * PI.toFloat()
            } else 0f
        chair.setPose(eased, state.floorY, spin)
        if (scenePose && now - lastPoseSentMs >= POSE_SAMPLE_MS) {
            lastPoseSentMs = now
            sink(AccessEvent.ChairMoved(next, settled = false))
        }
        // bumps
        val foot = next.footprint(state.chair)
        val moving = state.chairMode == ChairMode.PUSHING || state.chairMode == ChairMode.DRAGGING || stickActive || state.chairMode == ChairMode.PARKED
        if (moving && !state.trackingLost) collide(foot, state.floorY, state.chairMode == ChairMode.PUSHING, now)
        chair.setHit(activeHits.isNotEmpty() && !state.trackingLost)
        // doors crossed
        val front = next.center + next.heading * (state.chair.lengthM / 2f)
        val pc = prevCenter
        val pf = prevFront
        if (pc != null && pf != null && state.chairMode == ChairMode.PUSHING) {
            for (d in state.doors) {
                if (doorRule.crossesLine(d.entry.info, pf, front)) sink(AccessEvent.DoorCrossed(d.entry.info.id, frontWheels = true))
                else if (doorRule.crossesLine(d.entry.info, pc, next.center)) sink(AccessEvent.DoorCrossed(d.entry.info.id, frontWheels = false))
            }
        }
        prevCenter = next.center
        prevFront = front
        // standing overlays
        val parkedLike = state.chairMode == ChairMode.PARKED || state.chairMode == ChairMode.DRAGGING || state.chairMode == ChairMode.TURNING
        marks.syncClearFloor(next, parkedLike && state.turn == null, state.clearFloor != null, state.floorY)
        marks.syncSnap(if (parkedLike) state.snapTarget else null, if (parkedLike) foot else null, state.floorY)
        if (state.chairMode == ChairMode.PARKED) {
            val wall = field.nearestWall(foot, AccessRules.REACH_WALL_WITHIN_M)
            marks.syncReach(wall?.first?.box, next.center, state.floorY)
        } else {
            marks.syncReach(null, null, state.floorY)
        }
        syncEyeLine(state)
        syncGates(state, next.center)
    }

    private fun trackStill(foot: V2, now: Long) {
        val last = lastFoot
        lastFoot = foot
        if (last == null) return
        val still = foot.distanceTo(last) < AccessRules.STUCK_SPEED_M_S / 60f
        if (!still) {
            stillSinceMs = NEVER
            if (stuckSent) {
                stuckSent = false
                sink(AccessEvent.StuckChanged(false))
            }
            return
        }
        if (stillSinceMs == NEVER) stillSinceMs = now
        val stuck = activeHits.isNotEmpty() && now - stillSinceMs >= AccessRules.STUCK_MS
        if (stuck != stuckSent) {
            stuckSent = stuck
            sink(AccessEvent.StuckChanged(stuck))
        }
    }

    /** Footprint against the field: new contacts become scuffs, ended ones close them. */
    private fun collide(foot: Obb2, floorY: Float, pushing: Boolean, now: Long) {
        val found = field.hits(foot)
        val seen = HashSet<String>()
        for (h in found) {
            val id = h.obstacle.id
            seen += id
            val contact2 = h.contact
            activeHits[id] = contact2
            if (!ui.scuffs.any { it.obstacleId == id && it.exit == null } && !started.contains(id)) {
                started += id
                val y = floorY + if (h.obstacle.yLow > 0.05f) h.obstacle.yLow + 0.05f else 0.15f
                val n = Vec3(h.axis.x, 0f, h.axis.z).normalized()
                sink(AccessEvent.Bumped(id, h.obstacle.name, Vec3(contact2.x, y, contact2.z), n))
                AcLog.i("BUMP start id=$id name=${h.obstacle.name} depth=${AcLog.f(h.depth, 3)}")
            }
        }
        val ended = activeHits.keys.filter { it !in seen }
        for (id in ended) {
            val last = activeHits.remove(id) ?: continue
            started -= id
            val y = floorY + 0.15f
            sink(AccessEvent.BumpEnded(id, Vec3(last.x, y, last.z)))
            AcLog.i("BUMP end id=$id")
        }
        @Suppress("UNUSED_EXPRESSION") pushing
        @Suppress("UNUSED_EXPRESSION") now
    }

    private val started = HashSet<String>()

    private fun syncEyeLine(state: AccessUiState) {
        val marks = marks ?: return
        if (state.view == ViewHeight.STAND || !hasHead) {
            marks.syncEye(ViewHeight.STAND, null, null, state.floorY)
            eyeHit = null
            return
        }
        val flat = Vec3(head.forward.x, 0f, head.forward.z).let { if (it.length < 0.1f) Vec3.AHEAD else it.normalized() }
        val walls = planeTracker.all.filter { it.kind == SurfaceKind.WALL && it.vertical }
        val hit = hits.nearest(walls, head.position, flat, 8f)
        eyeHit = hit?.let { it.point to it.normal }
        marks.syncEye(state.view, hit?.point, hit?.normal, state.floorY)
    }

    private var eyeHit: Pair<Vec3, Vec3>? = null

    private fun syncGates(state: AccessUiState, chairCenter: V2?) {
        val marks = marks ?: return
        val now = SystemClock.elapsedRealtime()
        for (slot in 0..1) {
            val d = state.doors.firstOrNull { it.slot == slot }
            if (d == null) {
                marks.syncGate(slot, null, null, false, false, null)
                continue
            }
            val flash = d.flashAtMs != 0L && now - d.flashAtMs < FLASH_MS
            val thresholdRed = d.verdict.threshold == Verdict.FAIL
            marks.syncGate(slot, d.entry.info, d.verdict.overall, thresholdRed, flash, chairCenter)
        }
    }

    // ---- panels
    private fun placeConsoleFamily(state: AccessUiState, rig: PanelRig) {
        if (!hasHead) return
        val pose = consoleFollower.pose
        val rot = consoleFollower.rotation
        val k = PanelSpec.MAIN_SCALE
        rig[PanelSpec.CONSOLE]?.place(pose.position, rot, k)
        val (hw, hh) = PanelSpec.halfM(PanelSpec.CONSOLE, k)
        if (state.menuOpen) {
            val (mw, mh) = PanelSpec.halfM(PanelSpec.MENU, k)
            rig[PanelSpec.MENU]?.place(pose.position + rot.rotate(Vec3(hw - mw, hh + 0.012f + mh, 0.012f)), rot, k)
        }
        if (state.reportOpen) {
            val (rw, rh) = PanelSpec.halfM(PanelSpec.REPORT, k)
            rig[PanelSpec.REPORT]?.place(pose.position + rot.rotate(Vec3(hw + 0.03f + rw, rh - hh, 0.01f)), rot, k)
        }
        if (state.hint != null) {
            val hp = hintFollower.pose
            rig[PanelSpec.HINT]?.place(hp.position, hintFollower.rotation, HINT_SCALE)
        }
    }

    private fun placeWorldPanels(state: AccessUiState, rig: PanelRig) {
        val marks = marks ?: return
        val pose = shownPose ?: livePose
        val k = PanelSpec.PLATE_SCALE
        // chair plate above the push handle
        if (pose != null && (state.chairMode == ChairMode.PARKED || state.chairMode == ChairMode.TURNING || state.chairMode == ChairMode.DRAGGING)) {
            val handle = pose.handle(state.chair)
            val (_, ph) = PanelSpec.halfM(PanelSpec.CHAIR_PLATE, k)
            rig[PanelSpec.CHAIR_PLATE]?.show(Vec3(handle.x, state.floorY + state.chair.handleY + 0.25f + ph, handle.z), k)
        }
        // coach bubble ahead of the chair
        if (pose != null && state.coach && state.chairMode == ChairMode.PUSHING) {
            val front = pose.center + pose.heading * (state.chair.lengthM / 2f + 0.25f)
            rig[PanelSpec.COACH]?.show(Vec3(front.x, state.floorY + 1.05f, front.z), k)
        }
        // door plates and the expanded card
        val towardsRef = pose?.center ?: currentFoot() ?: V2.ZERO
        for (slot in 0..1) {
            val id = if (slot == 0) PanelSpec.DOOR_A else PanelSpec.DOOR_B
            val d = state.doors.firstOrNull { it.slot == slot }
            if (d == null || (d.expanded && state.card == Card.DOOR)) {
                rig[id]?.hide()
                continue
            }
            val (_, ph) = PanelSpec.halfM(id, k)
            val towards = doorRule.normalTowards(d.entry.info, towardsRef)
            rig[id]?.show(marks.doorPlatePosition(d.entry.info, towards, ph), k)
        }
        val expanded = state.doors.firstOrNull { it.expanded && state.card == Card.DOOR }
        if (expanded != null) {
            val (_, ch) = PanelSpec.halfM(PanelSpec.DOOR_CARD, k)
            val towards = doorRule.normalTowards(expanded.entry.info, towardsRef)
            rig[PanelSpec.DOOR_CARD]?.show(marks.doorPlatePosition(expanded.entry.info, towards, ch), k)
        }
        // pin tag card above the pin awaiting its tag
        if (state.card == Card.PIN_TAG) {
            val pin = state.pins.firstOrNull { it.id == state.cardPinId }
            if (pin != null) {
                val (_, th) = PanelSpec.halfM(PanelSpec.PIN_TAG, k)
                rig[PanelSpec.PIN_TAG]?.show(marks.pinCardPosition(pin) + Vec3(0f, th, 0f), k)
            }
        }
    }

    private fun pollPlanes(now: Long) {
        val count = planeTracker.count
        val planes = planeTracker.all
        val hasFloor = planes.any { it.kind == SurfaceKind.FLOOR }
        if (count == lastPlaneCount && hasFloor == lastFloor) return
        if (now - lastPlanesEmitMs < PLANES_EMIT_MS) return
        lastPlanesEmitMs = now
        lastPlaneCount = count
        lastFloor = hasFloor
        field.rebuildFromPlanes(planes)
        fieldPlaneVersion = count
        val doors = planes.filter { it.kind.isDoorway }.map { it.id }
        AcLog.i("PLANES n=$count floor=$hasFloor floorY=${AcLog.f(field.floorY)} doors=${doors.size} obstacles=${field.all().size}")
        sink(AccessEvent.PlanesChanged(count, hasFloor, field.floorY, doors))
    }

    /** Planes changed geometry (not count) or the mesh changed: rebuild the field, at most once a second. */
    private fun pollField(now: Long) {
        if (now - lastMeshRebuildMs < FIELD_REBUILD_MS) return
        val meshVersion = meshTracker.version
        val planesDirty = fieldPlaneVersion == -1
        if (!planesDirty && meshVersion == lastMeshVersion) return
        lastMeshRebuildMs = now
        if (planesDirty) {
            field.rebuildFromPlanes(planeTracker.all)
            fieldPlaneVersion = planeTracker.count
        }
        if (meshVersion != lastMeshVersion || planesDirty) {
            lastMeshVersion = meshVersion
            val before = field.meshCellCount
            field.rebuildMesh { block -> meshTracker.forEachVertex(block) }
            if (field.meshCellCount != before) AcLog.i("MESH anchors=${meshTracker.anchorCount} cells=${field.meshCellCount}")
        }
        if (obstaclesShown) marks?.showObstacles(field.all(), field.floorY, true)
    }

    private fun pollTracking(now: Long) {
        val lost = headTracker.lost(now)
        if (lost == lastLost) return
        lastLost = lost
        sink(AccessEvent.TrackingChanged(lost))
    }

    /** The provider's support state turns to SUPPORTED by itself while the Stage opens: asked for 3 s before blocking. */
    private fun pollBlock(now: Long) {
        val reason =
            when (headTracker.blockReason()) {
                io.embodit.accesscheck.domain.model.BlockReason.NOT_IN_FULL_SPACE -> BlockReason.NOT_IN_FULL_SPACE
                io.embodit.accesscheck.domain.model.BlockReason.WITHOUT_PERMISSION -> BlockReason.WITHOUT_PERMISSION
                io.embodit.accesscheck.domain.model.BlockReason.DEVICE_NOT_SUPPORTED -> BlockReason.DEVICE_NOT_SUPPORTED
                null -> null
            }
        if (reason == null) {
            if (lastBlock != null) {
                lastBlock = null
                sink(AccessEvent.Blocked(null))
            }
            blockChecked = true
            return
        }
        if (!blockChecked) {
            if (blockSinceMs == NEVER) blockSinceMs = now
            if (now - blockSinceMs < BLOCK_GRACE_MS) return
        }
        if (reason != lastBlock) {
            lastBlock = reason
            sink(AccessEvent.Blocked(reason))
        }
    }

    private var blockSinceMs = NEVER

    private fun countFrame(now: Long) {
        if (!fpsOn) return
        frames++
        if (fpsStartMs == 0L) fpsStartMs = now
        if (now - fpsStartMs >= 1000L) {
            AcLog.i("FPS app=${frames * 1000f / (now - fpsStartMs)} frames=$frames ms=${now - fpsStartMs}")
            frames = 0
            fpsStartMs = now
        }
    }

    // =========================================================================================
    // system pointer (gaze + pinch, emulator mouse)
    // =========================================================================================

    /** A system spatial tap on one of our entities. */
    fun onSpatialTap(value: SpatialTapValue) {
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        val kind = value.interactionKind
        if (target == null) {
            AcLog.i("TAP other target=none kind=$kind")
            return
        }
        val planeId = colliders?.planeIdOf(target)
        when {
            chair?.isChair(target) == true -> AcLog.i("TAP chair kind=$kind")
            planeId != null && stage != null -> {
                val plane = planeTracker.get(planeId)
                val point = if (plane != null) hits.project(plane, stage) else stage
                var normal = plane?.normal ?: Vec3.UP
                if (plane != null && (head.position - point).dot(normal) < 0f) normal = -normal
                AcLog.i("TAP surface plane=${name.removePrefix("plane:")} kind=$kind stage=${AcLog.pos(point)}")
                sink(AccessEvent.SurfaceTapped(planeId, point, normal))
            }
            colliders?.isBackstop(target) == true -> {
                AcLog.i("TAP backstop kind=$kind stage=${AcLog.pos(stage)}")
                sink(AccessEvent.NothingTapped)
            }
            else -> AcLog.i("TAP other target=$name kind=$kind stage=${AcLog.pos(stage)}")
        }
    }

    /**
     * A system spatial drag on the parked chair moves it over the floor: the hand's travel in view
     * pixels (0.4 mm each at the chair's depth) along the head's level right and forward.
     */
    fun onSpatialDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        when (phase) {
            "start" -> {
                val target = value.targetEntity ?: return
                if (chair?.isChair(target) != true) return
                val pose = livePose ?: return
                if (ui.chairMode != ChairMode.PARKED || ui.card != Card.NONE) return
                val dist = head.position.distanceTo(Vec3(pose.center.x, ui.floorY + 0.5f, pose.center.z)).coerceAtLeast(0.3f)
                dragPxToM = PX_M * (0.67f + 1.186f * dist)
                dragging = true
                AcLog.i("DRAG start dist=${AcLog.f(dist)}")
                sink(AccessEvent.DragStarted)
            }
            "move" -> {
                if (!dragging) return
                val pose = livePose ?: return
                val a = value.dragAmount
                val right = V2(head.right.x, head.right.z).let { if (it.length < 0.1f) V2(1f, 0f) else it.normalized() }
                val fwd = V2(head.forward.x, head.forward.z).let { if (it.length < 0.1f) V2(0f, -1f) else it.normalized() }
                val move = right * (a.x * dragPxToM) + fwd * (-a.y * dragPxToM)
                livePose = ChairPose(pose.center + move, pose.theta)
            }
            "end", "cancel" -> {
                if (!dragging) return
                dragging = false
                val pose = livePose ?: return
                AcLog.i("DRAG end at=(${AcLog.f(pose.center.x)}, ${AcLog.f(pose.center.z)})")
                sink(AccessEvent.DragEnded(pose))
            }
        }
        @Suppress("UNUSED_EXPRESSION") start
    }

    /** View-local pixels of a spatial tap -> Stage metres, through the SpatialView content conversion. */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .onFailure { AcLog.w("TAP convert failed: $it") }
            .getOrNull()
    }

    fun headPose(): HeadPose = head

    // =========================================================================================
    // debug probe
    // =========================================================================================

    override fun poseLine(): String =
        "POSE hmd pos=${AcLog.pos5(head.position)} ${AcLog.quat5(head.rotation)} fwd=${AcLog.pos5(head.forward)} valid=$hasHead chair=${livePose?.let { "(${AcLog.f(it.center.x)}, ${AcLog.f(it.center.z)}) yaw=${AcLog.f(Math.toDegrees(it.theta.toDouble()).toFloat(), 1)}" } ?: "none"}"

    override fun panelLines(): List<String> {
        val rig = rig ?: return emptyList()
        return rig.all.filter { it.shown && it.panel != null }.map { slot ->
            String.format(Locale.US, "PANEL id=%s pos=%s %s scale=%.3f size_dp=%.0fx%.0f engine=%s", slot.id, AcLog.pos5(slot.position), AcLog.quat5(slot.rotation), slot.scale, slot.widthDp, slot.heightDp, AcLog.pos5(slot.enginePosition()))
        }
    }

    override fun planesSummary(): List<String> =
        planeTracker.all.map { p -> "PLANE ${p.label} kind=${p.kind} center=${AcLog.pos(p.centroid)} normal=${AcLog.pos(p.normal)} verts=${p.vertices.size} minY=${AcLog.f(p.minY)}" } +
            field.all().map { o -> "OBSTACLE ${o.id} ${o.name} center=(${AcLog.f(o.box.center.x)}, ${AcLog.f(o.box.center.z)}) half=(${AcLog.f(o.box.hu)}, ${AcLog.f(o.box.hv)}) y=${AcLog.f(o.yLow)}..${AcLog.f(o.yHigh)}" }

    override fun parkPanel(): String {
        consoleFollower.park(head, planeTracker.all, SystemClock.elapsedRealtime())
        return "FOLLOW console park requested"
    }

    override fun setFpsLine(on: Boolean): String {
        fpsOn = on
        frames = 0
        fpsStartMs = 0L
        return "FPS ${if (on) "on" else "off"}"
    }

    override fun setDebugHead(x: Float?, z: Float?, yawDeg: Float?): String {
        debugFoot = if (x != null && z != null) V2(x, z) else null
        debugYaw = yawDeg?.let { Math.toRadians(it.toDouble()).toFloat() }
        return "HEAD override foot=${debugFoot} yaw=$yawDeg"
    }

    override fun setDebugEye(heightM: Float?): String {
        debugEye = heightM
        return "EYE override h=$heightM"
    }

    override fun showObstacles(on: Boolean): String {
        obstaclesShown = on
        marks?.showObstacles(field.all(), field.floorY, on)
        return "OBSTACLES shown=$on count=${field.all().size} cells=${field.meshCellCount}"
    }

    override fun debugDrag(x: Float, z: Float): String {
        val pose = livePose ?: return "DRAG ignored: no chair"
        if (ui.chairMode != ChairMode.PARKED) return "DRAG ignored: not parked"
        sink(AccessEvent.DragStarted)
        livePose = ChairPose(V2(x, z), pose.theta)
        sink(AccessEvent.DragEnded(livePose!!))
        return "DRAG to=($x, $z)"
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val REBIND_DELAY_MS = 150L
        private const val PLANES_EMIT_MS = 500L
        private const val FIELD_REBUILD_MS = 1000L
        private const val HEAD_SAMPLE_MS = 100L
        private const val POSE_SAMPLE_MS = 100L
        private const val PANEL_HOLD_MS = 600L
        private const val BLOCK_GRACE_MS = 3000L
        private const val FLASH_MS = 400L
        private const val HINT_SCALE = 1.0f

        /** View pixels of a system drag -> metres at 1 m (0.4 mm each, times the depth factor). */
        private const val PX_M = 0.0004f

        /** 18 degrees left and 26 degrees down, 0.6 m from the head (design `panels.console.placement`). */
        val CONSOLE_OFFSET = Vec3(-(sin(0.3142f) * cos(0.4538f) * 0.6f), -(sin(0.4538f) * 0.6f), -(cos(0.3142f) * cos(0.4538f) * 0.6f))

        /** While pushing the console sinks to 32 degrees so it does not cover the floor ahead. */
        val CONSOLE_OFFSET_PUSHING = Vec3(-(sin(0.3142f) * cos(0.5585f) * 0.6f), -(sin(0.5585f) * 0.6f), -(cos(0.3142f) * cos(0.5585f) * 0.6f))

        /** The console leans back 12 degrees. */
        val CONSOLE_LEAN: Quat4 = Quat4.axisAngle(Vec3.RIGHT, -0.20944f)

        /** Half sizes of the console at 0.75 (0.24 x 0.25 m) with a little air, for the surface rule. */
        private val CONSOLE_HALF_EXTENTS = listOf(0.13f, 0.13f, 0.01f)

        /** The hint bar 15 degrees above the view centre, 1.0 m away. */
        val HINT_OFFSET = Vec3(0f, sin(0.2618f) * 1.0f, -cos(0.2618f) * 1.0f)
        private val HINT_HALF_EXTENTS = listOf(0.20f, 0.03f, 0.01f)
    }
}
