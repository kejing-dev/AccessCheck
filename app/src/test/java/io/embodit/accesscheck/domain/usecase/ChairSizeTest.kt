package io.embodit.accesscheck.domain.usecase

import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.ChairPose
import io.embodit.accesscheck.domain.model.ChairPreset
import io.embodit.accesscheck.domain.model.DoorEntry
import io.embodit.accesscheck.domain.model.DoorInfo
import io.embodit.accesscheck.domain.model.Obb2
import io.embodit.accesscheck.domain.model.ThresholdPick
import io.embodit.accesscheck.domain.model.V2
import io.embodit.accesscheck.domain.model.Verdict
import io.embodit.accesscheck.domain.model.obbIntersects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adjustable wheelchair (v0.1.2, "the wheelchair's size should be adjustable in the settings"): presets,
 * the ranges, and that a new size changes what the checks say (doors, bumps, the handle).
 */
class ChairSizeTest {
    private val doors = DoorUseCase()

    private fun door(rawCm: Int, measuredCm: Int?) =
        DoorEntry(DoorInfo("d", 1, V2(0f, -2f), V2(1f, 0f), V2(0f, 1f), rawCm / 100f, 2.0f, 0f), measuredCm, ThresholdPick.NONE)

    @Test
    fun presetsKeepTheirSizesAndSizesStayInRange() {
        assertEquals(68, ChairModel.MANUAL.widthCm)
        assertEquals(110, ChairModel.MANUAL.lengthCm)
        assertEquals(0.92f, ChairModel.MANUAL.handleY, 1e-6f)
        val child = ChairModel.of(ChairPreset.CHILD)
        assertEquals(56, child.widthCm)
        assertEquals(90, child.lengthCm)
        assertFalse(child.resized)
        // each preset sits inside the slider ranges
        for (p in ChairPreset.entries) {
            val c = ChairModel.of(p)
            assertTrue(c.widthCm in ChairModel.WIDTH_CM && c.lengthCm in ChairModel.LENGTH_CM && c.heightCm in ChairModel.HEIGHT_CM)
        }
        // a size is clamped to 55 - 80 / 90 - 130 / 80 - 110 cm and keeps the preset's look
        val big = ChairModel.MANUAL.sized(widthCm = 95, lengthCm = 60, heightCm = 150)
        assertEquals(80, big.widthCm)
        assertEquals(90, big.lengthCm)
        assertEquals(110, big.heightCm)
        assertEquals(ChairPreset.MANUAL, big.preset)
        assertTrue(big.resized)
        // the saved tag still finds the preset
        assertEquals(ChairPreset.POWER, ChairModel.fromTag("power").preset)
        assertEquals(ChairPreset.MANUAL, ChairModel.fromTag("nonsense").preset)
    }

    @Test
    fun aWiderChairFailsADoorTheStandardOnePasses() {
        val manual = ChairModel.MANUAL
        val wide = manual.sized(widthCm = 78)
        val child = ChairModel.of(ChairPreset.CHILD)
        // measured 84 cm: passes the 68 cm chair; the 78 cm chair needs 83 cm, so it passes too
        assertEquals(Verdict.PASS, doors.verdict(door(89, 84), manual).width)
        assertEquals(Verdict.PASS, doors.verdict(door(89, 84), wide).width)
        // measured 82 cm: past the ADA 81.5 cm, but under 78 + 5 cm for the wide chair
        assertEquals(Verdict.PASS, doors.verdict(door(87, 82), manual).width)
        assertEquals(Verdict.FAIL, doors.verdict(door(87, 82), wide).width)
        // measured 75 cm: tight for the 68 cm chair, failing for the 78 cm one, tight for the child chair (needs 61)
        assertEquals(Verdict.TIGHT, doors.verdict(door(80, 75), manual).width)
        assertEquals(Verdict.FAIL, doors.verdict(door(80, 75), wide).width)
        assertEquals(Verdict.TIGHT, doors.verdict(door(80, 75), child).width)
        // not measured (plane estimate 76 cm): "measure" for 68 cm, "blocked" once the chair is set to 75 cm
        assertEquals(Verdict.TO_MEASURE, doors.verdict(door(76, null), manual).width)
        assertEquals(Verdict.FAIL, doors.verdict(door(76, null), manual.sized(widthCm = 75)).width)
    }

    @Test
    fun theFootprintAndTheHandleFollowTheSize() {
        val pose = ChairPose(V2(0f, -2f), 0f)
        // a wall 38 cm to the right of the chair's centre line: clear for 68 cm (half 34), hit at 80 cm (half 40)
        val wall = Obb2(V2(0.38f + 0.05f, -2f), V2(1f, 0f), 0.05f, 1f)
        assertFalse(obbIntersects(pose.footprint(ChairModel.MANUAL), wall))
        assertTrue(obbIntersects(pose.footprint(ChairModel.MANUAL.sized(widthCm = 80)), wall))
        // longer chair: the push handle moves back by half the extra length
        val long = ChairModel.MANUAL.sized(lengthCm = 130)
        assertEquals(-2f + 0.55f, pose.handle(ChairModel.MANUAL).z, 1e-5f)
        assertEquals(-2f + 0.65f, pose.handle(long).z, 1e-5f)
        // taller chair: the grips (and so taking hold by hand) are higher
        val grips = GripUseCase().grips(pose, ChairModel.MANUAL.sized(heightCm = 105), 0f)
        assertEquals(1.05f, grips.first.y, 1e-5f)
        assertEquals(-0.34f, grips.first.x, 1e-5f)
    }
}
