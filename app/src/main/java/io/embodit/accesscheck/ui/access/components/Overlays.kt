package io.embodit.accesscheck.ui.access.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.accesscheck.R
import io.embodit.accesscheck.ui.access.AccessEvent
import io.embodit.accesscheck.ui.access.AccessUiState
import io.embodit.accesscheck.ui.access.DialogKind
import io.embodit.accesscheck.ui.access.HintKind
import io.embodit.accesscheck.ui.access.HintUi
import io.embodit.accesscheck.ui.access.debug.probe
import io.embodit.accesscheck.ui.access.scene.PanelSpec
import io.embodit.accesscheck.ui.theme.AcColors

private const val FADE_IN_MS = 150

/**
 * The dialogs (self-built attachment panel, 440 x 240, 1.0 m ahead): clear confirmation (S22) and
 * "can't run right now" (S20). In a Stage the SpatialUI `AlertDialog` composes nothing (Spatial Ruler
 * finding), so the card is built to its metrics. v0.1.2: a solid ink card like the hint and the report (its
 * glass rendered black in the Stage and took the adaptive dark label and the Cancel key with it).
 */
@Composable
internal fun DialogPanel(dialog: DialogKind?, onEvent: (AccessEvent) -> Unit) {
    val (w, h) = PanelSpec.DIALOG_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (dialog == null) return@Box
        val fade = remember(dialog) { Animatable(0f) }
        LaunchedEffect(dialog) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        val ink = AcColors.White
        Column(
            modifier = Modifier.width(w.dp).heightIn(max = h.dp).graphicsLayer { alpha = fade.value }.clip(RoundedCornerShape(32.dp)).background(AcColors.PlateInk.copy(alpha = 0.96f)).padding(28.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(
                    painter = painterResource(if (dialog == DialogKind.CLEAR) R.drawable.ic_trash else R.drawable.ic_unavailable),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = if (dialog == DialogKind.CLEAR) PicoTheme.colorScheme.alert else ink,
                )
                Text(
                    text = rs(if (dialog == DialogKind.CLEAR) R.string.dialog_clear else R.string.dialog_unavailable),
                    color = ink,
                    style = PicoTheme.typography.bodyLargeMultiline,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (dialog == DialogKind.CLEAR) {
                    WordButton("dialog_cancel", rs(R.string.action_cancel), { onEvent(AccessEvent.DialogDismissed) }, minWidth = 120.dp, iconRes = R.drawable.ic_close, onInk = true)
                    WordButton("dialog_confirm", rs(R.string.action_clear), { onEvent(AccessEvent.ClearConfirmed) }, danger = true, minWidth = 120.dp, iconRes = R.drawable.ic_trash)
                } else {
                    WordButton("dialog_exit", rs(R.string.action_exit), { onEvent(AccessEvent.ExitClicked) }, primary = true, minWidth = 120.dp, iconRes = R.drawable.ic_exit)
                }
            }
        }
    }
}

@Composable
private fun hintText(h: HintUi, state: AccessUiState): String =
    when (h.kind) {
        HintKind.SCANNING -> rs(R.string.hint_scanning)
        HintKind.HIT -> rs(R.string.hint_hit, h.obstacle?.let { obstacleName(it) } ?: "")
        HintKind.STUCK -> rs(R.string.hint_stuck)
        HintKind.WAIT_BEHIND -> rs(R.string.hint_wait_behind)
        HintKind.SAVED -> rs(R.string.hint_saved, h.text)
        HintKind.PIN_MODE -> rs(R.string.hint_pin_mode)
        HintKind.LOST -> rs(R.string.hint_lost)
        HintKind.PINS_FULL -> rs(R.string.hint_pins_full)
        HintKind.SCUFFS_FULL -> rs(R.string.hint_scuffs_full)
        HintKind.SIT_DOWN -> rs(R.string.hint_sit_down)
        HintKind.CROUCH -> rs(R.string.hint_crouch)
        HintKind.THRESHOLD_CATCH -> rs(R.string.hint_threshold_catch, h.text)
        HintKind.DOOR_BLOCKED -> rs(R.string.hint_door_blocked)
        HintKind.STICK_HINT -> rs(R.string.stick_hint)
        HintKind.AIM_FLOOR -> rs(R.string.hint_aim_floor)
        HintKind.VIRTUAL_FLOOR -> rs(R.string.hint_virtual_floor)
    }

/**
 * The hint bar (design `panels.hint`, 480 x 64, lazy-following 15 degrees above the view): opaque ink,
 * icon + one line + an optional button ("stop and drag" when stuck).
 */
@Composable
internal fun HintPanel(state: AccessUiState, filled: Boolean, onEvent: (AccessEvent) -> Unit) {
    val (w, h) = PanelSpec.HINT_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        val hint = state.hint
        if (!filled || hint == null) return@Box
        val white = AcColors.White
        val fade = remember(hint.id) { Animatable(0f) }
        LaunchedEffect(hint.id) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        Row(
            modifier = Modifier.size(w.dp, h.dp).graphicsLayer { alpha = fade.value }.clip(RoundedCornerShape(28.dp)).background(AcColors.PlateInk.copy(alpha = 0.96f)).probe("hint").padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (hint.kind == HintKind.SCANNING) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = white, progressSize = CircularProgressDefaults.circleProgressSize(20.dp))
            } else {
                val (icon, tint) =
                    when (hint.kind) {
                        HintKind.HIT, HintKind.DOOR_BLOCKED, HintKind.THRESHOLD_CATCH -> R.drawable.ic_close to PicoTheme.colorScheme.error
                        HintKind.STUCK, HintKind.PINS_FULL, HintKind.SCUFFS_FULL -> R.drawable.ic_warning to PicoTheme.colorScheme.alert
                        HintKind.LOST -> R.drawable.ic_unavailable to AcColors.StaleGrey
                        HintKind.SAVED -> R.drawable.ic_check to PicoTheme.colorScheme.passable
                        HintKind.PIN_MODE -> R.drawable.ic_pin to white
                        HintKind.WAIT_BEHIND -> R.drawable.ic_push to white
                        HintKind.SIT_DOWN -> R.drawable.ic_seat to white
                        HintKind.CROUCH -> R.drawable.ic_child to white
                        HintKind.AIM_FLOOR -> R.drawable.ic_target to PicoTheme.colorScheme.alert
                        HintKind.VIRTUAL_FLOOR -> R.drawable.ic_warning to white
                        else -> R.drawable.ic_check to white
                    }
                Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
            }
            Text(
                text = hintText(hint, state),
                color = white,
                style = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (hint.kind == HintKind.STUCK) {
                WordButton("hint_action", rs(R.string.action_park_drag), { onEvent(AccessEvent.HintActionClicked) }, primary = true, minWidth = 120.dp, iconRes = R.drawable.ic_pause)
            }
        }
    }
}

/** First-run coachmark (360 x 96, S21) in front of the chair: pictogram, one sentence, got it. */
@Composable
internal fun CoachPanel(shown: Boolean, onEvent: (AccessEvent) -> Unit) {
    val (w, h) = PanelSpec.COACH_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (!shown) return@Box
        val fade = remember { Animatable(0f) }
        LaunchedEffect(Unit) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        Row(
            modifier = Modifier.size(w.dp, h.dp).graphicsLayer { alpha = fade.value }.clip(RoundedCornerShape(24.dp)).backgroundMaterial(enable = true, style = Material.Thick).probe("coach").padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painter = painterResource(R.drawable.ic_wheelchair), contentDescription = null, modifier = Modifier.size(28.dp), tint = PicoTheme.colorScheme.labelPrimary)
            Text(
                text = rs(R.string.coach),
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.bodyLargeMultiline,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            WordButton("coach_got_it", rs(R.string.action_got_it), { onEvent(AccessEvent.CoachDismissed) }, primary = true, minWidth = 88.dp)
        }
    }
}
