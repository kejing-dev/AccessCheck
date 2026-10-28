package io.embodit.accesscheck.domain.usecase

import io.embodit.accesscheck.domain.model.AccessRules
import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.ChairPose
import io.embodit.accesscheck.domain.model.V2
import io.embodit.accesscheck.domain.model.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The head-following console rises above the chair's box when the chair would hide it (QA q1-1).
 * A person pushing at the origin along -Z, eyes 1.6 m up (and 1.2 m, the emulator's head height).
 */
class ConsoleLiftUseCaseTest {
    private val rule = ConsoleLiftUseCase()
    private val model = ChairModel.MANUAL
    private val hh = 0.149f
    private val pose = ChairPose(V2(0f, -(AccessRules.HANDLE_M + model.lengthM / 2f)), 0f)

    @Test
    fun followConsoleBehindTheChairRises() {
        // v0.1.0 pushing pose with a 1.2 m head (the emulator): 32 degrees down, 0.6 m, 18 degrees left
        val head = Vec3(0f, 1.2f, 0f)
        val panel = Vec3(-0.157f, 1.2f - 0.318f, -0.484f)
        val y = rule.liftedY(panel, hh, pose, model, 0f, head)
        assertTrue(y > panel.y)
        assertEquals(model.handleY + 0.10f + 0.15f + hh, y, 1e-4f)
    }

    @Test
    fun followConsoleClearOfTheChairStays() {
        val head = Vec3(0f, 1.6f, 0f)
        val panel = Vec3(-0.17f, 1.6f - 0.205f, -0.53f)
        assertEquals(panel.y, rule.liftedY(panel, hh, pose, model, 0f, head), 1e-6f)
        // no chair nearby at all
        val far = ChairPose(V2(3f, -3f), 0f)
        assertEquals(0.5f, rule.liftedY(Vec3(0f, 0.5f, -0.6f), hh, far, model, 0f, head), 1e-6f)
    }

    @Test
    fun theLiftStopsUnderTheEyeAndAShortSlideClearsTheRest() {
        // v0.1.2: the lift stops with the top 8 cm under the eye
        val low = Vec3(0f, 1.2f, 0f)
        val cap = rule.maxCenterY(low, hh)
        assertEquals(1.2f - 0.08f - hh, cap, 1e-6f)
        val hidden = Vec3(-0.157f, 1.2f - 0.318f, -0.484f)
        assertEquals(cap, minOf(rule.liftedY(hidden, hh, pose, model, 0f, low), maxOf(hidden.y, cap)), 1e-6f)
        val left = Vec3(-1f, 0f, 0f)
        // that low eye right behind the handle: 0.3 m of slide is not enough (the scene then takes the chair's taps away)
        assertEquals(ConsoleLiftUseCase.MAX_SHIFT_M, rule.sideShift(Vec3(hidden.x, cap, hidden.z), hh, left, pose, model, 0f, low), 0f)
        // standing (1.6 m), the console just inside the chair's left edge: a short slide clears it
        val head = Vec3(0f, 1.6f, 0f)
        val panel = Vec3(-0.30f, 1.0f, -0.6f)
        assertTrue(rule.liftedY(panel, hh, pose, model, 0f, head) > panel.y)
        val d = rule.sideShift(panel, hh, left, pose, model, 0f, head)
        assertTrue("shift=$d", d > 0f && d < ConsoleLiftUseCase.MAX_SHIFT_M)
        val moved = panel + left * d
        assertEquals(moved.y, rule.liftedY(moved, hh, pose, model, 0f, head), 1e-6f)
        // a console already clear does not move
        assertEquals(0f, rule.sideShift(Vec3(-0.6f, 1.4f, -0.3f), hh, left, pose, model, 0f, head), 0f)
    }
}
