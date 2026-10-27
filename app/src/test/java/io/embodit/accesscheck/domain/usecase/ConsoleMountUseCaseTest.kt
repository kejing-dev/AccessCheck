package io.embodit.accesscheck.domain.usecase

import io.embodit.accesscheck.domain.model.AccessRules
import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.ChairPose
import io.embodit.accesscheck.domain.model.V2
import io.embodit.accesscheck.domain.model.Vec3
import io.embodit.accesscheck.domain.model.segmentEntersObb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design v2 console placement (QA q1-1): beside the push handle, outside the footprint, its sight line
 * clear of the chair; on the head follow it rises above the chair's box when the chair would hide it.
 * A person pushing at the origin along -Z, eyes 1.6 m up (and 1.2 m, the emulator's head height).
 */
class ConsoleMountUseCaseTest {
    private val rule = ConsoleMountUseCase()
    private val model = ChairModel.MANUAL
    private val hw = 0.12f
    private val hh = 0.149f
    private val pose = ChairPose(V2(0f, -(AccessRules.HANDLE_M + model.lengthM / 2f)), 0f)

    private fun sightClear(head: Vec3, panel: Vec3): Boolean {
        val box = pose.footprint(model).grown(ConsoleMountUseCase.BOX_GROW_M)
        val top = model.handleY + ConsoleMountUseCase.BOX_ABOVE_HANDLE_M
        val tIn = segmentEntersObb(V2.of(head), V2.of(panel), box) ?: return true
        val tOut = 1f - (segmentEntersObb(V2.of(panel), V2.of(head), box) ?: return true)
        return minOf(head.y + (panel.y - head.y) * tIn, head.y + (panel.y - head.y) * tOut) >= top
    }

    @Test
    fun mountedBesideTheLeftHandleOutsideTheFootprint() {
        val head = Vec3(0f, 1.6f, 0f)
        val (c, _) = rule.mount(pose, model, 0f, head, hw, hh, right = false)
        // left of the chair (-X when heading -Z), inner edge 8 cm off the footprint
        assertEquals(-(model.widthM / 2f + 0.08f + hw), c.x, 1e-4f)
        // level with the handle along the chair
        assertEquals(-AccessRules.HANDLE_M, c.z, 1e-4f)
        // top edge at handle + 0.40 = 1.32 m (inside 1.05 .. 1.40)
        assertEquals(model.handleY + 0.40f, c.y + hh, 1e-4f)
        assertFalse(pose.footprint(model).contains(V2.of(c), hw - 1e-3f))
        assertTrue(sightClear(head, c))
        val (r, _) = rule.mount(pose, model, 0f, head, hw, hh, right = true)
        assertEquals(-c.x, r.x, 1e-4f)
    }

    @Test
    fun mountedConsoleStaysUnderTheEyeForALowHead() {
        val head = Vec3(0f, 1.2f, 0f)
        val (c, _) = rule.mount(pose, model, 0f, head, hw, hh, right = false)
        assertEquals(head.y - 0.20f, c.y + hh, 1e-4f)
        assertTrue(sightClear(head, c))
    }

    @Test
    fun mountedConsoleFacesTheHead() {
        val head = Vec3(0f, 1.6f, 0f)
        val (c, yaw) = rule.mount(pose, model, 0f, head, hw, hh, right = false)
        // the panel's +Z (yaw about the vertical) points from the panel to the head
        val normal = V2(kotlin.math.sin(yaw), kotlin.math.cos(yaw))
        val to = V2.of(head - c).normalized()
        assertEquals(1f, normal.dot(to), 1e-4f)
    }

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
}
