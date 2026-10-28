package io.embodit.accesscheck.ui.access.scene

import io.embodit.accesscheck.domain.model.PlaneSurface
import io.embodit.accesscheck.domain.model.Quat4
import io.embodit.accesscheck.domain.model.Vec3
import io.embodit.accesscheck.domain.usecase.FacePanelUseCase
import io.embodit.accesscheck.domain.usecase.RestBodyUseCase
import io.embodit.accesscheck.platform.AcLog
import io.embodit.accesscheck.platform.tracking.HeadPose
import java.util.Locale
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2

/** A pose in Stage space: where a followed thing sits and its level turn (yaw about the vertical). */
data class FollowPose(val position: Vec3, val yaw: Float) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw)
}

/**
 * Lazy follow (Spatial Ruler rule T11, taken over for the main panel and the extinguisher prop): the
 * thing rests where it settled; once its centre has been more than 45 degrees off the view direction,
 * or nearer than [nearM] or farther than [farM], for 0.5 s without a break, it slides to the designed
 * pose of that moment in 0.35 s (ease-in-out, time based). It holds still while [hold]. The designed
 * pose is [headOffset] in the head's level frame (x right, y up, -z ahead): turned by the head's heading
 * only, never by its pitch or roll (v0.1.1, UI sweep: looking down at a threshold dropped the console
 * under the body), and "off to the side" is measured in the level plane too, so looking up and down
 * moves nothing. The surface rule keeps it out of real surfaces ([halfExtents] along its own axes).
 * Wherever it rests it always faces the head (v0.1.2, the user: "UI panels should follow the head and face
 * it"): every frame its yaw turns its front to the head's current position, about the vertical only.
 */
internal class Follower(
    private val name: String,
    var headOffset: Vec3,
    private val halfExtents: List<Float>,
    private val lean: Quat4 = Quat4.IDENTITY,
    private val nearM: Float = 0.35f,
    private val farM: Float = 0.9f,
) {
    private val restRule = RestBodyUseCase()
    private var started = false
    private var restYaw = 0f
    private var anchorPos = Vec3.ZERO
    private var applied = Vec3.ZERO
    private var outSinceMs = NEVER
    private var slideStartMs = NEVER
    private var slideFromPos = Vec3.ZERO
    private var slideFromYaw = 0f
    private var slideFromApplied = Vec3.ZERO
    private var slideToPos = Vec3.ZERO
    private var slideToYaw = 0f
    private var slideToApplied = Vec3.ZERO
    private var lastPlaneCount = -1
    private var headAt: Vec3? = null

    /** The current pose (centre and level turn). */
    var pose: FollowPose = FollowPose(Vec3.ZERO, 0f)
        private set

    val rotation: Quat4
        get() = pose.rotation * lean

    val sliding: Boolean
        get() = slideStartMs != NEVER

    fun reset() {
        started = false
        outSinceMs = NEVER
        slideStartMs = NEVER
    }

    private fun designedFor(head: HeadPose): Pair<Vec3, Float> {
        val yaw = FacePanelUseCase.yawOf(head.forward, restYaw)
        return designed(head.position, yaw, headOffset) to yaw
    }

    private fun correction(pos: Vec3, yaw: Float, headPos: Vec3, planes: Collection<PlaneSurface>): Vec3 {
        val turn = Quat4.axisAngle(Vec3.UP, yaw) * lean
        val axes = listOf(turn.rotate(Vec3.RIGHT), turn.rotate(Vec3.UP), turn.rotate(Vec3(0f, 0f, 1f)))
        return restRule.rest(pos, axes, halfExtents, headPos, planes) - pos
    }

    /** Once per frame. Returns true when the pose changed this frame. */
    fun follow(head: HeadPose, planes: Collection<PlaneSurface>, planeCount: Int, nowMs: Long, hold: Boolean): Boolean {
        headAt = head.position
        if (!started) {
            val (pos, yaw) = designedFor(head)
            started = true
            restYaw = yaw
            anchorPos = pos
            applied = correction(pos, yaw, head.position, planes)
            lastPlaneCount = planeCount
            write()
            AcLog.i("FOLLOW $name park reason=start pos=${AcLog.pos(pose.position)} yaw=${String.format(Locale.US, "%.1f", Math.toDegrees(yaw.toDouble()))}")
            return true
        }
        if (sliding) {
            slide(nowMs)
            return true
        }
        val to = pose.position - head.position
        val dist = to.length
        val away = levelAngleDeg(to, head.forward, restYaw)
        val reason =
            when {
                away > WINDOW_DEG -> "angle"
                dist < nearM -> "near"
                dist > farM -> "far"
                else -> null
            }
        if (reason == null || hold) {
            outSinceMs = NEVER
        } else if (outSinceMs == NEVER) {
            outSinceMs = nowMs
        } else if (nowMs - outSinceMs >= OUT_MS) {
            startSlide(head, planes, nowMs, reason)
            return true
        }
        if (planeCount != lastPlaneCount) {
            lastPlaneCount = planeCount
            val wanted = correction(anchorPos, restYaw, head.position, planes)
            if ((wanted - applied).length > DEAD_BAND_M) {
                applied = wanted
                write()
                return true
            }
        }
        // still resting: keep facing the head as it moves
        write()
        return false
    }

    /** Slides to the designed pose now (debug `park`, and after a hidden panel comes back). */
    fun park(head: HeadPose, planes: Collection<PlaneSurface>, nowMs: Long) {
        if (!started) return
        startSlide(head, planes, nowMs, "park")
    }

    private fun startSlide(head: HeadPose, planes: Collection<PlaneSurface>, nowMs: Long, reason: String) {
        val (pos, yaw) = designedFor(head)
        slideFromPos = anchorPos
        slideFromYaw = restYaw
        slideFromApplied = applied
        slideToPos = pos
        slideToYaw = yaw
        slideToApplied = correction(pos, yaw, head.position, planes)
        slideStartMs = nowMs
        outSinceMs = NEVER
        AcLog.i("FOLLOW $name slide reason=$reason to=${AcLog.pos(pos + slideToApplied)}")
        slide(nowMs)
    }

    private fun slide(nowMs: Long) {
        val q = ((nowMs - slideStartMs) / SLIDE_MS).coerceIn(0f, 1f)
        val t = Ease.inOutCubic(q)
        var dYaw = slideToYaw - slideFromYaw
        while (dYaw > PI.toFloat()) dYaw -= 2f * PI.toFloat()
        while (dYaw < -PI.toFloat()) dYaw += 2f * PI.toFloat()
        anchorPos = slideFromPos.lerp(slideToPos, t)
        restYaw = slideFromYaw + dYaw * t
        applied = slideFromApplied.lerp(slideToApplied, t)
        write()
        if (q >= 1f) slideStartMs = NEVER
    }

    private fun write() {
        val at = anchorPos + applied
        pose = FollowPose(at, headAt?.let { facingYaw(at, it, restYaw) } ?: restYaw)
    }

    companion object {
        /** Where [offset] (head frame) lands for a head at [head] turned to [yaw]: level, whatever the pitch. */
        fun designed(head: Vec3, yaw: Float, offset: Vec3): Vec3 = head + Quat4.axisAngle(Vec3.UP, yaw).rotate(offset)

        /** The yaw that turns a panel at [panel] (front = its +Z) to the head at [head]; [fallback] right above it. */
        fun facingYaw(panel: Vec3, head: Vec3, fallback: Float): Float {
            val to = head - panel
            if (to.x * to.x + to.z * to.z < 1e-6f) return fallback
            return atan2(to.x, to.z)
        }

        /**
         * Degrees between [to] and the view direction [forward], both flattened to the floor plane (the
         * heading [fallbackYaw] stands in when looking straight up or down).
         */
        fun levelAngleDeg(to: Vec3, forward: Vec3, fallbackYaw: Float): Float {
            val flatTo = Vec3(to.x, 0f, to.z)
            if (flatTo.length < 1e-3f) return 0f
            val yaw = FacePanelUseCase.yawOf(forward, fallbackYaw)
            val ahead = Quat4.axisAngle(Vec3.UP, yaw).rotate(Vec3.AHEAD)
            return Math.toDegrees(acos(flatTo.normalized().dot(ahead).coerceIn(-1f, 1f)).toDouble()).toFloat()
        }

        private const val NEVER = Long.MIN_VALUE
        private const val WINDOW_DEG = 45f
        private const val OUT_MS = 500L
        private const val SLIDE_MS = 350f
        private const val DEAD_BAND_M = 0.01f

        /** 18 degrees left and 24 degrees down, 0.6 m from the head (design `panels.home.placement`). */
        val MAIN_OFFSET = Vec3(-0.1694f, -0.2440f, -0.5213f)

        /** The main panel leans back 12 degrees. */
        val MAIN_LEAN: Quat4 = Quat4.axisAngle(Vec3.RIGHT, -0.20944f)

        /** 20 degrees right and 28 degrees down, 0.45 m from the head (design `entities3d.extinguisher`). */
        val PROP_OFFSET = Vec3(0.1359f, -0.2113f, -0.3734f)
    }
}
