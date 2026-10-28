package io.embodit.accesscheck.ui.access.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.SliderDefaults
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.Material
import io.embodit.accesscheck.R
import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.ChairPreset
import io.embodit.accesscheck.domain.model.LanguagePref
import io.embodit.accesscheck.domain.model.UnitPref
import io.embodit.accesscheck.ui.access.AccessEvent
import io.embodit.accesscheck.ui.access.AccessUiState
import io.embodit.accesscheck.ui.access.debug.probe
import io.embodit.accesscheck.ui.access.scene.PanelSpec
import io.embodit.accesscheck.ui.theme.AcColors
import kotlin.math.roundToInt

/**
 * The More menu (320 x 464 at 0.75, S23): the wheelchair (preset segments and, v0.1.2, width / length / height
 * sliders in whole cm), language and unit segments, "Clear scuffs and pins" and Exit. Segments and sliders act in
 * place and leave the menu open; Clear opens the dialog.
 */
@Composable
internal fun MoreMenu(state: AccessUiState, filled: Boolean, onEvent: (AccessEvent) -> Unit) {
    val (w, h) = PanelSpec.MENU_SIZE
    Glass(w.dp, h.dp, corner = 24.dp, material = Material.Thick, padding = 12.dp) {
        if (!filled) return@Glass
        MenuRow(rs(R.string.menu_chair)) {
            Segments(
                items = listOf("chair_manual" to rs(R.string.chair_manual_short), "chair_power" to rs(R.string.chair_power_short), "chair_child" to rs(R.string.chair_child_short)),
                selected = state.chair.preset.ordinal,
                onSelect = { onEvent(AccessEvent.ChairSelected(ChairModel.of(ChairPreset.entries[it]))) },
            )
        }
        val c = state.chairDraft ?: state.chair
        SizeRow("size_width", rs(R.string.size_width), c.widthCm, ChairModel.WIDTH_CM, state.units) { v, done -> onEvent(AccessEvent.ChairSized(v, c.lengthCm, c.heightCm, done)) }
        SizeRow("size_length", rs(R.string.size_length), c.lengthCm, ChairModel.LENGTH_CM, state.units) { v, done -> onEvent(AccessEvent.ChairSized(c.widthCm, v, c.heightCm, done)) }
        SizeRow("size_height", rs(R.string.size_height), c.heightCm, ChairModel.HEIGHT_CM, state.units) { v, done -> onEvent(AccessEvent.ChairSized(c.widthCm, c.lengthCm, v, done)) }
        MenuRow(rs(R.string.menu_language)) {
            Segments(
                items = listOf("lang_system" to null, "lang_zh" to rs(R.string.lang_zh), "lang_en" to rs(R.string.lang_en)),
                selected = state.language.ordinal,
                onSelect = { onEvent(AccessEvent.LanguageSelected(LanguagePref.entries[it])) },
                leadingIcon = R.drawable.ic_globe,
            )
        }
        MenuRow(rs(R.string.menu_units)) {
            Segments(items = listOf("unit_cm" to rs(R.string.unit_cm), "unit_in" to rs(R.string.unit_in)), selected = state.units.ordinal, onSelect = { onEvent(AccessEvent.UnitsSelected(UnitPref.entries[it])) })
        }
        Spacer(modifier = Modifier.height(8.dp))
        WordButton("menu_clear", rs(R.string.menu_clear), { onEvent(AccessEvent.ClearClicked) }, modifier = Modifier.fillMaxWidth(), enabled = !state.reportEmpty, iconRes = R.drawable.ic_trash)
        Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically) {
            Spacer(modifier = Modifier.weight(1f))
            Key("menu_exit", R.drawable.ic_exit, rs(R.string.action_exit)) { onEvent(AccessEvent.ExitClicked) }
        }
    }
}

@Composable
private fun MenuRow(label: String, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, color = PicoTheme.colorScheme.labelPrimary, style = AcType.figure, maxLines = 1, modifier = Modifier.width(72.dp))
        content()
    }
}

/** One size of the chair: label, a slider over [range] in whole cm, the value in the chosen units. */
@Composable
private fun SizeRow(name: String, label: String, cm: Int, range: IntRange, units: UnitPref, onChange: (Int, Boolean) -> Unit) {
    val latest by rememberUpdatedState(cm)
    Row(modifier = Modifier.fillMaxWidth().height(SizeRowHeight).probe(name), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, color = PicoTheme.colorScheme.labelPrimary, style = AcType.figure, maxLines = 1, modifier = Modifier.width(72.dp))
        // (value, onValueChange, modifier, valueRange, onValueChangeFinished): positional, as SDK 6.1.9 declares them.
        // The stock light track was 1.3:1 on the glass: a dark track (about 4:1) with the accent progress on it.
        Slider(
            cm.toFloat(),
            { v: Float -> v.roundToInt().let { if (it != latest) onChange(it, false) } },
            Modifier.width(144.dp),
            range.first.toFloat()..range.last.toFloat(),
            { onChange(latest, true) },
            colors = SliderDefaults.sliderColors(trackColor = PicoTheme.colorScheme.fillPrimary, progressColor = PicoTheme.colorScheme.interaction, progressHighColor = PicoTheme.colorScheme.interaction),
        )
        Text(text = len(units, cm), color = PicoTheme.colorScheme.labelPrimary, style = AcType.figure, maxLines = 1, softWrap = false, modifier = Modifier.width(64.dp))
    }
}

private val SizeRowHeight = 40.dp

/** A SegmentControl whose items share the row; an item with a null text shows [leadingIcon] instead. */
@Composable
private fun Segments(items: List<Pair<String, String?>>, selected: Int, onSelect: (Int) -> Unit, leadingIcon: Int? = null) {
    val itemW = (216.dp - 8.dp) / items.size
    SegmentControl(modifier = Modifier.width(216.dp).fillMaxHeight(), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEachIndexed { i, (name, text) ->
            val onClick = { onSelect(i) }
            SegmentItem(
                selected = selected == i,
                onClick = onClick,
                modifier = Modifier.width(itemW).height(KeyHeight).probe(name, onClick),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        // selected: the accent with ink, as every selected mode (design `palette.semantic`)
                        selectedItemBackgroundColor = PicoTheme.colorScheme.interaction,
                        selectedItemContentColor = AcColors.Ink,
                    ),
                title = {
                    if (text == null && leadingIcon != null) {
                        Icon(painter = painterResource(leadingIcon), contentDescription = rs(R.string.lang_system), modifier = Modifier.size(20.dp), tint = if (selected == i) AcColors.Ink else PicoTheme.colorScheme.labelPrimary)
                    } else {
                        // design-style: inherited-content-color SegmentItem
                        Text(text = text ?: "", maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                    }
                },
            )
        }
    }
}
