package io.embodit.accesscheck.ui.access.scene

import io.embodit.accesscheck.domain.model.Quat4
import io.embodit.accesscheck.domain.model.Vec3
import io.embodit.accesscheck.domain.usecase.FacePanelUseCase
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The console's lazy follow takes only the head's heading (v0.1.1, UI sweep access-check #1 / #2):
 * looking down at a threshold or up at a door top must not move the designed pose nor trigger a slide.
 */
class FollowerTest {
    private val eye = Vec3(0.3f, 1.6f, -0.2f)
    private val offset = AccessScene.CONSOLE_OFFSET

    /** A head turned [yaw] and pitched [pitch] (radians, positive = up). */
    private fun forward(yaw: Float, pitch: Float): Vec3 = (Quat4.axisAngle(Vec3.UP, yaw) * Quat4.axisAngle(Vec3.RIGHT, pitch)).rotate(Vec3.AHEAD)

    @Test
    fun designedPoseIgnoresPitch() {
        val yaw = 0.8f
        val level = Follower.designed(eye, FacePanelUseCase.yawOf(forward(yaw, 0f), 0f), offset)
        for (pitch in floatArrayOf(-1.22f, -0.6f, 0.5f, 1.0f)) {
            val p = Follower.designed(eye, FacePanelUseCase.yawOf(forward(yaw, pitch), 0f), offset)
            assertEquals(level.x, p.x, 1e-4f)
            assertEquals(level.y, p.y, 1e-4f)
            assertEquals(level.z, p.z, 1e-4f)
        }
    }

    @Test
    fun consoleSitsTenToTwentyDegreesUnderTheViewLine() {
        val p = Follower.designed(eye, 0f, offset) - eye
        val down = Math.toDegrees(atan2(-p.y, hypot(p.x, p.z)).toDouble())
        assertTrue("down=$down", down in 10.0..20.05)
        val push = Follower.designed(eye, 0f, AccessScene.CONSOLE_OFFSET_PUSHING) - eye
        val pushDown = Math.toDegrees(atan2(-push.y, hypot(push.x, push.z)).toDouble())
        assertTrue("pushDown=$pushDown", pushDown in 10.0..20.05)
    }

    @Test
    fun designedPoseTurnsWithTheHeading() {
        // turned 180 degrees the console is on the other side of the head, at the same height
        val a = Follower.designed(eye, 0f, offset) - eye
        val b = Follower.designed(eye, Math.PI.toFloat(), offset) - eye
        assertEquals(-a.x, b.x, 1e-4f)
        assertEquals(a.y, b.y, 1e-4f)
        assertEquals(-a.z, b.z, 1e-4f)
    }

    @Test
    fun lookingDownOrUpIsNotLookingAway() {
        val to = Follower.designed(eye, 0f, offset) - eye
        val level = Follower.levelAngleDeg(to, forward(0f, 0f), 0f)
        assertEquals(level, Follower.levelAngleDeg(to, forward(0f, -1.22f), 0f), 1e-3f)
        assertEquals(level, Follower.levelAngleDeg(to, forward(0f, 1.0f), 0f), 1e-3f)
        assertTrue(level < 45f)
        // turning away 90 degrees is
        assertTrue(Follower.levelAngleDeg(to, forward(-1.5708f, 0f), 0f) > 45f)
    }
}
