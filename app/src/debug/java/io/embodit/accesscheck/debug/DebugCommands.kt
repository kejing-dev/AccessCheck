package io.embodit.accesscheck.debug

import android.content.Context
import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.LanguagePref
import io.embodit.accesscheck.domain.model.PinTag
import io.embodit.accesscheck.domain.model.PointerRay
import io.embodit.accesscheck.domain.model.ThresholdPick
import io.embodit.accesscheck.domain.model.UnitPref
import io.embodit.accesscheck.domain.model.Vec3
import io.embodit.accesscheck.domain.model.ViewHeight
import io.embodit.accesscheck.platform.AcLog
import io.embodit.accesscheck.ui.access.AccessEvent
import io.embodit.accesscheck.ui.access.BlockReason
import io.embodit.accesscheck.ui.access.debug.AcProbe
import java.io.File
import java.util.Locale

/**
 * The QA hooks (debug builds only). Broadcast `io.embodit.accesscheck.DEBUG` with `--es cmd <name ...>`;
 * numbers may also come as `--ef x 1.2 --ef z -0.4 ...` extras (design `qaHooks`).
 *
 * - `state Sxx`                 straight to a designed state (S1 - S30; S31 = placing with the ghost)
 * - `place [x z yawDeg]`        place the chair (ahead of the head, or at a Stage point)
 * - `place_ahead m [side]`      where `place` / `state` land the chair from now on (default 1.2 m ahead, 0 sideways)
 * - `push` / `park` / `turn` / `rotl` / `rotr`
 * - `head x z [yawDeg]`         override the head's floor point (the push input), `head off` clears
 * - `drag x z`                  move the parked chair
 * - `door width [measured] [threshold]`  e.g. `door 0.78 1 0`: width m, 1 = tape measured, threshold cm (-1 = unset)
 * - `eye h`                     override the eye height above the floor, `eye off` clears
 * - `height stand|seat|child`
 * - `pin [reach|see|hazard]`    a pin on the wall ahead with that tag (none = tag card)
 * - `pin_fill n`, `scuff_fill n`
 * - `obstacle_add x z w d h`    a virtual obstacle, `obstacles` draws the field, `obstacles off`, `obstacles_clear`
 * - `report` / `save` / `menu` / `clear` / `clear_now` / `exit`
 * - `block [permission|fullspace|device|off]`, `lost 1|0`, `coach_reset`
 * - `chair manual|power`, `lang system|zh|en`, `units cm|in`
 * - `ui <control>`              presses a panel control by name (console_place, console_push, console_park, console_turn,
 *                               console_rot_left, console_rot_right, height_stand|seat|child, console_pin, console_report,
 *                               console_more, door_plate_0|1, door_close, threshold_none|1|2|3, measure_m5|m1|p1|p5,
 *                               measure_confirm, pin_tag_reach|see|hazard, pin_delete, report_save, report_delete,
 *                               menu_clear, menu_exit, chair_manual|power, lang_*, unit_cm|in, dialog_cancel|confirm|exit,
 *                               coach_got_it, hint_action)
 * - `ray ox oy oz dx dy dz`      stand-in controller ray (Stage metres), `ray off` gives the controller back
 * - `aim x z`                   stand-in controller beside the head, pointing at the floor point (x, z)
 * - `aim_ahead m [side]`        same, at the floor point m ahead of the head (level) and side to its right
 * - `trigger 1|0`, `stick x`    stand-in trigger / thumbstick x (-1..1; `stick 0` releases)
 * - `replace`, `bring`, `place_here`, `place_cancel`, `mute`, `recall`
 * - `dump`, `pose`, `bounds`, `planes`, `park_panel`, `fps on|off`
 */
internal object DebugCommands {

    fun run(context: Context, raw: String, extras: Map<String, Float>, s: String?) {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return
        val name = tokens.first().lowercase(Locale.US)
        val rest = tokens.drop(1)
        val numbers = rest.mapNotNull { it.toFloatOrNull() }
        val text = (s ?: rest.joinToString(" ")).trim()
        val host = AcProbe.host
        if (host == null) {
            AcLog.i("CMD $name ignored: the Stage is not running")
            return
        }
        try {
            dispatch(context, host, name, numbers, extras, text)
        } catch (t: Throwable) {
            AcLog.e("CMD $name failed: $t", t)
        }
    }

    private fun dispatch(context: Context, host: AcProbe.Host, name: String, numbers: List<Float>, x: Map<String, Float>, text: String) {
        val scene = host.scene()
        val word = text.lowercase(Locale.US)
        fun num(i: Int, key: String): Float? = x[key] ?: numbers.getOrNull(i)
        when (name) {
            "state" -> host.dispatch(AccessEvent.DebugState(text))
            "place" -> {
                val px = num(0, "x")
                val pz = num(1, "z")
                if (px != null && pz != null) host.dispatch(AccessEvent.DebugPlaceAt(px, pz, num(2, "yaw") ?: 0f)) else host.dispatch(AccessEvent.PlaceClicked)
            }
            "place_ahead" -> host.dispatch(AccessEvent.DebugPlaceAhead(num(0, "m") ?: 1.2f, num(1, "side") ?: 0f))
            "push" -> host.dispatch(AccessEvent.PushClicked)
            "park", "stop" -> host.dispatch(AccessEvent.ParkClicked)
            "turn" -> host.dispatch(AccessEvent.TurnClicked)
            "rotl" -> host.dispatch(AccessEvent.RotateClicked(-1))
            "rotr" -> host.dispatch(AccessEvent.RotateClicked(1))
            "head" -> AcLog.i(if (word == "off") scene?.setDebugHead(null, null, null) ?: NO_SCENE else scene?.setDebugHead(num(0, "x"), num(1, "z"), num(2, "yaw")) ?: NO_SCENE)
            "drag" -> {
                val dx = num(0, "x")
                val dz = num(1, "z")
                AcLog.i(if (dx != null && dz != null) scene?.debugDrag(dx, dz) ?: NO_SCENE else "CMD drag needs x z")
            }
            "door" -> {
                val width = num(0, "width") ?: return AcLog.i("CMD door needs width")
                val measured = (num(1, "measured") ?: 0f) >= 0.5f
                val threshold = (num(2, "threshold") ?: -1f).toInt()
                host.dispatch(AccessEvent.DebugDoor(width, measured, threshold))
            }
            "eye" -> AcLog.i(if (word == "off") scene?.setDebugEye(null) ?: NO_SCENE else scene?.setDebugEye(num(0, "h")) ?: NO_SCENE)
            "height" -> host.dispatch(AccessEvent.HeightSelected(ViewHeight.entries.firstOrNull { it.tag == word } ?: ViewHeight.STAND))
            "pin" -> host.dispatch(AccessEvent.DebugPin(PinTag.entries.firstOrNull { it.tag == word }))
            "pin_fill" -> host.dispatch(AccessEvent.DebugPinFill((num(0, "n") ?: 20f).toInt()))
            "scuff_fill" -> host.dispatch(AccessEvent.DebugScuffFill((num(0, "n") ?: 40f).toInt()))
            "obstacle_add" -> {
                val ox = num(0, "x") ?: return AcLog.i("CMD obstacle_add needs x z w d h")
                val oz = num(1, "z") ?: return AcLog.i("CMD obstacle_add needs x z w d h")
                host.dispatch(AccessEvent.DebugObstacleAdd(ox, oz, num(2, "w") ?: 0.4f, num(3, "d") ?: 0.4f, num(4, "h") ?: 0.4f))
            }
            "obstacles" -> AcLog.i(scene?.showObstacles(word != "off") ?: NO_SCENE)
            "obstacles_clear" -> host.dispatch(AccessEvent.DebugObstaclesClear)
            "report" -> host.dispatch(AccessEvent.ReportClicked)
            "save" -> host.dispatch(AccessEvent.SaveClicked)
            "menu" -> host.dispatch(AccessEvent.MoreClicked)
            "clear" -> host.dispatch(AccessEvent.ClearClicked)
            "clear_now" -> host.dispatch(AccessEvent.DebugClear)
            "exit" -> host.dispatch(AccessEvent.ExitClicked)
            "block" ->
                host.dispatch(
                    AccessEvent.DebugBlock(
                        when (word) {
                            "off", "none", "" -> null
                            "fullspace" -> BlockReason.NOT_IN_FULL_SPACE
                            "device" -> BlockReason.DEVICE_NOT_SUPPORTED
                            else -> BlockReason.WITHOUT_PERMISSION
                        }
                    )
                )
            "lost", "tracking" -> host.dispatch(AccessEvent.DebugTracking(if (word == "1" || word == "lost" || word == "on") true else if (word == "0" || word == "ok" || word == "off") false else null))
            "coach_reset", "coach" -> host.dispatch(AccessEvent.DebugCoachReset)
            "chair" -> host.dispatch(AccessEvent.ChairSelected(ChairModel.fromTag(word)))
            "lang" -> host.dispatch(AccessEvent.LanguageSelected(LanguagePref.fromTag(word)))
            "units" -> host.dispatch(AccessEvent.UnitsSelected(UnitPref.fromTag(word)))
            "threshold" -> {
                val id = host.uiState().doors.firstOrNull { it.slot >= 0 }?.entry?.info?.id ?: return AcLog.i("CMD threshold: no door shown")
                host.dispatch(AccessEvent.ThresholdSelected(id, ThresholdPick.fromCm((num(0, "cm") ?: -1f).toInt())))
            }
            "ui" -> AcLog.i("UI $text pressed=${host.pressControl(text)}")
            "dump" -> {
                val json = host.dumpJson()
                AcLog.i("DUMP $json")
                runCatching { File(context.filesDir, "dump.json").writeText(json) }
            }
            "pose" -> AcLog.i(scene?.poseLine() ?: NO_SCENE)
            "bounds" -> {
                scene?.panelLines()?.forEach { AcLog.i(it) } ?: AcLog.i(NO_SCENE)
                for (panel in listOf("console", "chairPlate", "doorPlateA", "doorPlateB", "doorCard", "pinTag", "report", "menu", "hint", "dialog", "coach")) {
                    for ((ctrl, b) in AcProbe.controlsOf(panel)) {
                        AcLog.i(String.format(Locale.US, "CTRL panel=%s name=%s cx=%.1f cy=%.1f w=%.1f h=%.1f", panel, ctrl, b.cx, b.cy, b.w, b.h))
                    }
                }
            }
            "planes" -> scene?.planesSummary()?.forEach { AcLog.i(it) } ?: AcLog.i(NO_SCENE)
            "park_panel" -> AcLog.i(scene?.parkPanel() ?: NO_SCENE)
            "ray" -> {
                if (word == "off") return AcLog.i(scene?.clearDebugPointer() ?: NO_SCENE)
                if (numbers.size < 6) return AcLog.i("CMD ray needs ox oy oz dx dy dz")
                val ray = PointerRay(Vec3(numbers[0], numbers[1], numbers[2]), Vec3(numbers[3], numbers[4], numbers[5]))
                AcLog.i(scene?.setDebugPointer(ray, null, null) ?: NO_SCENE)
            }
            "aim" -> {
                val ax = num(0, "x")
                val az = num(1, "z")
                AcLog.i(if (ax != null && az != null) scene?.aimAt(ax, az) ?: NO_SCENE else "CMD aim needs x z")
            }
            "aim_ahead" -> AcLog.i(scene?.aimAhead(num(0, "m") ?: 1.5f, num(1, "side") ?: 0f) ?: NO_SCENE)
            "trigger" -> AcLog.i(scene?.setDebugPointer(null, num(0, "v") ?: if (word == "on") 1f else 0f, null) ?: NO_SCENE)
            "stick" -> AcLog.i(scene?.setDebugPointer(null, null, num(0, "x") ?: 0f) ?: NO_SCENE)
            "replace" -> host.dispatch(AccessEvent.ReplaceClicked)
            "bring" -> host.dispatch(AccessEvent.BringChairClicked)
            "place_here" -> host.dispatch(AccessEvent.PlaceHereClicked)
            "place_cancel" -> host.dispatch(AccessEvent.PlaceCancelClicked)
            "mute" -> host.dispatch(AccessEvent.MuteClicked)
            "recall" -> host.dispatch(AccessEvent.RecallClicked)
            "fps" -> AcLog.i(scene?.setFpsLine(word == "on") ?: NO_SCENE)
            else -> AcLog.i("CMD unknown '$name'")
        }
    }

    private const val NO_SCENE = "CMD ignored: no scene"
}
