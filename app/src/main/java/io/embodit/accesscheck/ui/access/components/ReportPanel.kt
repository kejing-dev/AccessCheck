package io.embodit.accesscheck.ui.access.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.accesscheck.R
import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.ObstacleName
import io.embodit.accesscheck.domain.model.ThresholdPick
import io.embodit.accesscheck.ui.access.AccessEvent
import io.embodit.accesscheck.ui.access.AccessUiState
import io.embodit.accesscheck.ui.access.debug.probe
import io.embodit.accesscheck.ui.theme.AcColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The report card (design `panels.report`, 440 x 560 at 0.75, S18 / S19): opaque ink root, a title bar in
 * pine green, sections for the chair, turns, parking, doors, scuffs and pins with a delete key per row,
 * the save button and the disclaimer. Empty: one sentence, save disabled.
 */
@Composable
internal fun ReportPanel(state: AccessUiState, filled: Boolean, onEvent: (AccessEvent) -> Unit) {
    val (w, h) = io.embodit.accesscheck.ui.access.scene.PanelSpec.REPORT_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.TopCenter) {
        if (!filled) return@Box
        val white = AcColors.White
        val dim = white.copy(alpha = 0.66f)
        val res = LocalAcResources.current
        InkCard(w.toInt(), h.toInt(), corner = 28, padding = 0) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).background(AcColors.PineGreen).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = rs(R.string.report_title), color = white, style = PicoTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                Text(text = SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date()), color = white, style = AcType.figure)
            }
            Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (state.reportEmpty) {
                    Text(text = rs(R.string.report_empty), color = dim, style = PicoTheme.typography.bodyLargeMultiline, modifier = Modifier.padding(top = 24.dp).probe("report_empty"))
                } else {
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item { Section(rs(R.string.report_chair)); Line(rs(if (state.chair == ChairModel.MANUAL) R.string.chair_manual else R.string.chair_power)) }
                        if (state.turnRecords.isNotEmpty()) item { Section(rs(R.string.report_turn)) }
                        items(state.turnRecords, key = { "t${it.id}" }) { t ->
                            val body = if (t.pass) rs(R.string.report_turn_pass) else rs(R.string.report_turn_fail, len(state.units, t.shortCm), obstacleName(t.blocker ?: ObstacleName.WALL))
                            Line("${locationText(state.units, t.where)} · $body", ok = t.pass) { onEvent(AccessEvent.TurnRecordDeleted(t.id)) }
                        }
                        if (state.clearRecords.isNotEmpty()) {
                            item { Section(rs(R.string.report_clear)); Text(text = rs(R.string.report_clear_note), color = dim, style = PicoTheme.typography.labelMedium, maxLines = 2) }
                        }
                        items(state.clearRecords, key = { "c${it.id}" }) { c ->
                            Line("${locationText(state.units, c.where)} · " + rs(R.string.report_clear_row, len(state.units, c.shortCm), obstacleName(c.blocker)), warn = true) { onEvent(AccessEvent.ClearRecordDeleted(c.id)) }
                        }
                        val doors = state.doors.filter { it.entry.measuredCm != null || it.entry.threshold != ThresholdPick.UNSET || it.slot >= 0 }
                        if (doors.isNotEmpty()) item { Section(rs(R.string.report_doors)) }
                        items(doors, key = { "d${it.entry.info.id}" }) { d ->
                            val v = d.verdict
                            val width = if (v.approx) rs(R.string.door_about, len(state.units, v.widthCm)) + " " + rs(R.string.door_not_measured) else len(state.units, v.widthCm) + " " + rs(R.string.door_measured)
                            Line(rs(R.string.door_title, d.entry.info.index) + " · " + rs(R.string.report_door_row, width, "", Fmt.thresholdShort(res, d.entry.threshold), Fmt.verdict(res, v.overall)).replace("  ", " "), ok = v.overall == io.embodit.accesscheck.domain.model.Verdict.PASS)
                        }
                        if (state.scuffs.isNotEmpty()) item { Section(rs(R.string.report_scuffs) + " " + state.scuffs.size) }
                        items(state.scuffs, key = { "s${it.id}" }) { s ->
                            Line(rs(R.string.report_scuff_row, locationText(state.units, s.where), len(state.units, Math.round(s.heightM * 100f)))) { onEvent(AccessEvent.ScuffDeleted(s.id)) }
                        }
                        if (state.pins.isNotEmpty()) item { Section(rs(R.string.report_pins) + " " + state.pins.size) }
                        items(state.pins, key = { "p${it.id}" }) { p ->
                            Line(rs(R.string.report_pin_row, Fmt.pinTag(res, p.tag), locationText(state.units, p.where), len(state.units, Math.round(p.heightM * 100f))) + if (p.childMode) " " + rs(R.string.report_child_mode) else "") { onEvent(AccessEvent.PinDeleted(p.id)) }
                        }
                    }
                }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WordButton("report_save", rs(R.string.action_save), { onEvent(AccessEvent.SaveClicked) }, modifier = Modifier.fillMaxWidth().height(56.dp), primary = true, enabled = !state.reportEmpty, iconRes = R.drawable.ic_save)
                Text(text = rs(R.string.disclaimer), color = dim, style = PicoTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(text = title, color = AcColors.White.copy(alpha = 0.66f), style = AcType.figure, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
}

/** One report row on a 10 % white strip: text, and a delete key when [onDelete] is given. */
@Composable
private fun Line(text: String, ok: Boolean? = null, warn: Boolean = false, onDelete: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().background(AcColors.White.copy(alpha = 0.10f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = when { ok == true -> PicoTheme.colorScheme.passable; ok == false -> PicoTheme.colorScheme.error; warn -> PicoTheme.colorScheme.alert; else -> AcColors.White }
        Text(text = text, color = color, style = PicoTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onDelete != null) {
            Spacer(modifier = Modifier.size(4.dp))
            Key("report_delete", R.drawable.ic_trash, rs(R.string.action_delete), onClick = onDelete)
        }
    }
}
