package io.embodit.accesscheck.ui.access.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import io.embodit.accesscheck.R
import io.embodit.accesscheck.domain.model.ObstacleName
import io.embodit.accesscheck.domain.model.PinTag
import io.embodit.accesscheck.domain.model.ThresholdPick
import io.embodit.accesscheck.domain.model.Verdict
import io.embodit.accesscheck.ui.access.AccessEvent
import io.embodit.accesscheck.ui.access.AccessUiState
import io.embodit.accesscheck.ui.access.DoorUi
import io.embodit.accesscheck.ui.access.debug.probe
import io.embodit.accesscheck.ui.access.scene.PanelSpec
import io.embodit.accesscheck.ui.theme.AcColors

/** Colour and glyph of a verdict (design `palette.semantic`: never colour alone). */
@Composable
internal fun verdictColor(v: Verdict): Color =
    when (v) {
        Verdict.PASS -> PicoTheme.colorScheme.passable
        Verdict.TIGHT -> PicoTheme.colorScheme.alert
        Verdict.FAIL -> PicoTheme.colorScheme.error
        Verdict.TO_MEASURE -> AcColors.StaleGrey
    }

internal fun verdictIcon(v: Verdict): Int =
    when (v) {
        Verdict.PASS -> R.drawable.ic_check
        Verdict.TIGHT -> R.drawable.ic_warning
        Verdict.FAIL -> R.drawable.ic_close
        Verdict.TO_MEASURE -> R.drawable.ic_question
    }

/** A 40 dp round badge with the verdict glyph on its colour. */
@Composable
internal fun VerdictBadge(v: Verdict, size: Int = 40) {
    Box(modifier = Modifier.size(size.dp).clip(CircleShape).background(verdictColor(v)), contentAlignment = Alignment.Center) {
        Icon(painter = painterResource(verdictIcon(v)), contentDescription = null, modifier = Modifier.size((size * 0.6f).dp), tint = AcColors.Ink)
    }
}

/** Opaque ink card (plates, hint, report root): the design avoids large glass in the room. */
@Composable
internal fun InkCard(width: Int, height: Int, corner: Int = 24, padding: Int = 12, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.size(width.dp, height.dp).clip(RoundedCornerShape(corner.dp)).background(AcColors.PlateInk.copy(alpha = 0.94f)).padding(padding.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content(this)
    }
}

/**
 * The chair plate (design `panels.chairPlate`): 280 x 96 above the push handle; idle "try a turn",
 * turn result, and a second line when the clear floor is short (only when the footprint itself is clear).
 */
@Composable
internal fun ChairPlate(state: AccessUiState, filled: Boolean) {
    val (w, h) = PanelSpec.CHAIR_PLATE_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.TopCenter) {
        if (!filled) return@Box
        val turn = state.turn
        val clear = state.clearFloor
        val white = AcColors.White
        InkCard(w.toInt(), h.toInt()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(min = 40.dp)) {
                when {
                    turn == null -> Icon(painter = painterResource(R.drawable.ic_turn), contentDescription = null, modifier = Modifier.size(32.dp), tint = white)
                    turn.pass -> VerdictBadge(Verdict.PASS)
                    else -> VerdictBadge(Verdict.FAIL)
                }
                Text(
                    text =
                        when {
                            turn == null -> rs(R.string.plate_turn_idle)
                            turn.pass -> rs(R.string.plate_turn_pass)
                            else -> rs(R.string.plate_turn_fail, len(state.units, turn.shortCm), obstacleName(turn.blocker ?: ObstacleName.WALL))
                        },
                    color = white,
                    style = AcType.title.copy(fontFeatureSettings = "tnum, lnum"),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.probe("chair_plate_line"),
                )
            }
            if (clear != null && !state.hit) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painter = painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(18.dp), tint = PicoTheme.colorScheme.alert)
                    Text(text = rs(R.string.plate_clear_short, len(state.units, clear.shortCm), obstacleName(clear.blocker)), color = PicoTheme.colorScheme.alert, style = AcType.figure, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** First line of a door reading: badge, width (with "about"), verdict word. */
@Composable
private fun DoorLine(d: DoorUi, state: AccessUiState) {
    val v = d.verdict
    val widthText = if (v.approx) rs(R.string.door_about, len(state.units, v.widthCm)) else len(state.units, v.widthCm)
    val verdictWord =
        when (v.overall) {
            Verdict.PASS -> rs(R.string.door_pass)
            Verdict.TIGHT -> rs(R.string.door_tight)
            Verdict.FAIL -> rs(R.string.door_fail)
            Verdict.TO_MEASURE -> rs(R.string.door_to_measure)
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(40.dp)) {
        VerdictBadge(v.overall)
        Text(text = "$widthText · $verdictWord", color = AcColors.White, style = AcType.title.copy(fontFeatureSettings = "tnum, lnum"), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("door_line"))
    }
}

/** Second line: the threshold pick, coloured by its own verdict. */
@Composable
private fun ThresholdLine(d: DoorUi) {
    val v = d.verdict.threshold
    val color = if (d.entry.threshold == ThresholdPick.UNSET) AcColors.StaleGrey else verdictColor(v)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(text = rs(R.string.door_threshold) + " " + Fmt.threshold(LocalAcResources.current, d.entry.threshold), color = AcColors.White.copy(alpha = 0.8f), style = AcType.figure, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The collapsed door plate (300 x 112); a tap expands it. */
@Composable
internal fun DoorPlate(slot: Int, state: AccessUiState, filled: Boolean, onEvent: (AccessEvent) -> Unit) {
    val (w, h) = PanelSpec.DOOR_PLATE_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.TopCenter) {
        if (!filled) return@Box
        val d = state.doors.firstOrNull { it.slot == slot } ?: return@Box
        val onClick = { onEvent(AccessEvent.DoorPlateClicked(d.entry.info.id)) }
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight().clickable(onClick = onClick).probe("door_plate_$slot", onClick)) {
            InkCard(w.toInt(), h.toInt()) {
                Text(text = rs(R.string.door_title, d.entry.info.index), color = AcColors.White.copy(alpha = 0.66f), style = AcType.figure, maxLines = 1)
                DoorLine(d, state)
                ThresholdLine(d)
            }
        }
    }
}

/** The expanded door card (300 x 320): threshold segments, the tape row with -5 / -1 / +1 / +5 and confirm. */
@Composable
internal fun DoorCard(state: AccessUiState, filled: Boolean, onEvent: (AccessEvent) -> Unit) {
    val (w, h) = PanelSpec.DOOR_CARD_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.TopCenter) {
        if (!filled) return@Box
        val d = state.cardDoor ?: return@Box
        val id = d.entry.info.id
        InkCard(w.toInt(), h.toInt()) {
            Row(modifier = Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = rs(R.string.door_title, d.entry.info.index), color = AcColors.White.copy(alpha = 0.66f), style = AcType.figure, maxLines = 1, modifier = Modifier.weight(1f))
                Key("door_close", R.drawable.ic_close, rs(R.string.action_close)) { onEvent(AccessEvent.CardDismissed) }
            }
            DoorLine(d, state)
            Text(text = rs(R.string.door_threshold), color = AcColors.White.copy(alpha = 0.8f), style = AcType.figure, maxLines = 1)
            val picks = listOf(ThresholdPick.NONE, ThresholdPick.CM1, ThresholdPick.CM2, ThresholdPick.CM3)
            SegmentControl(modifier = Modifier.width(276.dp).height(KeyHeight), backgroundColor = AcColors.White.copy(alpha = 0.10f)) {
                picks.forEach { pick ->
                    val onClick = { onEvent(AccessEvent.ThresholdSelected(id, pick)) }
                    SegmentItem(
                        selected = d.entry.threshold == pick,
                        onClick = onClick,
                        modifier = Modifier.width(67.dp).height(KeyHeight).probe("threshold_${pick.tag}", onClick),
                        textStyle = PicoTheme.typography.labelLarge,
                        colors = SegmentControlDefaults.colors(itemBackgroundColor = Color.Transparent, itemContentColor = AcColors.White, selectedItemBackgroundColor = AcColors.White.copy(alpha = 0.22f), selectedItemContentColor = AcColors.White),
                        title = {
                            // design-style: inherited-content-color SegmentItem
                            Text(text = Fmt.thresholdShort(LocalAcResources.current, pick), maxLines = 1, softWrap = false)
                        },
                    )
                }
            }
            Text(text = rs(R.string.measured_row), color = AcColors.White.copy(alpha = 0.8f), style = AcType.figure, maxLines = 1)
            Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = len(state.units, d.draftCm), color = AcColors.White, style = AcType.title.copy(fontFeatureSettings = "tnum, lnum"), maxLines = 1, modifier = Modifier.width(72.dp))
                for ((name, delta) in listOf("measure_m5" to -5, "measure_m1" to -1, "measure_p1" to 1, "measure_p5" to 5)) {
                    val label = if (delta > 0) "+$delta" else "$delta"
                    WordButton(name, label, { onEvent(AccessEvent.MeasureAdjusted(id, delta)) }, modifier = Modifier.weight(1f), minWidth = 40.dp, onInk = true)
                }
            }
            Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WordButton("measure_confirm", rs(R.string.action_confirm), { onEvent(AccessEvent.MeasureConfirmed(id)) }, modifier = Modifier.weight(1f), primary = true, iconRes = R.drawable.ic_check, enabled = d.draftCm != d.entry.measuredCm)
                if (d.entry.measuredCm != null) Icon(painter = painterResource(R.drawable.ic_ruler), contentDescription = rs(R.string.door_measured), modifier = Modifier.size(24.dp), tint = PicoTheme.colorScheme.passable)
            }
            Text(text = rs(R.string.measured_note), color = AcColors.White.copy(alpha = 0.66f), style = PicoTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The pin tag card (360 x 72): three tags and delete; child height mode highlights "hazard". */
@Composable
internal fun PinTagCard(state: AccessUiState, filled: Boolean, onEvent: (AccessEvent) -> Unit) {
    val (w, h) = PanelSpec.PIN_TAG_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (!filled) return@Box
        val pin = state.pins.firstOrNull { it.id == state.cardPinId } ?: return@Box
        Row(
            modifier = Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(24.dp)).background(AcColors.PlateInk.copy(alpha = 0.94f)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (tag in PinTag.entries) {
                val hot = (pin.childMode && tag == PinTag.HAZARD) || pin.tag == tag
                WordButton("pin_tag_${tag.tag}", Fmt.pinTag(LocalAcResources.current, tag), { onEvent(AccessEvent.PinTagSelected(pin.id, tag)) }, modifier = Modifier.weight(1f).fillMaxHeight(), minWidth = 80.dp, onInk = true, selected = hot)
            }
            Key("pin_delete", R.drawable.ic_trash, rs(R.string.action_delete)) { onEvent(AccessEvent.PinDeleted(pin.id)) }
        }
    }
}
