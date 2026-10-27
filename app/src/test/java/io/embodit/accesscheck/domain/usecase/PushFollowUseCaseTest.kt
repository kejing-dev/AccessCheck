package io.embodit.accesscheck.domain.usecase

import io.embodit.accesscheck.domain.model.AccessRules
import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.ChairPose
import io.embodit.accesscheck.domain.model.V2
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The push-follow rule (design `rules.push`, review R2-1): expectations are written from the formula.
 * Paths: forward, backward, sidestep, head turn only, an arc.
 */
class PushFollowUseCaseTest {
    private val rule = PushFollowUseCase()
    private val chair = ChairModel.MANUAL
    private val dt = 1f / 60f

    /** Chair ahead of a person at the origin, both facing -Z; the handle 0.35 m ahead of the person. */
    private fun start(): PushFollowUseCase.State {
        val pose = ChairPose(V2(0f, -(AccessRules.HANDLE_M + chair.lengthM / 2f)), 0f)
        return rule.start(V2.ZERO, pose, chair)
    }

    private fun walk(s0: PushFollowUseCase.State, path: (Float) -> V2, seconds: Float): PushFollowUseCase.State {
        var s = s0
        var t = 0f
        while (t < seconds) {
            t += dt
            s = rule.step(s, path(t), dt, chair)
        }
        return s
    }

    @Test
    fun startLocksTheHandleDistance() {
        val s = start()
        assertEquals(AccessRules.HANDLE_M, s.d, 1e-4f)
        val far = rule.start(V2(0f, 1.3f), ChairPose(V2(0f, -0.55f), 0f), chair)
        assertEquals(1.0f, far.d, 1e-4f)
        val mid = rule.start(V2(0f, 0.9f), ChairPose(V2(0f, -0.55f), 0f), chair)
        assertEquals(0.9f, mid.d, 1e-4f)
    }

    @Test
    fun walkingForwardKeepsTheHeadingAndMovesTheChairAhead() {
        val s = walk(start(), { t -> V2(0f, -0.8f * t) }, 2f)
        assertEquals(0f, s.theta, 1e-3f)
        // the filtered head lags the raw one a little; the chair stays handle + half length ahead of it
        assertEquals(s.h.z - AccessRules.HANDLE_M - chair.lengthM / 2f, s.pose.center.z, 1e-3f)
        assertTrue("moved forward", s.pose.center.z < -1.6f)
    }

    @Test
    fun backingUpReversesStraight() {
        val s = walk(start(), { t -> V2(0f, 0.6f * t) }, 2f)
        assertEquals(0f, s.theta, 1e-4f)
        assertEquals(0f, s.pose.center.x, 1e-4f)
        assertTrue(s.pose.center.z > -0.4f)
    }

    @Test
    fun headTurnOnlyMovesNothing() {
        val s0 = start()
        // the head sways 1.2 cm about its place for a second (at most 2.4 cm from where it started): inside the dead zone
        val s = walk(s0, { t -> V2(0.012f * sin(t * 6f), 0.012f * cos(t * 6f) - 0.012f) }, 1f)
        assertEquals(s0.pose.center.x, s.pose.center.x, 1e-3f)
        assertEquals(s0.pose.center.z, s.pose.center.z, 1e-3f)
        assertEquals(s0.theta, s.theta, 1e-5f)
    }

    @Test
    fun sidestepIsAnArcBoundedByTheTurnRate() {
        // walking diagonally: 0.5 m/s ahead and 0.5 m/s to the right
        val s = walk(start(), { t -> V2(0.5f * t, -0.5f * t) }, 2f)
        assertTrue("turned towards the right", s.theta < -0.3f)
        // never more than k rad per metre of forward travel: in 2 s about 1 m forward -> |theta| <= 0.9 rad plus the lag
        assertTrue(abs(s.theta) <= 0.9f + 0.05f)
    }

    @Test
    fun arcPathBringsTheHeadingAround() {
        // quarter circle of radius 2 m to the left, 4 s
        val r = 2f
        val s = walk(start(), { t ->
            val a = (t / 4f) * (Math.PI / 2).toFloat()
            V2(-r + r * cos(a), -r * sin(a))
        }, 4f)
        val headingDeg = Math.toDegrees(s.theta.toDouble())
        assertTrue("turned left by tens of degrees: $headingDeg", headingDeg > 35.0 && headingDeg < 100.0)
        // the handle stays 0.35 m from the filtered head point
        val handle = s.pose.handle(chair)
        assertEquals(AccessRules.HANDLE_M, handle.distanceTo(s.h), 1e-3f)
    }

    @Test
    fun behindSectorIsSixtyDegreesAndWithinRange() {
        val pose = ChairPose(V2(0f, -1f), 0f)
        val handle = pose.handle(chair)
        assertTrue(rule.isBehind(handle + V2(0f, 0.5f), pose, chair))
        assertTrue(rule.isBehind(handle + V2(0.5f * sin(0.9f), 0.5f * cos(0.9f)), pose, chair))
        assertTrue(!rule.isBehind(handle + V2(0.5f * sin(1.2f), 0.5f * cos(1.2f)), pose, chair))
        assertTrue(!rule.isBehind(handle + V2(0f, 1.2f), pose, chair))
        assertTrue(!rule.isBehind(handle + V2(0f, 0.1f), pose, chair))
        assertTrue(!rule.isBehind(handle + V2(0f, -0.5f), pose, chair))
        @Suppress("UNUSED_VARIABLE") val unused = atan2(1f, 1f)
    }
}
