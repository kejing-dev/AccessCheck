package io.embodit.accesscheck.ui.access.components

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import io.embodit.accesscheck.R
import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.ChairPreset
import io.embodit.accesscheck.domain.model.LocationName
import io.embodit.accesscheck.domain.model.ObstacleName
import io.embodit.accesscheck.domain.model.PinTag
import io.embodit.accesscheck.domain.model.ThresholdPick
import io.embodit.accesscheck.domain.model.UnitPref
import io.embodit.accesscheck.domain.model.Verdict
import java.util.Locale
import kotlin.math.roundToInt

/** Readings and names as text (design `rules.units`: cm whole, inches to the half; names from `rules.scuff`). */
object Fmt {
    /** A length given in centimetres. */
    fun len(res: Resources, units: UnitPref, cm: Int): String =
        if (units == UnitPref.CM) res.getString(R.string.fmt_cm, cm)
        else res.getString(R.string.fmt_in, half(cm / 2.54f))

    fun lenM(res: Resources, units: UnitPref, m: Float): String = len(res, units, (m * 100f).roundToInt())

    /** The chair as "Manual 68 × 110 cm": its preset's name and its own footprint (v0.1.2: the size is adjustable). */
    fun chair(res: Resources, c: ChairModel): String {
        val name =
            when (c.preset) {
                ChairPreset.MANUAL -> R.string.chair_manual_short
                ChairPreset.POWER -> R.string.chair_power_short
                ChairPreset.CHILD -> R.string.chair_child_short
            }
        return res.getString(R.string.chair_label, res.getString(name), c.widthCm, c.lengthCm)
    }

    /** A distance in metres, one decimal (feet when in inches). */
    fun dist(res: Resources, units: UnitPref, m: Float): String =
        if (units == UnitPref.CM) res.getString(R.string.fmt_m, String.format(Locale.US, "%.1f", m))
        else res.getString(R.string.fmt_ft, String.format(Locale.US, "%.1f", m * 3.2808f))

    private fun half(v: Float): String {
        val h = (v * 2f).roundToInt() / 2f
        return if (h == h.toInt().toFloat()) h.toInt().toString() else String.format(Locale.US, "%.1f", h)
    }

    fun obstacle(res: Resources, name: ObstacleName): String =
        res.getString(
            when (name) {
                ObstacleName.WALL -> R.string.obs_wall
                ObstacleName.TABLE -> R.string.obs_table
                ObstacleName.CABINET -> R.string.obs_cabinet
                ObstacleName.WASHING_MACHINE -> R.string.obs_washing_machine
                ObstacleName.REFRIGERATOR -> R.string.obs_refrigerator
                ObstacleName.BED -> R.string.obs_bed
                ObstacleName.SOFA -> R.string.obs_sofa
                ObstacleName.CHAIR -> R.string.obs_chair
                ObstacleName.LOW_OBSTACLE -> R.string.obs_low
                ObstacleName.OBSTACLE -> R.string.obs_other
            }
        )

    fun location(res: Resources, units: UnitPref, loc: LocationName): String {
        val d = dist(res, units, loc.distanceM)
        val door = loc.doorIndex
        return if (door == null) {
            if (loc.obstacle == ObstacleName.WALL) res.getString(R.string.loc_start_wall, d) else res.getString(R.string.loc_start_thing, obstacle(res, loc.obstacle), d)
        } else if (loc.obstacle == ObstacleName.WALL && loc.hand != 0) {
            res.getString(R.string.loc_door_wall, door, res.getString(if (loc.hand < 0) R.string.hand_left else R.string.hand_right), d)
        } else {
            res.getString(R.string.loc_door_thing, obstacle(res, loc.obstacle), d, door)
        }
    }

    fun verdict(res: Resources, v: Verdict): String =
        res.getString(
            when (v) {
                Verdict.PASS -> R.string.report_verdict_pass
                Verdict.TIGHT -> R.string.report_verdict_tight
                Verdict.FAIL -> R.string.report_verdict_fail
                Verdict.TO_MEASURE -> R.string.report_verdict_measure
            }
        )

    fun threshold(res: Resources, t: ThresholdPick): String =
        res.getString(
            when (t) {
                ThresholdPick.UNSET -> R.string.threshold_unset
                ThresholdPick.NONE -> R.string.threshold_none
                ThresholdPick.CM1 -> R.string.threshold_1
                ThresholdPick.CM2 -> R.string.threshold_2
                ThresholdPick.CM3 -> R.string.threshold_3
            }
        )

    fun thresholdShort(res: Resources, t: ThresholdPick): String =
        res.getString(
            when (t) {
                ThresholdPick.UNSET -> R.string.threshold_unset
                ThresholdPick.NONE -> R.string.threshold_short_none
                ThresholdPick.CM1 -> R.string.threshold_short_1
                ThresholdPick.CM2 -> R.string.threshold_short_2
                ThresholdPick.CM3 -> R.string.threshold_short_3
            }
        )

    fun pinTag(res: Resources, t: PinTag?): String =
        res.getString(
            when (t) {
                PinTag.CANT_SEE -> R.string.tag_see
                PinTag.HAZARD -> R.string.tag_hazard
                else -> R.string.tag_reach
            }
        )
}

@Composable
@ReadOnlyComposable
internal fun len(units: UnitPref, cm: Int): String = Fmt.len(LocalAcResources.current, units, cm)

@Composable
@ReadOnlyComposable
internal fun chairText(c: ChairModel): String = Fmt.chair(LocalAcResources.current, c)

@Composable
@ReadOnlyComposable
internal fun obstacleName(name: ObstacleName): String = Fmt.obstacle(LocalAcResources.current, name)

@Composable
@ReadOnlyComposable
internal fun locationText(units: UnitPref, loc: LocationName): String = Fmt.location(LocalAcResources.current, units, loc)
