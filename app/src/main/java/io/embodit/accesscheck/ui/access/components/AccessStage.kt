package io.embodit.accesscheck.ui.access.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.accesscheck.ui.access.AccessEvent
import io.embodit.accesscheck.ui.access.AccessUiState
import io.embodit.accesscheck.ui.access.Card
import io.embodit.accesscheck.ui.access.ChairMode
import io.embodit.accesscheck.ui.access.debug.LocalProbePanel
import io.embodit.accesscheck.ui.access.scene.AccessScene
import io.embodit.accesscheck.ui.access.scene.PanelSpec

/**
 * The Stage content: one full-size SpatialView that carries the 3D scene and the panel pool. Every
 * attachment panel is declared here once, with a fixed size, whether it is showing or not; the scene
 * parents each panel entity under its holder and shows or hides the holder.
 *
 * Window model: single panel with popups. The console is the only standing panel; the chair plate,
 * door plates / card and pin tag ride on things in the room; menu, report, dialog, hint and coach float.
 */
@Composable
internal fun AccessStage(state: AccessUiState, scene: AccessScene, onEvent: (AccessEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue

    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                // tap and drag live in separate pointerInput blocks (two spatial recognizers must not share one)
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { start, value -> scene.onSpatialDrag("start", start, value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", null, value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", null, value) },
                    ) { value ->
                        scene.onSpatialDrag("move", null, value)
                    }
                },
        attachments = {
            AttachmentPanel(PanelSpec.CONSOLE) {
                PoolPanel(PanelSpec.CONSOLE, scene, watched = true) { inView -> ConsolePanel(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.CHAIR_PLATE) {
                PoolPanel(PanelSpec.CHAIR_PLATE, scene) { inView -> ChairPlate(state = state, filled = inView) }
            }
            AttachmentPanel(PanelSpec.DOOR_A) {
                PoolPanel(PanelSpec.DOOR_A, scene) { inView -> DoorPlate(slot = 0, state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.DOOR_B) {
                PoolPanel(PanelSpec.DOOR_B, scene) { inView -> DoorPlate(slot = 1, state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.DOOR_CARD) {
                PoolPanel(PanelSpec.DOOR_CARD, scene) { inView -> DoorCard(state = state, filled = inView && state.card == Card.DOOR, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.PIN_TAG) {
                PoolPanel(PanelSpec.PIN_TAG, scene) { inView -> PinTagCard(state = state, filled = inView && state.card == Card.PIN_TAG, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.REPORT) {
                PoolPanel(PanelSpec.REPORT, scene, watched = true) { inView -> ReportPanel(state = state, filled = inView && state.reportOpen, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.MENU) {
                PoolPanel(PanelSpec.MENU, scene, watched = true) { inView -> MoreMenu(state = state, filled = inView && state.menuOpen, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.HINT) {
                PoolPanel(PanelSpec.HINT, scene) { inView -> HintPanel(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.DIALOG) {
                PoolPanel(PanelSpec.DIALOG, scene) { inView -> DialogPanel(dialog = if (inView) state.dialog else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.COACH) {
                PoolPanel(PanelSpec.COACH, scene) { inView -> CoachPanel(shown = inView && state.coach && state.chairMode == ChairMode.PUSHING, onEvent = onEvent) }
            }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel. [content] gets `inView`: the panel has been moved into view. A pooled
 * panel must draw its content only then (what it draws while parked out of sight stays broken on the
 * emulator). [watched]: presses on it hold the lazy follow still.
 */
@Composable
private fun PoolPanel(id: String, scene: AccessScene, watched: Boolean = false, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    CompositionLocalProvider(LocalProbePanel provides id) {
        if (watched) {
            Box(
                modifier =
                    Modifier.pointerInput(id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press) scene.onPanelPointer(true)
                                if (event.type == PointerEventType.Release) scene.onPanelPointer(false)
                            }
                        }
                    }
            ) {
                content(inView)
            }
        } else {
            content(inView)
        }
    }
}
