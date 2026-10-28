package io.embodit.accesscheck.ui.access.components

import android.content.res.Resources
import io.embodit.accesscheck.R
import io.embodit.accesscheck.domain.model.ThresholdPick
import io.embodit.accesscheck.domain.model.Verdict
import io.embodit.accesscheck.ui.access.AccessUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The saved report as plain UTF-8 text (design `panels.report`, sample section 07). */
object ReportText {
    fun build(res: Resources, s: AccessUiState, date: Date): String {
        val u = s.units
        val sb = StringBuilder()
        sb.appendLine(res.getString(R.string.report_file_title, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(date)))
        sb.appendLine("${res.getString(R.string.report_chair)}: ${Fmt.chair(res, s.chair)}")
        for (t in s.turnRecords) {
            val mark = if (t.pass) "[✓]" else "[✗]"
            val body = if (t.pass) res.getString(R.string.report_turn_pass) else res.getString(R.string.report_turn_fail, Fmt.len(res, u, t.shortCm), Fmt.obstacle(res, t.blocker ?: io.embodit.accesscheck.domain.model.ObstacleName.WALL))
            sb.appendLine("$mark " + res.getString(R.string.report_row, res.getString(R.string.report_turn), Fmt.location(res, u, t.where), body))
        }
        for (c in s.clearRecords) {
            sb.appendLine("[!] " + res.getString(R.string.report_row, res.getString(R.string.report_clear), Fmt.location(res, u, c.where), res.getString(R.string.report_clear_row, Fmt.len(res, u, c.shortCm), Fmt.obstacle(res, c.blocker))))
            sb.appendLine("    " + res.getString(R.string.report_paren, res.getString(R.string.report_clear_note)))
        }
        for (d in s.doors) {
            if (d.entry.measuredCm == null && d.entry.threshold == ThresholdPick.UNSET && d.slot < 0) continue
            val v = d.verdict
            val mark = when (v.overall) { Verdict.PASS -> "[✓]"; Verdict.TO_MEASURE -> "[?]"; else -> "[✗]" }
            val width = res.getString(R.string.report_paren_after, if (v.approx) res.getString(R.string.door_about, Fmt.len(res, u, v.widthCm)) else Fmt.len(res, u, v.widthCm), res.getString(if (v.approx) R.string.door_not_measured else R.string.door_measured))
            sb.appendLine("$mark " + res.getString(R.string.report_label, res.getString(R.string.door_title, d.entry.info.index), width) + " · ${res.getString(R.string.door_threshold)} ${Fmt.thresholdShort(res, d.entry.threshold)} · " + Fmt.verdict(res, v.overall))
        }
        if (s.scuffs.isNotEmpty()) {
            sb.appendLine("[!] ${res.getString(R.string.report_scuffs)} ${s.scuffs.size}:")
            for (sc in s.scuffs) sb.appendLine("    - ${res.getString(R.string.report_scuff_row, Fmt.location(res, u, sc.where), Fmt.lenM(res, u, sc.heightM))}")
        }
        if (s.pins.isNotEmpty()) {
            sb.appendLine("[!] ${res.getString(R.string.report_pins)} ${s.pins.size}:")
            for (p in s.pins) {
                val child = if (p.childMode) " " + res.getString(R.string.report_child_mode) else ""
                sb.appendLine("    - ${res.getString(R.string.report_pin_row, Fmt.pinTag(res, p.tag), Fmt.location(res, u, p.where), Fmt.lenM(res, u, p.heightM))}$child")
            }
        }
        sb.appendLine()
        sb.appendLine(res.getString(R.string.report_refs))
        sb.appendLine(res.getString(R.string.disclaimer))
        return sb.toString()
    }
}
