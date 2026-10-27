package io.embodit.accesscheck.domain.usecase

import io.embodit.accesscheck.domain.model.AccessRules
import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.ChairPose
import io.embodit.accesscheck.domain.model.PointerRay
import io.embodit.accesscheck.domain.model.Quat4
import io.embodit.accesscheck.domain.model.V2
import io.embodit.accesscheck.domain.model.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Placing and dragging with the pointer (v0.1.1, taste profile D1 / D14): the ray's floor point, the last
 * good point while the ray misses, the clearance push, facing away, the drag offset, and the ray's hits on
 * the chair and on panels. A person at the origin, eyes 1.6 m up, floor at 0, looking along -Z.
 */
class FloorAimUseCaseTest {
    private val rule = FloorAimUseCase()
    private val eps = 1e-3f
    private val head = V2.ZERO
    private val ahead = V2(0f, -1f)

    private fun ray(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float) = PointerRay(Vec3(ox, oy, oz), Vec3(dx, dy, dz).normalized())

    @Test
    fun rayMeetsTheFloor() {
        val p = rule.floorPoint(ray(0.2f, 1.2f, 0f, 0f, -1f, -1f), 0f)
        assertNotNull(p)
        assertEquals(0.2f, p!!.x, eps)
        assertEquals(-1.2f, p.z, eps)
        // a raised floor plane counts from its own height
        val q = rule.floorPoint(ray(0f, 1.2f, 0f, 0f, -1f, -1f), 0.2f)!!
        assertEquals(-1.0f, q.z, eps)
    }

    @Test
    fun rayAimedLevelOrUpOrTooFarMisses() {
        assertNull(rule.floorPoint(ray(0f, 1.2f, 0f, 0f, 0f, -1f), 0f))
        assertNull(rule.floorPoint(ray(0f, 1.2f, 0f, 0f, 0.5f, -1f), 0f))
        // 1.2 m drop at a tenth of a slope: 12 m away, past the 6 m reach
        assertNull(rule.floorPoint(ray(0f, 1.2f, 0f, 0f, -0.1f, -1f), 0f))
        // 5 m: still in reach
        assertNotNull(rule.floorPoint(ray(0f, 1.0f, 0f, 0f, -0.2f, -1f), 0f))
    }

    @Test
    fun trackerKeepsTheLastFloorPointWhileTheRayPointsUp() {
        val t = FloorAimUseCase.Tracker(rule)
        val a = t.update(ray(0f, 1.2f, 0f, 0f, -1f, -1f), 0f)
        assertTrue(a.onFloor)
        assertEquals(-1.2f, a.point!!.z, eps)
        val b = t.update(ray(0f, 1.2f, 0f, 0f, 1f, -1f), 0f)
        assertFalse(b.onFloor)
        assertEquals(-1.2f, b.point!!.z, eps)
        val c = t.update(ray(0f, 1.2f, 0f, 0.3f, -1f, -1f), 0f)
        assertTrue(c.onFloor)
        assertTrue(c.point!!.x > 0.3f)
    }

    @Test
    fun trackerWithoutAnyFloorPointYetHasNone() {
        val t = FloorAimUseCase.Tracker(rule)
        val a = t.update(ray(0f, 1.2f, 0f, 0f, 1f, -1f), 0f)
        assertFalse(a.onFloor)
        assertNull(a.point)
    }

    @Test
    fun pointInsideTheClearanceIsPushedOut() {
        val c = rule.clear(V2(0f, -0.3f), head, ahead)
        assertEquals(0f, c.x, eps)
        assertEquals(-AccessRules.CLEARANCE_M, c.z, eps)
        // sideways stays sideways
        val s = rule.clear(V2(0.4f, 0f), head, ahead)
        assertEquals(AccessRules.CLEARANCE_M, s.x, eps)
        assertEquals(0f, s.z, eps)
        // right under the head: straight ahead
        val u = rule.clear(V2(0f, 0f), head, ahead)
        assertEquals(-AccessRules.CLEARANCE_M, u.z, eps)
        // outside: unchanged
        val o = rule.clear(V2(0.5f, -1.5f), head, ahead)
        assertEquals(0.5f, o.x, eps)
        assertEquals(-1.5f, o.z, eps)
    }

    @Test
    fun ghostFacesAwayFromThePersonAndAddsTheStickTurn() {
        val g = rule.ghost(V2(0f, -2f), head, ahead, 0f)
        assertEquals(0f, g.heading.x, eps)
        assertEquals(-1f, g.heading.z, eps)
        val side = rule.ghost(V2(2f, 0f), head, ahead, 0f)
        assertEquals(1f, side.heading.x, eps)
        assertEquals(0f, side.heading.z, eps)
        val turned = rule.ghost(V2(0f, -2f), head, ahead, 0.5f)
        assertEquals(g.theta + 0.5f, turned.theta, eps)
        // a ghost pointed onto the person's feet lands at the clearance edge, still facing away
        val feet = rule.ghost(V2(0f, -0.1f), head, ahead, 0f)
        assertEquals(-AccessRules.CLEARANCE_M, feet.center.z, eps)
        assertEquals(-1f, feet.heading.z, eps)
    }

    @Test
    fun dragKeepsTheGrabOffsetAndTheClearance() {
        val chair = V2(0.5f, -2f)
        val grab = V2(0.3f, -1.8f)
        val offset = chair - grab
        val moved = rule.dragged(V2(1.3f, -2.8f), offset, head, ahead)
        assertEquals(1.5f, moved.x, eps)
        assertEquals(-3.0f, moved.z, eps)
        // dragged onto the person: pushed back out to the clearance edge
        val onMe = rule.dragged(V2(-0.2f, 0.2f), offset, head, ahead)
        assertEquals(AccessRules.CLEARANCE_M, onMe.length, eps)
    }

    @Test
    fun rayHitsTheChairBoxOnlyWhenAimedAtIt() {
        val model = ChairModel.MANUAL
        val pose = ChairPose(V2(0f, -2f), 0.7f)
        val eye = Vec3(0.2f, 1.2f, 0f)
        val atSeat = Vec3(0f, 0.5f, -2f)
        assertNotNull(rule.hitsChair(PointerRay(eye, (atSeat - eye).normalized()), pose, model, 0f))
        val beside = Vec3(1.2f, 0.5f, -2f)
        assertNull(rule.hitsChair(PointerRay(eye, (beside - eye).normalized()), pose, model, 0f))
        // the chair stands on the floor at its height: a raised floor lifts the box
        assertNull(rule.hitsChair(PointerRay(eye, (atSeat - eye).normalized()), pose, model, 2.0f))
    }

    @Test
    fun rayHitsAPanelInsideItsRectangleOnly() {
        val center = Vec3(0f, 1.2f, -0.6f)
        val turn = Quat4.axisAngle(Vec3.UP, 0.3f)
        val eye = Vec3(0f, 1.6f, 0f)
        val t = rule.hitsPanel(PointerRay(eye, (center - eye).normalized()), center, turn, 0.12f, 0.15f)
        assertNotNull(t)
        assertEquals((center - eye).length, t!!, 1e-2f)
        assertNull(rule.hitsPanel(PointerRay(eye, (center + Vec3(0.4f, 0f, 0f) - eye).normalized()), center, turn, 0.12f, 0.15f))
        // behind the ray's origin: no hit
        assertNull(rule.hitsPanel(PointerRay(eye, Vec3(0f, 0f, 1f)), center, turn, 0.12f, 0.15f))
    }
}
