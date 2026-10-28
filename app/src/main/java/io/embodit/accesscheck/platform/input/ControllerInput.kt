package io.embodit.accesscheck.platform.input

import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerPose
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.accesscheck.domain.model.PointerRay
import io.embodit.accesscheck.domain.model.Quat4
import io.embodit.accesscheck.domain.model.Vec3
import io.embodit.accesscheck.platform.AcLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What the controller delivers in one frame, Stage space. */
data class ControllerSample(
    val ray: PointerRay,
    /** Trigger 0..1 and the device's own pressed flag. */
    val trigger: Float,
    val pressed: Boolean,
    /** Thumbstick x (-1 left .. 1 right) of the aiming controller. */
    val stickX: Float,
    val right: Boolean,
    /** Grip 0..1 and its pressed flag (v0.1.2: trigger or grip takes hold of the chair). */
    val grip: Float = 0f,
    val gripPressed: Boolean = false,
)

/**
 * Controller ray and buttons: pose from `ControllerTrackingProvider.latestData` (read on the main
 * thread), trigger and thumbstick from `ControllerActionListener`. That listener runs on the data
 * source's thread: it only copies the values into atomics, which the frame system reads.
 *
 * The ray leaves the controller pose along its -Z. With two controllers the one whose trigger was
 * touched last aims (the right one at first). Same approach as the Spatial Ruler's
 * `ControllerPointerSource`; the thumbstick is added for the factory's default interaction rule.
 */
class ControllerInput {
    private var provider: ControllerTrackingProvider? = null
    private val rightValue = AtomicInteger(0)
    private val leftValue = AtomicInteger(0)
    private val rightPressed = AtomicBoolean(false)
    private val leftPressed = AtomicBoolean(false)
    private val rightStick = AtomicInteger(0)
    private val leftStick = AtomicInteger(0)
    private val rightGrip = AtomicInteger(0)
    private val leftGrip = AtomicInteger(0)
    private val rightGripPressed = AtomicBoolean(false)
    private val leftGripPressed = AtomicBoolean(false)
    private var useRight = true
    private var lastSupport: DataProvider.SupportState? = null

    private val listener =
        ControllerTrackingProvider.ControllerActionListener { action: ControllerActionData ->
            rightValue.set(java.lang.Float.floatToIntBits(action.right.triggerValue))
            rightPressed.set(action.right.triggerPressed)
            leftValue.set(java.lang.Float.floatToIntBits(action.left.triggerValue))
            leftPressed.set(action.left.triggerPressed)
            rightStick.set(java.lang.Float.floatToIntBits(runCatching { action.right.thumbstickValue.x }.getOrDefault(0f)))
            leftStick.set(java.lang.Float.floatToIntBits(runCatching { action.left.thumbstickValue.x }.getOrDefault(0f)))
            rightGrip.set(java.lang.Float.floatToIntBits(action.right.gripValue))
            rightGripPressed.set(action.right.gripPressed)
            leftGrip.set(java.lang.Float.floatToIntBits(action.left.gripValue))
            leftGripPressed.set(action.left.gripPressed)
        }

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(listener) }
        val result = runCatching { p.start() }
        lastSupport = runCatching { p.supportState }.getOrNull()
        AcLog.i("TRACK controller start=${result.getOrNull() ?: result.exceptionOrNull()} support=$lastSupport")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(listener) }
            runCatching { p.stop() }
        }
        provider = null
    }

    /** Once per frame on the main thread. Null when no controller delivers a pose. */
    fun sample(): ControllerSample? {
        val p = provider ?: return null
        val support = runCatching { p.supportState }.getOrNull()
        if (support != lastSupport) {
            lastSupport = support
            AcLog.i("TRACK controller support=$support")
        }
        if (support != DataProvider.SupportState.SUPPORTED) return null
        val data = runCatching { p.latestData }.getOrNull() ?: return null
        val rightV = java.lang.Float.intBitsToFloat(rightValue.get())
        val leftV = java.lang.Float.intBitsToFloat(leftValue.get())
        val rightDown = rightPressed.get()
        val leftDown = leftPressed.get()
        if (useRight && (leftV > SWITCH_AT || leftDown) && rightV < REST_BELOW && !rightDown) useRight = false
        if (!useRight && (rightV > SWITCH_AT || rightDown) && leftV < REST_BELOW && !leftDown) useRight = true
        val pickRight = if (useRight) data.right != null else data.left == null
        return build(if (pickRight) data.right else data.left, pickRight)
    }

    /** Both controllers with their own buttons (v0.1.2: either or both may hold the push handle). */
    fun both(): List<ControllerSample> {
        val p = provider ?: return emptyList()
        if (runCatching { p.supportState }.getOrNull() != DataProvider.SupportState.SUPPORTED) return emptyList()
        val data = runCatching { p.latestData }.getOrNull() ?: return emptyList()
        return listOfNotNull(build(data.left, false), build(data.right, true))
    }

    private fun build(pose: ControllerPose?, right: Boolean): ControllerSample? {
        pose ?: return null
        val position = Vec3(pose.position.x, pose.position.y, pose.position.z)
        val rotation = Quat4(pose.rotation.x, pose.rotation.y, pose.rotation.z, pose.rotation.w)
        if (!position.isFinite()) return null
        val forward = rotation.rotate(Vec3.AHEAD)
        if (!forward.isFinite() || forward.length < 0.5f) return null
        fun f(a: AtomicInteger) = java.lang.Float.intBitsToFloat(a.get())
        return ControllerSample(
            ray = PointerRay(position, forward.normalized()),
            trigger = f(if (right) rightValue else leftValue),
            pressed = (if (right) rightPressed else leftPressed).get(),
            stickX = f(if (right) rightStick else leftStick),
            right = right,
            grip = f(if (right) rightGrip else leftGrip),
            gripPressed = (if (right) rightGripPressed else leftGripPressed).get(),
        )
    }

    companion object {
        private const val SWITCH_AT = 0.15f
        private const val REST_BELOW = 0.05f
    }
}
