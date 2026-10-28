package io.embodit.accesscheck.domain.usecase

import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.ChairPose
import io.embodit.accesscheck.domain.model.V2
import io.embodit.accesscheck.domain.model.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Taking hold of the chair (v0.1.2): trigger / grip held and let go, a hand closing (fist or pinch) and
 * opening, and "at the handle". A manual chair 1.2 m ahead of a person at the origin, facing -Z.
 */
class GripUseCaseTest {
    private val rule = GripUseCase()
    private val model = ChairModel.MANUAL
    private val pose = ChairPose(V2(0f, -(0.35f + model.lengthM / 2f)), 0f)

    @Test
    fun triggerOrGripHoldsUntilLetGo() {
        // pressing either one takes hold
        assertTrue(rule.controllerHeld(0.7f, false, 0f, false, was = false))
        assertTrue(rule.controllerHeld(0f, false, 0.7f, false, was = false))
        assertTrue(rule.controllerHeld(0f, false, 0f, true, was = false))
        // half way is not a press yet
        assertFalse(rule.controllerHeld(0.5f, false, 0.5f, false, was = false))
        // while held, easing off to 0.5 still holds (no flicker); below 0.4 lets go
        assertTrue(rule.controllerHeld(0.5f, false, 0f, false, was = true))
        assertFalse(rule.controllerHeld(0.3f, false, 0.2f, false, was = true))
        // the device's pressed flag holds whatever the analog value says
        assertTrue(rule.controllerHeld(0f, true, 0f, false, was = true))
    }

    @Test
    fun aClosedHandHoldsAndAnOpenOneLetsGo() {
        val palm = Vec3(0f, 0.9f, -0.4f)
        fun tips(d: Float) = listOf(palm + Vec3(0f, 0f, -d), palm + Vec3(0.01f, 0f, -d), palm + Vec3(-0.01f, 0f, -d))
        // an open hand (tips 9 cm out) does not hold; a fist (4 cm) does
        assertFalse(rule.fist(palm, tips(0.09f), was = false))
        assertTrue(rule.fist(palm, tips(0.04f), was = false))
        // relaxing to 7 cm keeps the hold, opening to 9 cm lets go
        assertTrue(rule.fist(palm, tips(0.07f), was = true))
        assertFalse(rule.fist(palm, tips(0.09f), was = true))
        // 7 cm from open is not a fist yet
        assertFalse(rule.fist(palm, tips(0.07f), was = false))
        // no tips tracked: no hold
        assertFalse(rule.fist(palm, emptyList(), was = true))
        // a pinch: 0.8 on, held down to 0.5
        assertTrue(rule.pinch(0.85f, was = false))
        assertFalse(rule.pinch(0.6f, was = false))
        assertTrue(rule.pinch(0.6f, was = true))
        assertFalse(rule.pinch(0.4f, was = true))
    }

    @Test
    fun onlyAHandAtTheHandleTakesHold() {
        val (l, r) = rule.grips(pose, model, 0f)
        // the grips: across the back of the chair at handle height, left to the left (-X when facing -Z)
        assertEquals(-model.widthM / 2f, l.x, 1e-4f)
        assertEquals(model.widthM / 2f, r.x, 1e-4f)
        assertEquals(-0.35f, l.z, 1e-4f)
        assertEquals(model.handleY, l.y, 1e-4f)
        // on the bar, a little behind a grip, 20 cm behind its middle: near
        assertTrue(rule.nearHandle(Vec3(0.1f, model.handleY, -0.35f), pose, model, 0f))
        assertTrue(rule.nearHandle(r + Vec3(0f, -0.05f, 0.08f), pose, model, 0f))
        assertTrue(rule.nearHandle(Vec3(0f, model.handleY, -0.15f), pose, model, 0f))
        // a hand at the person's side, at the seat or far in front: not near
        assertFalse(rule.nearHandle(Vec3(0.3f, 1.0f, 0.1f), pose, model, 0f))
        assertFalse(rule.nearHandle(Vec3(0f, 0.5f, -0.9f), pose, model, 0f))
        assertFalse(rule.nearHandle(Vec3(0f, model.handleY, -1.6f), pose, model, 0f))
        // the floor height counts
        assertTrue(rule.nearHandle(Vec3(0f, model.handleY + 0.3f, -0.35f), pose, model, 0.3f))
    }
}
