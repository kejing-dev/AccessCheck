package io.embodit.accesscheck.ui.access.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
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
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.accesscheck.R
import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.ViewHeight
import io.embodit.accesscheck.ui.access.AccessEvent
import io.embodit.accesscheck.ui.access.AccessUiState
import io.embodit.accesscheck.ui.access.Card
import io.embodit.accesscheck.ui.access.ChairMode
import io.embodit.accesscheck.ui.access.Phase
import io.embodit.accesscheck.ui.access.Tool
import io.embodit.accesscheck.ui.access.debug.probe
import io.embodit.accesscheck.ui.access.scene.PanelSpec
import io.embodit.accesscheck.ui.theme.AcColors

/**
 * The console (design `panels.console`): the only standing panel, 400 dp wide at 0.75, lazy-following at
 * the lower left. Its rows come and go with the chair layer: no chair = place + height + pin / report;
 * pushing = status + stop + report; parked = push + turn / rotate + height (+ readout) + pin / report.
 * The top edge is anchored and the big button stays on the second row.
 */
@Composable
internal fun ConsolePanel(state: AccessUiState, filled: Boolean, onEvent: (AccessEvent) -> Unit) {
    val (w, h) = PanelSpec.CONSOLE_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.TopCenter) {
        if (!filled) return@Box
        val ready = state.phase == Phase.READY && !state.busy && !state.trackingLost
        // a fixed-size glass (the pooled panel's surface takes its size once; a hugging glass left the rest of the
        // panel black on the emulator); the rows are anchored at the top, the big button on the second row
        Column(
            modifier = Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(32.dp)).backgroundMaterial(enable = true, style = Material.Regular).padding(PanelPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TitleRow(statusText(state), trailing = { Key("console_more", R.drawable.ic_more, rs(R.string.action_more), enabled = state.phase != Phase.BLOCKED) { onEvent(AccessEvent.MoreClicked) } })
            when (state.chairMode) {
                ChairMode.NONE ->
                    BigAction(
                        name = "console_place",
                        iconRes = R.drawable.ic_wheelchair,
                        text = rs(R.string.place_chair),
                        sub = rs(if (state.chair == ChairModel.MANUAL) R.string.chair_manual else R.string.chair_power),
                        enabled = ready && state.hasFloor,
                        busy = state.phase == Phase.STARTING || state.phase == Phase.SCANNING,
                        container = PicoTheme.colorScheme.fillPrimary,
                        content = PicoTheme.colorScheme.labelPrimary,
                    ) { onEvent(AccessEvent.PlaceClicked) }
                ChairMode.PUSHING, ChairMode.WAITING_BEHIND, ChairMode.DRAGGING ->
                    BigAction(
                        name = "console_park",
                        iconRes = R.drawable.ic_pause,
                        text = rs(R.string.park),
                        sub = "",
                        enabled = state.phase == Phase.READY,
                        busy = false,
                        container = PicoTheme.colorScheme.interaction,
                        content = AcColors.Ink,
                    ) { onEvent(AccessEvent.ParkClicked) }
                ChairMode.PARKED, ChairMode.TURNING ->
                    BigAction(
                        name = "console_push",
                        iconRes = R.drawable.ic_push,
                        text = rs(R.string.push),
                        sub = "",
                        enabled = ready,
                        busy = state.busy,
                        container = PicoTheme.colorScheme.fillPrimary,
                        content = PicoTheme.colorScheme.labelPrimary,
                    ) { onEvent(AccessEvent.PushClicked) }
            }
            if (state.chairMode == ChairMode.PARKED || state.chairMode == ChairMode.TURNING) {
                Row(modifier = Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    WordButton("console_turn", rs(R.string.turn), { onEvent(AccessEvent.TurnClicked) }, modifier = Modifier.weight(1f).fillMaxHeight(), enabled = ready, iconRes = R.drawable.ic_turn)
                    RotateKey("console_rot_left", R.drawable.ic_rot_left, rs(R.string.rot_left), ready) { onEvent(AccessEvent.RotateClicked(-1)) }
                    RotateKey("console_rot_right", R.drawable.ic_rot_right, rs(R.string.rot_right), ready) { onEvent(AccessEvent.RotateClicked(1)) }
                }
                Text(text = rs(R.string.stick_hint), color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
            }
            if (state.chairMode != ChairMode.PUSHING && state.chairMode != ChairMode.WAITING_BEHIND && state.chairMode != ChairMode.DRAGGING) {
                HeightSegments(state, ready) { onEvent(AccessEvent.HeightSelected(it)) }
                val hu = state.height
                if (hu != null && state.view != ViewHeight.STAND) HeightReadout(state)
                Row(modifier = Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pinOn = state.tool == Tool.PIN
                    WordButton("console_pin", rs(R.string.pin), { onEvent(AccessEvent.PinClicked) }, modifier = Modifier.weight(1f).fillMaxHeight(), enabled = ready && (!state.pinsFull || pinOn), iconRes = R.drawable.ic_pin, selected = pinOn)
                    WordButton("console_report", rs(R.string.report), { onEvent(AccessEvent.ReportClicked) }, modifier = Modifier.weight(1f).fillMaxHeight(), enabled = state.phase == Phase.READY, iconRes = R.drawable.ic_report, selected = state.card == Card.REPORT)
                }
            } else {
                WordButton("console_report", rs(R.string.report), { onEvent(AccessEvent.ReportClicked) }, modifier = Modifier.fillMaxWidth().height(56.dp), enabled = state.phase == Phase.READY, iconRes = R.drawable.ic_report, selected = state.card == Card.REPORT)
            }
        }
    }
}

@Composable
private fun statusText(state: AccessUiState): String =
    when {
        state.phase == Phase.STARTING || state.phase == Phase.BLOCKED -> rs(R.string.status_starting)
        state.phase == Phase.SCANNING -> rs(R.string.status_scanning)
        state.trackingLost -> rs(R.string.status_lost)
        state.tool == Tool.PIN -> rs(R.string.status_pin)
        state.chairMode == ChairMode.NONE -> rs(R.string.status_no_chair)
        state.chairMode == ChairMode.PUSHING -> rs(R.string.status_pushing)
        state.chairMode == ChairMode.WAITING_BEHIND -> rs(R.string.status_waiting_behind)
        state.chairMode == ChairMode.TURNING -> rs(R.string.status_turning)
        else -> rs(R.string.status_parked)
    }

/** The 376 x 88 big button: icon 40 + main text (+ a second line). */
@Composable
private fun BigAction(name: String, iconRes: Int, text: String, sub: String, enabled: Boolean, busy: Boolean, container: Color, content: Color, onClick: () -> Unit) {
    val tint = if (enabled) content else PicoTheme.colorScheme.labelQuaternary
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(88.dp).probe(name, onClick),
        size = ButtonDefaults.Regular.copy(height = 88.dp, minWidth = 376.dp),
        shape = RoundedCornerShape(24.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = tint),
    ) {
        if (busy) {
            com.pico.spatial.ui.design.CircularProgressIndicator(modifier = Modifier.size(40.dp), color = tint, progressSize = com.pico.spatial.ui.design.CircularProgressDefaults.circleProgressSize(40.dp))
        } else {
            Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(40.dp), tint = tint)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.Center) {
            Text(text = text, color = tint, style = PicoTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(text = sub, color = tint, style = AcType.figure, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RotateKey(name: String, iconRes: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    val content = if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary
    Button(
        onClick = onClick,
        modifier = Modifier.width(88.dp).fillMaxHeight().probe(name, onClick),
        size = ButtonDefaults.Regular.copy(height = 56.dp, minWidth = 88.dp),
        shape = RoundedCornerShape(18.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = content),
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = description, modifier = Modifier.size(24.dp), tint = content)
    }
}

/** "See from": standing / seated 1.20 m / child 1.00 m. */
@Composable
private fun HeightSegments(state: AccessUiState, enabled: Boolean, onSelect: (ViewHeight) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = rs(R.string.height_title), color = PicoTheme.colorScheme.labelPrimary, style = AcType.figure, maxLines = 1, modifier = Modifier.width(88.dp))
        val items = listOf(Triple("height_stand", rs(R.string.height_stand), R.drawable.ic_stand), Triple("height_seat", rs(R.string.height_seat_short), R.drawable.ic_seat), Triple("height_child", rs(R.string.height_child_short), R.drawable.ic_child))
        // the control keeps about 4 dp of its own padding on each side (Furniture Fit's menu measurement)
        SegmentControl(modifier = Modifier.width(280.dp).fillMaxHeight(), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
            items.forEachIndexed { i, (name, text, icon) ->
                val view = ViewHeight.entries[i]
                val onClick = { if (enabled) onSelect(view) }
                SegmentItem(
                    selected = state.view == view,
                    onClick = onClick,
                    modifier = Modifier.width(90.dp).height(KeyHeight).probe(name, onClick),
                    textStyle = PicoTheme.typography.labelLarge,
                    colors =
                        SegmentControlDefaults.colors(
                            itemBackgroundColor = Color.Transparent,
                            itemContentColor = PicoTheme.colorScheme.labelPrimary,
                            selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                            selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                        ),
                    title = {
                        @Suppress("UNUSED_EXPRESSION") icon
                        // design-style: inherited-content-color SegmentItem
                        Text(text = text, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                    },
                )
            }
        }
    }
}

/** The 56 dp readout under the segments: "18 cm lower" / "that is the height" (green). */
@Composable
private fun HeightReadout(state: AccessUiState) {
    val h = state.height ?: return
    val ok = h.ok
    val container = if (ok) PicoTheme.colorScheme.passable else PicoTheme.colorScheme.fillTertiary
    val content = if (ok) AcColors.Ink else PicoTheme.colorScheme.labelPrimary
    val text =
        when {
            ok -> rs(R.string.at_height)
            h.diffCm > 0 -> rs(R.string.lower_by, len(state.units, h.diffCm))
            else -> rs(R.string.higher_by, len(state.units, -h.diffCm))
        }
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).background(container).padding(horizontal = 16.dp).probe("height_readout"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painter = painterResource(if (ok) R.drawable.ic_check else if (state.view == ViewHeight.CHILD) R.drawable.ic_child else R.drawable.ic_seat), contentDescription = null, modifier = Modifier.size(24.dp), tint = content)
        Text(text = text, color = content, style = PicoTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum"), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
